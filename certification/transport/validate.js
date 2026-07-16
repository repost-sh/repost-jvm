#!/usr/bin/env node

"use strict";

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const crypto = require("node:crypto");
const { TextDecoder } = require("node:util");

const args = process.argv.slice(2);
let inventoryTarget = null;
let requestedPath = null;
let explicitSharedPath = null;
let strictJsonManifest = null;
let rawEvidencePath = null;
if (args[0] === "--inventory") {
  if (args.length !== 2 || !["TYPESCRIPT", "GO", "PYTHON", "JVM"].includes(args[1])) {
    console.error("usage: node validate.js [contract.json] | --inventory TYPESCRIPT|GO|PYTHON|JVM");
    process.exit(2);
  }
  inventoryTarget = args[1];
} else if (args[0] === "--shared") {
  if (args.length !== 3) {
    console.error("usage: node validate.js [contract.json] | --shared shared-v2.json jvm-overlay.json | --inventory TYPESCRIPT|GO|PYTHON|JVM");
    process.exit(2);
  }
  explicitSharedPath = path.resolve(args[1]);
  requestedPath = args[2];
} else if (args[0] === "--strict-json-corpus") {
  if (args.length !== 2) {
    console.error("usage: node validate.js --strict-json-corpus corpus-manifest.tsv");
    process.exit(2);
  }
  strictJsonManifest = path.resolve(args[1]);
} else if (args[0] === "--verify-jvm-raw-evidence") {
  if (args.length !== 2) {
    console.error("usage: node validate.js --verify-jvm-raw-evidence evidence.json");
    process.exit(2);
  }
  rawEvidencePath = path.resolve(args[1]);
  explicitSharedPath = path.join(__dirname, "v2.json");
  requestedPath = path.join(__dirname, "jvm-v2.json");
} else if (args.length <= 1) {
  requestedPath = args[0] ?? null;
} else {
  console.error("usage: node validate.js [contract.json] | --shared shared-v2.json jvm-overlay.json | --inventory TYPESCRIPT|GO|PYTHON|JVM");
  process.exit(2);
}

const file = requestedPath ? path.resolve(requestedPath) : path.join(__dirname, "v2.json");
let document;
let overlayDocument = null;
let sharedCanonicalBytes = null;

function assertUniqueJsonObjectKeys(text, limits = {}) {
  let index = 0;
  let tokens = 0;
  const maxDepth = limits.maxDepth ?? Number.MAX_SAFE_INTEGER;
  const maxTokens = limits.maxTokens ?? Number.MAX_SAFE_INTEGER;
  const whitespace = () => { while (/[\x20\x09\x0a\x0d]/u.test(text[index] || "")) index += 1; };
  function stringToken() {
    const start = index;
    index += 1;
    while (index < text.length) {
      if (text[index] === "\\") index += 2;
      else if (text[index] === '"') {
        index += 1;
        const raw = text.slice(start, index);
        const decoded = JSON.parse(raw);
        for (let offset = 0; offset < decoded.length; offset += 1) {
          const unit = decoded.charCodeAt(offset);
          if (unit >= 0xd800 && unit <= 0xdbff) {
            const next = decoded.charCodeAt(offset + 1);
            if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error("canonical contract strings must contain Unicode scalar values only");
            offset += 1;
          } else if (unit >= 0xdc00 && unit <= 0xdfff) throw new Error("canonical contract strings must contain Unicode scalar values only");
        }
        return raw;
      } else index += 1;
    }
    throw new Error("unterminated JSON string");
  }
  function value(location, depth) {
    if (depth > maxDepth) throw new Error(`canonical JSON exceeds maximum depth ${maxDepth}`);
    tokens += 1;
    if (tokens > maxTokens) throw new Error(`canonical JSON exceeds maximum token count ${maxTokens}`);
    whitespace();
    if (text[index] === "{") {
      index += 1;
      whitespace();
      const keys = new Set();
      if (text[index] === "}") { index += 1; return; }
      while (index < text.length) {
        whitespace();
        if (text[index] !== '"') throw new Error(`${location}: object key must be a JSON string`);
        const rawKey = stringToken();
        const key = JSON.parse(rawKey);
        if (keys.has(key)) throw new Error(`${location}: duplicate object key ${JSON.stringify(key)}`);
        keys.add(key);
        whitespace();
        if (text[index] !== ":") throw new Error(`${location}: object key must be followed by colon`);
        index += 1;
        value(`${location}.${key}`, depth + 1);
        whitespace();
        if (text[index] === "}") { index += 1; return; }
        if (text[index] !== ",") throw new Error(`${location}: object members must be comma-delimited`);
        index += 1;
      }
      throw new Error(`${location}: unterminated JSON object`);
    }
    if (text[index] === "[") {
      index += 1;
      whitespace();
      let itemIndex = 0;
      if (text[index] === "]") { index += 1; return; }
      while (index < text.length) {
        value(`${location}[${itemIndex}]`, depth + 1);
        itemIndex += 1;
        whitespace();
        if (text[index] === "]") { index += 1; return; }
        if (text[index] !== ",") throw new Error(`${location}: array items must be comma-delimited`);
        index += 1;
      }
      throw new Error(`${location}: unterminated JSON array`);
    }
    if (text[index] === '"') { stringToken(); return; }
    const start = index;
    while (index < text.length && !/[\x20\x09\x0a\x0d,}\]]/u.test(text[index])) index += 1;
    if (index === start) throw new Error(`${location}: missing JSON value`);
  }
  whitespace();
  value("$", 0);
  whitespace();
  if (index !== text.length) throw new Error("trailing data after canonical JSON value");
}

function parseCanonicalJsonFile(jsonPath, limits = {}) {
  const maxBytes = limits.maxBytes ?? Number.MAX_SAFE_INTEGER;
  const size = fs.statSync(jsonPath).size;
  if (size > maxBytes) throw new Error(`canonical JSON exceeds maximum byte size ${maxBytes}`);
  const bytes = fs.readFileSync(jsonPath);
  if (bytes.length > maxBytes) throw new Error(`canonical JSON exceeds maximum byte size ${maxBytes}`);
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    throw new Error("canonical contract must not begin with a UTF-8 BOM");
  }
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new Error("canonical contract must be scalar-valid UTF-8");
  }
  assertUniqueJsonObjectKeys(text, limits);
  return JSON.parse(text);
}

function runStrictJsonCorpus(manifestPath) {
  const manifestBytes = fs.readFileSync(manifestPath);
  let manifest;
  try { manifest = new TextDecoder("utf-8", { fatal: true }).decode(manifestBytes); }
  catch { throw new Error("strict JSON manifest must be scalar-valid UTF-8"); }
  if (manifest.length === 0 || !manifest.endsWith("\n") || manifest.includes("\r")) throw new Error("strict JSON manifest must be nonempty, LF-terminated, and contain no CR");
  const root = path.dirname(manifestPath);
  const expectedRows = [
    ["REJECT", "duplicate-escaped-equivalent"], ["REJECT", "duplicate-nested"], ["REJECT", "duplicate-nested-action"],
    ["REJECT", "duplicate-nested-expected-error"], ["REJECT", "duplicate-nested-header"], ["REJECT", "duplicate-top-level"],
    ["ACCEPT", "empty-object"], ["REJECT", "invalid-escape"], ["REJECT", "invalid-utf8"], ["ACCEPT", "json-whitespace"],
    ["REJECT", "leading-zero-number"], ["REJECT", "missing-colon"], ["ACCEPT", "nested-values"], ["REJECT", "non-ascii-digit"],
    ["ACCEPT", "number-grammar"], ["REJECT", "raw-control-in-string"], ["REJECT", "trailing-comma"], ["REJECT", "trailing-data"],
    ["REJECT", "truncated-utf8"], ["ACCEPT", "unicode-scalars"], ["REJECT", "unpaired-high-surrogate"],
    ["REJECT", "unpaired-low-surrogate"], ["REJECT", "utf8-bom"], ["REJECT", "utf8-surrogate"],
  ];
  const rows = manifest.slice(0, -1).split("\n");
  if (rows.length !== expectedRows.length) throw new Error(`strict JSON manifest must contain exactly ${expectedRows.length} rows`);
  const output = [];
  let accepted = 0;
  let rejected = 0;
  let previous = "";
  const paths = new Set();
  for (const [index, row] of rows.entries()) {
    const parts = row.split("\t");
    if (parts.length !== 3) throw new Error(`strict JSON manifest row ${index + 1} must contain exactly three tab-separated fields`);
    const [expected, id, relative] = parts;
    if (!/^(?:ACCEPT|REJECT)$/u.test(expected) || !/^[a-z0-9-]+$/u.test(id) || id <= previous) throw new Error(`strict JSON manifest row ${index + 1} has invalid decision, ID, or order`);
    if (expected !== expectedRows[index][0] || id !== expectedRows[index][1]) throw new Error(`strict JSON manifest row ${index + 1} must be ${expectedRows[index].join("\t")}`);
    if (relative !== `corpus/${id}.json` || relative.includes("\\") || paths.has(relative)) throw new Error(`strict JSON manifest row ${index + 1} has invalid or duplicate corpus path`);
    const corpusPath = path.resolve(root, relative);
    if (!corpusPath.startsWith(`${root}${path.sep}`) || !fs.statSync(corpusPath).isFile()) throw new Error(`strict JSON corpus path escapes the manifest directory or is not a file: ${relative}`);
    paths.add(relative);
    let actual = "ACCEPT";
    try { parseCanonicalJsonFile(corpusPath); } catch { actual = "REJECT"; }
    if (actual !== expected) throw new Error(`${id}: expected ${expected}, received ${actual}`);
    output.push(`${actual} ${id}`);
    if (actual === "ACCEPT") accepted += 1; else rejected += 1;
    previous = id;
  }
  output.push(`SUMMARY accept=${accepted} reject=${rejected} total=${accepted + rejected}`);
  return `${output.join("\n")}\n`;
}

if (strictJsonManifest !== null) {
  try { process.stdout.write(runStrictJsonCorpus(strictJsonManifest)); }
  catch (error) { console.error(error.message); process.exit(1); }
  process.exit(0);
}
try {
  document = parseCanonicalJsonFile(file);
  if (document?.target === "JVM") {
    overlayDocument = document;
    const sharedPath = explicitSharedPath ?? path.join(__dirname, "v2.json");
    document = parseCanonicalJsonFile(sharedPath);
    sharedCanonicalBytes = fs.readFileSync(sharedPath);
  } else if (inventoryTarget === "JVM") {
    const sharedPath = path.join(__dirname, "v2.json");
    overlayDocument = parseCanonicalJsonFile(path.join(__dirname, "jvm-v2.json"));
    sharedCanonicalBytes = fs.readFileSync(sharedPath);
  } else if (explicitSharedPath !== null) {
    throw new Error("--shared requires a JVM overlay as the second contract");
  }
} catch (error) {
  console.error(`${file}: ${error.message}`);
  process.exit(1);
}
const failures = [];

function fail(location, message) {
  failures.push(`${location}: ${message}`);
}

function object(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(location, value, required, optional = []) {
  if (!object(value)) {
    fail(location, "must be an object");
    return false;
  }
  const allowed = new Set([...required, ...optional]);
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) fail(`${location}.${key}`, "unknown field");
  }
  for (const key of required) {
    if (!(key in value)) fail(location, `missing required field ${key}`);
  }
  return true;
}

function stringArray(location, value) {
  if (!Array.isArray(value)) {
    fail(location, "must be an array");
    return;
  }
  value.forEach((item, index) => {
    if (typeof item !== "string") fail(`${location}[${index}]`, "must be a string");
  });
}

function integerArray(location, value) {
  if (!Array.isArray(value)) {
    fail(location, "must be an array");
    return;
  }
  value.forEach((item, index) => {
    if (!Number.isSafeInteger(item)) fail(`${location}[${index}]`, "must be a safe integer");
  });
}

function nonEmptyString(location, value) {
  if (typeof value !== "string" || value.length === 0) fail(location, "must be a non-empty string");
}

function uniqueStringArray(location, value, { nonEmpty = true } = {}) {
  stringArray(location, value);
  if (!Array.isArray(value)) return;
  if (nonEmpty && value.length === 0) fail(location, "must be a non-empty array");
  if (new Set(value).size !== value.length) fail(location, "must not contain duplicates");
  value.forEach((item, index) => {
    if (typeof item === "string" && item.length === 0) fail(`${location}[${index}]`, "must be non-empty");
  });
}

function integer(location, value, minimum = undefined, maximum = undefined) {
  if (!Number.isSafeInteger(value)) {
    fail(location, "must be a safe integer");
    return;
  }
  if (minimum !== undefined && value < minimum) fail(location, `must be at least ${minimum}`);
  if (maximum !== undefined && value > maximum) fail(location, `must be at most ${maximum}`);
}

function nullableString(location, value) {
  if (value !== null && typeof value !== "string") fail(location, "must be null or a string");
}

function sameMembers(location, value, expected) {
  if (!Array.isArray(value) || value.length !== expected.length || new Set(value).size !== expected.length || expected.some((item) => !value.includes(item))) {
    fail(location, `must contain exactly ${JSON.stringify(expected)}`);
  }
}

function validateJson(location, value) {
  if (value === null || typeof value === "string" || typeof value === "boolean") return;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) fail(location, "must be a finite JSON number");
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => validateJson(`${location}[${index}]`, item));
    return;
  }
  if (object(value)) {
    for (const [key, item] of Object.entries(value)) validateJson(`${location}.${key}`, item);
    return;
  }
  fail(location, "must be a JSON value");
}

function strictBase64(location, value) {
  if (typeof value !== "string") {
    fail(location, "must be a base64 string");
    return null;
  }
  if (value.length % 4 !== 0 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(value)) {
    fail(location, "must be canonical RFC 4648 base64");
    return null;
  }
  const decoded = Buffer.from(value, "base64");
  if (decoded.toString("base64") !== value) {
    fail(location, "must be canonical RFC 4648 base64");
    return null;
  }
  return decoded;
}

function mergeObjects(base, overlay) {
  if (!object(base) || !object(overlay)) return cloneJson(overlay);
  const merged = cloneJson(base);
  for (const [key, value] of Object.entries(overlay)) {
    merged[key] = object(value) && object(merged[key]) ? mergeObjects(merged[key], value) : cloneJson(value);
  }
  return merged;
}

function cloneJson(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function exactIntegerRange(location, value, expected) {
  integerArray(location, value);
  if (!Array.isArray(value) || value.length !== 2) {
    fail(location, "must contain exactly an inclusive minimum and maximum");
    return;
  }
  if (JSON.stringify(value) !== JSON.stringify(expected)) {
    fail(location, `must equal ${JSON.stringify(expected)}`);
  }
}

function idempotencyKeyViolation(value) {
  if (typeof value !== "string") return "must be a string";
  if (value.length === 0) return "must contain 1..255 printable ASCII field-value octets";
  if (!/^[\x20-\x7e]+$/u.test(value)) return "must contain only printable ASCII field-value octets 0x20..0x7e";
  if (/^ | $/u.test(value)) return "must not have leading or trailing SP";
  if (value.length > 255) return "must be at most 255 ASCII octets";
  return null;
}

function credentialViolation(value) {
  if (typeof value !== "string") return "must be a string";
  if (value.length === 0 || value.length > 4096) return "must contain 1..4096 printable ASCII field-value octets";
  if (!/^[\x20-\x7e]+$/u.test(value)) return "must contain only printable ASCII field-value octets 0x20..0x7e";
  if (!/[^ ]/u.test(value)) return "must contain at least one non-SP octet";
  return null;
}

function canonicalUtcMillisecondTimestamp(value) {
  const match = typeof value === "string"
    ? value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3})Z$/u)
    : null;
  if (!match) return false;
  const [, year, month, day, hour, minute, second, millisecond] = match.map(Number);
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, millisecond);
  return Number.isSafeInteger(date.getTime()) && date.toISOString() === value;
}

const imfWeekdays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const imfMonths = new Map([
  ["Jan", 0], ["Feb", 1], ["Mar", 2], ["Apr", 3], ["May", 4], ["Jun", 5],
  ["Jul", 6], ["Aug", 7], ["Sep", 8], ["Oct", 9], ["Nov", 10], ["Dec", 11],
]);

function parseImfFixdate(value) {
  const match = typeof value === "string"
    ? value.match(/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$/u)
    : null;
  if (!match) return null;
  const [, weekday, dayText, monthText, yearText, hourText, minuteText, secondText] = match;
  const date = new Date(0);
  date.setUTCFullYear(Number(yearText), imfMonths.get(monthText), Number(dayText));
  date.setUTCHours(Number(hourText), Number(minuteText), Number(secondText), 0);
  if (!Number.isSafeInteger(date.getTime()) || imfWeekdays[date.getUTCDay()] !== weekday || date.toUTCString() !== value) return null;
  return date.getTime();
}

const fatalUtf8 = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
function decodeUtf8(bytes) {
  try {
    return fatalUtf8.decode(bytes);
  } catch {
    return null;
  }
}

function inspectJsonText(text) {
  if (typeof text !== "string" || text.charCodeAt(0) === 0xfeff) return { valid: false, reason: "bom" };
  let index = 0;
  let reason = null;
  let tokens = 0;
  const maximumDepth = 32;
  const maximumTokens = 10_000;
  const maximumFieldNameUtf8Bytes = 64;
  const maximumScalarStringUtf8Bytes = 8192;
  const maximumScalarStringChars = 8192;
  const maximumNumberChars = 128;
  const maximumMembersPerObject = 16;
  const whitespace = /[\x20\x09\x0a\x0d]/u;
  const skipWhitespace = () => { while (whitespace.test(text[index] || "")) index += 1; };
  const countToken = () => {
    tokens += 1;
    if (tokens > maximumTokens && reason === null) reason = "tokens";
  };
  function parseString() {
    const start = index;
    index += 1;
    while (index < text.length) {
      if (text[index] === "\\") index += 2;
      else if (text[index] === "\"") {
        index += 1;
        return JSON.parse(text.slice(start, index));
      } else index += 1;
    }
    throw new Error("unterminated JSON string");
  }
  function parseValue(depth) {
    skipWhitespace();
    countToken();
    if (text[index] === "{") return parseObject(depth + 1);
    if (text[index] === "[") return parseArray(depth + 1);
    if (text[index] === "\"") {
      const value = parseString();
      if (!validUnicodeScalarString(value) && reason === null) reason = "unicode";
      else if (Array.from(value).length > maximumScalarStringChars && reason === null) reason = "string-chars";
      else if (Buffer.byteLength(value, "utf8") > maximumScalarStringUtf8Bytes && reason === null) reason = "string-bytes";
      return value;
    }
    const start = index;
    while (index < text.length && !/[\x20\x09\x0a\x0d,\]}]/u.test(text[index])) index += 1;
    const raw = text.slice(start, index);
    if (/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/u.test(raw)
      && raw.length > maximumNumberChars && reason === null) reason = "number-chars";
  }
  function parseObject(depth) {
    if (depth > maximumDepth && reason === null) reason = "depth";
    index += 1;
    const keys = new Set();
    let members = 0;
    skipWhitespace();
    if (text[index] === "}") { index += 1; countToken(); return; }
    while (index < text.length) {
      skipWhitespace();
      if (text[index] !== "\"") throw new Error("object key must be a string");
      const key = parseString();
      countToken();
      members += 1;
      if (members > maximumMembersPerObject && reason === null) reason = "members";
      if (!validUnicodeScalarString(key) && reason === null) reason = "unicode";
      else if (Buffer.byteLength(key, "utf8") > maximumFieldNameUtf8Bytes && reason === null) reason = "field-name-bytes";
      if (keys.has(key) && reason === null) reason = "duplicate";
      keys.add(key);
      skipWhitespace();
      if (text[index] !== ":") throw new Error("missing object colon");
      index += 1;
      parseValue(depth);
      skipWhitespace();
      if (text[index] === "}") { index += 1; countToken(); return; }
      if (text[index] !== ",") throw new Error("missing object comma");
      index += 1;
    }
    throw new Error("unterminated object");
  }
  function parseArray(depth) {
    if (depth > maximumDepth && reason === null) reason = "depth";
    index += 1;
    skipWhitespace();
    if (text[index] === "]") { index += 1; countToken(); return; }
    while (index < text.length) {
      parseValue(depth);
      skipWhitespace();
      if (text[index] === "]") { index += 1; countToken(); return; }
      if (text[index] !== ",") throw new Error("missing array comma");
      index += 1;
    }
    throw new Error("unterminated array");
  }
  try {
    parseValue(0);
    skipWhitespace();
    if (index !== text.length) return { valid: false, reason: "trailing" };
    JSON.parse(text);
    return reason === null ? { valid: true, value: JSON.parse(text), tokens } : { valid: false, reason, tokens };
  } catch {
    return { valid: false, reason: "syntax", tokens };
  }
}

function acceptedResponseContentType(headers) {
  const values = headerValues(headers, "content-type");
  if (values.length !== 1 || typeof values[0] !== "string") return false;
  if (!/^[\x00-\x7f]*$/u.test(values[0])) return false;
  return /^[\x20\x09]*application\/json(?:[\x20\x09]*;[\x20\x09]*charset[\x20\x09]*=[\x20\x09]*(?:utf-8|"utf-8"))?[\x20\x09]*$/u.test(values[0].toLowerCase());
}

function validSendResultId(value) {
  return typeof value === "string" && /^msg_[A-Za-z0-9_-]{1,124}$/u.test(value);
}

function validUnicodeScalarString(value) {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) return false;
  }
  return true;
}

function scanCanonicalUnicodeScalars(value, location) {
  if (typeof value === "string") {
    if (!validUnicodeScalarString(value)) fail(location, "canonical contract strings must contain Unicode scalar values only; materialize non-scalar test data through declared post-parse harness tokens");
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => scanCanonicalUnicodeScalars(item, `${location}[${index}]`));
    return;
  }
  if (!object(value)) return;
  for (const [key, item] of Object.entries(value)) {
    if (!validUnicodeScalarString(key)) fail(location, "canonical contract object keys must contain Unicode scalar values only; materialize non-scalar test data through declared post-parse harness tokens");
    scanCanonicalUnicodeScalars(item, `${location}.${key}`);
  }
}
scanCanonicalUnicodeScalars(document, "$");
if (overlayDocument) scanCanonicalUnicodeScalars(overlayDocument, "$jvm");

function assessResponseHeaders(headers) {
  if (!object(headers)) return { valid: false, kind: "protocol", count: 0, logicalBytes: 0 };
  let count = 0;
  let logicalBytes = 0;
  for (const [name, raw] of Object.entries(headers)) {
    if (name.startsWith(":")) continue;
    const nameBytes = Buffer.byteLength(name, "utf8");
    if (nameBytes > 256 || !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/u.test(name)) return { valid: false, kind: "protocol", count, logicalBytes };
    const values = Array.isArray(raw) ? raw : [raw];
    if (values.length === 0) return { valid: false, kind: "protocol", count, logicalBytes };
    for (const value of values) {
      if (typeof value !== "string") return { valid: false, kind: "protocol", count, logicalBytes };
      if (!validUnicodeScalarString(value) || /[\u0000-\u0008\u000a-\u001f\u007f]/u.test(value)) return { valid: false, kind: "protocol", count, logicalBytes };
      const valueBytes = Buffer.byteLength(value, "utf8");
      count += 1;
      logicalBytes += nameBytes + 2 + valueBytes + 2;
      if (!Number.isSafeInteger(logicalBytes) || count > 100 || valueBytes > 8192 || logicalBytes > 65536) {
        return { valid: false, kind: "limit", count, logicalBytes };
      }
    }
  }
  return { valid: true, kind: null, count, logicalBytes };
}

const topKeys = [
  "contractVersion",
  "units",
  "boundaries",
  "constants",
  "enums",
  "errorCatalog",
  "sentinels",
  "inputProfiles",
  "fixtureProtocol",
  "traceVocabulary",
  "concurrencyProtocol",
  "caseManifest",
  "cases",
  "concurrencyScenarios",
];
exactKeys("$", document, topKeys);

const singleCaseCount = 401;
const requiredConcurrencyScenarioIds = [
  "concurrency-immediate-success-release-and-probe",
  "concurrency-retry-success-release-and-probe",
  "concurrency-exact-capacity-and-overflow",
  "concurrency-retry-holds-one-permit",
  "concurrency-success-failure-release-once",
  "concurrency-cancel-releases-once",
  "concurrency-server-failure-release-and-probe",
  "concurrency-protocol-failure-release-and-probe",
  "concurrency-close-drains-and-releases-once",
  "concurrency-reject-after-close",
  "concurrency-no-queued-waiter",
  "concurrency-weighted-byte-contention-and-reuse",
  "concurrency-atomic-runtime-diagnostics-snapshots",
  "concurrency-runtime-diagnostics-counter-saturates-at-max-safe-integer",
];
const requiredCaseManifestSha256 = "a70783f743133a4ffb7ed8ca9bbc61963ba10f7d35845beb64fa0688ea584c00";
const requiredConcurrencyProtocolSha256 = "62ff99446f5ac736c8102b8bdaeab90555a762357bbe43cdaa984fa086d59bf7";
const requiredConcurrencyInputsSha256 = "6ff4566973329402e6fba4c973ff6b2a27b50578f2575fef614707aa76c18047";
uniqueStringArray("$.caseManifest", document.caseManifest);
for (const [index, caseId] of (document.caseManifest || []).entries()) {
  if (typeof caseId === "string" && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(caseId)) fail(`$.caseManifest[${index}]`, "must be a lowercase kebab-case case ID");
}
if (crypto.createHash("sha256").update(JSON.stringify(document.caseManifest)).digest("hex") !== requiredCaseManifestSha256) {
  fail("$.caseManifest", "must equal the validator-owned canonical 415-vector manifest");
}

if (document.contractVersion !== 2) fail("$.contractVersion", "must equal 2");

function exactStringSequence(location, value, expected) {
  stringArray(location, value);
  if (JSON.stringify(value) !== JSON.stringify(expected)) fail(location, `must equal ${JSON.stringify(expected)}`);
}

const concurrencyProtocolFields = [
  "protocolVersion",
  "runtimeRule",
  "admissionRule",
  "completionRule",
  "synchronizationRule",
  "diagnosticsRule",
  "manifestRule",
  "legacyOccupiedPermitsRule",
  "operationIdPattern",
  "runtimeStates",
  "fixtureActions",
  "operationCodes",
  "completionRoutes",
  "stepActions",
  "sideEffectCounters",
  "scenarioFields",
  "runtimeFields",
  "operationFields",
  "expectedFields",
  "operationExpectedFields",
  "checkpointFields",
  "finalRuntimeFields",
  "stepSchemas",
  "fixtureActionSchemas",
  "orderedTraceGrammar",
  "diagnosticStressIterations",
];
const concurrencyProtocol = document.concurrencyProtocol;
exactKeys("$.concurrencyProtocol", concurrencyProtocol, concurrencyProtocolFields);
if (crypto.createHash("sha256").update(JSON.stringify(concurrencyProtocol)).digest("hex") !== requiredConcurrencyProtocolSha256) {
  fail("$.concurrencyProtocol", "must equal the validator-owned production concurrency protocol");
}
if (concurrencyProtocol?.protocolVersion !== 2) fail("$.concurrencyProtocol.protocolVersion", "must equal 2");
if (concurrencyProtocol?.diagnosticStressIterations !== 1000) fail("$.concurrencyProtocol.diagnosticStressIterations", "must equal 1000 deterministic executions");
for (const field of [
  "runtimeRule",
  "admissionRule",
  "completionRule",
  "synchronizationRule",
  "diagnosticsRule",
  "manifestRule",
  "legacyOccupiedPermitsRule",
  "orderedTraceGrammar",
]) nonEmptyString(`$.concurrencyProtocol.${field}`, concurrencyProtocol?.[field]);
if (concurrencyProtocol?.operationIdPattern !== "^op-[a-z][a-z0-9-]{0,31}$") {
  fail("$.concurrencyProtocol.operationIdPattern", "must equal the closed operation ID grammar");
}
const concurrencyRuntimeStates = ["OPEN", "CLOSING", "CLOSED"];
const concurrencyFixtureActions = ["hold", "failConnection", "respond"];
const concurrencyOperationCodes = ["OK", "OVERLOADED", "HTTP_REJECTED", "SERVER_FAILURE", "RESPONSE_PROTOCOL", "CANCELLED", "CLOSED", "OPERATION_DEADLINE"];
const concurrencyCompletionRoutes = ["OPERATION_EXECUTION", "TERMINAL_DISPATCHER", "NATIVE_CANCEL", "DIRECT_PRE_ADMISSION"];
const concurrencyStepActions = ["seedDiagnosticCounter", "start", "awaitBarrier", "release", "awaitRetryScheduled", "advanceTime", "cancel", "closeRuntime", "awaitTerminal", "checkpoint"];
const concurrencySideEffectCounters = [
  "permitAcquires",
  "permitRejects",
  "permitReleases",
  "observerStarts",
  "configurationSnapshots",
  "credentialResolutions",
  "defaultSnapshots",
  "idempotencyGenerations",
  "serializations",
  "networkAttempts",
  "byteAcquires",
  "byteRejects",
  "byteReleases",
  "deadlineCallbacks",
  "terminalDispatches",
  "ghostWorkAttempts",
  "reservationAcquires",
  "reservationReleases",
];
const concurrencyScenarioFields = ["scenarioId", "description", "runtime", "operations", "steps", "expected"];
const concurrencyRuntimeFields = ["maxInFlight", "maxBufferedBytes"];
const concurrencyOperationFields = ["operationId", "input", "options", "requestBytes", "fixtureScript"];
const concurrencyExpectedFields = ["orderedTrace", "checkpoints", "operations", "finalRuntime"];
const concurrencyOperationExpectedFields = ["operationId", "code", "delivery", "attemptCount", "completionRoute", "sideEffects"];
const runtimeDiagnosticsFields = ["inFlightOperations", "bufferedBytes", "concurrencyOverloadRejections", "requestByteOverloadRejections", "responseByteOverloadRejections", "responseHeaderLimitFailures", "executorOverloadRejections", "schedulerOverloadRejections", "responseCloseFailures", "droppedObserverEvents", "observerFailures", "closed"];
const concurrencyCheckpointFields = ["name", "state", "inFlight", "completionReservations", "activeTerminalOperation", "queuedTerminalTasks", "currentBufferedBytes", "queuedWaiters", "totalPermitAcquires", "totalPermitRejects", "totalPermitReleases", "totalByteAcquires", "totalByteRejects", "totalByteReleases", "deadlineCallbacks", "terminalDispatches", "ghostWorkAttempts", "diagnostics"];
const concurrencyFinalRuntimeFields = ["state", "inFlight", "maxObservedInFlight", "completionReservations", "maxObservedCompletionReservations", "activeTerminalOperation", "queuedTerminalTasks", "currentBufferedBytes", "maxObservedBufferedBytes", "queuedWaiters", "totalPermitAcquires", "totalPermitRejects", "totalPermitReleases", "totalReservationAcquires", "totalReservationReleases", "totalByteAcquires", "totalByteRejects", "totalByteReleases", "deadlineCallbacks", "terminalDispatches", "ghostWorkAttempts", "diagnostics"];
const jvmConcurrencyStepActions = ["blockWorkers", "releaseWorkers", "shutdownBorrowedExecutor", "blockPrimaryDependent", "releasePrimaryDependent", "blockOutcomeDependent", "releaseOutcomeDependent", "closeFromPrimaryDependent", "closeFromOutcomeDependent", ...concurrencyStepActions];
const jvmConcurrencyRuntimeFields = ["maxInFlight", "maxBufferedBytes", "workerThreads", "executorMode", "executorOwnership", "executorQueueCapacity"];
const jvmConcurrencyCheckpointFields = ["name", "state", "inFlight", "completionReservations", "activeTerminalOperation", "queuedTerminalTasks", "currentBufferedBytes", "queuedWaiters", "queuedWorkerTasks", "blockedWorkers", "totalPermitAcquires", "totalPermitRejects", "totalPermitReleases", "totalByteAcquires", "totalByteRejects", "totalByteReleases", "deadlineCallbacks", "terminalDispatches", "ghostWorkAttempts", "diagnostics"];
const jvmConcurrencyFinalRuntimeFields = ["state", "inFlight", "maxObservedInFlight", "completionReservations", "maxObservedCompletionReservations", "activeTerminalOperation", "queuedTerminalTasks", "currentBufferedBytes", "maxObservedBufferedBytes", "queuedWaiters", "queuedWorkerTasks", "blockedWorkers", "totalPermitAcquires", "totalPermitRejects", "totalPermitReleases", "totalReservationAcquires", "totalReservationReleases", "totalByteAcquires", "totalByteRejects", "totalByteReleases", "deadlineCallbacks", "terminalDispatches", "ghostWorkAttempts", "diagnostics"];
for (const [field, expected] of Object.entries({
  runtimeStates: concurrencyRuntimeStates,
  fixtureActions: concurrencyFixtureActions,
  operationCodes: concurrencyOperationCodes,
  completionRoutes: concurrencyCompletionRoutes,
  stepActions: concurrencyStepActions,
  sideEffectCounters: concurrencySideEffectCounters,
  scenarioFields: concurrencyScenarioFields,
  runtimeFields: concurrencyRuntimeFields,
  operationFields: concurrencyOperationFields,
  expectedFields: concurrencyExpectedFields,
  operationExpectedFields: concurrencyOperationExpectedFields,
  checkpointFields: concurrencyCheckpointFields,
  finalRuntimeFields: concurrencyFinalRuntimeFields,
})) exactStringSequence(`$.concurrencyProtocol.${field}`, concurrencyProtocol?.[field], expected);

const concurrencyStepFieldSchemas = {
  seedDiagnosticCounter: { requiredFields: ["action", "counter", "value"], optionalFields: [] },
  blockWorkers: { requiredFields: ["action", "count"], optionalFields: [] },
  releaseWorkers: { requiredFields: ["action"], optionalFields: [] },
  shutdownBorrowedExecutor: { requiredFields: ["action"], optionalFields: [] },
  blockPrimaryDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  releasePrimaryDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  blockOutcomeDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  releaseOutcomeDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  closeFromPrimaryDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  closeFromOutcomeDependent: { requiredFields: ["action", "operationId"], optionalFields: [] },
  start: { requiredFields: ["action", "operationId"], optionalFields: [] },
  awaitBarrier: { requiredFields: ["action", "operationId", "barrier"], optionalFields: [] },
  release: { requiredFields: ["action", "barrier"], optionalFields: [] },
  awaitRetryScheduled: { requiredFields: ["action", "operationId", "retryIndex"], optionalFields: [] },
  advanceTime: { requiredFields: ["action", "byMs"], optionalFields: [] },
  cancel: { requiredFields: ["action", "operationId", "phase"], optionalFields: [] },
  closeRuntime: { requiredFields: ["action"], optionalFields: [] },
  awaitTerminal: { requiredFields: ["action", "operationId"], optionalFields: [] },
  checkpoint: { requiredFields: ["action", "name"], optionalFields: [] },
};
exactKeys("$.concurrencyProtocol.stepSchemas", concurrencyProtocol?.stepSchemas, concurrencyStepActions);
for (const action of concurrencyStepActions) {
  const location = `$.concurrencyProtocol.stepSchemas.${action}`;
  if (exactKeys(location, concurrencyProtocol?.stepSchemas?.[action], ["requiredFields", "optionalFields", "semantics"])) {
    exactStringSequence(`${location}.requiredFields`, concurrencyProtocol.stepSchemas[action].requiredFields, concurrencyStepFieldSchemas[action].requiredFields);
    exactStringSequence(`${location}.optionalFields`, concurrencyProtocol.stepSchemas[action].optionalFields, concurrencyStepFieldSchemas[action].optionalFields);
    nonEmptyString(`${location}.semantics`, concurrencyProtocol.stepSchemas[action].semantics);
  }
}
const concurrencyFixtureFieldSchemas = {
  hold: ["action", "attempt", "phase", "barrier"],
  failConnection: ["action", "attempt", "phase", "kind"],
  respond: ["action", "attempt", "status", "bodyKind", "responseBytes"],
};
exactKeys("$.concurrencyProtocol.fixtureActionSchemas", concurrencyProtocol?.fixtureActionSchemas, concurrencyFixtureActions);
for (const action of concurrencyFixtureActions) {
  const location = `$.concurrencyProtocol.fixtureActionSchemas.${action}`;
  if (exactKeys(location, concurrencyProtocol?.fixtureActionSchemas?.[action], ["requiredFields", "semantics"])) {
    exactStringSequence(`${location}.requiredFields`, concurrencyProtocol.fixtureActionSchemas[action].requiredFields, concurrencyFixtureFieldSchemas[action]);
    nonEmptyString(`${location}.semantics`, concurrencyProtocol.fixtureActionSchemas[action].semantics);
  }
}

const unitFields = ["time", "size", "entropy"];
exactKeys("$.units", document.units, unitFields);
for (const field of unitFields) nonEmptyString(`$.units.${field}`, document.units?.[field]);

const boundaryFields = ["limits", "deadline", "commitment", "jitter", "ratio"];
exactKeys("$.boundaries", document.boundaries, boundaryFields);
for (const field of boundaryFields) nonEmptyString(`$.boundaries.${field}`, document.boundaries?.[field]);

const constantFields = [
  "credentialPrecedence",
  "baseUrlPrecedence",
  "endpointPath",
  "endpointAppend",
  "credentialValidation",
  "runtimeConstructionSnapshotRule",
  "baseUrlValidation",
  "baseUrlMaxBytes",
  "baseAuthorityMaxBytes",
  "basePathMaxBytes",
  "requestTargetMaxBytes",
  "canonicalUriMaxBytes",
  "outboundRequiredHeaderOrder",
  "outboundFramingRule",
  "userAgentValidation",
  "idempotencyKeyValidation",
  "followRedirects",
  "maxAttemptsTotal",
  "maxAttemptsRange",
  "connectTimeoutMs",
  "attemptTimeoutMs",
  "operationTimeoutMs",
  "durationMaxMs",
  "durationValidation",
  "maxInFlight",
  "maxBufferedBytes",
  "maxInFlightRange",
  "maxBufferedBytesRange",
  "requestSerializationWorkspaceBytes",
  "responseWorkspaceBytes",
  "portableOperationWorkspaceBytes",
  "bufferBudgetRule",
  "admissionLivenessRule",
  "observerLifecycleRule",
  "responseHeaderRule",
  "operationIdRule",
  "runtimeDiagnosticsCounterMax",
  "runtimeDiagnosticsRule",
  "retryBaseMs",
  "retryMaxMs",
  "retryAfterMaxMs",
  "requestLimitBytes",
  "responseCompressedLimitBytes",
  "responseDecompressedLimitBytes",
  "expansionRatioLimit",
  "errorInspectionLimitBytes",
  "terminalErrorSafeFields",
  "legacyErrorBodyAllowlist",
  "remoteErrorRule",
  "jitterFormula",
  "retryAfterFormula",
  "retryable",
  "nonRetryable",
  "acceptedResponse",
  "responseContentType",
  "jsonMaxNestingDepth",
  "jsonMaxTokens",
  "jsonMaxFieldNameUtf8Bytes",
  "jsonMaxScalarStringUtf8Bytes",
  "jsonMaxScalarStringChars",
  "jsonMaxNumberChars",
  "jsonMaxMembersPerObject",
  "jsonParserScratchReservationBytes",
  "jsonPolicy",
  "jsonAmplificationRule",
  "successResponseFieldOrder",
  "configurationSnapshot",
  "admission",
  "testOperationId",
];
exactKeys("$.constants", document.constants, constantFields);

for (const field of ["credentialPrecedence", "baseUrlPrecedence", "terminalErrorSafeFields", "legacyErrorBodyAllowlist", "retryable", "nonRetryable", "successResponseFieldOrder", "outboundRequiredHeaderOrder"]) {
  uniqueStringArray(`$.constants.${field}`, document.constants?.[field]);
}
for (const field of [
  "endpointPath",
  "endpointAppend",
  "credentialValidation",
  "runtimeConstructionSnapshotRule",
  "baseUrlValidation",
  "outboundFramingRule",
  "userAgentValidation",
  "idempotencyKeyValidation",
  "remoteErrorRule",
  "jitterFormula",
  "retryAfterFormula",
  "acceptedResponse",
  "responseContentType",
  "jsonPolicy",
  "jsonAmplificationRule",
  "bufferBudgetRule",
  "admissionLivenessRule",
  "observerLifecycleRule",
  "responseHeaderRule",
  "operationIdRule",
  "runtimeDiagnosticsRule",
  "configurationSnapshot",
  "admission",
  "testOperationId",
]) {
  nonEmptyString(`$.constants.${field}`, document.constants?.[field]);
}
const requiredContractRules = {
  baseUrlValidation: "validate the raw supplied bytes with a custom lexical parser before platform URI parsing, then platform-parse, serialize, and require a byte-for-byte canonical roundtrip: nonempty absolute lowercase http:// or https:// ASCII RFC3986 only; total raw URI <=2048 bytes, authority <=512 bytes, base path <=1536 bytes; no whitespace, control, backslash, userinfo, query, or fragment; an explicit port is exactly decimal 1..65535 with no sign or leading zero; path permits only slash, unreserved, sub-delims, colon, at-sign, or percent escapes with uppercase hex, and rejects malformed or lowercase escapes, percent-encoded slash or backslash, and dot or dot-dot segments; https accepts a DNS name or canonical IP literal, while http accepts only exact lowercase localhost, canonical four-octet 127.0.0.0/8 without leading zeros, or bracketed ::1; aliases, integer/hex/octal IPv4, expanded or zone-scoped IPv6, mapped IPv6, trailing-dot localhost, and Unicode confusables are rejected; after appending /v1/messages, the origin-form final path without query is <=2048 bytes and the full canonical URI is <=4096 bytes",
  outboundFramingRule: "Content-Length is exactly one canonical decimal field equal to actual serialized body bytes 0 or 1..1048576, with no leading zero except the single value 0; never send Transfer-Encoding or chunked framing; H1 is exactly POST origin-form HTTP/1.1 followed by one Host containing the canonical authority with every explicit input port preserved including default ports, one Content-Length, the five outboundRequiredHeaderOrder fields in that order, then zero or one target-authorized traceparent, with no Connection, Expect, Upgrade, TE, duplicate, or extra field; H2 pseudo-fields are exactly :method POST, :scheme, :authority with the canonical authority, :path with the origin-form target in that order, followed by one Content-Length, the five required fields in order, then zero or one target-authorized traceparent, with no regular Host, connection-specific, TE, duplicate, or extra field",
  credentialValidation: "every selected fixed, provider, or environment credential must be 1..4096 printable ASCII octets 0x20..0x7e, contain at least one non-SP octet, and preserve leading/trailing SP byte-exactly; absence falls through precedence, but empty, all-SP, controls, DEL, and non-ASCII are terminal CONFIGURATION",
  runtimeConstructionSnapshotRule: "ClientOptions/runtime construction reads options and environment exactly once; resolve precedence explicit baseUrl over present REPOST_API_URL over https://api.repost.sh, validate and snapshot one canonical origin for the runtime lifetime, and snapshot fixed/environment credential fallbacks; an invalid present selected environment base URI is synchronous constructionThrow with snapshot:0 and no operation, outcome, admission, hook, or side effect; later process-environment mutation changes neither route nor credential; only a configured apiKeyProvider is invoked dynamically once per admitted operation",
  userAgentValidation: "undefined means no suffix; an explicit suffix must be 1..256 printable ASCII octets 0x20..0x7e with no leading or trailing SP; controls including TAB, DEL, non-ASCII, empty, and 257+ octets are construction CONFIGURATION",
  remoteErrorRule: "consume and discard at most 65536 decoded payload bytes under the compressed, decompressed, and expansion caps without JSON decoding or field extraction; retain no remote code, message, request/correlation ID, stack, detail, unknown field, partial field, or unsafe value; byteTrace uses error-discarded and public code/message always come from the SDK errorCatalog",
  acceptedResponse: "exactly one accepted JSON Content-Type and a complete identity or single-member gzip JSON object satisfying jsonAmplificationRule; id must match msg_[A-Za-z0-9_-]{1,124}; type, customerId, and strict YYYY-MM-DDTHH:mm:ss.SSSZ timestamp must exactly equal the snapshotted serialized request envelope; unknown fields are ignored only after the same limits and duplicate checks",
  responseContentType: "exactly one field/value; trim only HTTP OWS SP/HTAB; require case-insensitive application/json followed by no parameter or exactly one charset parameter with OWS around semicolon and equals and a case-insensitive utf-8 token or unescaped quoted UTF-8; reject duplicate/other parameters, escapes, trailing semicolon, comma-combined values, and application/*+json",
  jsonPolicy: "successful response bodies use fatal UTF-8, no BOM, exactly one JSON value followed only by JSON whitespace, no duplicate object keys, and the exact jsonAmplificationRule; non-2xx bodies are never JSON-decoded",
  jsonAmplificationRule: "successful-response JSON is parsed streaming with maximum open object/array nesting depth 32 including the root container, maximum 10000 parser tokens counting START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, FIELD_NAME, and every scalar exactly once, maximum 64 UTF-8 bytes per decoded field name, maximum 8192 Unicode scalar values and 8192 UTF-8 bytes per decoded string scalar, maximum 128 ASCII characters per raw numeric token including sign, decimal point, and exponent, and maximum 16 members per object; exact limits are accepted and the first +1 breach cancels parsing as nonretryable RESPONSE_TOO_LARGE/POSSIBLY_SENT for 2xx; malformed syntax/UTF-8 remains RESPONSE_PROTOCOL; the parser, fixed/prebounded duplicate detection, decoded-name/string windows, and numeric token window use only the distinct pre-admission 262144-byte JSON_PARSER_SCRATCH_RESERVATION, retained across retries and released once at terminal cleanup, with no hidden or post-publication byte acquisition",
  durationValidation: "all timeout and backoff durations are positive whole milliseconds in 1..9223372036854; fractional milliseconds, sub-millisecond values, zero, negatives, max+1, and overflow are rejected without truncation using overflow-safe conversion",
  bufferBudgetRule: "one nonblocking per-runtime weighted byte budget covers all retained-or-reserved request/response/parser workspaces, transport reservations, and payload storage; default 67108864 bytes, range 4194304..1073741824; every admitted operation atomically reserves exactly 1048576 REQUEST_SERIALIZATION_WORKSPACE bytes plus 1048576 RESPONSE_WORKSPACE bytes plus a distinct 262144 JSON_PARSER_SCRATCH_RESERVATION, total 2359296 bytes, before observer, configuration, provider, default, idempotency, serialization, custom Transport, DNS, or network side effects; serialization converts request workspace to actual retained request bytes and releases the unused portion while retaining actual request bytes across retries; the response workspace may retain the complete capped raw response while parser scratch independently bounds streaming parser and duplicate structures; response and parser reservations remain across retries until terminal cleanup, with no post-publication byte acquisition; RuntimeDiagnostics.bufferedBytes counts every retained-or-reserved byte; exact atomic permits, no queued waiter, release exactly once; every target overlay must account additional fixed reservations and prove the 4194304 minimum makes progress for one exact-1048576-byte request plus one exact-1048576-byte valid raw and gzip response",
  admissionLivenessRule: "sample monotonic operation start and exclusive deadline synchronously at public admission; caller work is bounded to O(1) clock read plus language-native deadline and ordinary-operation-execution handoff; register independent deadline authority before ordinary execution; every pending execution state-checks before observer, provider, default, key, serialization, and network side effects; cancellation, close, or deadline must produce no late ghost side effect",
  observerLifecycleRule: "observer callbacks are serialized and tracked through a bounded language-native dispatcher even when its delivery resource is borrowed; one delivered callback completes before the next; observer exceptions cannot affect sending; close cancels tracked callbacks cooperatively, never closes borrowed resources, and never waits for its own active callback; close from an observer or send completion may mark CLOSED after initiating shutdown while the current callback exits on return",
  responseHeaderRule: "snapshot response headers once and count each non-pseudo name/list-value pair as UTF8(name)+2+UTF8(value)+2 using overflow-safe arithmetic; exclude HTTP/2 pseudo-headers; maximum 100 field lines and 65536 logical bytes; names are 1..256 ASCII token bytes; values are valid Unicode scalar strings up to 8192 UTF-8 bytes and reject NUL, CR, LF, other C0 controls except HTAB, DEL, and unpaired surrogates; parsed media/security headers are ASCII-only before case folding; no null name/list/value; headers are outside the payload budget",
  operationIdRule: "production operation IDs are generated locally as op_ plus a lowercase RFC4122 UUIDv4 and are not injectable; each runner must assert the actual public error, outcome, and observer IDs match ^op_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$, are identical for one operation, and are unique across operations before normalizing first-seen IDs to op-0001, op-0002, and so on; manifest operation IDs and concurrency operationId values are runner-only aliases, never public values",
  runtimeDiagnosticsRule: "one linearizable immutable runtime-state epoch owns admission, weighted bytes, lifecycle, and every public diagnostic counter behind one language-appropriate synchronization boundary; each causal transition publishes one coherent epoch; public counters saturate exactly at MAX_COUNTER_VALUE=9007199254740991 so JSON and IEEE-754 runtimes preserve them; wider native counters remain private; diagnostics() observes exactly one epoch; closed=true implies inFlightOperations=0 and bufferedBytes=0 in that same epoch",
  configurationSnapshot: "construction snapshots options, environment, canonical origin, and fixed credential fallbacks once for runtime lifetime; each admitted operation invokes only a configured apiKeyProvider dynamically and snapshots provider result, defaults, idempotency key, and clocks once for all attempts",
};
for (const [field, expected] of Object.entries(requiredContractRules)) {
  if (document.constants?.[field] !== expected) fail(`$.constants.${field}`, "must equal the closed transport contract rule");
}
if (JSON.stringify(document.constants?.credentialPrecedence) !== JSON.stringify(["apiKeyProvider when configured", "apiKey when present", "REPOST_SEND_API_KEY when present", "REPOST_TOKEN when present"])) {
  fail("$.constants.credentialPrecedence", "must express presence-based precedence before validation");
}
if (JSON.stringify(document.constants?.nonRetryable) !== JSON.stringify(["tls-certificate", "tls-hostname", "tls-protocol", "redirect", "other-4xx", "validation", "serialization", "descriptor-version", "amplification-or-resource-limit", "cancellation"])) {
  fail("$.constants.nonRetryable", "must reserve retry suppression for closed security, validation, cancellation, and amplification/resource-limit causes");
}
if (JSON.stringify(document.constants?.successResponseFieldOrder) !== JSON.stringify(["id", "type", "customerId", "timestamp"])) {
  fail("$.constants.successResponseFieldOrder", "must equal the authoritative successful response field order id,type,customerId,timestamp");
}
if (JSON.stringify(document.constants?.outboundRequiredHeaderOrder) !== JSON.stringify(["Authorization", "Content-Type", "Accept-Encoding", "User-Agent", "Idempotency-Key"])) {
  fail("$.constants.outboundRequiredHeaderOrder", "must equal the exact five-field portable outbound order");
}
for (const [field, expected] of Object.entries({
  jsonMaxNestingDepth: 32,
  jsonMaxTokens: 10000,
  jsonMaxFieldNameUtf8Bytes: 64,
  jsonMaxScalarStringUtf8Bytes: 8192,
  jsonMaxScalarStringChars: 8192,
  jsonMaxNumberChars: 128,
  jsonMaxMembersPerObject: 16,
  jsonParserScratchReservationBytes: 262144,
})) {
  if (document.constants?.[field] !== expected) fail(`$.constants.${field}`, `must equal ${expected}`);
}
if (document.constants?.runtimeDiagnosticsCounterMax !== Number.MAX_SAFE_INTEGER) fail("$.constants.runtimeDiagnosticsCounterMax", "must equal the portable public MAX_COUNTER_VALUE 9007199254740991");
if (typeof document.constants?.followRedirects !== "boolean") fail("$.constants.followRedirects", "must be boolean");
for (const field of [
  "maxAttemptsTotal",
  "connectTimeoutMs",
  "attemptTimeoutMs",
  "operationTimeoutMs",
  "maxInFlight",
  "maxBufferedBytes",
  "baseUrlMaxBytes",
  "baseAuthorityMaxBytes",
  "basePathMaxBytes",
  "requestTargetMaxBytes",
  "canonicalUriMaxBytes",
  "requestSerializationWorkspaceBytes",
  "responseWorkspaceBytes",
  "portableOperationWorkspaceBytes",
  "retryBaseMs",
  "retryMaxMs",
  "retryAfterMaxMs",
  "requestLimitBytes",
  "responseCompressedLimitBytes",
  "responseDecompressedLimitBytes",
  "expansionRatioLimit",
  "errorInspectionLimitBytes",
  "durationMaxMs",
]) {
  integer(`$.constants.${field}`, document.constants?.[field], 1);
}
const retryableSet = new Set(document.constants?.retryable || []);
for (const item of document.constants?.nonRetryable || []) {
  if (retryableSet.has(item)) fail("$.constants.nonRetryable", `${item} is also retryable`);
}

exactIntegerRange("$.constants.maxAttemptsRange", document.constants?.maxAttemptsRange, [1, 10]);
exactIntegerRange("$.constants.maxInFlightRange", document.constants?.maxInFlightRange, [1, 65536]);
exactIntegerRange("$.constants.maxBufferedBytesRange", document.constants?.maxBufferedBytesRange, [4194304, 1073741824]);
if (!Number.isInteger(document.constants?.maxAttemptsTotal) || document.constants.maxAttemptsTotal < 1 || document.constants.maxAttemptsTotal > 10) {
  fail("$.constants.maxAttemptsTotal", "must be an integer inside constants.maxAttemptsRange");
}
if (!Number.isInteger(document.constants?.maxInFlight) || document.constants.maxInFlight < 1 || document.constants.maxInFlight > 65536) {
  fail("$.constants.maxInFlight", "must be an integer inside constants.maxInFlightRange");
}
if (!Number.isInteger(document.constants?.maxBufferedBytes) || document.constants.maxBufferedBytes < 4194304 || document.constants.maxBufferedBytes > 1073741824) {
  fail("$.constants.maxBufferedBytes", "must be an integer inside constants.maxBufferedBytesRange");
}
if (document.constants?.requestSerializationWorkspaceBytes !== 1048576) fail("$.constants.requestSerializationWorkspaceBytes", "must equal 1048576");
if (document.constants?.responseWorkspaceBytes !== 1048576) fail("$.constants.responseWorkspaceBytes", "must equal 1048576");
if (document.constants?.portableOperationWorkspaceBytes !== 2359296
  || document.constants.portableOperationWorkspaceBytes !== document.constants.requestSerializationWorkspaceBytes + document.constants.responseWorkspaceBytes + document.constants.jsonParserScratchReservationBytes) {
  fail("$.constants.portableOperationWorkspaceBytes", "must equal the exact 2359296-byte sum of request, response, and parser-scratch reservations");
}
for (const [field, value] of Object.entries({ baseUrlMaxBytes: 2048, baseAuthorityMaxBytes: 512, basePathMaxBytes: 1536, requestTargetMaxBytes: 2048, canonicalUriMaxBytes: 4096 })) {
  if (document.constants?.[field] !== value) fail(`$.constants.${field}`, `must equal ${value}`);
}

const enumNames = ["categories", "deliveryStates", "errorCodes", "fixtureActions", "fixturePhases", "failureKinds", "observerEvents", "causeCategories", "failureReasons", "validationIssueCodes", "configurationIssueCodes", "clientOptionKeys"];
exactKeys("$.enums", document.enums, enumNames);
for (const name of enumNames) uniqueStringArray(`$.enums.${name}`, document.enums?.[name]);

const categories = new Set(document.enums?.categories || []);
const deliveries = new Set(document.enums?.deliveryStates || []);
const errorCodes = new Set(document.enums?.errorCodes || []);
const actions = new Set(document.enums?.fixtureActions || []);
const phases = new Set(document.enums?.fixturePhases || []);
const failureKinds = new Set(document.enums?.failureKinds || []);
const observerEvents = new Set(document.enums?.observerEvents || []);
const requiredCauseCategories = ["API_KEY_PROVIDER", "DEFAULT_GENERATOR", "IDEMPOTENCY_GENERATOR", "RETRY_ENTROPY", "DNS_RESOLVER", "PROXY_CREDENTIAL_PROVIDER", "TLS_PROVIDER", "CUSTOM_TRANSPORT", "RESPONSE_BODY", "OBSERVER", "HTTP_RUNTIME", "TRANSPORT_CLOSE", "SCHEDULER_CLOSE", "OPERATION_EXECUTOR_CLOSE", "DNS_EXECUTOR_CLOSE", "PROXY_CREDENTIAL_EXECUTOR_CLOSE", "TLS_EXECUTOR_CLOSE", "TERMINAL_SETTLEMENT_CLOSE", "OBSERVER_CLOSE", "UNKNOWN"];
const requiredFailureReasonRemediationKeys = {
  DNS_NOT_FOUND: "network.dns.not-found", DNS_TIMEOUT: "network.dns.timeout", CONNECT_REFUSED: "network.connect.refused", CONNECT_TIMEOUT: "network.connect.timeout",
  CONNECTION_RESET: "network.connection.reset", CONNECTION_CLOSED: "network.connection.closed", TLS_UNTRUSTED: "network.tls.untrusted",
  TLS_CERTIFICATE_EXPIRED: "network.tls.certificate-expired", TLS_CERTIFICATE_NOT_YET_VALID: "network.tls.certificate-not-yet-valid",
  TLS_HOSTNAME_MISMATCH: "network.tls.hostname-mismatch", TLS_NEGOTIATION: "network.tls.negotiation", PROXY_AUTH_REQUIRED: "network.proxy.auth-required",
  PROXY_CONNECT_FAILED: "network.proxy.connect-failed", UNKNOWN: "network.unknown",
};
exactStringSequence("$.enums.causeCategories", document.enums?.causeCategories, requiredCauseCategories);
exactStringSequence("$.enums.failureReasons", document.enums?.failureReasons, Object.keys(requiredFailureReasonRemediationKeys));
const requiredValidationIssueCodes = [
  "REQUIRED", "NULL_NOT_ALLOWED", "TYPE_MISMATCH", "OUT_OF_RANGE", "NON_FINITE", "INVALID_DATETIME", "INVALID_ENUM",
  "INVALID_JSON", "INVALID_UNICODE", "COLLECTION_LIMIT", "CYCLE",
];
const requiredConfigurationIssueCodes = ["MISSING", "CONFLICT", "INVALID_VALUE", "OUT_OF_RANGE", "UNSUPPORTED", "RESOURCE_MISMATCH"];
const requiredClientOptionKeys = [
  "API_KEY", "API_KEY_PROVIDER", "BASE_URI", "CONNECT_TIMEOUT", "ATTEMPT_TIMEOUT", "OPERATION_TIMEOUT", "MAX_ATTEMPTS",
  "MAX_IN_FLIGHT_OPERATIONS", "MAX_BUFFERED_BYTES", "RETRY_BASE_DELAY", "RETRY_MAX_DELAY", "HTTP_TRANSPORT_OPTIONS", "TRANSPORT", "EXECUTOR",
  "SCHEDULER", "OBSERVER", "OBSERVER_EXECUTOR", "TELEMETRY", "DEFAULT_VALUE_GENERATORS", "IDEMPOTENCY_KEY_GENERATOR",
  "MONOTONIC_CLOCK", "WALL_CLOCK", "RETRY_ENTROPY", "USER_AGENT_SUFFIX",
];
exactStringSequence("$.enums.validationIssueCodes", document.enums?.validationIssueCodes, requiredValidationIssueCodes);
exactStringSequence("$.enums.configurationIssueCodes", document.enums?.configurationIssueCodes, requiredConfigurationIssueCodes);
exactStringSequence("$.enums.clientOptionKeys", document.enums?.clientOptionKeys, requiredClientOptionKeys);
const validationIssueCodes = new Set(requiredValidationIssueCodes);
const configurationIssueCodes = new Set(requiredConfigurationIssueCodes);
const clientOptionKeys = new Set(requiredClientOptionKeys);
const causeCategories = new Set(requiredCauseCategories);
const failureReasons = new Set(Object.keys(requiredFailureReasonRemediationKeys));
const causeCategoryBySyntheticCauseType = new Map([
  ["ConformanceProviderFailure", "API_KEY_PROVIDER"],
  ["ConformanceIdempotencyFailure", "IDEMPOTENCY_GENERATOR"],
  ["ConformanceEntropyFailure", "RETRY_ENTROPY"],
  ["ConformanceCustomTransportFailure", "CUSTOM_TRANSPORT"],
  ["ConformanceIoFailure", "HTTP_RUNTIME"],
  ["ConformanceDnsFailure", "HTTP_RUNTIME"],
  ["ConformanceConnectFailure", "HTTP_RUNTIME"],
  ["ConformanceProxyFailure", "HTTP_RUNTIME"],
  ["ConformanceTlsCertificateFailure", "HTTP_RUNTIME"],
  ["ConformanceTlsHostnameFailure", "HTTP_RUNTIME"],
  ["ConformanceTlsProtocolFailure", "HTTP_RUNTIME"],
  ["ConformanceAttemptTimeoutFailure", "HTTP_RUNTIME"],
]);
function publicCauseCategory(causeType) {
  return causeType === null || causeType === undefined
    ? null
    : causeCategoryBySyntheticCauseType.get(causeType) ?? "UNKNOWN";
}

const sentinelFields = [
  "apiKey",
  "authorization",
  "proxyCredential",
  "certificatePassword",
  "certificatePath",
  "payload",
  "customerId",
  "fullUrl",
  "remoteMessage",
  "idempotencyKey",
];
const requiredSyntheticCauseTypes = [
  "ConformanceAttemptTimeoutFailure",
  "ConformanceConnectFailure",
  "ConformanceCustomTransportFailure",
  "ConformanceDnsFailure",
  "ConformanceEntropyFailure",
  "ConformanceIdempotencyFailure",
  "ConformanceIoFailure",
  "ConformanceProviderFailure",
  "ConformanceProxyFailure",
  "ConformanceTlsCertificateFailure",
  "ConformanceTlsHostnameFailure",
  "ConformanceTlsProtocolFailure",
  "ConformanceTlsProviderFailure",
];
const syntheticCauseTypes = new Set(requiredSyntheticCauseTypes);
exactKeys("$.sentinels", document.sentinels, sentinelFields);
for (const field of sentinelFields) nonEmptyString(`$.sentinels.${field}`, document.sentinels?.[field]);
if (new Set(Object.values(document.sentinels || {})).size !== sentinelFields.length) fail("$.sentinels", "values must be unique");

const environmentFields = ["REPOST_SEND_API_KEY", "REPOST_TOKEN", "REPOST_API_URL"];
const sendFields = ["eventType", "customerId", "payload", "descriptorVersion"];
const clockFields = ["monotonicMs", "defaultNowUnixMs", "retryAfterWallUnixMs"];

function validateEnvironment(location, value) {
  if (!exactKeys(location, value, [], environmentFields)) return;
  for (const [name, environmentValue] of Object.entries(value)) {
    if (typeof environmentValue !== "string") fail(`${location}.${name}`, "must be a string");
  }
}

function validateSend(location, value, required) {
  if (!exactKeys(location, value, required ? sendFields : [], required ? [] : sendFields)) return;
  for (const field of ["eventType", "customerId"]) {
    if (field in value && typeof value[field] !== "string") fail(`${location}.${field}`, "must be a string");
  }
  if ("descriptorVersion" in value) integer(`${location}.descriptorVersion`, value.descriptorVersion, 0);
  if ("payload" in value) validateJson(`${location}.payload`, value.payload);
}

function validateClock(location, value, required) {
  if (!exactKeys(location, value, required ? clockFields : [], required ? [] : clockFields)) return;
  for (const field of clockFields) if (field in value) integer(`${location}.${field}`, value[field], 0);
}

function validateProviderValues(location, value) {
  if (!Array.isArray(value)) {
    fail(location, "must be an array");
    return;
  }
  value.forEach((providerValue, index) => {
    const itemLocation = `${location}[${index}]`;
    if (providerValue === null || typeof providerValue === "string") return;
    if (!exactKeys(itemLocation, providerValue, ["$providerError"])) return;
    if (!exactKeys(`${itemLocation}.$providerError`, providerValue.$providerError, ["causeType", "message"])) return;
    if (typeof providerValue.$providerError.causeType !== "string" || !/^[A-Za-z_$][A-Za-z0-9_.$]*$/u.test(providerValue.$providerError.causeType)) {
      fail(`${itemLocation}.$providerError.causeType`, "must be a class name");
    }
    if (!syntheticCauseTypes.has(providerValue.$providerError.causeType)) fail(`${itemLocation}.$providerError.causeType`, "must be a declared synthetic cause type");
    nonEmptyString(`${itemLocation}.$providerError.message`, providerValue.$providerError.message);
  });
}

function validateGeneratedValues(location, value, required, allowInvalidIdempotencyKey = false, allowGeneratorError = false) {
  if (!exactKeys(location, value, required ? ["idempotencyKeys"] : [], required ? [] : ["idempotencyKeys"])) return;
  if (!("idempotencyKeys" in value)) return;
  if (!Array.isArray(value.idempotencyKeys) || value.idempotencyKeys.length === 0) {
    fail(`${location}.idempotencyKeys`, "must be a non-empty array");
    return;
  }
  for (const [index, key] of value.idempotencyKeys.entries()) {
    const itemLocation = `${location}.idempotencyKeys[${index}]`;
    if (object(key?.$providerError)) {
      if (!allowGeneratorError || index !== 0) fail(itemLocation, "generator exceptions are allowed only in the pinned injected-generator failure case");
      else if (exactKeys(`${itemLocation}.$providerError`, key.$providerError, ["causeType", "message"])) {
        if (key.$providerError.causeType !== "ConformanceIdempotencyFailure") fail(`${itemLocation}.$providerError.causeType`, "must equal ConformanceIdempotencyFailure");
        nonEmptyString(`${itemLocation}.$providerError.message`, key.$providerError.message);
      }
      continue;
    }
    const violation = idempotencyKeyViolation(key);
    if (violation && !(allowInvalidIdempotencyKey && index === 0)) fail(itemLocation, violation);
  }
}

function validateSentinelValues(location, value) {
  if (!object(value)) {
    fail(location, "must be an object");
    return;
  }
  for (const [name, sentinelValue] of Object.entries(value)) {
    if (!(name in (document.sentinels || {}))) fail(`${location}.${name}`, "unknown sentinel name");
    if (typeof sentinelValue !== "string") fail(`${location}.${name}`, "must be a string");
    else if (document.sentinels?.[name] !== sentinelValue) fail(`${location}.${name}`, "must equal the named sentinel value");
  }
}

if (!object(document.inputProfiles) || Object.keys(document.inputProfiles).length === 0) fail("$.inputProfiles", "must be a non-empty object");
for (const [profileName, profile] of Object.entries(document.inputProfiles || {})) {
  const location = `$.inputProfiles.${profileName}`;
  if (!/^[a-z][A-Za-z0-9_-]*$/u.test(profileName)) fail(location, "profile name must be an identifier");
  if (!exactKeys(location, profile, ["env", "send", "clock", "entropy", "providerValues", "generatedValues", "sentinelValues"])) continue;
  validateEnvironment(`${location}.env`, profile.env);
  validateSend(`${location}.send`, profile.send, true);
  validateClock(`${location}.clock`, profile.clock, true);
  integerArray(`${location}.entropy`, profile.entropy);
  for (const [index, entropy] of (profile.entropy || []).entries()) if (entropy < 0) fail(`${location}.entropy[${index}]`, "must be nonnegative");
  validateProviderValues(`${location}.providerValues`, profile.providerValues);
  validateGeneratedValues(`${location}.generatedValues`, profile.generatedValues, true);
  validateSentinelValues(`${location}.sentinelValues`, profile.sentinelValues);
}

if (JSON.stringify(document.enums?.fixtureActions) !== JSON.stringify(document.fixtureProtocol?.actions)) {
  fail("$.fixtureProtocol.actions", "must exactly match enums.fixtureActions");
}

const fixtureProtocolFields = [
  "actions",
  "executionModes",
  "defaultExecutionMode",
  "executionModeSemantics",
  "fixtureBaseUrlToken",
  "networkFixtureUrlToken",
  "httpFixture",
  "networkFixture",
  "optionClassification",
  "actionSchemas",
  "requestAssertions",
  "assertionDefinitions",
  "requestBodyBytesByProfile",
  "testTokens",
  "testTokenSemantics",
  "bodyKinds",
  "bodyKindsSemantics",
  "bodyAssets",
  "captureAllowlist",
  "captureDenylist",
  "executionRule",
  "barrierRule",
  "partialRequestRule",
  "partialResponseRule",
  "captureRule",
  "disconnectRule",
  "orderedResponseRule",
  "echoRule",
  "causeTypeAllowlist",
  "failureCauseTypes",
  "failureReasonRemediationKeys",
  "issueReporting",
  "issueFixtures",
  "rawMapCanonicalization",
  "targetPublicMappings",
];
exactKeys("$.fixtureProtocol", document.fixtureProtocol, fixtureProtocolFields);
if (JSON.stringify(document.fixtureProtocol?.failureReasonRemediationKeys) !== JSON.stringify(requiredFailureReasonRemediationKeys)) fail("$.fixtureProtocol.failureReasonRemediationKeys", "must equal the closed failure-reason remediation catalog");
const requiredIssueReporting = {
  validationPathGrammar: "^\\$(?:(?:\\.[A-Za-z_][A-Za-z0-9_]*)|(?:\\[(?:0|[1-9][0-9]*)\\])|(?:\\[\\{\\*\\}\\]))*(?:\\.<truncated>)?$",
  rawJsonMapEntrySegment: "[{*}]",
  pathTruncationMarker: ".<truncated>",
  validationTraversal: "descriptor declaration order, then immutable collection/map snapshot order, depth first and preorder; at most one issue per visited location by ValidationIssueCode declaration priority",
  configurationOrdering: "first ClientOptionKey declaration position, then ConfigurationIssueCode declaration position; CONFLICT and RESOURCE_MISMATCH have exactly two distinct declaration-ordered keys and every other code exactly one",
  maxIssues: 32,
  maxIssueCount: 2147483647,
  maxIssuePathUtf8Bytes: 1024,
  maxTotalIssuePathUtf8Bytes: 16384,
  countRule: "continue the already bounded traversal after retention fills; issueCount is the total encountered count saturated at maxIssueCount and issuesTruncated is exactly issueCount != retained list size",
  redactionRule: "retain only closed issue enums, static descriptor member paths, fixed list indices, fixed raw-map placeholders, and closed option keys; never retain a value, raw map key, rejected class/type name, iterator text, exception text, or framework config source",
  rawMapKeyRule: "Validate every raw-JSON map key before entry traversal; any non-string key yields exactly one INVALID_JSON issue and any invalid-Unicode key yields exactly one INVALID_UNICODE issue at the container path plus [{*}], with no entry traversal.",
  rawMapOrderRule: "When every key is a Unicode scalar string, compare its unnormalized UTF-8 bytes as unsigned octets lexicographically and use that one snapshot order for validation issues and compact serialization in every target; insertion, shuffle, and host-map iteration order are irrelevant.",
};
if (JSON.stringify(document.fixtureProtocol?.issueReporting) !== JSON.stringify(requiredIssueReporting)) {
  fail("$.fixtureProtocol.issueReporting", "must equal the frozen bounded issue-path, traversal, ordering, counting, and redaction contract");
}
const canonicalRawMapPayload = "{\"raw\":{\"a\":1,\"z\":2,\"é\":3}}";
const requiredRawMapCanonicalization = {
  variants: {
    "order-a": { entries: [["é", 3], ["z", 2], ["a", 1]], canonicalRequestUtf8Bytes: 127, canonicalRequestSha256: "ccd9f89cf32dcbcda48210687939e3b4c7b928893bc585001c9802afdd73700e" },
    "order-b": { entries: [["z", 2], ["a", 1], ["é", 3]], canonicalRequestUtf8Bytes: 127, canonicalRequestSha256: "ccd9f89cf32dcbcda48210687939e3b4c7b928893bc585001c9802afdd73700e" },
    "non-string-key": { syntheticInvalidKey: "non-string" },
    "invalid-unicode-key": { syntheticInvalidKey: "unpaired-high-surrogate-after-json-parse" },
  },
  canonicalPayloadUtf8: canonicalRawMapPayload,
  canonicalPayloadSha256: crypto.createHash("sha256").update(canonicalRawMapPayload, "utf8").digest("hex"),
  validKeyOrderRule: "unsigned UTF-8 byte lexicographic order, no Unicode normalization",
  invalidKeyIssuePath: "$.raw[{*}]",
  invalidKeyTraversalCount: 0,
  publicRawKeyRetention: 0,
};
if (JSON.stringify(document.fixtureProtocol?.rawMapCanonicalization) !== JSON.stringify(requiredRawMapCanonicalization)) {
  fail("$.fixtureProtocol.rawMapCanonicalization", "must equal the frozen raw-map key validation, unsigned-UTF8 ordering, compact-byte, and redaction fixtures");
}
const expectedAggregatePrefix = `$${".a".repeat(295)}`;
const allAggregateIssues = Array.from({ length: 40 }, (_, index) => ({ code: "REQUIRED", path: `${expectedAggregatePrefix}.f${String(index).padStart(2, "0")}` }));
const expectedRetainedAggregateIssues = [];
let expectedAggregatePathBytes = 0;
for (const issue of allAggregateIssues) {
  const bytes = Buffer.byteLength(issue.path, "utf8");
  if (expectedRetainedAggregateIssues.length >= 32 || expectedAggregatePathBytes + bytes > 16384) continue;
  expectedRetainedAggregateIssues.push(issue);
  expectedAggregatePathBytes += bytes;
}
const requiredIssueFixtures = {
  variants: {
    "first-32-of-33": {
      retainedIssues: Array.from({ length: 32 }, (_, index) => ({ code: "REQUIRED", path: `$.items[${index}]` })),
      issueCount: 33,
      issuesTruncated: true,
      encounteredTraversalCount: 33,
    },
    "path-truncation": {
      retainedIssues: [{ code: "INVALID_JSON", path: `$${".aa".repeat(333)}.<truncated>` }],
      issueCount: 1,
      issuesTruncated: false,
      originalNextMemberUtf8Bytes: 64,
    },
    "aggregate-path-cap": {
      retainedIssues: expectedRetainedAggregateIssues,
      issueCount: 40,
      issuesTruncated: true,
      retainedPathUtf8Bytes: expectedAggregatePathBytes,
      encounteredTraversalCount: 40,
    },
  },
  retentionOrder: "first encountered issues whose individual and aggregate static paths fit, up to 32; continue bounded traversal only to count",
};
if (JSON.stringify(document.fixtureProtocol?.issueFixtures) !== JSON.stringify(requiredIssueFixtures)) {
  fail("$.fixtureProtocol.issueFixtures", "must equal the validator-owned max-issues, path-truncation, and aggregate-path-cap fixtures");
}
const requiredTargetPublicMappings = {
  TYPESCRIPT: {
    runtime: "server-side Node 22.x and 24.x only",
    operation: "one ordinary native Promise decorated with non-enumerable non-writable non-configurable outcome and cancel properties; not a Promise subclass",
    basicSend: "generated event method returns SendOperation and remains await/then compatible",
    cancel: "SendOperation.cancel() or the caller AbortSignal enters the same idempotent operation cancellation path",
    primaryCancellation: "reject with the original Node DOMException identity whose name is AbortError and which remains instanceof DOMException",
    outcome: "the stable read-only outcome Promise always fulfills exactly once with reconciliation metadata",
    derived: "then/catch/finally return plain native promises whose cancellation or handling cannot mutate the send operation",
  },
  GO: {
    runtime: "Go 1.25 and 1.26",
    operation: "BeginSend returns *SendOperation with Done, Wait, Outcome, and Cancel; generated BeginX pairs with existing synchronous X",
    basicSend: "Send is exactly BeginSend(...).Wait() and Wait is repeatable",
    cancel: "SendOperation.Cancel() is idempotent and cancels the native operation context",
    primaryCancellation: "return enriched *PublishError whose Unwrap preserves the exact context.Canceled or context.DeadlineExceeded winner for errors.Is",
    outcome: "Outcome waits for terminal state and returns one immutable SendOutcome value with reconciliation metadata",
    derived: "Done closes exactly once and no caller-visible channel or error mutation can settle the operation",
  },
  PYTHON: {
    runtime: "CPython 3.10 through 3.14 with distinct sync RepostClient and async AsyncRepostClient",
    operation: "async begin_send returns an Awaitable SendOperation with cancel, cancelled, outcome, and __await__; generated async begin_x pairs with awaitable x",
    basicSend: "sync send remains synchronous; async send awaits begin_send without mixing sync and async ownership",
    cancel: "SendOperation.cancel() or task cancellation enters the same idempotent operation cancellation path",
    primaryCancellation: "re-raise the original asyncio.CancelledError identity",
    outcome: "operation.outcome remains awaitable under asyncio.shield and returns reconciliation metadata exactly once",
    derived: "cancelling or handling a derived task cannot forge settlement or detach queued retry/network cancellation",
  },
};
if (JSON.stringify(document.fixtureProtocol?.targetPublicMappings) !== JSON.stringify(requiredTargetPublicMappings)) {
  fail("$.fixtureProtocol.targetPublicMappings", "must equal the frozen TypeScript, Go, and Python public cancellation/outcome mappings");
}

uniqueStringArray("$.fixtureProtocol.actions", document.fixtureProtocol?.actions);
const requiredExecutionModes = ["core-attempt-seam", "commitment-probe-seam", "production-http-fixture", "enterprise-network-fixture", "custom-one-attempt"];
const requiredExecutionModeSemantics = {
  "core-attempt-seam": "Run the generated SDK core with the deterministic one-attempt seam; preserve logical URI, configuration, clock, and entropy assertions without opening a socket.",
  "commitment-probe-seam": "Run the unmodified production request-body commitment tracker against an instrumented body publisher; inject one transport failure before client invocation or publisher subscription, open no socket, and prove zero publisher subscriptions and zero demand without a runner-owned commitment state machine.",
  "production-http-fixture": "Resolve $fixtureBaseUrl from the fixture handshake and run the unmodified default production HTTP transport against that explicit loopback URL, including private raw-socket request assertions; custom transports and dial rewriting are forbidden.",
  "enterprise-network-fixture": "Resolve $networkFixtureUrl from the capability-selected enterprise fixture handshake endpoint and run the actual raw-streaming production transport; exact fixture state, operation count, core-attempt count, and one-shot wire-body publication evidence are mandatory.",
  "custom-one-attempt": "Invoke the public custom one-attempt transport seam once per started attempt with distinct effective connectTimeoutMs and attemptTimeoutMs while the core retains admission, operation deadline, retry, serialization, response validation, error, and observer ownership.",
};
if (JSON.stringify(document.fixtureProtocol?.executionModes) !== JSON.stringify(requiredExecutionModes)) {
  fail("$.fixtureProtocol.executionModes", "must equal the closed execution mode list");
}
if (document.fixtureProtocol?.defaultExecutionMode !== "core-attempt-seam") {
  fail("$.fixtureProtocol.defaultExecutionMode", "must equal core-attempt-seam");
}
if (exactKeys("$.fixtureProtocol.executionModeSemantics", document.fixtureProtocol?.executionModeSemantics, requiredExecutionModes)) {
  if (JSON.stringify(document.fixtureProtocol.executionModeSemantics) !== JSON.stringify(requiredExecutionModeSemantics)) {
    fail("$.fixtureProtocol.executionModeSemantics", "must equal the closed execution mode semantics");
  }
}
if (document.fixtureProtocol?.fixtureBaseUrlToken !== "$fixtureBaseUrl") fail("$.fixtureProtocol.fixtureBaseUrlToken", "must equal $fixtureBaseUrl");
if (document.fixtureProtocol?.networkFixtureUrlToken !== "$networkFixtureUrl") fail("$.fixtureProtocol.networkFixtureUrlToken", "must equal $networkFixtureUrl");
const requiredHttpFixture = {
  command: "node sdk/jvm/certification/transport/http-fixture.js",
  bind: "127.0.0.1:0",
  protocol: "repost-transport-fixture",
  handshakeFields: ["protocol", "contractVersion", "baseUrl", "dataUrl", "controlUrl"],
  dataEndpoint: "POST /v1/messages",
  redirectTargetEndpoint: "* /__fixture/redirect-target -> 202 application/json validIdentity",
  stateFields: ["status", "ready", "caseId", "nextAttempt", "activeRequests", "barriers", "attempts", "captures", "redirectTargetHits"],
  controlEndpoints: [
    "POST /__fixture/load {caseId}",
    "GET /__fixture/state",
    "POST /__fixture/release {barrier}",
    "POST /__fixture/reset {}",
    "POST /__fixture/close {}",
  ],
};
if (JSON.stringify(document.fixtureProtocol?.httpFixture) !== JSON.stringify(requiredHttpFixture)) {
  fail("$.fixtureProtocol.httpFixture", "must equal the executable loopback fixture command, handshake, data, and control protocol");
}
const requiredNetworkFixture = {
  command: "node sdk/jvm/certification/transport/network-fixture.js",
  bind: "127.0.0.1:0 (IPv4 only)",
  protocol: "repost-enterprise-network-fixture",
  handshakeFields: ["protocol", "contractVersion", "controlUrl", "trustedBaseUrl", "trustedIpBaseUrl", "heldResponseBaseUrl", "preallocationTrapBaseUrl", "transparentDecompressionTrapBaseUrl", "staleH1PrimeBaseUrl", "staleH1ReplayBaseUrl", "hostnameMismatchBaseUrl", "tls12OnlyBaseUrl", "mtlsBaseUrl", "resetBeforeRequestBaseUrl", "resetAfterRequestBaseUrl", "directTrapBaseUrl", "noProxyTrapBaseUrl", "http2BaseUrl", "http2HeldBaseUrl", "http2ResetBaseUrl", "http2GoawayBaseUrl", "http2GoawayRetryBaseUrl", "http2FallbackBaseUrl", "proxyUrl", "authenticatedProxyUrl", "proxyUsername", "assets", "capabilities", "control", "limits"],
  capabilities: ["trusted-custom-ca", "untrusted-ca-failure", "hostname-mismatch", "tls-1.2-only", "mtls-required", "http-connect-proxy", "authenticated-http-connect-proxy", "proxy-407", "direct-trap-counter", "no-proxy-trap-counter", "reset-before-request-bytes", "reset-after-exact-body-bytes", "explicit-response-header-barrier", "alpn-h2-with-http1-fallback", "h2-multiplexing", "h2-rst-stream", "h2-client-cancellation", "h2-goaway-metadata", "control-close", "declared-length-preallocation-trap", "transparent-decompression-trap", "stale-pooled-h1-replay"],
  controlEndpoints: ["GET /__fixture/state", "POST /__fixture/reset {}", "POST /__fixture/release {barrier:responseHeaders|http2Streams|preallocationBody|staleConnection}", "POST /__fixture/close {}"],
  certificationRule: "Every supported production language transport lane must consume the handshake and prove all applicable capabilities with no silent skips; DNS and refused-connect remain platform lanes because the fixture is IPv4-only.",
  directRule: "SDK-created transports are DIRECT and must record zero direct/no-proxy trap hits despite ambient host-runtime and process proxy configuration; only explicit injected transports may proxy.",
  protocolRule: "Prefer negotiated HTTP/2 with HTTP/1.1 fallback; certify multiplexing, per-stream cancellation/reset, GOAWAY metadata and delivery/retry handling, proxy/ALPN redaction, and fallback behavior.",
};
if (JSON.stringify(document.fixtureProtocol?.networkFixture) !== JSON.stringify(requiredNetworkFixture)) {
  fail("$.fixtureProtocol.networkFixture", "must equal the closed enterprise network certification fixture declaration");
}
uniqueStringArray("$.fixtureProtocol.requestAssertions", document.fixtureProtocol?.requestAssertions);
if (JSON.stringify(document.fixtureProtocol?.requestAssertions) !== JSON.stringify(["authorization-octets-preserved", "request-shape-headers", "user-agent-suffix-boundary-256"])) {
  fail("$.fixtureProtocol.requestAssertions", "must equal the closed request assertion list");
}
uniqueStringArray("$.fixtureProtocol.testTokens", document.fixtureProtocol?.testTokens);
const requiredTestTokens = ["$bytes", "$asciiBytes", "$invalidValue", "$rawMapFixture", "$issueFixture", "$int64Max", "$providerError", "$unpairedSurrogateHeaderName", "$unpairedSurrogateHeaderValue", "$fixtureBaseUrl", "$networkFixtureUrl"];
if (JSON.stringify(document.fixtureProtocol?.testTokens) !== JSON.stringify(requiredTestTokens)) {
  fail("$.fixtureProtocol.testTokens", "must equal the closed harness token list");
}
uniqueStringArray("$.fixtureProtocol.bodyKinds", document.fixtureProtocol?.bodyKinds);
uniqueStringArray("$.fixtureProtocol.captureAllowlist", document.fixtureProtocol?.captureAllowlist);
uniqueStringArray("$.fixtureProtocol.captureDenylist", document.fixtureProtocol?.captureDenylist);
sameMembers("$.fixtureProtocol.bodyKinds", document.fixtureProtocol?.bodyKinds, ["base64", "repeat", "paddedAsset", "gzipStored", "concatenated", "jsonRecipe"]);
for (const [index, token] of (document.fixtureProtocol?.testTokens || []).entries()) {
  if (!/^\$[A-Za-z][A-Za-z0-9]*$/u.test(token)) fail(`$.fixtureProtocol.testTokens[${index}]`, "must be a $-prefixed identifier");
}
const requiredAssertionDefinitions = {
  "authorization-octets-preserved": {
    scope: "production-http-fixture-raw-socket-private",
    headerName: "Authorization",
    cardinality: "exactly-one",
    construction: "UTF-8 bytes of ASCII `Bearer ` concatenated byte-exact with the snapshotted resolved credential",
    comparison: "streaming SHA-256 digest of the raw socket field-value octets, byte-exact with no parser trimming, normalization, or rewriting",
    canonicalExpectedUtf8Hex: "42656172657220206170692d6b65792d776974682d73706163657320",
    evidence: "fixture:attempt:<n>:assertion:authorization-octets-preserved:pass",
    retention: "discard raw octets and digests immediately after private comparison; never expose them in fixture state, errors, logs, or public traces",
  },
  "request-shape-headers": {
    scope: "production-http-fixture-raw-socket-private",
    requestLine: "exactly POST /v1/messages HTTP/1.1 with no query or redirect follow",
    headerOrder: "exactly Host, Content-Length, Authorization, Content-Type, Accept-Encoding, User-Agent, Idempotency-Key, then optional traceparent; no other or duplicate field",
    host: "exactly one canonical fixture authority with its explicit port preserved",
    contentLength: "exactly one canonical decimal field equal to actual request-body bytes, with no leading zero except 0",
    contentType: "exactly one Content-Type field with value application/json",
    acceptEncoding: "exactly one Accept-Encoding field with value gzip",
    userAgent: "exactly one User-Agent field matching ^repost-[a-z0-9.-]+/[0-9A-Za-z.+-]+ contract-suite/1$",
    idempotencyKey: "exactly one Idempotency-Key field matching the snapshotted operation key",
    optionalTraceparent: "zero or one exact lowercase 55-byte W3C traceparent when target-authorized",
    forbidden: "Transfer-Encoding, Connection, Expect, Upgrade, TE, chunked framing, duplicate Host or Content-Length, and every unlisted field",
    evidence: "fixture:attempt:<n>:assertion:request-shape-headers:pass",
    retention: "inspect raw request line, header names/values/order, and byte count only while serving the request; retain no header value, credential, trace identifier, or body in fixture state, errors, logs, or public traces",
  },
  "user-agent-suffix-boundary-256": {
    scope: "production-http-fixture-raw-request-private",
    cardinality: "exactly one User-Agent field",
    construction: "SDK-owned runtime identity, one SP, then exactly 256 lowercase u octets",
    evidence: "fixture:attempt:<n>:assertion:user-agent-suffix-boundary-256:pass",
    retention: "validate and immediately discard the User-Agent value; never expose it in fixture state, errors, logs, or public traces",
  },
};
if (exactKeys("$.fixtureProtocol.assertionDefinitions", document.fixtureProtocol?.assertionDefinitions, document.fixtureProtocol?.requestAssertions || [])) {
  for (const assertion of document.fixtureProtocol?.requestAssertions || []) {
    if (JSON.stringify(document.fixtureProtocol.assertionDefinitions[assertion]) !== JSON.stringify(requiredAssertionDefinitions[assertion])) {
      fail(`$.fixtureProtocol.assertionDefinitions.${assertion}`, "must equal the closed private request-assertion definition");
    }
  }
}
if (exactKeys("$.fixtureProtocol.testTokenSemantics", document.fixtureProtocol?.testTokenSemantics, document.fixtureProtocol?.testTokens || [])) {
  for (const token of document.fixtureProtocol?.testTokens || []) nonEmptyString(`$.fixtureProtocol.testTokenSemantics.${token}`, document.fixtureProtocol.testTokenSemantics[token]);
  if (document.fixtureProtocol.testTokenSemantics.$providerError !== "the private injected provider throws using the declared synthetic cause discriminator and sentinel message; the public contract retains only the mapped closed causeCategory and never a class name, discriminator, or provider message") {
    fail("$.fixtureProtocol.testTokenSemantics.$providerError", "must pin private synthetic provider injection and public closed cause-category semantics");
  }
  if (document.fixtureProtocol.testTokenSemantics.$unpairedSurrogateHeaderName !== "only as a response-action header key, materialize after JSON parsing to x-repost followed by one unpaired UTF-16 high surrogate U+D800; canonical JSON itself contains scalar Unicode only") {
    fail("$.fixtureProtocol.testTokenSemantics.$unpairedSurrogateHeaderName", "must pin post-parse header-name surrogate materialization without non-scalar canonical JSON");
  }
  if (document.fixtureProtocol.testTokenSemantics.$unpairedSurrogateHeaderValue !== "only as a response-action header value, materialize after JSON parsing to value followed by one unpaired UTF-16 high surrogate U+D800; canonical JSON itself contains scalar Unicode only") {
    fail("$.fixtureProtocol.testTokenSemantics.$unpairedSurrogateHeaderValue", "must pin post-parse header-value surrogate materialization without non-scalar canonical JSON");
  }
  if (document.fixtureProtocol.testTokenSemantics.$fixtureBaseUrl !== "resolve only for production-http-fixture cases from the one-line loopback fixture handshake; never expose it as public SDK configuration") {
    fail("$.fixtureProtocol.testTokenSemantics.$fixtureBaseUrl", "must pin the private handshake-only substitution semantics");
  }
  if (document.fixtureProtocol.testTokenSemantics.$networkFixtureUrl !== "resolve only for enterprise-network-fixture cases from the proveNetworkCapability action's endpointField in the one-line enterprise fixture handshake; never expose it as public SDK configuration") {
    fail("$.fixtureProtocol.testTokenSemantics.$networkFixtureUrl", "must pin the private capability-selected enterprise handshake substitution semantics");
  }
  if (document.fixtureProtocol.testTokenSemantics.$rawMapFixture !== "materialize only in the four raw-map canonicalization vectors: validate all keys before entry traversal, then snapshot valid entries in unsigned UTF-8 byte lexicographic key order without normalization; the token and raw keys never enter a public SDK surface") {
    fail("$.fixtureProtocol.testTokenSemantics.$rawMapFixture", "must pin private raw-map fixture materialization, canonical ordering, and public redaction");
  }
  if (document.fixtureProtocol.testTokenSemantics.$issueFixture !== "materialize only in the three bounded issue-reporting vectors as a fixed invalid descriptor/model traversal; retain the declared static paths and counts without materializing customer values, raw keys, classes, or exception text") {
    fail("$.fixtureProtocol.testTokenSemantics.$issueFixture", "must pin private bounded issue-fixture materialization without dynamic public text");
  }
}
if (exactKeys("$.fixtureProtocol.bodyKindsSemantics", document.fixtureProtocol?.bodyKindsSemantics, document.fixtureProtocol?.bodyKinds || [])) {
  for (const kind of document.fixtureProtocol?.bodyKinds || []) nonEmptyString(`$.fixtureProtocol.bodyKindsSemantics.${kind}`, document.fixtureProtocol.bodyKindsSemantics[kind]);
}
for (const field of ["executionRule", "barrierRule", "partialRequestRule", "partialResponseRule", "captureRule", "disconnectRule", "orderedResponseRule", "echoRule"]) {
  nonEmptyString(`$.fixtureProtocol.${field}`, document.fixtureProtocol?.[field]);
}
if (JSON.stringify(document.fixtureProtocol?.causeTypeAllowlist) !== JSON.stringify(requiredSyntheticCauseTypes)) {
  fail("$.fixtureProtocol.causeTypeAllowlist", "must be the closed synthetic cause allowlist");
}
const requiredFailureCauseTypes = {
  dns: "ConformanceDnsFailure",
  connect: "ConformanceConnectFailure",
  proxy: "ConformanceProxyFailure",
  io: "ConformanceIoFailure",
  "tls-certificate": "ConformanceTlsCertificateFailure",
  "tls-hostname": "ConformanceTlsHostnameFailure",
  "tls-protocol": "ConformanceTlsProtocolFailure",
  mtls: "ConformanceTlsProtocolFailure",
};
const allowedFailureReasonsByKind = {
  dns: new Set(["DNS_NOT_FOUND", "DNS_TIMEOUT", "UNKNOWN"]),
  connect: new Set(["CONNECT_REFUSED", "CONNECT_TIMEOUT", "CONNECTION_RESET", "CONNECTION_CLOSED", "UNKNOWN"]),
  proxy: new Set(["PROXY_CONNECT_FAILED", "UNKNOWN"]),
  io: new Set(["CONNECTION_RESET", "CONNECTION_CLOSED", "UNKNOWN"]),
  "tls-certificate": new Set(["TLS_UNTRUSTED", "TLS_CERTIFICATE_EXPIRED", "TLS_CERTIFICATE_NOT_YET_VALID", "UNKNOWN"]),
  "tls-hostname": new Set(["TLS_HOSTNAME_MISMATCH", "UNKNOWN"]),
  "tls-protocol": new Set(["TLS_NEGOTIATION", "UNKNOWN"]),
  mtls: new Set(["TLS_UNTRUSTED", "TLS_NEGOTIATION", "UNKNOWN"]),
};
if (exactKeys("$.fixtureProtocol.failureCauseTypes", document.fixtureProtocol?.failureCauseTypes, [...failureKinds])) {
  for (const kind of failureKinds) {
    const causeType = document.fixtureProtocol.failureCauseTypes[kind];
    if (causeType !== requiredFailureCauseTypes[kind]) fail(`$.fixtureProtocol.failureCauseTypes.${kind}`, "must equal the fixed synthetic failure cause type");
  }
}
if (exactKeys("$.fixtureProtocol.requestBodyBytesByProfile", document.fixtureProtocol?.requestBodyBytesByProfile, Object.keys(document.inputProfiles || {}))) {
  for (const [profileName, byteLength] of Object.entries(document.fixtureProtocol.requestBodyBytesByProfile)) {
    integer(`$.fixtureProtocol.requestBodyBytesByProfile.${profileName}`, byteLength, 0);
  }
}

const traceVocabularyFields = ["configuration", "attempt", "commit", "schedule", "sideEffect", "error", "byte", "redaction", "observer", "context", "fixture"];
if (exactKeys("$.traceVocabulary", document.traceVocabulary, traceVocabularyFields)) {
  for (const field of traceVocabularyFields) {
    const value = document.traceVocabulary[field];
    if (Array.isArray(value)) uniqueStringArray(`$.traceVocabulary.${field}`, value);
    else nonEmptyString(`$.traceVocabulary.${field}`, value);
  }
  const requiredContextVocabulary = [
    "caller.set|token=<opaque private token>",
    "operation.capture|token=<opaque private token>",
    "callback|event=<observer event>|token=<opaque private token>",
    "caller.restore|token=<opaque private token>",
  ];
  if (JSON.stringify(document.traceVocabulary.context) !== JSON.stringify(requiredContextVocabulary)) {
    fail("$.traceVocabulary.context", "must equal the closed private observer-context vocabulary");
  }
  const requiredFixtureVocabulary = "fixture:attempt:<n>:<accepted|request-bytes:<count>|body-subscriptions:<count>:body-demands:<count>|idempotency:<value>|response-bytes:<count>|response-cancelled:<count>|response-close:<count>|transferred-body-close:<count>|seam-failure:<mode>:<defect>|disconnect:<phase>|failure:<kind>|proxy-auth:407|assertion:<name>:pass> or fixture:network:<capability>:<pass|setup:<tokens>|outcomes:<tokens>|operations:<count>:core-attempts:<count>:wire-body-publications:<count>:state:<assertion>> or fixture:redirect-target-hits:<count> or fixture:serialization-buffer-peak:<count>";
  if (document.traceVocabulary.fixture !== requiredFixtureVocabulary) {
    fail("$.traceVocabulary.fixture", "must equal the closed fixture evidence vocabulary");
  }
}

const actionSchemaFields = ["actor", "requiredFields", "optionalFields", "fieldSchemas", "allowedPhases", "allowedKinds", "allowedEvents", "semantics"];
const fieldSchemaFields = ["type", "const", "minimum", "maximum", "minLength", "pattern", "items", "minItems", "uniqueItems", "values", "format"];
const actionSchemas = document.fixtureProtocol?.actionSchemas || {};
const actionDefinitions = {
  hold: ["fixture", ["actor", "action", "attempt", "phase", "barrier"], ["releaseAt"], [...phases], [], []],
  release: ["runner", ["actor", "action", "barrier"], [], [], [], []],
  advanceTime: ["runner", ["actor", "action", "byMs"], [], [], [], []],
  advanceWallTime: ["runner", ["actor", "action", "byMs"], [], [], [], []],
  cancelSend: ["runner", ["actor", "action", "phase"], [], [...phases], [], []],
  closeClient: ["runner", ["actor", "action", "phase"], [], [...phases], [], []],
  acceptRequest: ["fixture", ["actor", "action", "attempt", "assertions"], [], [], [], []],
  captureRequestMetadata: ["fixture", ["actor", "action", "attempt"], [], [], [], []],
  disconnect: ["fixture", ["actor", "action", "attempt", "phase"], [], ["beforeRequestBytes", "afterRequestBytes"], [], []],
  failConnection: ["fixture", ["actor", "action", "attempt", "phase", "kind"], ["causeType", "causeMessage", "failureReason", "bodySubscriptions", "bodyDemands", "commitState", "customException", "seamFailure", "responseConstructionDefect", "transferredBodyCloseCount"], ["beforeRequestBytes", "afterRequestBytes"], [...failureKinds], []],
  failProxyAuthentication: ["fixture", ["actor", "action", "attempt", "status"], ["failureReason"], [], [], []],
  proveNetworkCapability: ["runner", ["actor", "action", "capability", "endpointField", "setup", "operationOutcomes", "operationCount", "coreAttemptCount", "wireBodyPublications", "expectedState"], [], [], [], []],
  mutateEnvironmentAfterConstruction: ["runner", ["actor", "action"], [], [], [], []],
  rejectDeadlineScheduler: ["runner", ["actor", "action"], [], [], [], []],
  rejectExecutorSubmission: ["runner", ["actor", "action"], [], [], [], []],
  runWorkerInlineDuringSubmit: ["runner", ["actor", "action"], [], [], [], []],
  starveQueuedWorkerPastDeadline: ["runner", ["actor", "action"], [], [], [], []],
  cancelQueuedWorker: ["runner", ["actor", "action"], [], [], [], []],
  closeQueuedWorker: ["runner", ["actor", "action"], [], [], [], []],
  rejectRetryScheduler: ["runner", ["actor", "action", "phase"], [], ["backoff"], [], []],
  drainRuntimeDiagnostics: ["runner", ["actor", "action"], [], [], [], []],
  observerDrop: ["runner", ["actor", "action", "event"], [], [], [], [...observerEvents]],
  respond: ["fixture", ["actor", "action", "attempt", "status", "headers", "body"], ["closeThrows", "mutateSourceHeadersAfterFactory"], [], [], []],
  respondChunks: ["fixture", ["actor", "action", "attempt", "status", "headers", "chunks"], ["complete"], [], [], []],
  respondEchoSentinels: ["fixture", ["actor", "action", "attempt", "status", "sentinels"], [], [], [], []],
  observerFail: ["runner", ["actor", "action", "event"], [], [], [], [...observerEvents]],
};
if (JSON.stringify(Object.keys(actionSchemas).sort()) !== JSON.stringify([...actions].sort())) {
  fail("$.fixtureProtocol.actionSchemas", "must contain exactly one schema for every fixture action");
}
for (const [actionName, schema] of Object.entries(actionSchemas)) {
  const location = `$.fixtureProtocol.actionSchemas.${actionName}`;
  if (!exactKeys(location, schema, actionSchemaFields)) continue;
  const definition = actionDefinitions[actionName];
  if (!definition) fail(location, "has no validator-owned action definition");
  else {
    const actualDefinition = [schema.actor, schema.requiredFields, schema.optionalFields, schema.allowedPhases, schema.allowedKinds, schema.allowedEvents];
    if (JSON.stringify(actualDefinition) !== JSON.stringify(definition)) fail(location, "actor, fields, phases, kinds, and events must match the closed action definition");
  }
  if (schema.actor !== "runner" && schema.actor !== "fixture") fail(`${location}.actor`, "must be runner or fixture");
  uniqueStringArray(`${location}.requiredFields`, schema.requiredFields);
  uniqueStringArray(`${location}.optionalFields`, schema.optionalFields, { nonEmpty: false });
  uniqueStringArray(`${location}.allowedPhases`, schema.allowedPhases, { nonEmpty: false });
  uniqueStringArray(`${location}.allowedKinds`, schema.allowedKinds, { nonEmpty: false });
  uniqueStringArray(`${location}.allowedEvents`, schema.allowedEvents, { nonEmpty: false });
  const required = new Set(schema.requiredFields || []);
  const optional = new Set(schema.optionalFields || []);
  if (required.size !== (schema.requiredFields || []).length) fail(`${location}.requiredFields`, "must not contain duplicates");
  if (optional.size !== (schema.optionalFields || []).length) fail(`${location}.optionalFields`, "must not contain duplicates");
  if (!required.has("actor") || !required.has("action")) fail(`${location}.requiredFields`, "must require actor and action");
  for (const field of optional) if (required.has(field)) fail(`${location}.optionalFields`, `${field} is also required`);
  const declaredFields = [...required, ...optional].sort();
  const schemaFields = object(schema.fieldSchemas) ? Object.keys(schema.fieldSchemas).sort() : [];
  if (JSON.stringify(declaredFields) !== JSON.stringify(schemaFields)) fail(`${location}.fieldSchemas`, "must exactly describe requiredFields and optionalFields");
  for (const [field, fieldSchema] of Object.entries(schema.fieldSchemas || {})) {
    const fieldLocation = `${location}.fieldSchemas.${field}`;
    if (!exactKeys(fieldLocation, fieldSchema, ["type"], fieldSchemaFields.filter((key) => key !== "type"))) continue;
    if (!["string", "integer", "boolean", "object", "array"].includes(fieldSchema.type)) fail(`${fieldLocation}.type`, "unknown primitive type");
    if ("minimum" in fieldSchema && !Number.isSafeInteger(fieldSchema.minimum)) fail(`${fieldLocation}.minimum`, "must be a safe integer");
    if ("maximum" in fieldSchema && !Number.isSafeInteger(fieldSchema.maximum)) fail(`${fieldLocation}.maximum`, "must be a safe integer");
    if ("minLength" in fieldSchema && (!Number.isInteger(fieldSchema.minLength) || fieldSchema.minLength < 0)) fail(`${fieldLocation}.minLength`, "must be a nonnegative integer");
    if ("minItems" in fieldSchema && (!Number.isInteger(fieldSchema.minItems) || fieldSchema.minItems < 0)) fail(`${fieldLocation}.minItems`, "must be a nonnegative integer");
    if ("uniqueItems" in fieldSchema && typeof fieldSchema.uniqueItems !== "boolean") fail(`${fieldLocation}.uniqueItems`, "must be boolean");
    if ("pattern" in fieldSchema) {
      if (typeof fieldSchema.pattern !== "string") fail(`${fieldLocation}.pattern`, "must be a string");
      else try { new RegExp(fieldSchema.pattern); } catch { fail(`${fieldLocation}.pattern`, "must be a valid regular expression"); }
    }
    if ("const" in fieldSchema) {
      const constMatches = fieldSchema.type === "integer"
        ? Number.isSafeInteger(fieldSchema.const)
        : fieldSchema.type === "array"
          ? Array.isArray(fieldSchema.const)
          : fieldSchema.type === "object"
            ? object(fieldSchema.const)
            : typeof fieldSchema.const === fieldSchema.type;
      if (!constMatches) fail(`${fieldLocation}.const`, "must match the declared type");
    }
    if ("minimum" in fieldSchema && fieldSchema.type !== "integer") fail(`${fieldLocation}.minimum`, "is valid only for integer fields");
    if ("maximum" in fieldSchema && fieldSchema.type !== "integer") fail(`${fieldLocation}.maximum`, "is valid only for integer fields");
    if (Number.isSafeInteger(fieldSchema.minimum) && Number.isSafeInteger(fieldSchema.maximum) && fieldSchema.minimum > fieldSchema.maximum) fail(fieldLocation, "minimum must not exceed maximum");
    if (("minLength" in fieldSchema || "pattern" in fieldSchema) && fieldSchema.type !== "string") fail(fieldLocation, "string constraints require type string");
    if (("items" in fieldSchema || "minItems" in fieldSchema || "uniqueItems" in fieldSchema) && fieldSchema.type !== "array") fail(fieldLocation, "array constraints require type array");
    if ("items" in fieldSchema && !["body", "requestAssertion", "sentinelName", "networkSetup", "networkOutcome"].includes(fieldSchema.items)) fail(`${fieldLocation}.items`, "unknown array item schema");
    if ("values" in fieldSchema && (fieldSchema.type !== "object" || fieldSchema.values !== "headerValue")) fail(`${fieldLocation}.values`, "unknown object value schema");
    if ("format" in fieldSchema && (fieldSchema.type !== "object" || fieldSchema.format !== "body")) fail(`${fieldLocation}.format`, "unknown object format");
  }
  if (schema.fieldSchemas?.actor?.const !== schema.actor) fail(`${location}.fieldSchemas.actor.const`, "must match actor");
  if (schema.fieldSchemas?.action?.const !== actionName) fail(`${location}.fieldSchemas.action.const`, "must match the action name");
  for (const phase of schema.allowedPhases || []) if (!phases.has(phase)) fail(`${location}.allowedPhases`, `unknown phase ${phase}`);
  for (const kind of schema.allowedKinds || []) if (!failureKinds.has(kind)) fail(`${location}.allowedKinds`, `unknown kind ${kind}`);
  for (const event of schema.allowedEvents || []) if (!observerEvents.has(event)) fail(`${location}.allowedEvents`, `unknown event ${event}`);
  if (typeof schema.semantics !== "string" || schema.semantics.length === 0) fail(`${location}.semantics`, "must be a non-empty string");
}
stringArray("$.fixtureProtocol.requestAssertions", document.fixtureProtocol?.requestAssertions);
for (const [profileName, byteLength] of Object.entries(document.fixtureProtocol?.requestBodyBytesByProfile || {})) {
  if (!document.inputProfiles?.[profileName]) fail(`$.fixtureProtocol.requestBodyBytesByProfile.${profileName}`, "unknown input profile");
  if (!Number.isInteger(byteLength) || byteLength < 0) fail(`$.fixtureProtocol.requestBodyBytesByProfile.${profileName}`, "must be a nonnegative integer");
}
for (const profileName of Object.keys(document.inputProfiles || {})) {
  if (!Number.isInteger(document.fixtureProtocol?.requestBodyBytesByProfile?.[profileName])) fail("$.fixtureProtocol.requestBodyBytesByProfile", `missing deterministic byte length for ${profileName}`);
  const profile = document.inputProfiles[profileName];
  const defaultEnvelope = {
    type: profile.send?.eventType,
    customerId: profile.send?.customerId,
    timestamp: new Date(profile.clock?.defaultNowUnixMs).toISOString(),
    data: profile.send?.payload,
  };
  const actualBytes = Buffer.byteLength(JSON.stringify(defaultEnvelope), "utf8");
  if (document.fixtureProtocol?.requestBodyBytesByProfile?.[profileName] !== actualBytes) {
    fail(`$.fixtureProtocol.requestBodyBytesByProfile.${profileName}`, `must equal compact default envelope length ${actualBytes}`);
  }
}
const requiredCaptureAllowlist = ["attempt", "commitment", "responseProgress", "idempotencyKey"];
const requiredCaptureDenylist = ["authorization", "apiKey", "headers", "url", "customerId", "eventType", "schema", "payload"];
if (JSON.stringify(document.fixtureProtocol?.captureAllowlist) !== JSON.stringify(requiredCaptureAllowlist)) fail("$.fixtureProtocol.captureAllowlist", "must be the closed private capture allowlist");
if (JSON.stringify(document.fixtureProtocol?.captureDenylist) !== JSON.stringify(requiredCaptureDenylist)) fail("$.fixtureProtocol.captureDenylist", "must be the closed private capture denylist");

exactKeys("$.errorCatalog", document.errorCatalog, [...errorCodes]);
for (const [code, entry] of Object.entries(document.errorCatalog || {})) {
  if (!errorCodes.has(code)) fail(`$.errorCatalog.${code}`, "code is absent from enums.errorCodes");
  exactKeys(`$.errorCatalog.${code}`, entry, ["message"]);
  if (typeof entry?.message !== "string" || entry.message.length === 0) {
    fail(`$.errorCatalog.${code}.message`, "must be a non-empty SDK-owned message");
  }
}
for (const code of errorCodes) {
  if (!document.errorCatalog?.[code]) fail("$.errorCatalog", `missing catalog entry ${code}`);
}

const optionFields = new Set([
  "apiKey",
  "apiKeyProvider",
  "baseUrl",
  "maxAttempts",
  "connectTimeoutMs",
  "attemptTimeoutMs",
  "operationTimeoutMs",
  "maxInFlight",
  "maxBufferedBytes",
  "retryBaseMs",
  "retryMaxMs",
  "retryAfterMaxMs",
  "requestLimitBytes",
  "responseCompressedLimitBytes",
  "responseDecompressedLimitBytes",
  "expansionRatioLimit",
  "errorInspectionLimitBytes",
  "userAgent",
  "idempotencyKey",
  "generateIdempotencyKey",
  "followRedirects",
  "proxyUrl",
  "proxyCredential",
  "clientCertificatePath",
  "clientCertificatePassword",
  "observer",
  "occupiedPermits",
  "serializationFailure",
  "descriptorVersion",
  "transportMode",
  "builtInTransportOptions",
]);
const requiredOptionClassification = {
  publicClientConfig: [
    "apiKey",
    "apiKeyProvider",
    "baseUrl",
    "maxAttempts",
    "connectTimeoutMs",
    "attemptTimeoutMs",
    "operationTimeoutMs",
    "maxInFlight",
    "maxBufferedBytes",
    "retryBaseMs",
    "retryMaxMs",
    "userAgent",
    "builtInTransportOptions",
  ],
  publicSendConfig: ["idempotencyKey"],
  injectedSeams: ["generateIdempotencyKey", "observer", "transportMode"],
  enterpriseClientInputs: ["followRedirects", "proxyUrl", "proxyCredential", "clientCertificatePath", "clientCertificatePassword"],
  harnessOnlyOverrides: [
    "retryAfterMaxMs",
    "requestLimitBytes",
    "responseCompressedLimitBytes",
    "responseDecompressedLimitBytes",
    "expansionRatioLimit",
    "errorInspectionLimitBytes",
    "occupiedPermits",
    "serializationFailure",
    "descriptorVersion",
  ],
};
if (exactKeys("$.fixtureProtocol.optionClassification", document.fixtureProtocol?.optionClassification, Object.keys(requiredOptionClassification))) {
  const classified = [];
  for (const [classification, requiredFields] of Object.entries(requiredOptionClassification)) {
    const location = `$.fixtureProtocol.optionClassification.${classification}`;
    uniqueStringArray(location, document.fixtureProtocol.optionClassification[classification], { nonEmpty: false });
    if (JSON.stringify(document.fixtureProtocol.optionClassification[classification]) !== JSON.stringify(requiredFields)) {
      fail(location, `must equal the closed ${classification} option list`);
    }
    classified.push(...(document.fixtureProtocol.optionClassification[classification] || []));
  }
  if (new Set(classified).size !== classified.length) fail("$.fixtureProtocol.optionClassification", "must classify every option exactly once without duplicates");
  if (JSON.stringify([...classified].sort()) !== JSON.stringify([...optionFields].sort())) {
    fail("$.fixtureProtocol.optionClassification", "must classify every declared option exactly once");
  }
}
const optionModeValues = new Map([
  ["transportMode", new Set(["default", "custom"])],
]);

function hasForbiddenBaseUrlComponents(options) {
  if (typeof options?.baseUrl !== "string") return false;
  try {
    const parsed = new URL(options.baseUrl);
    return Boolean(parsed.username || parsed.password || parsed.search || parsed.hash);
  } catch {
    return false;
  }
}

function hasConstructionOptionConflict(options) {
  if (!object(options)) return false;
  const providerAndFixedKey = options.apiKeyProvider === true && Object.hasOwn(options, "apiKey");
  const transportSelections = [options.transportMode === "custom", options.builtInTransportOptions === true].filter(Boolean).length;
  const invalidFixedBaseUrl = Object.hasOwn(options, "baseUrl") && !rawBaseUrlValid(options.baseUrl, true);
  return providerAndFixedKey || transportSelections > 1 || hasForbiddenBaseUrlComponents(options) || invalidFixedBaseUrl;
}
const maxAttemptsOutOfRangeCases = new Map([
  ["defaults-max-attempts-zero-rejected", 0],
  ["defaults-max-attempts-above-maximum-rejected", 11],
]);
const maxInFlightOutOfRangeCases = new Map([
  ["defaults-max-in-flight-zero-rejected", 0],
  ["defaults-max-in-flight-above-maximum-rejected", 65537],
]);
const maxBufferedBytesOutOfRangeCases = new Map([
  ["configuration-buffer-budget-below-minimum-rejected", 4194303],
  ["configuration-buffer-budget-above-maximum-rejected", 1073741825],
]);
const invalidDurationCaseIds = new Set([
  "configuration-duration-zero-rejected",
  "configuration-duration-fractional-millisecond-rejected",
  "configuration-duration-maximum-plus-one-rejected",
]);

const inputFields = new Set(["profile", "env", "send", "clock", "entropy", "retryEntropyError", "providerValues", "generatedValues", "sentinelValues", "observerContext"]);
const caseFields = ["caseId", "category", "description", "input", "options", "script", "expected"];
const optionalCaseFields = ["executionMode"];
const expectedFields = [
  "configurationTrace",
  "attemptTrace",
  "commitTrace",
  "scheduleTrace",
  "sideEffectTrace",
  "errorTrace",
  "error",
  "legacyBody",
  "delivery",
  "byteTrace",
  "redactionTrace",
  "observerEmissionTrace",
  "observerCallbackTrace",
  "fixtureTrace",
  "result",
  "primarySurface",
  "completionRoute",
];
const optionalExpectedFields = ["contextTrace", "outcomeEvidence", "constructionEvidence", "runtimeDiagnostics", "rawMapEvidence"];
const outcomeEvidenceFields = ["operationId", "primaryKind", "nativeCancelled", "settlements", "code", "delivery", "attemptCount", "httpStatus", "idempotencyKey", "noGhostWork", "failureReason", "remediationKey", "causeCategory"];
const constructionEvidenceFields = ["code", "causeCategory", "message", "operationId", "idempotencyKey", "admitted", "settlements"];
const resultFields = ["id", "type", "customerId", "timestamp"];
const errorFields = ["operationId", "code", "delivery", "attemptCount", "httpStatus", "retryable", "responseHeaderFields", "responseHeaderBytes", "compressedBytes", "decompressedBytes", "truncated", "closeFailureCount", "closeFailureCategories", "validationIssues", "configurationIssues", "issueCount", "issuesTruncated", "idempotencyKey", "failureReason", "remediationKey", "causeCategory", "message"];
const legacyBodyFields = ["code", "message", "truncated"];
if (JSON.stringify(document.constants?.terminalErrorSafeFields) !== JSON.stringify(errorFields)) fail("$.constants.terminalErrorSafeFields", "must exactly match the closed terminal error fields");
if (JSON.stringify(document.constants?.legacyErrorBodyAllowlist) !== JSON.stringify(legacyBodyFields)) fail("$.constants.legacyErrorBodyAllowlist", "must exactly match the closed legacy body fields");
if (document.constants?.followRedirects !== false) fail("$.constants.followRedirects", "must be false");
const attemptPattern = /^attempt:[1-9][0-9]*:(start|commit|response:[2-5][0-9]{2}|end:[a-z0-9-]+)@[0-9]+$/;
const configurationPattern = /^(credential:(apiKeyProvider|apiKey|REPOST_SEND_API_KEY|REPOST_TOKEN|missing|invalid)|base-url:(baseUrl|REPOST_API_URL|default|invalid)|endpoint-path:\/[^\r\n]*|redirects:false|snapshot:[0-9]+)$/;
const commitPattern = /^(attempt:[1-9][0-9]*:(NOT_SENT|POSSIBLY_SENT)|operation:POSSIBLY_SENT)@[0-9]+$/;
const schedulePattern = /^retry:[1-9][0-9]*:(?:(?:cap|requested|delay|clamped|deadline|cancelled|scheduler-rejected)=[0-9]+|bound=[1-9][0-9]*|entropy=-?[0-9]+|entropy-error=1|retry-after=(?:invalid|[0-9]+))@[0-9]+$/;
const invalidEntropyCases = new Map([
  ["jitter-negative-injected-output-rejected", "negative"],
  ["jitter-exclusive-bound-injected-output-rejected", "exclusive-bound"],
]);
const operationId = "[A-Za-z0-9._:-]{1,64}";
const observerPatterns = [
  new RegExp(`^operation\\.start\\|operationId=${operationId}$`),
  new RegExp(`^attempt\\.start\\|operationId=${operationId}\\|attempt=[1-9][0-9]*$`),
  new RegExp(`^attempt\\.end\\|operationId=${operationId}\\|attempt=[1-9][0-9]*\\|durationMs=[0-9]+\\|outcome=[a-z0-9-]+\\|code=(?:NONE|[A-Z_]+)\\|failureReason=(?:NONE|[A-Z_]+)\\|delivery=(?:NOT_SENT|POSSIBLY_SENT|ACCEPTED|REJECTED|CANCELLED_UNKNOWN)\\|statusClass=(?:none|[2-5]xx)$`),
  new RegExp(`^retry\\.delay\\|operationId=${operationId}\\|attempt=(?:[2-9]|[1-9][0-9]+)\\|delayMs=[0-9]+$`),
  new RegExp(`^operation\\.cancel\\|operationId=${operationId}\\|delivery=(?:NOT_SENT|CANCELLED_UNKNOWN)$`),
  new RegExp(`^operation\\.end\\|operationId=${operationId}\\|durationMs=[0-9]+\\|outcome=(?:accepted|rejected|cancelled|overloaded|failed)\\|code=(?:NONE|[A-Z_]+)\\|failureReason=(?:NONE|[A-Z_]+)\\|delivery=(?:NOT_SENT|POSSIBLY_SENT|ACCEPTED|REJECTED|CANCELLED_UNKNOWN)\\|attempts=[0-9]+$`),
];
const attemptOutcomeCodes = new Map([
  ["accepted", "NONE"],
  ["attempt-timeout", "ATTEMPT_TIMEOUT"],
  ["cancelled", "CANCELLED"],
  ["closed", "CLOSED"],
  ["conflict", "HTTP_REJECTED"],
  ["connect", "CONNECT"],
  ["connect-timeout", "CONNECT"],
  ["dns", "DNS"],
  ["http-rejected", "HTTP_REJECTED"],
  ["io", "IO"],
  ["mtls", "TLS"],
  ["operation-deadline", "OPERATION_DEADLINE"],
  ["proxy", "PROXY"],
  ["proxy-auth", "PROXY"],
  ["rate-limited", "RATE_LIMITED"],
  ["response-protocol", "RESPONSE_PROTOCOL"],
  ["response-too-large", "RESPONSE_TOO_LARGE"],
  ["server-failure", "SERVER_FAILURE"],
  ["tls-certificate", "TLS"],
  ["tls-hostname", "TLS"],
  ["tls-protocol", "TLS"],
]);
const sideEffectPattern = /^(permit\.(?:acquire|reject)|configuration\.snapshot|credentials\.(?:snapshot|provider:[0-9]+)|defaults\.snapshot|idempotency\.generate:[0-9]+|serialize:[0-9]+|network:[0-9]+|response\.cancel|permit\.release)$/;
const bytePattern = /^(request|response-wire|response-decoded|error-discarded):[0-9]+:(accepted|rejected|cancelled)$/;
const redactionPattern = /^(scan:(render|logs|observer|metric-tags|spans):clean|allow:error\.idempotencyKey:1)$/;
const fixturePattern = /^(?:fixture:attempt:[1-9][0-9]*:(?:accepted|request-bytes:[0-9]+|body-subscriptions:[0-9]+:body-demands:[0-9]+|idempotency:[^\u0000-\u001f\u007f]+|response-bytes:[0-9]+|response-cancelled:[0-9]+|response-close:[0-9]+|transferred-body-close:[01]|seam-failure:(?:no-response-and-no-failure|transport-invocation-failure|response-construction-failure):(?:none|status-below-range|status-above-range|null-headers|null-body)|disconnect:(?:beforeRequestBytes|afterRequestBytes)|failure:[a-z-]+|proxy-auth:407|capture-private:clean|assertion:[a-z0-9-]+:pass)|fixture:network:[a-z0-9.-]+:(?:pass|setup:[A-Za-z0-9,-]+|outcomes:[A-Za-z0-9_=:\-|]+)|fixture:network:[a-z0-9.-]+:operations:[1-9][0-9]*:core-attempts:[1-9][0-9]*:wire-body-publications:[0-9]+:state:[\x20-\x7e]+|fixture:redirect-target-hits:[0-9]+|fixture:serialization-buffer-peak:[0-9]+)$/u;

function validateBody(location, body) {
  if (!object(body)) {
    fail(location, "must be a body object");
    return;
  }
  const bodyKinds = new Set(document.fixtureProtocol?.bodyKinds || []);
  if (!("kind" in body) && "asset" in body) {
    exactKeys(location, body, ["asset"]);
    if (typeof body.asset !== "string" || !document.fixtureProtocol?.bodyAssets?.[body.asset]) fail(`${location}.asset`, "unknown body asset");
    return;
  }
  if (!bodyKinds.has(body.kind)) fail(`${location}.kind`, "unknown body kind");
  if (body.kind === "base64") {
    exactKeys(location, body, ["kind", "data"]);
    strictBase64(`${location}.data`, body.data);
  }
  if (body.kind === "repeat") {
    exactKeys(location, body, ["kind", "byte", "length"]);
    if (!Number.isInteger(body.byte) || body.byte < 0 || body.byte > 255) fail(`${location}.byte`, "must be one octet");
    if (!Number.isInteger(body.length) || body.length < 0) fail(`${location}.length`, "must be a nonnegative integer");
  }
  if (body.kind === "paddedAsset") {
    exactKeys(location, body, ["kind", "asset", "totalLength"]);
    if (!document.fixtureProtocol?.bodyAssets?.[body.asset]) fail(`${location}.asset`, "unknown body asset");
    if (!Number.isInteger(body.totalLength) || body.totalLength < 0) fail(`${location}.totalLength`, "must be a nonnegative integer");
    const assetLength = document.fixtureProtocol?.bodyAssets?.[body.asset]?.wireBytes;
    if (Number.isInteger(assetLength) && Number.isInteger(body.totalLength) && body.totalLength < assetLength) fail(`${location}.totalLength`, "must not be smaller than the referenced asset");
  }
  if (body.kind === "gzipStored") {
    exactKeys(location, body, ["kind", "source"]);
    validateBody(`${location}.source`, body.source);
  }
  if (body.kind === "concatenated") {
    exactKeys(location, body, ["kind", "members"]);
    if (!Array.isArray(body.members) || body.members.length < 2) fail(`${location}.members`, "must contain at least two body members");
    for (const [index, member] of (body.members || []).entries()) {
      if (typeof member === "string") {
        if (!document.fixtureProtocol?.bodyAssets?.[member]) fail(`${location}.members[${index}]`, "unknown body asset");
      } else validateBody(`${location}.members[${index}]`, member);
    }
  }
  if (body.kind === "jsonRecipe") {
    exactKeys(location, body, ["kind", "shape", "count"]);
    if (!["nestedArrays", "flatObject"].includes(body.shape)) fail(`${location}.shape`, "must be nestedArrays or flatObject");
    if (!Number.isInteger(body.count) || body.count < 0 || body.count > 10001) fail(`${location}.count`, "must be an integer in 0..10001");
  }
}

function validateActionField(location, value, schema) {
  const typeMatches = schema.type === "integer"
    ? Number.isSafeInteger(value)
    : schema.type === "array"
      ? Array.isArray(value)
      : schema.type === "object"
        ? object(value)
        : typeof value === schema.type;
  if (!typeMatches) {
    fail(location, `must be ${schema.type === "integer" ? "an integer" : schema.type}`);
    return;
  }
  if ("const" in schema && value !== schema.const) fail(location, `must equal ${JSON.stringify(schema.const)}`);
  if (schema.type === "integer") {
    if ("minimum" in schema && value < schema.minimum) fail(location, `must be at least ${schema.minimum}`);
    if ("maximum" in schema && value > schema.maximum) fail(location, `must be at most ${schema.maximum}`);
  }
  if (schema.type === "string") {
    if ("minLength" in schema && value.length < schema.minLength) fail(location, `must have length at least ${schema.minLength}`);
    if ("pattern" in schema && !(new RegExp(schema.pattern).test(value))) fail(location, `must match ${schema.pattern}`);
  }
  if (schema.type === "object" && schema.format === "body") validateBody(location, value);
  if (schema.type === "object" && schema.values === "headerValue") {
    for (const [name, headerValue] of Object.entries(value)) {
      if (name.length === 0) fail(`${location}.${name}`, "header name must be non-empty; semantic token validation is exercised by the response interpreter");
      if (typeof headerValue === "string") continue;
      if (!Array.isArray(headerValue) || headerValue.length === 0 || headerValue.some((item) => typeof item !== "string")) {
        fail(`${location}.${name}`, "must be a string or non-empty string array");
      }
    }
  }
  if (schema.type === "array") {
    if ("minItems" in schema && value.length < schema.minItems) fail(location, `must contain at least ${schema.minItems} item(s)`);
    if (schema.uniqueItems && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) fail(location, "items must be unique");
    value.forEach((item, index) => {
      const itemLocation = `${location}[${index}]`;
      if (schema.items === "body") validateBody(itemLocation, item);
      if (schema.items === "requestAssertion" && !(document.fixtureProtocol?.requestAssertions || []).includes(item)) fail(itemLocation, "unknown request assertion");
      if (schema.items === "sentinelName" && !(item in (document.sentinels || {}))) fail(itemLocation, "unknown sentinel name");
      if (schema.items === "networkSetup" && (typeof item !== "string" || !/^[a-z0-9]+(?:-[A-Za-z0-9]+)*$/u.test(item))) fail(itemLocation, "must be a closed safe network setup token");
      if (schema.items === "networkOutcome" && (typeof item !== "string" || !/^op[1-9][0-9]*(?::[a-z][a-z0-9-]*=[A-Za-z0-9_-]+)+$/u.test(item))) fail(itemLocation, "must be a normalized per-operation outcome token");
    });
  }
}

if (!object(document.fixtureProtocol?.bodyAssets) || Object.keys(document.fixtureProtocol.bodyAssets).length === 0) fail("$.fixtureProtocol.bodyAssets", "must be a non-empty object");
const assetBase64Bytes = new Map();
for (const [assetName, asset] of Object.entries(document.fixtureProtocol?.bodyAssets || {})) {
  const location = `$.fixtureProtocol.bodyAssets.${assetName}`;
  if (!/^[A-Za-z][A-Za-z0-9]*$/u.test(assetName)) fail(location, "asset name must be an identifier");
  if (!object(asset)) {
    fail(location, "must be an object");
  } else if (asset.kind === "base64") {
    exactKeys(location, asset, ["kind", "data", "wireBytes", "decodedBytes"]);
    const bytes = strictBase64(`${location}.data`, asset.data);
    integer(`${location}.wireBytes`, asset.wireBytes, 0);
    if (asset.decodedBytes !== null) integer(`${location}.decodedBytes`, asset.decodedBytes, 0);
    if (bytes) {
      assetBase64Bytes.set(assetName, bytes);
      if (bytes.length !== asset.wireBytes) fail(`${location}.wireBytes`, `must equal decoded base64 length ${bytes.length}`);
    }
  } else if (asset.kind === "concatenated") {
    exactKeys(location, asset, ["kind", "members", "wireBytes", "decodedBytes"]);
    integer(`${location}.wireBytes`, asset.wireBytes, 0);
    if (asset.decodedBytes !== null) fail(`${location}.decodedBytes`, "must be null for a concatenated multi-member asset");
    if (!Array.isArray(asset.members) || asset.members.length < 2) fail(`${location}.members`, "must contain at least two asset members");
    for (const [index, member] of (asset.members || []).entries()) {
      if (typeof member !== "string" || !document.fixtureProtocol.bodyAssets[member]) fail(`${location}.members[${index}]`, "unknown body asset");
    }
  } else fail(`${location}.kind`, "unknown asset kind");
}

function assetBuffer(assetName, seenAssets = new Set()) {
  if (assetBase64Bytes.has(assetName)) return assetBase64Bytes.get(assetName);
  const asset = document.fixtureProtocol?.bodyAssets?.[assetName];
  if (!asset || asset.kind !== "concatenated" || seenAssets.has(assetName)) return null;
  const nextSeen = new Set(seenAssets).add(assetName);
  const members = (asset.members || []).map((member) => assetBuffer(member, nextSeen));
  return members.every(Buffer.isBuffer) ? Buffer.concat(members) : null;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function gzipStored(bytes) {
  const parts = [Buffer.from([0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff])];
  const blockCount = Math.max(1, Math.ceil(bytes.length / 65535));
  for (let index = 0; index < blockCount; index += 1) {
    const start = index * 65535;
    const chunk = bytes.subarray(start, Math.min(start + 65535, bytes.length));
    const prefix = Buffer.alloc(5);
    prefix[0] = index === blockCount - 1 ? 1 : 0;
    prefix.writeUInt16LE(chunk.length, 1);
    prefix.writeUInt16LE((~chunk.length) & 0xffff, 3);
    parts.push(prefix, chunk);
  }
  const trailer = Buffer.alloc(8);
  trailer.writeUInt32LE(crc32(bytes), 0);
  trailer.writeUInt32LE(bytes.length >>> 0, 4);
  parts.push(trailer);
  return Buffer.concat(parts);
}

function materializeBody(body) {
  if (!object(body)) return null;
  if (body.asset && !("kind" in body)) return assetBuffer(body.asset);
  if (body.kind === "base64") return strictBase64("$.body.data", body.data);
  if (body.kind === "repeat" && Number.isInteger(body.byte) && Number.isInteger(body.length) && body.length >= 0) return Buffer.alloc(body.length, body.byte);
  if (body.kind === "paddedAsset" && Number.isInteger(body.totalLength)) {
    const source = assetBuffer(body.asset);
    if (!source || body.totalLength < source.length) return null;
    return Buffer.concat([source, Buffer.alloc(body.totalLength - source.length, 0x20)]);
  }
  if (body.kind === "gzipStored") {
    const source = materializeBody(body.source);
    return source ? gzipStored(source) : null;
  }
  if (body.kind === "concatenated" && Array.isArray(body.members)) {
    const members = body.members.map((member) => typeof member === "string" ? assetBuffer(member) : materializeBody(member));
    return members.every(Buffer.isBuffer) ? Buffer.concat(members) : null;
  }
  if (body.kind === "jsonRecipe" && Number.isInteger(body.count)) {
    if (body.shape === "nestedArrays") return Buffer.from(`${"[".repeat(body.count)}0${"]".repeat(body.count)}`, "utf8");
    if (body.shape === "flatObject") {
      const entries = Array.from({ length: body.count }, (_, index) => `"k${index}":0`);
      return Buffer.from(`{${entries.join(",")}}`, "utf8");
    }
  }
  return null;
}

function bodyByteLength(body) {
  return materializeBody(body)?.length;
}

for (const [assetName, bytes] of assetBase64Bytes) {
  const asset = document.fixtureProtocol.bodyAssets[assetName];
  const location = `$.fixtureProtocol.bodyAssets.${assetName}`;
  const gzip = bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (!gzip) {
    if (asset.decodedBytes !== bytes.length) fail(`${location}.decodedBytes`, `must equal identity body length ${bytes.length}`);
    continue;
  }
  try {
    const inflated = zlib.gunzipSync(bytes);
    if (assetName === "corruptGzipTrailer") fail(`${location}.data`, "corrupt gzip trailer must fail inflation");
    if (asset.decodedBytes !== inflated.length) fail(`${location}.decodedBytes`, `must equal inflated length ${inflated.length}`);
  } catch (error) {
    if (assetName !== "corruptGzipTrailer") fail(`${location}.data`, `must be valid gzip: ${error.message}`);
    if (asset.decodedBytes !== null) fail(`${location}.decodedBytes`, "must be null when gzip inflation fails");
  }
}

const validIdentityBytes = assetBuffer("validIdentity");
const validGzipBytes = assetBuffer("validGzip");
if (!validIdentityBytes) fail("$.fixtureProtocol.bodyAssets.validIdentity", "required identity asset is missing or invalid");
if (!validGzipBytes) fail("$.fixtureProtocol.bodyAssets.validGzip", "required gzip asset is missing or invalid");
if (validIdentityBytes && validGzipBytes) {
  try {
    if (!zlib.gunzipSync(validGzipBytes).equals(validIdentityBytes)) fail("$.fixtureProtocol.bodyAssets.validGzip.data", "must inflate byte-identically to validIdentity");
  } catch (error) {
    fail("$.fixtureProtocol.bodyAssets.validGzip.data", `must be valid gzip: ${error.message}`);
  }
}
if (!document.fixtureProtocol?.bodyAssets?.corruptGzipTrailer) fail("$.fixtureProtocol.bodyAssets.corruptGzipTrailer", "required corrupt gzip trailer asset is missing");

for (const [assetName, asset] of Object.entries(document.fixtureProtocol?.bodyAssets || {})) {
  if (asset.kind !== "concatenated") continue;
  const location = `$.fixtureProtocol.bodyAssets.${assetName}`;
  const members = (asset.members || []).map((member) => assetBuffer(member));
  const concatenated = assetBuffer(assetName);
  if (!concatenated || !members.every(Buffer.isBuffer)) continue;
  if (concatenated.length !== asset.wireBytes) fail(`${location}.wireBytes`, `must equal referenced member concatenation length ${concatenated.length}`);
  if (members.length < 2 || members.some((member) => member[0] !== 0x1f || member[1] !== 0x8b)) fail(`${location}.members`, "must identify at least two gzip members");
  try {
    const expectedInflated = Buffer.concat(members.map((member) => zlib.gunzipSync(member)));
    if (!zlib.gunzipSync(concatenated).equals(expectedInflated)) fail(location, "must byte-equal the referenced gzip member concatenation");
  } catch (error) {
    fail(`${location}.members`, `must reference valid gzip members: ${error.message}`);
  }
}

function responseActionByteLength(action) {
  if (action.action === "respond") return bodyByteLength(action.body);
  if (action.action === "respondChunks") {
    const lengths = action.chunks.map(bodyByteLength);
    return lengths.every(Number.isInteger) ? lengths.reduce((sum, length) => sum + length, 0) : undefined;
  }
  if (action.action === "respondEchoSentinels") {
    const echo = {};
    for (const sentinel of action.sentinels) echo[sentinel] = document.sentinels[sentinel];
    const body = { code: "remote", message: document.sentinels.remoteMessage, requestId: "req_echo", echo };
    return Buffer.byteLength(JSON.stringify(body), "utf8");
  }
  return undefined;
}

function responseActionBuffer(action) {
  if (action.action === "respond") return materializeBody(action.body);
  if (action.action === "respondChunks") {
    const chunks = (action.chunks || []).map(materializeBody);
    return chunks.every(Buffer.isBuffer) ? Buffer.concat(chunks) : null;
  }
  if (action.action === "respondEchoSentinels") {
    const echo = {};
    for (const sentinel of action.sentinels || []) echo[sentinel] = document.sentinels[sentinel];
    return Buffer.from(JSON.stringify({ code: "remote", message: document.sentinels.remoteMessage, requestId: "req_echo", echo }), "utf8");
  }
  return null;
}

function decodeSingleGzipMember(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 18 || bytes[0] !== 0x1f || bytes[1] !== 0x8b || bytes[2] !== 8 || (bytes[3] & 0xe0) !== 0) return { kind: "corrupt" };
  const flags = bytes[3];
  let offset = 10;
  if (flags & 0x04) {
    if (offset + 2 > bytes.length) return { kind: "corrupt" };
    const extraLength = bytes.readUInt16LE(offset);
    offset += 2 + extraLength;
    if (offset > bytes.length) return { kind: "corrupt" };
  }
  for (const flag of [0x08, 0x10]) {
    if (!(flags & flag)) continue;
    while (offset < bytes.length && bytes[offset] !== 0) offset += 1;
    if (offset >= bytes.length) return { kind: "corrupt" };
    offset += 1;
  }
  if (flags & 0x02) offset += 2;
  if (offset >= bytes.length) return { kind: "corrupt" };
  try {
    const inflated = zlib.inflateRawSync(bytes.subarray(offset), { info: true });
    const trailerOffset = offset + inflated.engine.bytesWritten;
    if (trailerOffset + 8 > bytes.length) return { kind: "corrupt" };
    const decoded = inflated.buffer;
    if (bytes.readUInt32LE(trailerOffset) !== crc32(decoded) || bytes.readUInt32LE(trailerOffset + 4) !== (decoded.length >>> 0)) return { kind: "corrupt" };
    const memberEnd = trailerOffset + 8;
    return memberEnd === bytes.length ? { kind: "valid", decoded } : { kind: "concatenated", decoded, memberEnd };
  } catch {
    return { kind: "corrupt" };
  }
}

function headerValues(headers, targetName) {
  const values = [];
  for (const [name, raw] of Object.entries(headers || {})) {
    if (name.toLowerCase() !== targetName) continue;
    if (Array.isArray(raw)) values.push(...raw);
    else values.push(raw);
  }
  return values;
}

function materializeResponseHeaders(headers) {
  if (!object(headers)) return headers;
  const materialized = {};
  for (const [rawName, rawValue] of Object.entries(headers)) {
    const name = rawName === "$unpairedSurrogateHeaderName" ? `x-repost${String.fromCharCode(0xd800)}` : rawName;
    const materializeValue = (value) => value === "$unpairedSurrogateHeaderValue" ? `value${String.fromCharCode(0xd800)}` : value;
    materialized[name] = Array.isArray(rawValue) ? rawValue.map(materializeValue) : materializeValue(rawValue);
  }
  return materialized;
}

function classifyContentEncoding(headers) {
  const values = headerValues(headers, "content-encoding");
  if (values.length === 0) return { valid: true, encoding: "identity" };
  if (values.length !== 1 || typeof values[0] !== "string") return { valid: false, encoding: null };
  const value = values[0];
  if (!/^[\x00-\x7f]*$/u.test(value) || !/^[\x20\x09]*(?:identity|gzip)[\x20\x09]*$/u.test(value.toLowerCase())) {
    return { valid: false, encoding: null };
  }
  const encoding = value.replace(/^[\x20\x09]+|[\x20\x09]+$/gu, "").toLowerCase();
  return { valid: true, encoding };
}

function classifyHttpStatus(status, retryableOverride) {
  if (status >= 300 && status < 400) return { attemptOutcome: "http-rejected", code: "HTTP_REJECTED", retryable: false };
  if (status === 409) return { attemptOutcome: "conflict", code: "HTTP_REJECTED", retryable: retryableOverride ?? true };
  if (status === 429) return { attemptOutcome: "rate-limited", code: "RATE_LIMITED", retryable: retryableOverride ?? true };
  if (status >= 400 && status < 500) return { attemptOutcome: "http-rejected", code: "HTTP_REJECTED", retryable: false };
  return { attemptOutcome: "server-failure", code: "SERVER_FAILURE", retryable: retryableOverride ?? true };
}

function classifyResponseAction(testCase, action, materializedInput, script) {
  const bytes = responseActionBuffer(action);
  if (!Buffer.isBuffer(bytes)) return null;
  const responseHeaders = action.action === "respondEchoSentinels" ? { "content-type": "application/json" } : materializeResponseHeaders(action.headers);
  const contentEncoding = classifyContentEncoding(responseHeaders);
  const encoding = contentEncoding.encoding;
  const nonSuccessStatus = action.status < 200 || action.status >= 300;
  const complete = action.action !== "respondChunks" || action.complete !== false;
  const responseActionIndex = script.indexOf(action);
  const responseHeadersHoldIndex = script.findIndex((candidate, candidateIndex) => candidateIndex > responseActionIndex
    && candidate.action === "hold" && candidate.attempt === action.attempt && candidate.phase === "responseHeaders");
  const responseHeadersHold = responseHeadersHoldIndex >= 0 ? script[responseHeadersHoldIndex] : null;
  const responseHeadersTerminator = responseHeadersHold
    ? script.slice(responseHeadersHoldIndex + 1).find((candidate) => ["advanceTime", "release", "cancelSend", "closeClient"].includes(candidate.action))
    : null;
  const responseHoldIndex = script.findIndex((candidate, candidateIndex) => candidateIndex > responseActionIndex
    && candidate.action === "hold" && candidate.attempt === action.attempt && candidate.phase === "responseBody");
  const responseHold = responseHoldIndex >= 0 ? script[responseHoldIndex] : null;
  const explicitlyCancelled = Boolean(responseHold && script.some((candidate) => candidate.action === "cancelSend" && candidate.phase === "responseBody"));
  const responseHoldTerminator = responseHold
    ? script.slice(responseHoldIndex + 1).find((candidate) => ["advanceTime", "release", "cancelSend", "closeClient"].includes(candidate.action))
    : null;
  const bodyAttemptTimeout = testCase.options?.attemptTimeoutMs ?? document.constants.attemptTimeoutMs;
  const bodyOperationTimeout = testCase.options?.operationTimeoutMs ?? document.constants.operationTimeoutMs;
  const bodyTimeoutAt = Math.min(bodyAttemptTimeout, bodyOperationTimeout);
  const timedOutDuringBody = responseHoldTerminator?.action === "advanceTime" && responseHoldTerminator.byMs >= bodyTimeoutAt;
  const bodyTimeoutOutcome = bodyOperationTimeout <= bodyAttemptTimeout ? "operation-deadline" : "attempt-timeout";
  const base = {
    wireCount: bytes.length,
    wireOutcome: "accepted",
    decodedCount: bytes.length,
    decodedOutcome: "accepted",
    progressKind: "bytes",
    progressCount: bytes.length,
    responseCancel: false,
    truncated: false,
    inspectedCount: null,
    resultEligible: false,
    retryable: false,
    explicitCancellation: explicitlyCancelled,
  };
  if (responseHeadersTerminator?.action === "advanceTime") {
    const headerAttemptTimeout = testCase.options?.attemptTimeoutMs ?? document.constants.attemptTimeoutMs;
    const headerOperationTimeout = testCase.options?.operationTimeoutMs ?? document.constants.operationTimeoutMs;
    const headerTimeoutAt = Math.min(headerAttemptTimeout, headerOperationTimeout);
    if (responseHeadersTerminator.byMs >= headerTimeoutAt) {
      const operationWins = headerOperationTimeout <= headerAttemptTimeout;
      return { ...base, wireCount: 0, wireOutcome: "cancelled", decodedCount: 0, decodedOutcome: "cancelled", progressKind: "cancelled", progressCount: 0, responseCancel: true,
        attemptOutcome: operationWins ? "operation-deadline" : "attempt-timeout", code: operationWins ? "OPERATION_DEADLINE" : "ATTEMPT_TIMEOUT", retryable: !operationWins };
    }
  }
  const headerAssessment = assessResponseHeaders(responseHeaders);
  if (!headerAssessment.valid) {
    const headerFailure = { ...base, wireCount: 0, wireOutcome: "cancelled", decodedCount: 0, decodedOutcome: "cancelled", progressKind: "cancelled", progressCount: 0, responseCancel: true,
      headerCount: headerAssessment.kind === "limit" ? headerAssessment.count : 0,
      headerLogicalBytes: headerAssessment.kind === "limit" ? headerAssessment.logicalBytes : 0 };
    if (nonSuccessStatus) return { ...headerFailure, ...classifyHttpStatus(action.status, false) };
    return headerAssessment.kind === "limit"
      ? { ...headerFailure, attemptOutcome: "response-too-large", code: "RESPONSE_TOO_LARGE", retryable: false }
      : { ...headerFailure, attemptOutcome: "response-protocol", code: "RESPONSE_PROTOCOL", retryable: false };
  }
  if (!contentEncoding.valid) {
    const invalidEncoding = { ...base, wireCount: 0, wireOutcome: "cancelled", decodedCount: 0, decodedOutcome: "cancelled", progressKind: "cancelled", progressCount: 0, responseCancel: true };
    return nonSuccessStatus
      ? { ...invalidEncoding, ...classifyHttpStatus(action.status, false) }
      : { ...invalidEncoding, attemptOutcome: "response-protocol", code: "RESPONSE_PROTOCOL" };
  }
  const declaredLengths = headerValues(responseHeaders, "content-length");
  const compressedLimitBeforeBody = testCase.options?.responseCompressedLimitBytes ?? document.constants.responseCompressedLimitBytes;
  if (declaredLengths.length === 1 && typeof declaredLengths[0] === "string" && /^(?:0|[1-9][0-9]*)$/u.test(declaredLengths[0])
    && BigInt(declaredLengths[0]) > BigInt(compressedLimitBeforeBody)) {
    const declaredLimitFailure = { ...base, wireCount: 0, wireOutcome: "cancelled", decodedCount: 0, decodedOutcome: "cancelled", progressKind: "cancelled", progressCount: 0, responseCancel: true };
    return nonSuccessStatus
      ? { ...declaredLimitFailure, ...classifyHttpStatus(action.status, false) }
      : { ...declaredLimitFailure, attemptOutcome: "response-too-large", code: "RESPONSE_TOO_LARGE", retryable: false };
  }
  if (explicitlyCancelled) return { ...base, wireCount: responseHold.releaseAt, wireOutcome: "cancelled", decodedCount: responseHold.releaseAt, decodedOutcome: "cancelled", progressKind: "cancelled", progressCount: responseHold.releaseAt, responseCancel: true, attemptOutcome: "cancelled", code: "CANCELLED" };
  if (timedOutDuringBody) {
    return {
      ...base,
      wireCount: responseHold.releaseAt,
      wireOutcome: "cancelled",
      decodedCount: responseHold.releaseAt,
      decodedOutcome: "cancelled",
      progressKind: "cancelled",
      progressCount: responseHold.releaseAt,
      responseCancel: true,
      attemptOutcome: bodyTimeoutOutcome,
      code: bodyTimeoutOutcome === "operation-deadline" ? "OPERATION_DEADLINE" : "ATTEMPT_TIMEOUT",
      retryable: bodyTimeoutOutcome !== "operation-deadline",
    };
  }

  const compressedLimit = testCase.options?.responseCompressedLimitBytes ?? document.constants.responseCompressedLimitBytes;
  if (bytes.length > compressedLimit) {
    const count = compressedLimit + 1;
    const protocolViolation = encoding === "gzip";
    const limitFailure = {
      ...base,
      wireCount: count,
      wireOutcome: "rejected",
      decodedCount: encoding === "identity" ? count : 0,
      decodedOutcome: "rejected",
      progressKind: "cancelled",
      progressCount: count,
      responseCancel: true,
    };
    return nonSuccessStatus
      ? { ...limitFailure, ...classifyHttpStatus(action.status, false) }
      : { ...limitFailure, attemptOutcome: protocolViolation ? "response-protocol" : "response-too-large", code: protocolViolation ? "RESPONSE_PROTOCOL" : "RESPONSE_TOO_LARGE" };
  }

  let decodedBytes = bytes;
  if (encoding === "gzip") {
    const gzip = decodeSingleGzipMember(bytes);
    decodedBytes = gzip.kind === "valid" ? gzip.decoded : null;
  }
  if (!Buffer.isBuffer(decodedBytes)) {
    const decodeFailure = { ...base, decodedCount: 0, decodedOutcome: "rejected", progressKind: "cancelled", progressCount: bytes.length, responseCancel: true };
    return nonSuccessStatus
      ? { ...decodeFailure, ...classifyHttpStatus(action.status, false) }
      : { ...decodeFailure, attemptOutcome: "response-protocol", code: "RESPONSE_PROTOCOL" };
  }

  const decompressedLimit = testCase.options?.responseDecompressedLimitBytes ?? document.constants.responseDecompressedLimitBytes;
  const ratioLimit = testCase.options?.expansionRatioLimit ?? document.constants.expansionRatioLimit;
  const firstDecompressedLimitByte = decodedBytes.length > decompressedLimit ? decompressedLimit + 1 : Number.POSITIVE_INFINITY;
  const firstRatioLimitByte = decodedBytes.length > bytes.length * ratioLimit ? Math.floor(bytes.length * ratioLimit) + 1 : Number.POSITIVE_INFINITY;
  if (Number.isFinite(firstDecompressedLimitByte) || Number.isFinite(firstRatioLimitByte)) {
    const decodedCount = Math.min(decodedBytes.length, firstDecompressedLimitByte, firstRatioLimitByte);
    const expansionFailure = { ...base, decodedCount, decodedOutcome: "rejected", progressKind: "cancelled", progressCount: bytes.length, responseCancel: true };
    return nonSuccessStatus
      ? { ...expansionFailure, ...classifyHttpStatus(action.status, false) }
      : { ...expansionFailure, attemptOutcome: "response-protocol", code: "RESPONSE_PROTOCOL" };
  }
  base.decodedCount = decodedBytes.length;

  if (nonSuccessStatus) {
    if (!complete) {
      base.truncated = true;
      base.inspectedCount = null;
      return { ...base, ...classifyHttpStatus(action.status) };
    }
    const inspectionLimit = testCase.options?.errorInspectionLimitBytes ?? document.constants.errorInspectionLimitBytes;
    if (decodedBytes.length > inspectionLimit) {
      base.wireCount = Math.min(bytes.length, inspectionLimit + 1);
      base.decodedCount = inspectionLimit + 1;
      base.wireOutcome = "cancelled";
      base.decodedOutcome = "cancelled";
      base.progressKind = "cancelled";
      base.progressCount = base.wireCount;
      base.responseCancel = true;
      base.truncated = true;
    }
    base.inspectedCount = Math.min(base.decodedCount, inspectionLimit);
    let inspected = null;
    if (!base.truncated && acceptedResponseContentType(responseHeaders)) {
      const text = decodeUtf8(decodedBytes.subarray(0, base.inspectedCount));
      const inspection = text === null ? null : inspectJsonText(text);
      inspected = inspection?.valid ? inspection.value : null;
      if (["depth", "tokens", "field-name-bytes", "string-bytes", "string-chars", "number-chars", "members"].includes(inspection?.reason)) base.resourceInspectionFailure = true;
    }
    return { ...base, ...classifyHttpStatus(action.status, base.resourceInspectionFailure ? false : undefined) };
  }

  const text = decodeUtf8(decodedBytes);
  const inspection = text === null ? null : inspectJsonText(text);
  const result = acceptedResponseContentType(responseHeaders) && inspection?.valid ? inspection.value : null;
  const validTimestamp = canonicalUtcMillisecondTimestamp(result?.timestamp);
  const expectedTimestamp = Number.isSafeInteger(materializedInput.clock?.defaultNowUnixMs)
    ? new Date(materializedInput.clock.defaultNowUnixMs).toISOString()
    : null;
  const validResult = complete && object(result)
    && validSendResultId(result.id)
    && result.type === materializedInput.send?.eventType
    && result.customerId === materializedInput.send?.customerId
    && validTimestamp && result.timestamp === expectedTimestamp;
  const structuralResourceFailure = ["depth", "tokens", "field-name-bytes", "string-bytes", "string-chars", "number-chars", "members"].includes(inspection?.reason);
  return validResult
    ? { ...base, attemptOutcome: "accepted", code: "NONE", resultEligible: true, result: { id: result.id, type: result.type, customerId: result.customerId, timestamp: result.timestamp } }
    : structuralResourceFailure
      ? { ...base, progressKind: "cancelled", progressCount: bytes.length, responseCancel: true, attemptOutcome: "response-too-large", code: "RESPONSE_TOO_LARGE", retryable: false }
      : { ...base, attemptOutcome: "response-protocol", code: "RESPONSE_PROTOCOL", retryable: true };
}

function saturatingAdd(left, right) {
  if (!Number.isSafeInteger(left) || !Number.isSafeInteger(right) || right < 0) return null;
  return left > Number.MAX_SAFE_INTEGER - right ? Number.MAX_SAFE_INTEGER : left + right;
}

function retryAfterHeader(headers) {
  const values = headerValues(headers, "retry-after");
  if (values.length === 0) return { present: false };
  if (values.length === 0 || values.some((value) => typeof value !== "string") || values.some((value) => value !== values[0])) {
    return { present: true, valid: false };
  }
  return { present: true, value: values[0] };
}

function parseRetryAfter(headers, wallUnixMs, maximumMs) {
  const header = retryAfterHeader(headers);
  if (!header.present) return { present: false };
  if (header.valid === false) return { present: true, valid: false };
  const value = header.value;
  if (/^[0-9]+$/u.test(value)) {
    let seconds;
    try {
      seconds = BigInt(value);
    } catch {
      return { present: true, valid: false };
    }
    const maximumSignedInt64Milliseconds = (1n << 63n) - 1n;
    if (seconds > maximumSignedInt64Milliseconds / 1000n) return { present: true, valid: false };
    const milliseconds = seconds * 1000n;
    return {
      present: true,
      valid: true,
      milliseconds: milliseconds > BigInt(maximumMs) ? maximumMs : Number(milliseconds),
    };
  }
  const absolute = parseImfFixdate(value);
  if (!Number.isSafeInteger(absolute) || !Number.isSafeInteger(wallUnixMs)) return { present: true, valid: false };
  const milliseconds = BigInt(absolute) - BigInt(wallUnixMs);
  if (milliseconds <= 0n || milliseconds > ((1n << 63n) - 1n)) return { present: true, valid: false };
  return {
    present: true,
    valid: true,
    milliseconds: milliseconds > BigInt(maximumMs) ? maximumMs : Number(milliseconds),
  };
}

const terminalFailureSemantics = {
  dns: { code: "DNS", retryable: true, causeType: "ConformanceDnsFailure" },
  connect: { code: "CONNECT", retryable: true, causeType: "ConformanceConnectFailure" },
  proxy: { code: "PROXY", retryable: true, causeType: "ConformanceProxyFailure" },
  "proxy-auth": { code: "PROXY", retryable: false, causeType: "ConformanceProxyFailure" },
  io: { code: "IO", retryable: true, causeType: "ConformanceIoFailure" },
  "tls-certificate": { code: "TLS", retryable: false, causeType: "ConformanceTlsCertificateFailure" },
  "tls-hostname": { code: "TLS", retryable: false, causeType: "ConformanceTlsHostnameFailure" },
  "tls-protocol": { code: "TLS", retryable: false, causeType: "ConformanceTlsProtocolFailure" },
  mtls: { code: "TLS", retryable: false, causeType: "ConformanceTlsProtocolFailure" },
  "connect-timeout": { code: "CONNECT", retryable: true, causeType: "ConformanceConnectFailure" },
  "attempt-timeout": { code: "ATTEMPT_TIMEOUT", retryable: true, causeType: "ConformanceAttemptTimeoutFailure" },
  "operation-deadline": { code: "OPERATION_DEADLINE", retryable: false, causeType: null },
  cancelled: { code: "CANCELLED", retryable: false, causeType: null },
  closed: { code: "CLOSED", retryable: false, causeType: null },
};

function deriveExecutionReference(testCase, materializedInput, responseClassifications) {
  const script = testCase.script || [];
  const operationStart = materializedInput.clock?.monotonicMs;
  const wallStart = materializedInput.clock?.retryAfterWallUnixMs;
  if (!Number.isSafeInteger(operationStart) || !Number.isSafeInteger(wallStart)) return null;
  const operationTimeout = testCase.options?.operationTimeoutMs ?? document.constants.operationTimeoutMs;
  const operationDeadline = saturatingAdd(operationStart, operationTimeout);
  const states = new Map();
  let monotonic = operationStart;
  let wall = wallStart;

  function stateFor(attempt) {
    let state = states.get(attempt);
    if (!state) {
      state = { attempt, start: monotonic, commit: null, responseStatus: null, responseTime: null, responseWall: null, pendingResponse: null, outcome: null, end: null, wallEnd: null, terminalScriptIndex: null, terminalAction: null, hold: null };
      states.set(attempt, state);
    }
    return state;
  }
  function commit(state) {
    if (state.commit === null) state.commit = monotonic;
  }
  function terminal(state, outcome, action, scriptIndex) {
    state.outcome = outcome;
    state.end = monotonic;
    state.wallEnd = wall;
    state.terminalAction = action;
    state.terminalScriptIndex = scriptIndex;
  }

  for (const [scriptIndex, action] of script.entries()) {
    if (action.action === "advanceWallTime") {
      wall = saturatingAdd(wall, action.byMs);
      continue;
    }
    if (action.action === "release") {
      const active = [...states.values()].reverse().find((state) => state.hold?.barrier === action.barrier && state.end === null);
      if (active && active.hold.phase === "responseHeaders") {
        const responseAction = active.pendingResponse;
        if (responseAction) {
          active.responseStatus = responseAction.status;
          active.responseTime = monotonic;
          active.responseWall = wall;
          const laterBodyHold = script.some((candidate, candidateIndex) => candidateIndex > scriptIndex && candidate.action === "hold" && candidate.attempt === active.attempt && candidate.phase === "responseBody");
          if (!laterBodyHold) terminal(active, responseClassifications.get(active.attempt)?.attemptOutcome, responseAction, scriptIndex);
        }
      } else if (active && active.hold.phase === "responseBody") {
        const responseAction = active.pendingResponse;
        terminal(active, responseClassifications.get(active.attempt)?.attemptOutcome, responseAction, scriptIndex);
      }
      continue;
    }
    if (action.action === "advanceTime") {
      const target = saturatingAdd(monotonic, action.byMs);
      const active = [...states.values()].reverse().find((state) => state.hold && state.end === null);
      if (active) {
        const attemptDeadline = saturatingAdd(active.start, testCase.options?.attemptTimeoutMs ?? document.constants.attemptTimeoutMs);
        const connectDeadline = active.hold.phase === "beforeRequestBytes" ? saturatingAdd(active.start, testCase.options?.connectTimeoutMs ?? document.constants.connectTimeoutMs) : Number.MAX_SAFE_INTEGER;
        const deadline = Math.min(operationDeadline, connectDeadline, attemptDeadline);
        if (target >= deadline) {
          monotonic = deadline;
          const outcome = operationDeadline === deadline
            ? "operation-deadline"
            : connectDeadline === deadline ? "connect-timeout" : "attempt-timeout";
          terminal(active, outcome, action, scriptIndex);
          monotonic = target;
        } else monotonic = target;
      } else monotonic = target;
      continue;
    }
    if (action.action === "cancelSend" || action.action === "closeClient") {
      if (action.phase === "beforeAttempt" || action.phase === "backoff") continue;
      const active = [...states.values()].reverse().find((state) => state.hold?.phase === action.phase);
      if (active) terminal(active, action.action === "closeClient" ? "closed" : "cancelled", action, scriptIndex);
      continue;
    }
    if (!Number.isInteger(action.attempt)) continue;
    const state = stateFor(action.attempt);
    if (state.end !== null) continue;
    if (action.action === "hold") {
      state.hold = { phase: action.phase, barrier: action.barrier, scriptIndex };
      if (["afterRequestBytes", "responseHeaders", "responseBody"].includes(action.phase)) commit(state);
      if (action.phase === "responseBody" && state.pendingResponse) {
        state.responseStatus = state.pendingResponse.status;
        state.responseTime = monotonic;
        state.responseWall = wall;
      }
      continue;
    }
    if (action.action === "respond" || action.action === "respondChunks" || action.action === "respondEchoSentinels") {
      commit(state);
      state.pendingResponse = action;
      const laterResponseHold = script.find((candidate, candidateIndex) => candidateIndex > scriptIndex
        && candidate.action === "hold" && candidate.attempt === action.attempt && ["responseHeaders", "responseBody"].includes(candidate.phase));
      if (!laterResponseHold) {
        state.responseStatus = action.status;
        state.responseTime = monotonic;
        state.responseWall = wall;
        terminal(state, responseClassifications.get(action.attempt)?.attemptOutcome, action, scriptIndex);
      }
      continue;
    }
    if (action.action === "failConnection" || action.action === "disconnect") {
      if (action.phase === "afterRequestBytes" || action.bodyDemands > 0 || action.commitState === "UNKNOWN") commit(state);
      terminal(state, action.action === "disconnect" ? "io" : action.kind, action, scriptIndex);
      continue;
    }
    if (action.action === "failProxyAuthentication") {
      state.responseStatus = action.status;
      state.responseTime = monotonic;
      state.responseWall = wall;
      terminal(state, "proxy-auth", action, scriptIndex);
    }
  }

  const attempts = [...states.values()].sort((left, right) => left.attempt - right.attempt);
  const attemptTrace = [];
  const commitTrace = [];
  let operationCommitted = false;
  for (const state of attempts) {
    attemptTrace.push(`attempt:${state.attempt}:start@${state.start}`);
    commitTrace.push(`attempt:${state.attempt}:NOT_SENT@${state.start}`);
    if (state.commit !== null) {
      attemptTrace.push(`attempt:${state.attempt}:commit@${state.commit}`);
      commitTrace.push(`attempt:${state.attempt}:POSSIBLY_SENT@${state.commit}`);
      if (!operationCommitted) {
        commitTrace.push(`operation:POSSIBLY_SENT@${state.commit}`);
        operationCommitted = true;
      }
    }
    if (state.responseStatus !== null) attemptTrace.push(`attempt:${state.attempt}:response:${state.responseStatus}@${state.responseTime}`);
    if (state.outcome !== null && state.end !== null) attemptTrace.push(`attempt:${state.attempt}:end:${state.outcome}@${state.end}`);
  }
  return { attempts, attemptTrace, commitTrace, operationStart, operationDeadline, operationCommitted, monotonic, wall };
}

function deterministicRequestByteLength(testCase) {
  const override = testCase.input?.send?.payload?.$bytes?.serializedRequestBytes;
  if (Number.isInteger(override)) return override;
  const rawMapVariant = testCase.input?.send?.payload?.$rawMapFixture;
  const rawMapBytes = document.fixtureProtocol?.rawMapCanonicalization?.variants?.[rawMapVariant]?.canonicalRequestUtf8Bytes;
  if (Number.isInteger(rawMapBytes)) return rawMapBytes;
  return document.fixtureProtocol?.requestBodyBytesByProfile?.[testCase.input?.profile];
}

function structuralConstructionFailureFor(testCase, materializedInput) {
  const descriptorVersionMismatch = Number.isInteger(testCase.options?.descriptorVersion)
    && Number.isInteger(materializedInput.send?.descriptorVersion)
    && testCase.options.descriptorVersion !== materializedInput.send.descriptorVersion;
  return {
    failed: (Object.hasOwn(testCase.options || {}, "apiKey") && credentialViolation(materializeHarnessString(testCase.options.apiKey)) !== null)
      || (Object.hasOwn(testCase.options || {}, "baseUrl") && !rawBaseUrlValid(testCase.options.baseUrl, true))
      || (!Object.hasOwn(testCase.options || {}, "baseUrl") && Object.hasOwn(materializedInput.env || {}, "REPOST_API_URL") && !rawBaseUrlValid(materializedInput.env.REPOST_API_URL, false))
      || (Number.isInteger(testCase.options?.maxAttempts) && (testCase.options.maxAttempts < 1 || testCase.options.maxAttempts > 10))
      || (Number.isInteger(testCase.options?.maxInFlight) && (testCase.options.maxInFlight < 1 || testCase.options.maxInFlight > 65536))
      || (Number.isInteger(testCase.options?.maxBufferedBytes) && (testCase.options.maxBufferedBytes < 4194304 || testCase.options.maxBufferedBytes > 1073741824))
      || !validUserAgent(testCase.options?.userAgent)
      || hasInvalidDurationOption(testCase.options)
      || hasConstructionOptionConflict(testCase.options)
      || descriptorVersionMismatch,
    code: descriptorVersionMismatch ? "DESCRIPTOR_VERSION" : "CONFIGURATION",
  };
}

function expectedConfigurationIssueFor(testCase) {
  const id = testCase.caseId;
  if (id === "configuration-provider-and-fixed-key-mutually-exclusive") return { code: "CONFLICT", optionKeys: ["API_KEY", "API_KEY_PROVIDER"] };
  if (id === "configuration-custom-transport-built-in-options-conflict") return { code: "CONFLICT", optionKeys: ["HTTP_TRANSPORT_OPTIONS", "TRANSPORT"] };
  if (id === "idempotency-retry-without-key-impossible") return { code: "CONFLICT", optionKeys: ["MAX_ATTEMPTS", "IDEMPOTENCY_KEY_GENERATOR"] };
  if (id === "configuration-missing-credential") return { code: "MISSING", optionKeys: ["API_KEY"] };
  if (/provider/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["API_KEY_PROVIDER"] };
  if (/jitter|entropy/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["RETRY_ENTROPY"] };
  if (/idempotency-generated|idempotency-generator/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["IDEMPOTENCY_KEY_GENERATOR"] };
  if (/base-url|relative-uri|http-|uri-|environment-base-url/u.test(id)) return { code: /port-(?:zero|above-maximum|negative)/u.test(id) ? "OUT_OF_RANGE" : "INVALID_VALUE", optionKeys: ["BASE_URI"] };
  if (/user-agent/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["USER_AGENT_SUFFIX"] };
  if (/max-attempts/u.test(id)) return { code: "OUT_OF_RANGE", optionKeys: ["MAX_ATTEMPTS"] };
  if (/max-in-flight/u.test(id)) return { code: "OUT_OF_RANGE", optionKeys: ["MAX_IN_FLIGHT_OPERATIONS"] };
  if (/buffer-budget/u.test(id)) return { code: "OUT_OF_RANGE", optionKeys: ["MAX_BUFFERED_BYTES"] };
  if (/duration/u.test(id)) return { code: "OUT_OF_RANGE", optionKeys: ["CONNECT_TIMEOUT"] };
  if (/environment|legacy/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["API_KEY"] };
  if (/credential/u.test(id)) return { code: "INVALID_VALUE", optionKeys: ["API_KEY"] };
  return null;
}

function expectedValidationIssuesFor(testCase) {
  if (testCase.caseId === "retry-validation-failure-not-attempted") return [{ code: "INVALID_JSON", path: "$" }];
  if (testCase.caseId === "idempotency-key-256-ascii-bytes-rejected") return [{ code: "COLLECTION_LIMIT", path: "$" }];
  if (["idempotency-key-non-ascii-rejected", "idempotency-invalid-key-prevents-attempt"].includes(testCase.caseId)) return [{ code: "INVALID_UNICODE", path: "$" }];
  if (testCase.caseId === "raw-map-non-string-key-container-issue") return [{ code: "INVALID_JSON", path: "$.raw[{*}]" }];
  if (testCase.caseId === "raw-map-invalid-unicode-key-container-issue") return [{ code: "INVALID_UNICODE", path: "$.raw[{*}]" }];
  if (testCase.caseId.startsWith("issue-reporting-")) {
    const variant = testCase.input?.send?.payload?.$issueFixture;
    return structuredClone(requiredIssueFixtures.variants[variant]?.retainedIssues ?? null);
  }
  return null;
}

function materializeHarnessString(value) {
  if (!object(value?.$asciiBytes)) return value;
  const { byte, length } = value.$asciiBytes;
  return Number.isInteger(byte) && byte >= 0x20 && byte <= 0x7e && Number.isInteger(length) && length >= 0
    ? String.fromCharCode(byte).repeat(length)
    : value;
}

function selectCredential(testCase, materializedInput) {
  if (testCase.options?.apiKeyProvider === true) {
    const value = materializeHarnessString(materializedInput.providerValues?.[0]);
    if (value === null || object(value?.$providerError) || credentialViolation(value)) {
      return { source: "invalid", valid: false, provider: true, key: null, providerError: value?.$providerError || null };
    }
    return { source: "apiKeyProvider", valid: true, provider: true, key: value };
  }
  const candidates = [
    ["apiKey", testCase.options?.apiKey],
    ["REPOST_SEND_API_KEY", materializedInput.env?.REPOST_SEND_API_KEY],
    ["REPOST_TOKEN", materializedInput.env?.REPOST_TOKEN],
  ];
  for (const [source, candidate] of candidates) {
    if (candidate === undefined) continue;
    const value = materializeHarnessString(candidate);
    if (credentialViolation(value)) return { source: "invalid", valid: false, provider: false, key: null, providerError: null };
    return { source, valid: true, provider: false, key: value, providerError: null };
  }
  return { source: "missing", valid: false, provider: false, key: null, providerError: null };
}

function loopbackHost(hostname) {
  const host = hostname.startsWith("[") && hostname.endsWith("]") ? hostname.slice(1, -1) : hostname;
  if (host === "localhost" || host === "::1") return true;
  const octets = host.split(".");
  return octets.length === 4 && octets.every((octet) => /^(?:0|[1-9][0-9]{0,2})$/u.test(octet) && Number(octet) <= 255) && Number(octets[0]) === 127;
}

function rawBaseUrlValid(raw, fixtureToken = false) {
  if (fixtureToken && [document.fixtureProtocol?.fixtureBaseUrlToken, document.fixtureProtocol?.networkFixtureUrlToken].includes(raw)) return true;
  if (typeof raw !== "string" || raw.length === 0 || raw.length > document.constants.baseUrlMaxBytes
    || /[^\x00-\x7f]/u.test(raw) || /[\x00-\x20\x7f\\]/u.test(raw)) return false;
  const scheme = raw.match(/^([A-Za-z][A-Za-z0-9+.-]*):\/\//u)?.[1];
  if (scheme !== "http" && scheme !== "https") return false;
  if (/[?#]/u.test(raw)) return false;
  const rawAuthority = raw.slice(scheme.length + 3).split("/", 1)[0];
  if (rawAuthority.length === 0 || rawAuthority.length > document.constants.baseAuthorityMaxBytes) return false;
  const rawPath = raw.slice(scheme.length + 3 + rawAuthority.length);
  if (rawPath.length > document.constants.basePathMaxBytes) return false;
  const decodedPathSegments = [];
  let decodedSegment = "";
  for (let index = 0; index < rawPath.length; index += 1) {
    const character = rawPath[index];
    if (character === "/") {
      decodedPathSegments.push(decodedSegment);
      decodedSegment = "";
      continue;
    }
    if (character === "%") {
      const hex = rawPath.slice(index + 1, index + 3);
      if (!/^[0-9A-F]{2}$/u.test(hex)) return false;
      const octet = Number.parseInt(hex, 16);
      if (octet === 0x2f || octet === 0x5c) return false;
      decodedSegment += String.fromCharCode(octet);
      index += 2;
      continue;
    }
    if (!/[A-Za-z0-9\-._~!$&'()*+,;=:@]/u.test(character)) return false;
    decodedSegment += character;
  }
  decodedPathSegments.push(decodedSegment);
  if (decodedPathSegments.some((segment) => segment === "." || segment === "..")) return false;
  const rawPort = rawAuthority.startsWith("[")
    ? rawAuthority.match(/^\[[^\]]+\](?::(.*))?$/u)?.[1]
    : rawAuthority.includes(":") ? rawAuthority.slice(rawAuthority.lastIndexOf(":") + 1) : undefined;
  if (rawPort !== undefined && (!/^[1-9][0-9]{0,4}$/u.test(rawPort) || Number(rawPort) > 65535)) return false;
  let parsed;
  try { parsed = new URL(raw); } catch { return false; }
  if (parsed.username || parsed.password || !parsed.hostname) return false;
  const strippedRawPath = rawPath.replace(/\/+$/u, "");
  const resolvedPath = strippedRawPath.endsWith(document.constants.endpointPath) ? strippedRawPath : `${strippedRawPath}${document.constants.endpointPath}`;
  if (resolvedPath.length > document.constants.requestTargetMaxBytes) return false;
  const canonicalUri = `${scheme}://${rawAuthority}${resolvedPath}`;
  if (canonicalUri.length > document.constants.canonicalUriMaxBytes) return false;
  if (scheme === "https") return parsed.protocol === "https:";
  const authority = raw.slice("http://".length).split("/", 1)[0];
  const host = authority.startsWith("[") ? authority.match(/^(\[[^\]]+\])(?::[0-9]+)?$/u)?.[1] : authority.replace(/:[0-9]+$/u, "");
  if (host === "localhost" || host === "[::1]") return true;
  const octets = host?.split(".") || [];
  return octets.length === 4 && octets[0] === "127" && octets.every((octet) => /^(?:0|[1-9][0-9]{0,2})$/u.test(octet) && Number(octet) <= 255);
}

function selectBaseUrl(testCase, materializedInput) {
  const source = Object.hasOwn(testCase.options || {}, "baseUrl")
    ? "baseUrl"
    : Object.hasOwn(materializedInput.env || {}, "REPOST_API_URL")
      ? "REPOST_API_URL"
      : "default";
  const raw = source === "baseUrl" ? testCase.options.baseUrl : source === "REPOST_API_URL" ? materializedInput.env.REPOST_API_URL : "https://api.repost.sh";
  if (source === "baseUrl" && [document.fixtureProtocol?.fixtureBaseUrlToken, document.fixtureProtocol?.networkFixtureUrlToken].includes(raw)) {
    return { source, valid: true, endpointPath: document.constants.endpointPath };
  }
  if (!rawBaseUrlValid(raw)) return { source: "invalid", valid: false, endpointPath: null };
  let parsed;
  try { parsed = new URL(raw); } catch { return { source: "invalid", valid: false, endpointPath: null }; }
  if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash || !parsed.hostname) {
    return { source: "invalid", valid: false, endpointPath: null };
  }
  if (parsed.protocol === "http:" && !loopbackHost(parsed.hostname)) return { source: "invalid", valid: false, endpointPath: null };
  const stripped = parsed.pathname.replace(/\/+$/u, "");
  const endpointPath = stripped.endsWith(document.constants.endpointPath) ? stripped : `${stripped}${document.constants.endpointPath}`;
  return { source, valid: true, endpointPath: endpointPath || document.constants.endpointPath };
}

function validUserAgent(value) {
  return value === undefined || (typeof value === "string"
    && /^[\x20-\x7e]{1,256}$/u.test(value)
    && !/^ | $/u.test(value));
}

const durationOptionFields = ["connectTimeoutMs", "attemptTimeoutMs", "operationTimeoutMs", "retryBaseMs", "retryMaxMs", "retryAfterMaxMs"];
function hasInvalidDurationOption(options) {
  return durationOptionFields.some((field) => Object.hasOwn(options || {}, field)
    && (!Number.isSafeInteger(options[field]) || options[field] < 1 || options[field] > document.constants.durationMaxMs));
}

function deriveOperationSetup(testCase, materializedInput, executionReference, responseClassifications) {
  const structural = structuralConstructionFailureFor(testCase, materializedInput);
  if (structural.failed) return { structural, overloaded: false, configurationTrace: ["snapshot:0"], sideEffectTrace: [], operationKey: null, credential: null, baseUrl: null, terminalCode: structural.code };
  const maxInFlight = testCase.options?.maxInFlight ?? document.constants.maxInFlight;
  const overloaded = (testCase.options?.occupiedPermits ?? 0) >= maxInFlight;
  if (overloaded) return { structural, overloaded: true, configurationTrace: [], sideEffectTrace: ["permit.reject"], operationKey: null, credential: null, baseUrl: null, terminalCode: "OVERLOADED" };

  if ((testCase.script || []).some((action) => action.action === "closeClient" && action.phase === "beforeAttempt")) {
    return {
      structural,
      overloaded: false,
      configurationTrace: [],
      sideEffectTrace: [],
      operationKey: null,
      credential: null,
      baseUrl: null,
      terminalCode: "CLOSED",
      closedBeforeAdmission: true,
    };
  }

  if ((testCase.script || []).some((action) => ["rejectDeadlineScheduler", "rejectExecutorSubmission"].includes(action.action))) {
    return {
      structural,
      overloaded: false,
      configurationTrace: [],
      sideEffectTrace: ["permit.acquire", "permit.release"],
      operationKey: null,
      credential: null,
      baseUrl: null,
      terminalCode: "OVERLOADED",
      executorRejection: true,
    };
  }

  if ((testCase.script || []).some((action) => action.action === "runWorkerInlineDuringSubmit")) {
    return {
      structural,
      overloaded: false,
      configurationTrace: [],
      sideEffectTrace: ["permit.acquire", "permit.release"],
      operationKey: null,
      credential: null,
      baseUrl: null,
      terminalCode: "CONFIGURATION",
      inlineExecutorRejected: true,
    };
  }

  const queuedTerminal = (testCase.script || []).find((action) => ["starveQueuedWorkerPastDeadline", "cancelQueuedWorker", "closeQueuedWorker"].includes(action.action));
  if (queuedTerminal) {
    const terminalCode = queuedTerminal.action === "starveQueuedWorkerPastDeadline"
      ? "OPERATION_DEADLINE"
      : queuedTerminal.action === "cancelQueuedWorker" ? "CANCELLED" : "CLOSED";
    return {
      structural,
      overloaded: false,
      configurationTrace: [],
      sideEffectTrace: ["permit.acquire", "permit.release"],
      operationKey: null,
      credential: null,
      baseUrl: null,
      terminalCode,
      queuedTerminal: true,
    };
  }

  const sideEffectTrace = ["permit.acquire", "configuration.snapshot"];
  const credential = selectCredential(testCase, materializedInput);
  sideEffectTrace.push(credential.provider ? "credentials.provider:1" : "credentials.snapshot");
  if (!credential.valid) {
    sideEffectTrace.push("permit.release");
    return { structural, overloaded: false, configurationTrace: [`credential:${credential.source}`, "snapshot:1"], sideEffectTrace, operationKey: null, credential, baseUrl: null, terminalCode: "CONFIGURATION" };
  }
  if (credential.provider) sideEffectTrace.push("credentials.snapshot");
  const baseUrl = selectBaseUrl(testCase, materializedInput);
  if (!baseUrl.valid) {
    sideEffectTrace.push("permit.release");
    return { structural, overloaded: false, configurationTrace: [`credential:${credential.source}`, "base-url:invalid", "snapshot:1"], sideEffectTrace, operationKey: null, credential, baseUrl, terminalCode: "CONFIGURATION" };
  }
  const configurationTrace = [
    `credential:${credential.source}`,
    `base-url:${baseUrl.source}`,
    `endpoint-path:${baseUrl.endpointPath}`,
    "redirects:false",
    "snapshot:1",
  ];
  sideEffectTrace.push("defaults.snapshot");
  const callerKey = testCase.options?.idempotencyKey;
  const generated = callerKey === undefined && testCase.options?.generateIdempotencyKey !== false;
  sideEffectTrace.push(`idempotency.generate:${generated ? 1 : 0}`);
  const generatedValue = generated ? materializedInput.generatedValues?.idempotencyKeys?.[0] ?? null : null;
  const idempotencyGeneratorError = object(generatedValue?.$providerError) ? generatedValue.$providerError : null;
  if (idempotencyGeneratorError) {
    sideEffectTrace.push("permit.release");
    return { structural, overloaded: false, configurationTrace, sideEffectTrace, operationKey: null, credential, baseUrl, terminalCode: "CONFIGURATION", idempotencyGeneratorError };
  }
  const operationKey = generated ? generatedValue : callerKey ?? null;
  if (operationKey === null || idempotencyKeyViolation(operationKey)) {
    sideEffectTrace.push("permit.release");
    const terminalCode = callerKey !== undefined && idempotencyKeyViolation(callerKey) ? "VALIDATION" : "CONFIGURATION";
    return { structural, overloaded: false, configurationTrace, sideEffectTrace, operationKey: null, credential, baseUrl, terminalCode, idempotencyGeneratorError: null };
  }
  if (object(materializedInput.send?.payload) && (Object.hasOwn(materializedInput.send.payload, "$invalidValue") || Object.hasOwn(materializedInput.send.payload, "$issueFixture"))) {
    sideEffectTrace.push("permit.release");
    return { structural, overloaded: false, configurationTrace, sideEffectTrace, operationKey, credential, baseUrl, terminalCode: "VALIDATION" };
  }
  sideEffectTrace.push("serialize:1");
  if (testCase.options?.serializationFailure === true || deterministicRequestByteLength(testCase) > (testCase.options?.requestLimitBytes ?? document.constants.requestLimitBytes)
    || (testCase.script || []).some((action) => action.action === "cancelSend" && action.phase === "beforeAttempt")) {
    sideEffectTrace.push("permit.release");
    const terminalCode = testCase.options?.serializationFailure === true
      ? "SERIALIZATION"
      : deterministicRequestByteLength(testCase) > (testCase.options?.requestLimitBytes ?? document.constants.requestLimitBytes)
        ? "REQUEST_TOO_LARGE"
        : "CANCELLED";
    return { structural, overloaded: false, configurationTrace, sideEffectTrace, operationKey, credential, baseUrl, terminalCode };
  }
  if ((executionReference?.attempts.length || 0) > 0) sideEffectTrace.push(`network:${executionReference.attempts.length}`);
  if ([...responseClassifications.values()].some((classification) => classification.responseCancel)) sideEffectTrace.push("response.cancel");
  sideEffectTrace.push("permit.release");
  return { structural, overloaded: false, configurationTrace, sideEffectTrace, operationKey, credential, baseUrl, terminalCode: null };
}

const seen = new Set();
if (!Array.isArray(document.cases) || document.cases.length === 0) fail("$.cases", "must be a non-empty array");
if (Array.isArray(document.cases) && document.cases.length !== singleCaseCount) fail("$.cases", `must contain exactly ${singleCaseCount} single-operation cases`);
if (Array.isArray(document.cases) && JSON.stringify(document.cases.map((testCase) => testCase?.caseId)) !== JSON.stringify((document.caseManifest || []).slice(0, singleCaseCount))) {
  fail("$.cases", "case IDs and order must exactly match the single-operation prefix of caseManifest");
}
for (const [index, testCase] of (document.cases || []).entries()) {
  const location = `$.cases[${index}]`;
  if (!exactKeys(location, testCase, caseFields, optionalCaseFields)) continue;
  if (typeof testCase.caseId !== "string" || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(testCase.caseId)) {
    fail(`${location}.caseId`, "must be a lowercase kebab-case string");
  } else if (seen.has(testCase.caseId)) {
    fail(`${location}.caseId`, "duplicate caseId");
  } else {
    seen.add(testCase.caseId);
  }
  if (!categories.has(testCase.category)) fail(`${location}.category`, "unknown category");
  if (typeof testCase.description !== "string" || testCase.description.length === 0) fail(`${location}.description`, "must be non-empty");
  const executionMode = testCase.executionMode ?? document.fixtureProtocol?.defaultExecutionMode;
  if (!requiredExecutionModes.includes(executionMode)) fail(`${location}.executionMode`, "must be a closed execution mode");

  let materializedInput = {};
  if (exactKeys(`${location}.input`, testCase.input, ["profile"], [...inputFields].filter((field) => field !== "profile"))) {
    if (!document.inputProfiles?.[testCase.input.profile]) fail(`${location}.input.profile`, "unknown profile");
    if ("entropy" in testCase.input) integerArray(`${location}.input.entropy`, testCase.input.entropy);
    if ("retryEntropyError" in testCase.input) {
      const value = testCase.input.retryEntropyError;
      if (!exactKeys(`${location}.input.retryEntropyError`, value, ["causeType", "message"])) continue;
      if (value.causeType !== "ConformanceEntropyFailure") fail(`${location}.input.retryEntropyError.causeType`, "must equal ConformanceEntropyFailure");
      nonEmptyString(`${location}.input.retryEntropyError.message`, value.message);
    }
    if ("env" in testCase.input) validateEnvironment(`${location}.input.env`, testCase.input.env);
    if ("send" in testCase.input) validateSend(`${location}.input.send`, testCase.input.send, false);
    if ("clock" in testCase.input) validateClock(`${location}.input.clock`, testCase.input.clock, false);
    if ("providerValues" in testCase.input) validateProviderValues(`${location}.input.providerValues`, testCase.input.providerValues);
    if ("generatedValues" in testCase.input) validateGeneratedValues(
      `${location}.input.generatedValues`,
      testCase.input.generatedValues,
      false,
      testCase.caseId === "idempotency-generated-invalid-key-rejected",
      testCase.caseId === "idempotency-generator-exception-sanitized",
    );
    if ("sentinelValues" in testCase.input) validateSentinelValues(`${location}.input.sentinelValues`, testCase.input.sentinelValues);
    if ("observerContext" in testCase.input && (typeof testCase.input.observerContext !== "string"
      || !/^[\x20-\x7e]{1,128}$/u.test(testCase.input.observerContext))) {
      fail(`${location}.input.observerContext`, "must be an opaque 1..128 byte printable ASCII token");
    }
    const overlay = { ...testCase.input };
    delete overlay.profile;
    materializedInput = mergeObjects(document.inputProfiles?.[testCase.input.profile] || {}, overlay);
  }

  if (object(testCase.options)) {
    for (const key of Object.keys(testCase.options)) if (!optionFields.has(key)) fail(`${location}.options.${key}`, "unknown field");
    for (const [field, allowed] of optionModeValues) {
      if (field in testCase.options && !allowed.has(testCase.options[field])) fail(`${location}.options.${field}`, `must be one of ${[...allowed].join(", ")}`);
    }
    for (const field of ["apiKey", "baseUrl", "userAgent", "idempotencyKey", "proxyUrl", "proxyCredential", "clientCertificatePath", "clientCertificatePassword"]) {
      if (field === "apiKey" && object(testCase.options[field]?.$asciiBytes)) continue;
      if (field in testCase.options && typeof testCase.options[field] !== "string") fail(`${location}.options.${field}`, "must be a string");
    }
    for (const field of ["apiKeyProvider", "generateIdempotencyKey", "followRedirects", "observer", "serializationFailure", "builtInTransportOptions"]) {
      if (field in testCase.options && typeof testCase.options[field] !== "boolean") fail(`${location}.options.${field}`, "must be boolean");
    }
    for (const field of ["connectTimeoutMs", "attemptTimeoutMs", "operationTimeoutMs", "retryBaseMs", "retryMaxMs", "retryAfterMaxMs", "requestLimitBytes", "responseCompressedLimitBytes", "responseDecompressedLimitBytes", "expansionRatioLimit", "errorInspectionLimitBytes", "occupiedPermits", "descriptorVersion", "maxBufferedBytes"]) {
      if (field in testCase.options && !(durationOptionFields.includes(field) && invalidDurationCaseIds.has(testCase.caseId))) integer(`${location}.options.${field}`, testCase.options[field], 0);
    }
    if ("maxAttempts" in testCase.options && !Number.isInteger(testCase.options.maxAttempts)) {
      fail(`${location}.options.maxAttempts`, "must be an integer");
    }
    if (Number.isInteger(testCase.options.maxAttempts) && (testCase.options.maxAttempts < 1 || testCase.options.maxAttempts > 10)) {
      if (maxAttemptsOutOfRangeCases.get(testCase.caseId) !== testCase.options.maxAttempts) {
        fail(`${location}.options.maxAttempts`, "out-of-range values are allowed only in the required boundary rejection cases");
      }
    }
    if ("maxInFlight" in testCase.options && !Number.isInteger(testCase.options.maxInFlight)) {
      fail(`${location}.options.maxInFlight`, "must be an integer");
    }
    if (Number.isInteger(testCase.options.maxInFlight) && (testCase.options.maxInFlight < 1 || testCase.options.maxInFlight > 65536)) {
      if (maxInFlightOutOfRangeCases.get(testCase.caseId) !== testCase.options.maxInFlight) {
        fail(`${location}.options.maxInFlight`, "out-of-range values are allowed only in the required boundary rejection cases");
      }
    }
    if (Number.isInteger(testCase.options.maxBufferedBytes) && (testCase.options.maxBufferedBytes < 4194304 || testCase.options.maxBufferedBytes > 1073741824)) {
      if (maxBufferedBytesOutOfRangeCases.get(testCase.caseId) !== testCase.options.maxBufferedBytes) {
        fail(`${location}.options.maxBufferedBytes`, "out-of-range values are allowed only in the required buffer-budget boundary rejection cases");
      }
    }
    if ("idempotencyKey" in testCase.options) {
      const violation = idempotencyKeyViolation(testCase.options.idempotencyKey);
      const intentionallyInvalid = new Set([
        "idempotency-invalid-key-prevents-attempt",
        "idempotency-key-256-ascii-bytes-rejected",
        "idempotency-key-non-ascii-rejected",
      ]).has(testCase.caseId);
      if (intentionallyInvalid && !violation) fail(`${location}.options.idempotencyKey`, "must exercise an invalid key");
      if (!intentionallyInvalid && violation) fail(`${location}.options.idempotencyKey`, violation);
    }
  } else fail(`${location}.options`, "must be an object");

  const usesFixtureBaseUrl = testCase.options?.baseUrl === document.fixtureProtocol?.fixtureBaseUrlToken;
  const usesNetworkFixtureUrl = testCase.options?.baseUrl === document.fixtureProtocol?.networkFixtureUrlToken;
  if (executionMode === "production-http-fixture") {
    if (!usesFixtureBaseUrl) fail(`${location}.options.baseUrl`, "production-http-fixture requires explicit $fixtureBaseUrl");
    if (testCase.options?.transportMode === "custom") {
      fail(`${location}.executionMode`, "production-http-fixture must use the unmodified default production HTTP transport");
    }
  } else if (executionMode === "enterprise-network-fixture") {
    if (!usesNetworkFixtureUrl) fail(`${location}.options.baseUrl`, "enterprise-network-fixture requires explicit $networkFixtureUrl");
    if (testCase.options?.transportMode === "custom") fail(`${location}.executionMode`, "enterprise-network-fixture must use a supported production transport, not the custom seam");
  } else if (usesFixtureBaseUrl) {
    fail(`${location}.options.baseUrl`, "$fixtureBaseUrl is legal only in production-http-fixture mode");
  } else if (usesNetworkFixtureUrl) {
    fail(`${location}.options.baseUrl`, "$networkFixtureUrl is legal only in enterprise-network-fixture mode");
  }
  if (executionMode === "custom-one-attempt" && testCase.options?.transportMode !== "custom") {
    fail(`${location}.executionMode`, "custom-one-attempt requires transportMode=custom");
  }
  if (testCase.options?.transportMode === "custom" && executionMode !== "custom-one-attempt") {
    fail(`${location}.executionMode`, "transportMode=custom requires explicit custom-one-attempt mode");
  }

  if (!Array.isArray(testCase.script)) fail(`${location}.script`, "must be an array");
  for (const [actionIndex, action] of (testCase.script || []).entries()) {
    const actionLocation = `${location}.script[${actionIndex}]`;
    if (!object(action)) {
      fail(actionLocation, "must be an object");
      continue;
    }
    if (!actions.has(action.action)) {
      fail(`${actionLocation}.action`, "unknown action");
      continue;
    }
    const schema = actionSchemas[action.action];
    if (!schema || !exactKeys(actionLocation, action, schema.requiredFields || [], schema.optionalFields || [])) continue;
    for (const [field, value] of Object.entries(action)) {
      if (schema.fieldSchemas[field]) validateActionField(`${actionLocation}.${field}`, value, schema.fieldSchemas[field]);
    }
    if (action.actor !== schema.actor) fail(`${actionLocation}.actor`, `must be ${schema.actor}`);
    if ("phase" in action && !(schema.allowedPhases || []).includes(action.phase)) fail(`${actionLocation}.phase`, "phase is forbidden for this action");
    if ("kind" in action && !(schema.allowedKinds || []).includes(action.kind)) fail(`${actionLocation}.kind`, "kind is forbidden for this action");
    if ("event" in action && !(schema.allowedEvents || []).includes(action.event)) fail(`${actionLocation}.event`, "event is forbidden for this action");
    if ("causeType" in action && !syntheticCauseTypes.has(action.causeType)) fail(`${actionLocation}.causeType`, "must be a declared synthetic cause type");
    if (action.action === "failConnection" && "causeType" in action && action.causeType !== requiredFailureCauseTypes[action.kind]) {
      fail(`${actionLocation}.causeType`, `must equal ${requiredFailureCauseTypes[action.kind]} for failure kind ${action.kind}`);
    }
    if (action.action === "failConnection" && "failureReason" in action && !allowedFailureReasonsByKind[action.kind]?.has(action.failureReason)) {
      fail(`${actionLocation}.failureReason`, `is not a closed failure reason for failure kind ${action.kind}`);
    }
    if (action.action === "failProxyAuthentication" && "failureReason" in action && action.failureReason !== "PROXY_AUTH_REQUIRED") {
      fail(`${actionLocation}.failureReason`, "must equal PROXY_AUTH_REQUIRED for proxy 407");
    }
    if (["closeThrows", "mutateSourceHeadersAfterFactory"].some((field) => field in action) && executionMode !== "custom-one-attempt") {
      fail(actionLocation, "custom response controls are legal only in custom-one-attempt cases");
    }
    if (action.action === "failConnection") {
      const hasPublisherProbe = "bodySubscriptions" in action || "bodyDemands" in action;
      if (hasPublisherProbe) {
        if (executionMode !== "commitment-probe-seam") fail(actionLocation, "publisher probe counters are legal only in commitment-probe-seam cases");
        if (!Number.isInteger(action.bodySubscriptions) || !Number.isInteger(action.bodyDemands)
          || action.bodySubscriptions < 0 || action.bodySubscriptions > 1
          || action.bodyDemands < 0 || action.bodyDemands > 1
          || action.bodyDemands > action.bodySubscriptions) {
          fail(actionLocation, "publisher probe counters must be 0..1 with demand no greater than subscriptions");
        }
      }
      if ("commitState" in action || "customException" in action) {
        if (executionMode !== "custom-one-attempt") fail(actionLocation, "explicit custom commit state is legal only in custom-one-attempt cases");
        if (action.commitState !== "UNKNOWN") fail(`${actionLocation}.commitState`, "must equal UNKNOWN for the pinned conservative custom-transport vectors");
        if ("customException" in action && action.customException !== true) fail(`${actionLocation}.customException`, "must equal true when present");
      }
      if ("seamFailure" in action || "responseConstructionDefect" in action || "transferredBodyCloseCount" in action) {
        const constructionFailure = action.seamFailure === "response-construction-failure";
        if (executionMode !== "custom-one-attempt" || action.kind !== "io" || action.commitState !== "UNKNOWN" || action.customException !== true) {
          fail(actionLocation, "custom seam failures require custom-one-attempt IO/UNKNOWN with sanitized arbitrary failure evidence");
        }
        if (constructionFailure !== (typeof action.responseConstructionDefect === "string")) fail(actionLocation, "responseConstructionDefect is required exactly for response-construction-failure");
        if (constructionFailure !== Number.isInteger(action.transferredBodyCloseCount)) fail(actionLocation, "transferredBodyCloseCount is required exactly for response construction failure");
        if (constructionFailure) {
          const expectedCloseCount = action.responseConstructionDefect === "null-body" ? 0 : 1;
          if (action.transferredBodyCloseCount !== expectedCloseCount) fail(`${actionLocation}.transferredBodyCloseCount`, `must equal ${expectedCloseCount} for ${action.responseConstructionDefect}`);
        }
      }
    }
  }

  const networkProofs = (testCase.script || []).filter((action) => action.action === "proveNetworkCapability");
  if (executionMode === "enterprise-network-fixture") {
    if (networkProofs.length !== 1) fail(`${location}.script`, "enterprise-network-fixture requires exactly one proveNetworkCapability action");
    const proof = networkProofs[0];
    if (proof) {
      if (!document.fixtureProtocol.networkFixture.capabilities.includes(proof.capability)) fail(`${location}.script`, "network proof capability must be advertised by the enterprise fixture handshake");
      if (!document.fixtureProtocol.networkFixture.handshakeFields.includes(proof.endpointField) || !/BaseUrl$/u.test(proof.endpointField)) fail(`${location}.script`, "network proof endpointField must name an SDK base URL in the enterprise fixture handshake");
      if (!Number.isInteger(proof.operationCount) || proof.operationCount < 1) fail(`${location}.script`, "network proof operationCount must be positive");
      if (!Array.isArray(proof.setup) || proof.setup[0] !== "reset" || new Set(proof.setup).size !== proof.setup.length) fail(`${location}.script`, "network proof setup must be a unique ordered list beginning with reset");
      if (!Array.isArray(proof.operationOutcomes) || proof.operationOutcomes.length !== proof.operationCount
        || proof.operationOutcomes.some((outcome, outcomeIndex) => !outcome.startsWith(`op${outcomeIndex + 1}:`))) {
        fail(`${location}.script`, "network proof operationOutcomes must contain one normalized ordered outcome per operation");
      }
      if (!Number.isInteger(proof.coreAttemptCount) || proof.coreAttemptCount < proof.operationCount) fail(`${location}.script`, "network proof coreAttemptCount must cover every operation");
      if (!Number.isInteger(proof.wireBodyPublications) || proof.wireBodyPublications < 0 || proof.wireBodyPublications > proof.coreAttemptCount) {
        fail(`${location}.script`, "network proof wireBodyPublications must prove at most one publication per core attempt");
      }
      const statePredicates = typeof proof.expectedState === "string" ? proof.expectedState.split(";") : [];
      const stateFields = new Set();
      const statePredicatePattern = /^([a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)+)=(?:0|[1-9][0-9]*|limits\.[a-z][A-Za-z0-9]*)$/u;
      if (statePredicates.length === 0 || statePredicates.some((predicate) => {
        const match = predicate.match(statePredicatePattern);
        if (!match || stateFields.has(match[1])) return true;
        stateFields.add(match[1]);
        return false;
      })) {
        fail(`${location}.script`, "network proof expectedState must be unique semicolon-delimited safe state-path equality predicates with decimal or limits-field values");
      }
      const passEvidence = `fixture:network:${proof.capability}:pass`;
      const setupEvidence = `fixture:network:${proof.capability}:setup:${proof.setup.join(",")}`;
      const outcomesEvidence = `fixture:network:${proof.capability}:outcomes:${proof.operationOutcomes.join("|")}`;
      const countEvidence = `fixture:network:${proof.capability}:operations:${proof.operationCount}:core-attempts:${proof.coreAttemptCount}:wire-body-publications:${proof.wireBodyPublications}:state:${proof.expectedState}`;
      if (!testCase.expected.fixtureTrace.includes(passEvidence) || !testCase.expected.fixtureTrace.includes(setupEvidence)
        || !testCase.expected.fixtureTrace.includes(outcomesEvidence) || !testCase.expected.fixtureTrace.includes(countEvidence)) {
        fail(`${location}.expected.fixtureTrace`, "enterprise network proof requires exact capability, setup, per-operation outcome, and state/counter evidence");
      }
    }
  } else if (networkProofs.length !== 0) {
    fail(`${location}.script`, "proveNetworkCapability is legal only in enterprise-network-fixture mode");
  }

  if (!exactKeys(`${location}.expected`, testCase.expected, expectedFields, optionalExpectedFields)) continue;
  const rawMapVariant = testCase.input?.send?.payload?.$rawMapFixture ?? testCase.input?.send?.payload?.$invalidValue?.$rawMapFixture ?? null;
  if (rawMapVariant !== null) {
    if (!Object.hasOwn(requiredRawMapCanonicalization.variants, rawMapVariant)) fail(`${location}.input.send.payload`, "must name a closed raw-map fixture variant");
    const expectedRawMapEvidence = ["order-a", "order-b"].includes(rawMapVariant)
      ? [
          `fixture:raw-map:canonical-payload-sha256:${requiredRawMapCanonicalization.canonicalPayloadSha256}`,
          `fixture:raw-map:canonical-request-sha256:${requiredRawMapCanonicalization.variants[rawMapVariant].canonicalRequestSha256}`,
          "fixture:raw-map:validation-issues:0",
          "fixture:raw-map:raw-key-publications:0",
        ]
      : [
          `fixture:raw-map:key-rejection:${rawMapVariant}`,
          "fixture:raw-map:issue-path:$.raw[{*}]",
          "fixture:raw-map:entry-traversal:0",
          "fixture:raw-map:raw-key-publications:0",
        ];
    if (JSON.stringify(testCase.expected.rawMapEvidence) !== JSON.stringify(expectedRawMapEvidence)) {
      fail(`${location}.expected.rawMapEvidence`, "must equal the validator-derived raw-map canonical-byte/key-rejection/redaction oracle");
    }
  } else if (Object.hasOwn(testCase.expected, "rawMapEvidence")) {
    fail(`${location}.expected.rawMapEvidence`, "is legal only for a closed raw-map fixture variant");
  }
  if (!["constructionThrow", "sendOperation"].includes(testCase.expected.primarySurface)) fail(`${location}.expected.primarySurface`, "must be constructionThrow or sendOperation");
  const nativeCancelledForRoute = (testCase.script || []).some((action) => ["cancelSend", "cancelQueuedWorker"].includes(action.action));
  const fallbackTerminalForRoute = (testCase.script || []).some((action) => [
    "rejectDeadlineScheduler", "rejectExecutorSubmission", "runWorkerInlineDuringSubmit", "starveQueuedWorkerPastDeadline",
    "closeQueuedWorker", "rejectRetryScheduler", "closeClient",
  ].includes(action.action)) || (testCase.expected.attemptTrace || []).some((entry) => /:end:(?:attempt-timeout|operation-deadline|closed)@/u.test(entry));
  const directPreAdmissionForRoute = testCase.expected.error?.code === "OVERLOADED"
    && !(testCase.expected.sideEffectTrace || []).includes("permit.acquire")
    && !fallbackTerminalForRoute;
  const derivedCompletionRoute = testCase.expected.primarySurface === "constructionThrow"
    ? null
    : nativeCancelledForRoute
      ? "NATIVE_CANCEL"
      : directPreAdmissionForRoute
        ? "DIRECT_PRE_ADMISSION"
        : fallbackTerminalForRoute
          ? "TERMINAL_DISPATCHER"
          : "OPERATION_EXECUTION";
  if (testCase.expected.completionRoute !== derivedCompletionRoute) {
    fail(`${location}.expected.completionRoute`, `must equal the input-derived completion route ${derivedCompletionRoute}`);
  }
  const diagnosticsDrains = (testCase.script || []).flatMap((action, actionIndex) => action.action === "drainRuntimeDiagnostics" ? [actionIndex] : []);
  if (testCase.expected.primarySurface === "sendOperation" && (diagnosticsDrains.length !== 1 || diagnosticsDrains[0] !== testCase.script.length - 1)) {
    fail(`${location}.script`, "sendOperation vectors must end with exactly one harness-only drainRuntimeDiagnostics quiescence/read step");
  }
  if (testCase.expected.primarySurface === "constructionThrow" && diagnosticsDrains.length !== 0) fail(`${location}.script`, "construction throws have no runtime diagnostics drain");
  const observerDrops = (testCase.script || []).flatMap((action, actionIndex) => action.action === "observerDrop" ? [actionIndex] : []);
  if (observerDrops.length > 0 && (testCase.options?.observer !== true || observerDrops.some((index) => index !== 0))) {
    fail(`${location}.script`, "observerDrop must be the first action and requires observer=true so the runner installs the borrowed rejecting observer executor before runtime construction");
  }
  let derivedObserverCallbacks = testCase.options?.observer === true ? [...(testCase.expected.observerEmissionTrace || [])] : [];
  for (const action of (testCase.script || []).filter((candidate) => candidate.action === "observerDrop")) {
    const droppedIndex = derivedObserverCallbacks.findIndex((entry) => entry.startsWith(`${action.event}|`));
    if (droppedIndex < 0) fail(`${location}.script`, `observerDrop event ${action.event} must name one emitted observer event`);
    else derivedObserverCallbacks.splice(droppedIndex, 1);
  }
  if (JSON.stringify(testCase.expected.observerCallbackTrace) !== JSON.stringify(derivedObserverCallbacks)) {
    fail(`${location}.expected.observerCallbackTrace`, "must equal observerEmissionTrace minus exactly the rejected dropped callback; observer failures remain delivered callbacks");
  }
  for (const field of [...expectedFields, ...optionalExpectedFields].filter((field) => field.endsWith("Trace") && field in testCase.expected)) {
    stringArray(`${location}.expected.${field}`, testCase.expected[field]);
  }
  for (const [traceIndex, trace] of (testCase.expected.attemptTrace || []).entries()) {
    if (!attemptPattern.test(trace)) fail(`${location}.expected.attemptTrace[${traceIndex}]`, "unknown attempt trace entry");
  }
  for (const [traceIndex, trace] of (testCase.expected.configurationTrace || []).entries()) {
    if (!configurationPattern.test(trace)) fail(`${location}.expected.configurationTrace[${traceIndex}]`, "unknown configuration trace entry");
  }
  for (const [traceIndex, trace] of (testCase.expected.commitTrace || []).entries()) {
    if (!commitPattern.test(trace)) fail(`${location}.expected.commitTrace[${traceIndex}]`, "unknown commitment trace entry");
  }
  const scheduleFacts = new Map();
  for (const [traceIndex, trace] of (testCase.expected.scheduleTrace || []).entries()) {
    if (!schedulePattern.test(trace)) fail(`${location}.expected.scheduleTrace[${traceIndex}]`, "unknown schedule trace entry");
    const match = trace.match(/^retry:([1-9][0-9]*):([a-z-]+)=([^@]+)@([0-9]+)$/);
    if (!match) continue;
    const retryIndex = Number(match[1]);
    const fact = scheduleFacts.get(retryIndex) || {};
    if (["cap", "bound", "entropy", "entropy-error", "delay", "deadline", "cancelled", "scheduler-rejected"].includes(match[2])) {
      if (fact[match[2]]) fail(`${location}.expected.scheduleTrace[${traceIndex}]`, `duplicate ${match[2]} fact for retry ${retryIndex}`);
      fact[match[2]] = { value: Number(match[3]), position: traceIndex, time: Number(match[4]) };
    }
    scheduleFacts.set(retryIndex, fact);
  }
  const invalidEntropyKind = invalidEntropyCases.get(testCase.caseId);
  const scheduledEntropy = [];
  for (const [retryIndex, fact] of [...scheduleFacts.entries()].sort(([left], [right]) => left - right)) {
    for (const requiredFact of ["cap", "bound"]) {
      if (!fact[requiredFact]) fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} is missing ${requiredFact}`);
    }
    if (!fact.entropy && !fact["entropy-error"]) fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} is missing entropy or entropy-error`);
    if (!fact.cap || !fact.bound || (!fact.entropy && !fact["entropy-error"])) continue;
    if (fact.bound.value <= 0) fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} bound must be positive`);
    if (fact.bound.value !== fact.cap.value + 1) fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} bound must equal cap + 1`);
    const entropyFact = fact.entropy || fact["entropy-error"];
    if (!(fact.cap.position < fact.bound.position && fact.bound.position < entropyFact.position)) {
      fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} must order cap, bound, then entropy`);
    }
    if (fact["entropy-error"]) {
      if (fact["entropy-error"].value !== 1 || fact.delay) fail(`${location}.expected.scheduleTrace`, "entropy-error must equal 1 and terminate before delay selection");
      continue;
    }
    scheduledEntropy.push(fact.entropy.value);
    if (!invalidEntropyKind && (fact.entropy.value < 0 || fact.entropy.value >= fact.bound.value)) {
      fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} entropy must be within [0, bound)`);
    }
    if (invalidEntropyKind === "negative" && fact.entropy.value >= 0) {
      fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} must contain a negative injected entropy result`);
    }
    if (invalidEntropyKind === "exclusive-bound" && fact.entropy.value !== fact.bound.value) {
      fail(`${location}.expected.scheduleTrace`, `retry ${retryIndex} entropy must equal the exclusive bound`);
    }
    if (invalidEntropyKind && fact.delay) fail(`${location}.expected.scheduleTrace`, "invalid entropy must terminate before a delay is selected");
  }
  if (Array.isArray(testCase.input?.entropy) && JSON.stringify(testCase.input.entropy) !== JSON.stringify(scheduledEntropy)) {
    fail(`${location}.input.entropy`, "must exactly match returned entropy facts in retry order");
  }
  for (const [traceIndex, trace] of (testCase.expected.observerEmissionTrace || []).entries()) {
    if (!observerPatterns.some((pattern) => pattern.test(trace))) fail(`${location}.expected.observerEmissionTrace[${traceIndex}]`, "unknown observer trace entry");
    if (!trace.includes(`operationId=${document.constants.testOperationId}`)) fail(`${location}.expected.observerEmissionTrace[${traceIndex}]`, "must use the deterministic operation ID");
    const delay = trace.match(/\|delayMs=([0-9]+)$/);
    if (delay && Number(delay[1]) > document.constants.retryMaxMs) fail(`${location}.expected.observerEmissionTrace[${traceIndex}]`, "retry delay exceeds the bounded maximum");
    const code = trace.match(/\|code=([A-Z_]+)/)?.[1];
    if (code && code !== "NONE" && !errorCodes.has(code)) fail(`${location}.expected.observerEmissionTrace[${traceIndex}]`, "unknown observer error code");
  }
  for (const [traceIndex, trace] of (testCase.expected.sideEffectTrace || []).entries()) {
    if (!sideEffectPattern.test(trace)) fail(`${location}.expected.sideEffectTrace[${traceIndex}]`, "unknown side-effect trace entry");
  }
  for (const [traceIndex, trace] of (testCase.expected.byteTrace || []).entries()) {
    if (!bytePattern.test(trace)) fail(`${location}.expected.byteTrace[${traceIndex}]`, "unknown byte trace entry");
  }
  for (const [traceIndex, trace] of (testCase.expected.redactionTrace || []).entries()) {
    if (!redactionPattern.test(trace)) fail(`${location}.expected.redactionTrace[${traceIndex}]`, "unknown redaction trace entry");
  }
  const usedSentinelNames = new Set();
  function collectUsedSentinels(value) {
    if (Buffer.isBuffer(value)) {
      for (const [sentinelName, sentinelValue] of Object.entries(document.sentinels || {})) {
        if (value.includes(Buffer.from(sentinelValue, "utf8"))) usedSentinelNames.add(sentinelName);
      }
      if (value.length >= 2 && value[0] === 0x1f && value[1] === 0x8b) {
        try { collectUsedSentinels(zlib.gunzipSync(value)); } catch { /* malformed gzip has no reachable decoded body */ }
      }
      return;
    }
    if (Array.isArray(value)) return value.forEach(collectUsedSentinels);
    if (object(value)) return Object.values(value).forEach(collectUsedSentinels);
    if (typeof value !== "string") return;
    for (const [sentinelName, sentinelValue] of Object.entries(document.sentinels || {})) {
      if (value.includes(sentinelValue)) usedSentinelNames.add(sentinelName);
    }
  }
  collectUsedSentinels(materializedInput);
  collectUsedSentinels(testCase.options);
  collectUsedSentinels(testCase.script);
  for (const action of testCase.script || []) {
    if (action.action === "respond") collectUsedSentinels(materializeBody(action.body));
    if (action.action === "respondChunks") for (const chunk of action.chunks || []) collectUsedSentinels(materializeBody(chunk));
    if (action.action === "respondEchoSentinels") collectUsedSentinels(responseActionBuffer(action));
  }
  const requiredRedactionTrace = [
    "scan:render:clean",
    "scan:logs:clean",
    "scan:observer:clean",
    "scan:metric-tags:clean",
    "scan:spans:clean",
  ];
  const allowsIdempotencySentinel = testCase.expected.error?.idempotencyKey === document.sentinels?.idempotencyKey;
  if (allowsIdempotencySentinel) requiredRedactionTrace.push("allow:error.idempotencyKey:1");
  if (usedSentinelNames.size > 0) {
    if (JSON.stringify(testCase.expected.redactionTrace) !== JSON.stringify(requiredRedactionTrace)) {
      fail(`${location}.expected.redactionTrace`, "reachable sentinel data requires the exact five public scans and only the explicit error.idempotencyKey allowance");
    }
  }
  if (!allowsIdempotencySentinel && testCase.expected.redactionTrace.includes("allow:error.idempotencyKey:1")) {
    fail(`${location}.expected.redactionTrace`, "allow:error.idempotencyKey:1 requires the sentinel in the terminal error idempotencyKey field");
  }
  for (const [traceIndex, trace] of (testCase.expected.fixtureTrace || []).entries()) {
    if (!fixturePattern.test(trace)) fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "unknown fixture trace entry");
    const idempotency = trace.match(/^fixture:attempt:[1-9][0-9]*:idempotency:(.*)$/u)?.[1];
    if (idempotency !== undefined) {
      const violation = idempotencyKeyViolation(idempotency);
      if (violation) fail(`${location}.expected.fixtureTrace[${traceIndex}]`, violation);
      if (typeof testCase.options?.idempotencyKey === "string" && idempotency !== testCase.options.idempotencyKey) {
        fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "must preserve the caller key byte-exact");
      }
    }
  }
  for (const action of (testCase.script || []).filter((candidate) => candidate.action === "acceptRequest")) {
    for (const assertion of action.assertions || []) {
      const evidence = `fixture:attempt:${action.attempt}:assertion:${assertion}:pass`;
      if (!testCase.expected.fixtureTrace.includes(evidence)) {
        fail(`${location}.expected.fixtureTrace`, `request assertion ${assertion} requires only the private pass evidence ${evidence}`);
      }
    }
  }

  const responseActions = new Set(["respond", "respondChunks", "respondEchoSentinels"]);
  const connectionActions = new Set(["disconnect", "failConnection", "failProxyAuthentication"]);
  const terminalActions = new Set([...responseActions, ...connectionActions]);
  const script = testCase.script || [];
  const responseClassifications = new Map();
  for (const action of script.filter((candidate) => responseActions.has(candidate.action))) {
    const classification = classifyResponseAction(testCase, action, materializedInput, script);
    if (!classification) fail(`${location}.script`, `response attempt ${action.attempt} must have a deterministic semantic classification`);
    else responseClassifications.set(action.attempt, classification);
  }
  const executionReference = deriveExecutionReference(testCase, materializedInput, responseClassifications);
  const configuredMaxAttempts = testCase.options?.maxAttempts ?? document.constants.maxAttemptsTotal;
  const requestLength = deterministicRequestByteLength(testCase);
  const requestLimit = testCase.options?.requestLimitBytes ?? document.constants.requestLimitBytes;
  const explicitRequestSize = Number.isInteger(testCase.input?.send?.payload?.$bytes?.serializedRequestBytes);
  const requestTooLarge = Number.isInteger(requestLength) && Number.isInteger(requestLimit) && requestLength > requestLimit;
  if (executionReference) {
    if (executionReference.attempts.length > configuredMaxAttempts) {
      fail(`${location}.script`, `starts ${executionReference.attempts.length} attempts but maxAttempts permits only ${configuredMaxAttempts}`);
    }
    for (let attemptIndex = 0; attemptIndex < executionReference.attempts.length; attemptIndex += 1) {
      if (executionReference.attempts[attemptIndex].attempt !== attemptIndex + 1) {
        fail(`${location}.script`, "attempt actions must derive contiguous attempts beginning at 1");
      }
      if (executionReference.attempts[attemptIndex].outcome === null || executionReference.attempts[attemptIndex].end === null) {
        fail(`${location}.script`, `attempt ${attemptIndex + 1} has no input-derived terminal outcome`);
      }
    }
    if (JSON.stringify(testCase.expected.attemptTrace) !== JSON.stringify(executionReference.attemptTrace)) {
      fail(`${location}.expected.attemptTrace`, `must exactly equal the input/script-derived attempt timeline ${JSON.stringify(executionReference.attemptTrace)}`);
    }
    if (JSON.stringify(testCase.expected.commitTrace) !== JSON.stringify(executionReference.commitTrace)) {
      fail(`${location}.expected.commitTrace`, `must exactly equal the input/script-derived commitment timeline ${JSON.stringify(executionReference.commitTrace)}`);
    }
  }
  const requestFacts = (testCase.expected.byteTrace || []).filter((trace) => trace.startsWith("request:"));
  if (explicitRequestSize || requestTooLarge || requestFacts.length > 0) {
    const expectedRequestFacts = Number.isInteger(requestLength)
      ? [`request:${requestTooLarge ? requestLimit + 1 : requestLength}:${requestTooLarge ? "rejected" : "accepted"}`]
      : [];
    if (JSON.stringify(requestFacts) !== JSON.stringify(expectedRequestFacts)) {
      fail(`${location}.expected.byteTrace`, `request byte facts must derive from the inclusive request limit: ${JSON.stringify(expectedRequestFacts)}`);
    }
  }
  if (requestTooLarge) {
    if ((executionReference?.attempts.length || 0) !== 0 || script.some((action) => Number.isInteger(action.attempt))) {
      fail(`${location}.script`, "a request larger than the inclusive request limit must fail before every attempt and fixture action");
    }
    if (testCase.expected.error?.code !== "REQUEST_TOO_LARGE" || testCase.expected.error?.retryable !== false || testCase.expected.delivery !== "NOT_SENT") {
      fail(`${location}.expected.error`, "a request larger than the inclusive request limit must be REQUEST_TOO_LARGE/NOT_SENT/non-retryable");
    }
    if ((testCase.expected.sideEffectTrace || []).some((trace) => trace.startsWith("network:"))) {
      fail(`${location}.expected.sideEffectTrace`, "request-limit rejection must not reach the network");
    }
  } else if (explicitRequestSize && testCase.expected.error?.code === "REQUEST_TOO_LARGE") {
    fail(`${location}.expected.error`, "a request at or below the inclusive request limit cannot be REQUEST_TOO_LARGE");
  }

  const derivedScheduleTrace = [];
  let scheduleTerminal = null;
  if (executionReference) {
    const retryBase = testCase.options?.retryBaseMs ?? document.constants.retryBaseMs;
    const retryMax = testCase.options?.retryMaxMs ?? document.constants.retryMaxMs;
    const retryAfterMax = testCase.options?.retryAfterMaxMs ?? document.constants.retryAfterMaxMs;
    let entropyIndex = 0;
    for (const state of executionReference.attempts) {
      const responseClassification = responseClassifications.get(state.attempt);
      const failureSemantics = terminalFailureSemantics[state.outcome];
      const retryable = state.terminalAction?.customException === true
        ? false
        : state.terminalAction && responseActions.has(state.terminalAction.action)
        ? responseClassification?.retryable === true
        : failureSemantics?.retryable === true;
      if (!retryable || state.attempt >= configuredMaxAttempts) continue;
      const remainingOperationMs = Math.max(0, executionReference.operationDeadline - state.end);
      const exponent = Math.max(0, state.attempt - 1);
      const exponentialCap = retryBase >= retryMax
        ? retryMax
        : Math.min(retryMax, retryBase * (2 ** exponent));
      const cap = Math.min(exponentialCap, remainingOperationMs);
      const bound = cap + 1;
      if (object(materializedInput.retryEntropyError)) {
        derivedScheduleTrace.push(`retry:${state.attempt}:cap=${cap}@${state.end}`);
        derivedScheduleTrace.push(`retry:${state.attempt}:bound=${bound}@${state.end}`);
        derivedScheduleTrace.push(`retry:${state.attempt}:entropy-error=1@${state.end}`);
        scheduleTerminal = { code: "CONFIGURATION", retryable: false, causeType: "ConformanceEntropyFailure", time: state.end, state };
        continue;
      }
      const entropy = materializedInput.entropy?.[entropyIndex];
      entropyIndex += 1;
      derivedScheduleTrace.push(`retry:${state.attempt}:cap=${cap}@${state.end}`);
      derivedScheduleTrace.push(`retry:${state.attempt}:bound=${bound}@${state.end}`);
      derivedScheduleTrace.push(`retry:${state.attempt}:entropy=${entropy}@${state.end}`);
      if (!Number.isSafeInteger(entropy) || entropy < 0 || entropy >= bound) {
        scheduleTerminal = { code: "CONFIGURATION", retryable: false, causeType: null, time: state.end, state };
        continue;
      }
      const retryAfter = state.terminalAction && responseActions.has(state.terminalAction.action)
        ? parseRetryAfter(state.terminalAction.headers, state.responseWall, retryAfterMax)
        : { present: false };
      if (retryAfter.present) {
        derivedScheduleTrace.push(`retry:${state.attempt}:retry-after=${retryAfter.valid ? retryAfter.milliseconds : "invalid"}@${state.end}`);
      }
      const retryAfterMs = retryAfter.valid ? retryAfter.milliseconds : 0;
      const delay = Math.max(entropy, Math.min(retryAfterMs, remainingOperationMs));
      derivedScheduleTrace.push(`retry:${state.attempt}:delay=${delay}@${state.end}`);

      const intervalEndIndex = executionReference.attempts.find((candidate) => candidate.attempt === state.attempt + 1)?.terminalAction
        ? script.findIndex((action, actionIndex) => actionIndex > state.terminalScriptIndex && action.attempt === state.attempt + 1)
        : script.length;
      const interval = script.slice(state.terminalScriptIndex + 1, intervalEndIndex < 0 ? script.length : intervalEndIndex);
      const elapsed = interval.filter((action) => action.action === "advanceTime").reduce((sum, action) => saturatingAdd(sum, action.byMs), 0);
      const backoffTerminal = interval.find((action) => (action.action === "cancelSend" && action.phase === "backoff")
        || action.action === "closeClient" || action.action === "rejectRetryScheduler");
      const nextState = executionReference.attempts.find((candidate) => candidate.attempt === state.attempt + 1);
      if (backoffTerminal?.action === "rejectRetryScheduler") {
        derivedScheduleTrace.push(`retry:${state.attempt}:scheduler-rejected=${state.end + elapsed}@${state.end + elapsed}`);
        scheduleTerminal = { code: "OVERLOADED", retryable: false, causeType: "ConformanceExecutorRejection", time: state.end + elapsed, state };
        if (nextState) fail(`${location}.script`, `retry ${state.attempt} starts another attempt after input-derived scheduler rejection`);
      } else if (backoffTerminal) {
        derivedScheduleTrace.push(`retry:${state.attempt}:cancelled=${state.end + elapsed}@${state.end + elapsed}`);
        scheduleTerminal = { code: backoffTerminal.action === "closeClient" ? "CLOSED" : "CANCELLED", retryable: false, causeType: null, time: state.end + elapsed, state };
        if (nextState) fail(`${location}.script`, `retry ${state.attempt} starts another attempt after input-derived backoff cancellation`);
      } else if (delay >= remainingOperationMs) {
        derivedScheduleTrace.push(`retry:${state.attempt}:deadline=${remainingOperationMs}@${executionReference.operationDeadline}`);
        scheduleTerminal = { code: "OPERATION_DEADLINE", retryable: false, causeType: null, time: executionReference.operationDeadline, state };
        if (elapsed !== delay) fail(`${location}.script`, `retry ${state.attempt} must advance monotonic time by exact deadline-clamped delay ${delay}, got ${elapsed}`);
        if (nextState) fail(`${location}.script`, `retry ${state.attempt} starts another attempt at the exclusive operation deadline`);
      } else {
        if (elapsed !== delay) fail(`${location}.script`, `retry ${state.attempt} must advance monotonic time by exact effective delay ${delay}, got ${elapsed}`);
        if (!nextState) fail(`${location}.script`, `retry ${state.attempt} requires attempt ${state.attempt + 1} after its input-derived delay`);
        else if (nextState.start !== state.end + delay) fail(`${location}.script`, `attempt ${nextState.attempt} must start at ${state.end + delay}, got ${nextState.start}`);
      }
    }
  }
  if (JSON.stringify(testCase.expected.scheduleTrace) !== JSON.stringify(derivedScheduleTrace)) {
    fail(`${location}.expected.scheduleTrace`, `must exactly equal input/script-derived retry scheduling ${JSON.stringify(derivedScheduleTrace)}`);
  }
  const operationSetup = deriveOperationSetup(testCase, materializedInput, executionReference, responseClassifications);
  if (JSON.stringify(testCase.expected.configurationTrace) !== JSON.stringify(operationSetup.configurationTrace)) {
    fail(`${location}.expected.configurationTrace`, `must exactly equal input-derived configuration precedence ${JSON.stringify(operationSetup.configurationTrace)}`);
  }
  if (JSON.stringify(testCase.expected.sideEffectTrace) !== JSON.stringify(operationSetup.sideEffectTrace)) {
    fail(`${location}.expected.sideEffectTrace`, `must exactly equal the input/script-derived lifecycle ${JSON.stringify(operationSetup.sideEffectTrace)}`);
  }
  if (testCase.expected.error !== null && testCase.expected.error.idempotencyKey !== operationSetup.operationKey) {
    fail(`${location}.expected.error.idempotencyKey`, `must equal the operation key at the terminal stage (${JSON.stringify(operationSetup.operationKey)})`);
  }
  const providerFailure = operationSetup.credential?.providerError;
  if (providerFailure && providerFailure.causeType !== "ConformanceProviderFailure") {
    fail(`${location}.input.providerValues`, "provider exceptions must use the fixed ConformanceProviderFailure synthetic cause type");
  }
  const idempotencyGeneratorFailure = operationSetup.idempotencyGeneratorError;
  if (idempotencyGeneratorFailure && idempotencyGeneratorFailure.causeType !== "ConformanceIdempotencyFailure") {
    fail(`${location}.input.generatedValues`, "idempotency generator exceptions must use the fixed ConformanceIdempotencyFailure synthetic cause type");
  }

  const lastState = executionReference?.attempts.at(-1) || null;
  const hasUnresolvedPossibleAcceptanceThrough = (terminalState) => Boolean(terminalState) && executionReference.attempts
    .filter((state) => state.attempt <= terminalState.attempt)
    .some((state) => state.commit !== null && !["http-rejected", "rate-limited"].includes(state.outcome));
  let derivedTerminal = null;
  if (operationSetup.terminalCode !== null) {
    derivedTerminal = {
      code: operationSetup.terminalCode,
      retryable: false,
      causeType: providerFailure
        ? "ConformanceProviderFailure"
        : idempotencyGeneratorFailure
          ? "ConformanceIdempotencyFailure"
          : operationSetup.executorRejection ? "ConformanceExecutorRejection" : null,
      delivery: "NOT_SENT",
      httpStatus: null,
      result: null,
    };
  } else if (scheduleTerminal) {
    const unresolvedPossibleAcceptance = hasUnresolvedPossibleAcceptanceThrough(scheduleTerminal.state);
    derivedTerminal = {
      ...scheduleTerminal,
      delivery: ["CANCELLED", "CLOSED"].includes(scheduleTerminal.code)
        ? unresolvedPossibleAcceptance ? "CANCELLED_UNKNOWN" : "NOT_SENT"
        : unresolvedPossibleAcceptance ? "POSSIBLY_SENT" : "NOT_SENT",
      httpStatus: scheduleTerminal.state.responseStatus,
      result: null,
    };
  } else if (lastState) {
    const classification = responseClassifications.get(lastState.attempt);
    const responseTerminal = Boolean(classification) && lastState.terminalAction && responseActions.has(lastState.terminalAction.action) && lastState.outcome === classification.attemptOutcome;
    const semantics = lastState.terminalAction?.customException === true
      ? { code: "IO", retryable: false, causeType: "ConformanceCustomTransportFailure" }
      : responseTerminal
      ? { code: classification.code, retryable: classification.retryable, causeType: null }
      : terminalFailureSemantics[lastState.outcome];
    const definitelyRejected = ["http-rejected", "rate-limited"].includes(lastState.outcome);
    const ambiguouslyRejected = lastState.outcome === "conflict";
    const cancelled = ["cancelled", "closed"].includes(lastState.outcome);
    const unresolvedAtTerminal = hasUnresolvedPossibleAcceptanceThrough(lastState);
    const priorUncertainCommittedAttempt = executionReference.attempts
      .filter((state) => state.attempt < lastState.attempt)
      .some((state) => state.commit !== null && !["http-rejected", "rate-limited"].includes(state.outcome));
    derivedTerminal = {
      ...semantics,
      delivery: lastState.outcome === "accepted"
        ? "ACCEPTED"
        : ambiguouslyRejected
          ? "POSSIBLY_SENT"
        : definitelyRejected
          ? priorUncertainCommittedAttempt ? "POSSIBLY_SENT" : "REJECTED"
          : cancelled
            ? unresolvedAtTerminal ? "CANCELLED_UNKNOWN" : "NOT_SENT"
            : unresolvedAtTerminal ? "POSSIBLY_SENT" : "NOT_SENT",
      httpStatus: lastState.responseStatus,
      result: lastState.outcome === "accepted" ? classification.result : null,
    };
  }
  if (derivedTerminal) {
    if (derivedTerminal.result !== null) {
      if (JSON.stringify(testCase.expected.result) !== JSON.stringify(derivedTerminal.result)) {
        fail(`${location}.expected.result`, `must exactly equal the parsed response SendResult ${JSON.stringify(derivedTerminal.result)}`);
      }
      if (testCase.expected.error !== null || testCase.expected.delivery !== "ACCEPTED") {
        fail(`${location}.expected`, "input-derived successful response must terminate as ACCEPTED without an error");
      }
    } else {
      const actual = testCase.expected.error;
      if (actual === null) fail(`${location}.expected.error`, `input-derived terminal ${derivedTerminal.code} requires an error`);
      else {
        for (const field of ["code", "retryable", "httpStatus"]) {
          if (actual[field] !== derivedTerminal[field]) fail(`${location}.expected.error.${field}`, `must equal input/script-derived ${JSON.stringify(derivedTerminal[field])}`);
        }
        const derivedCauseCategory = publicCauseCategory(derivedTerminal.causeType);
        if (actual.causeCategory !== derivedCauseCategory) {
          fail(`${location}.expected.error.causeCategory`, `must equal input/script-derived ${JSON.stringify(derivedCauseCategory)}`);
        }
        const derivedFailureReason = ["DNS", "CONNECT", "PROXY", "TLS", "IO", "ATTEMPT_TIMEOUT"].includes(derivedTerminal.code)
          ? (lastState?.terminalAction?.failureReason ?? "UNKNOWN")
          : null;
        if (actual.failureReason !== derivedFailureReason) {
          fail(`${location}.expected.error.failureReason`, `must equal input/script-derived ${JSON.stringify(derivedFailureReason)}`);
        }
      }
      if (testCase.expected.result !== null) fail(`${location}.expected.result`, "input-derived terminal failure cannot return a result");
      if (testCase.expected.delivery !== derivedTerminal.delivery || actual?.delivery !== derivedTerminal.delivery) {
        fail(`${location}.expected.delivery`, `must equal input/script-derived delivery ${derivedTerminal.delivery}`);
      }
    }
  }
  const attemptEntries = new Map();
  const attemptFacts = new Map();
  let priorTraceAttempt = 0;
  let priorTraceTime = 0;
  for (const [traceIndex, trace] of (testCase.expected.attemptTrace || []).entries()) {
    const match = trace.match(/^attempt:([1-9][0-9]*):(.+)@([0-9]+)$/u);
    if (!match) continue;
    const attempt = Number(match[1]);
    const time = Number(match[3]);
    if (attempt < priorTraceAttempt) fail(`${location}.expected.attemptTrace[${traceIndex}]`, "attempts must be strictly grouped in increasing order");
    if (time < priorTraceTime) fail(`${location}.expected.attemptTrace[${traceIndex}]`, "timestamps must be nondecreasing");
    priorTraceAttempt = attempt;
    priorTraceTime = time;
    const entries = attemptEntries.get(attempt) || [];
    entries.push(match[2]);
    attemptEntries.set(attempt, entries);
    const facts = attemptFacts.get(attempt) || {};
    if (match[2] === "start") facts.startTime = time;
    if (match[2] === "commit") facts.committed = true;
    const response = match[2].match(/^response:([2-5][0-9]{2})$/u);
    if (response) facts.responseStatus = Number(response[1]);
    const terminal = match[2].match(/^end:([a-z0-9-]+)$/u);
    if (terminal) {
      facts.endTime = time;
      facts.endOutcome = terminal[1];
    }
    attemptFacts.set(attempt, facts);
  }
  const attemptStarts = (testCase.expected.attemptTrace || []).flatMap((trace) => {
    const match = trace.match(/^attempt:([1-9][0-9]*):start@/);
    return match ? [Number(match[1])] : [];
  });
  for (let attemptIndex = 0; attemptIndex < attemptStarts.length; attemptIndex += 1) {
    if (attemptStarts[attemptIndex] !== attemptIndex + 1) fail(`${location}.expected.attemptTrace`, "attempt starts must be ordered contiguously from 1");
  }
  for (const attempt of attemptStarts.slice(1)) {
    const facts = scheduleFacts.get(attempt - 1);
    if (!facts || !facts.cap || !facts.bound || !facts.entropy || !facts.delay) fail(`${location}.expected.scheduleTrace`, `started retry attempt ${attempt} requires cap, bound, entropy, and delay facts`);
  }
  for (const [attempt, classification] of responseClassifications) {
    if (!classification.retryable || attempt >= configuredMaxAttempts) continue;
    const facts = scheduleFacts.get(attempt);
    const responseIndex = script.findIndex((action) => responseActions.has(action.action) && action.attempt === attempt);
    const laterScript = script.slice(responseIndex + 1);
    const backoffCancellation = laterScript.some((action) => (action.action === "cancelSend" && action.phase === "backoff") || action.action === "closeClient");
    const schedulerRejection = laterScript.some((action) => action.action === "rejectRetryScheduler");
    const operationStart = materializedInput.clock?.monotonicMs;
    const operationTimeout = testCase.options?.operationTimeoutMs ?? document.constants.operationTimeoutMs;
    const operationDeadline = Number.isInteger(operationStart) ? Math.min(Number.MAX_SAFE_INTEGER, operationStart + operationTimeout) : null;
    const attemptEnd = attemptFacts.get(attempt)?.endTime;
    const advancedMs = laterScript.filter((action) => action.action === "advanceTime").reduce((sum, action) => sum + action.byMs, 0);
    const deadlineReached = Number.isInteger(operationDeadline) && Number.isInteger(attemptEnd) && attemptEnd + advancedMs >= operationDeadline;
    const retryBase = testCase.options?.retryBaseMs ?? document.constants.retryBaseMs;
    const retryMax = testCase.options?.retryMaxMs ?? document.constants.retryMaxMs;
    const remainingOperationBudget = Number.isInteger(operationDeadline) && Number.isInteger(attemptEnd) ? Math.max(0, operationDeadline - attemptEnd) : Number.POSITIVE_INFINITY;
    const derivedCap = Math.min(retryMax, retryBase * (2 ** Math.max(0, attempt - 1)), remainingOperationBudget);
    const injectedEntropy = materializedInput.entropy?.[attempt - 1];
    const entropyFailureTerminal = object(materializedInput.retryEntropyError);
    const invalidEntropyTerminal = Number.isInteger(injectedEntropy) && (injectedEntropy < 0 || injectedEntropy >= derivedCap + 1);
    if (facts && (facts.cap?.value !== derivedCap || facts.bound?.value !== derivedCap + 1
      || (!entropyFailureTerminal && facts.entropy?.value !== injectedEntropy)
      || (entropyFailureTerminal && facts["entropy-error"]?.value !== 1))) {
      fail(`${location}.expected.scheduleTrace`, `retry ${attempt} cap, bound, and entropy must derive from configured jitter and materialized input entropy`);
    }
    if (Boolean(facts?.deadline) !== deadlineReached) {
      fail(`${location}.expected.scheduleTrace`, `retry ${attempt} deadline fact must match the input clock, operation timeout, attempt elapsed time, and scripted advanceTime`);
    }
    if (facts?.deadline && (facts.deadline.value !== operationTimeout || facts.deadline.time !== operationDeadline)) {
      fail(`${location}.expected.scheduleTrace`, `retry ${attempt} deadline fact must equal elapsed ${operationTimeout} at monotonic ${operationDeadline}`);
    }
    if (Boolean(facts?.cancelled) !== backoffCancellation) {
      fail(`${location}.expected.scheduleTrace`, `retry ${attempt} cancelled fact requires a scripted backoff cancelSend or closeClient action`);
    }
    const independentlyInterrupted = backoffCancellation || schedulerRejection || deadlineReached || invalidEntropyTerminal || entropyFailureTerminal;
    if (independentlyInterrupted && attemptStarts.includes(attempt + 1)) {
      fail(`${location}.expected.attemptTrace`, `retryable response attempt ${attempt} must not start another attempt after an input-derived terminal interruption`);
    }
    if (!independentlyInterrupted && !attemptStarts.includes(attempt + 1)) {
      fail(`${location}.expected.attemptTrace`, `retryable response attempt ${attempt} below maxAttempts ${configuredMaxAttempts} requires a scheduled next attempt or an input-derived terminal interruption`);
    }
    const backoffClose = laterScript.some((action) => action.action === "closeClient");
    if (backoffCancellation && testCase.expected.error?.code !== (backoffClose ? "CLOSED" : "CANCELLED")) fail(`${location}.expected.error`, `scripted backoff ${backoffClose ? "close" : "cancellation"} must preserve its structured terminal identity`);
    if (deadlineReached && testCase.expected.error?.code !== "OPERATION_DEADLINE") fail(`${location}.expected.error`, "input-derived operation deadline must terminate with OPERATION_DEADLINE");
    if (invalidEntropyTerminal && testCase.expected.error?.code !== "CONFIGURATION") fail(`${location}.expected.error`, "input-derived invalid entropy must terminate with CONFIGURATION");
    if (entropyFailureTerminal && (testCase.expected.error?.code !== "CONFIGURATION" || testCase.expected.error?.causeCategory !== "RETRY_ENTROPY")) {
      fail(`${location}.expected.error`, "injected entropy exceptions must terminate as sanitized CONFIGURATION/RETRY_ENTROPY");
    }
  }
  const structuralConstructionFailure = operationSetup.structural.failed;
  const preObserverFailure = structuralConstructionFailure || operationSetup.executorRejection || operationSetup.closedBeforeAdmission || operationSetup.queuedTerminal;
  if (structuralConstructionFailure) {
    if (JSON.stringify(testCase.expected.configurationTrace) !== JSON.stringify(["snapshot:0"])) fail(`${location}.expected.configurationTrace`, "construction failure must use snapshot:0");
    if (testCase.expected.sideEffectTrace.length !== 0) fail(`${location}.expected.sideEffectTrace`, "construction failure occurs before side effects");
    if (testCase.expected.observerEmissionTrace.length !== 0) fail(`${location}.expected.observerEmissionTrace`, "construction failure occurs before operation observers");
    if (testCase.expected.attemptTrace.length !== 0 || testCase.expected.commitTrace.length !== 0 || testCase.expected.scheduleTrace.length !== 0 || testCase.expected.fixtureTrace.length !== 0) {
      fail(`${location}.expected`, "construction failure occurs before attempts, scheduling, and fixture execution");
    }
    const constructionCode = operationSetup.structural.code;
    if (testCase.expected.error?.code !== constructionCode || testCase.expected.delivery !== "NOT_SENT" || testCase.expected.error?.attemptCount !== 0 || testCase.expected.error?.idempotencyKey !== null) {
      fail(`${location}.expected`, `construction failure must be ${constructionCode}/NOT_SENT with zero attempts and no idempotency key`);
    }
    if (testCase.expected.primarySurface !== "constructionThrow" || Object.hasOwn(testCase.expected, "outcomeEvidence")) {
      fail(`${location}.expected`, "snapshot:0 structural failure must synchronously throw from construction and cannot create a SendOperation or outcome channel");
    }
  } else if (testCase.expected.primarySurface !== "sendOperation" || !Object.hasOwn(testCase.expected, "outcomeEvidence") || Object.hasOwn(testCase.expected, "constructionEvidence")) {
    fail(`${location}.expected`, "every non-construction path must return SendOperation with outcomeEvidence and no constructionEvidence");
  }
  const observerTrace = testCase.expected.observerEmissionTrace || [];
  const observerOperationStarts = observerTrace.flatMap((trace, traceIndex) => trace.startsWith("operation.start|") ? [{ traceIndex }] : []);
  const observerAttemptStarts = observerTrace.flatMap((trace, traceIndex) => {
    const match = trace.match(/^attempt\.start\|[^|]+\|attempt=([1-9][0-9]*)$/u);
    return match ? [{ attempt: Number(match[1]), traceIndex }] : [];
  });
  const observerAttemptEnds = observerTrace.flatMap((trace, traceIndex) => {
    const match = trace.match(/^attempt\.end\|[^|]+\|attempt=([1-9][0-9]*)\|durationMs=([0-9]+)\|outcome=([a-z0-9-]+)\|code=([A-Z_]+|NONE)\|failureReason=([A-Z_]+|NONE)\|delivery=([A-Z_]+)\|statusClass=(none|[2-5]xx)$/u);
    return match ? [{ attempt: Number(match[1]), duration: Number(match[2]), outcome: match[3], code: match[4], failureReason: match[5], delivery: match[6], statusClass: match[7], traceIndex }] : [];
  });
  const observerRetryDelays = observerTrace.flatMap((trace, traceIndex) => {
    const match = trace.match(/^retry\.delay\|[^|]+\|attempt=((?:[2-9]|[1-9][0-9]+))\|delayMs=([0-9]+)$/u);
    return match ? [{ attempt: Number(match[1]), delay: Number(match[2]), traceIndex }] : [];
  });
  const observerCancels = observerTrace.flatMap((trace, traceIndex) => {
    const match = trace.match(/^operation\.cancel\|[^|]+\|delivery=([A-Z_]+)$/u);
    return match ? [{ delivery: match[1], traceIndex }] : [];
  });
  const observerOperationEnds = observerTrace.flatMap((trace, traceIndex) => {
    const match = trace.match(/^operation\.end\|[^|]+\|durationMs=([0-9]+)\|outcome=([a-z0-9-]+)\|code=([A-Z_]+|NONE)\|failureReason=([A-Z_]+|NONE)\|delivery=([A-Z_]+)\|attempts=([0-9]+)$/u);
    return match ? [{ duration: Number(match[1]), outcome: match[2], code: match[3], failureReason: match[4], delivery: match[5], attempts: Number(match[6]), traceIndex }] : [];
  });
  const operationStartTime = materializedInput.clock?.monotonicMs;
  const expectedOperationDuration = Number.isInteger(operationStartTime)
    ? (scheduleTerminal?.time ?? lastState?.end ?? operationStartTime) - operationStartTime
    : null;
  const expectedOperationOutcome = testCase.expected.result !== null
    ? "accepted"
    : script.some((action) => action.action === "cancelSend")
      ? "cancelled"
      : testCase.expected.error?.code === "OVERLOADED"
        ? "overloaded"
        : testCase.expected.delivery === "REJECTED"
          ? "rejected"
          : "failed";
  if (preObserverFailure) {
    if (observerTrace.length !== 0) fail(`${location}.expected.observerEmissionTrace`, "pre-observer construction/admission failure must not contain operation observers");
  } else if (observerOperationEnds.length !== 1) fail(`${location}.expected.observerEmissionTrace`, "must contain exactly one operation.end entry");
  else {
    if (observerOperationStarts.length !== 1 || observerOperationStarts[0].traceIndex !== 0) fail(`${location}.expected.observerEmissionTrace`, "must begin with exactly one operation.start entry");
    if (observerOperationEnds[0].traceIndex !== observerTrace.length - 1) fail(`${location}.expected.observerEmissionTrace`, "must end with the terminal operation.end entry");
    const end = observerOperationEnds[0];
    const expectedCode = testCase.expected.error?.code || "NONE";
    if (end.code !== expectedCode) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end code must match terminal error");
    const expectedFailureReason = testCase.expected.error?.failureReason ?? "NONE";
    if (end.failureReason !== expectedFailureReason) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end failureReason must match terminal error");
    if (end.delivery !== testCase.expected.delivery) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end delivery must match expected.delivery");
    if (end.attempts !== attemptStarts.length) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end attempts must match started attempts");
    if (end.outcome !== expectedOperationOutcome) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end outcome must match the terminal result/error semantics");
    if (end.duration !== expectedOperationDuration) fail(`${location}.expected.observerEmissionTrace[${end.traceIndex}]`, "operation.end durationMs must equal deterministic monotonic elapsed time");
    if (JSON.stringify(observerAttemptStarts.map(({ attempt }) => attempt)) !== JSON.stringify(attemptStarts)) fail(`${location}.expected.observerEmissionTrace`, "attempt.start entries must exactly match attempted attempts");
    if (JSON.stringify(observerAttemptEnds.map(({ attempt }) => attempt)) !== JSON.stringify(attemptStarts)) fail(`${location}.expected.observerEmissionTrace`, "attempt.end entries must exactly match attempted attempts");
    for (const attempt of attemptStarts) {
      const start = observerAttemptStarts.find((entry) => entry.attempt === attempt);
      const attemptEnd = observerAttemptEnds.find((entry) => entry.attempt === attempt);
      if (start && attemptEnd && start.traceIndex >= attemptEnd.traceIndex) fail(`${location}.expected.observerEmissionTrace`, `attempt ${attempt} observer start must precede its end`);
      const priorEnd = observerAttemptEnds.find((entry) => entry.attempt === attempt - 1);
      if (start && priorEnd && priorEnd.traceIndex >= start.traceIndex) fail(`${location}.expected.observerEmissionTrace`, `attempt ${attempt - 1} observer end must precede attempt ${attempt} start`);
      const facts = attemptFacts.get(attempt) || {};
      const terminalAction = script.find((action) => terminalActions.has(action.action) && action.attempt === attempt);
      let causalOutcome = facts.endOutcome;
      if (terminalAction?.action === "failConnection") causalOutcome = terminalAction.kind;
      if (terminalAction?.action === "disconnect") causalOutcome = "io";
      if (responseActions.has(terminalAction?.action)) {
        causalOutcome = responseClassifications.get(attempt)?.attemptOutcome;
      } else if (!terminalAction) {
        if (script.some((action) => action.action === "closeClient")) causalOutcome = "closed";
        else if (script.some((action) => action.action === "cancelSend")) causalOutcome = "cancelled";
      }
      if (facts.endOutcome !== causalOutcome) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} terminal outcome must match its fixture/runner action semantics`);
      const causalCode = attemptOutcomeCodes.get(causalOutcome);
      const unresolvedFloor = attemptStarts.slice(0, attempt).some((candidate) => {
        const fact = attemptFacts.get(candidate);
        return fact?.committed && !["http-rejected", "rate-limited"].includes(fact.endOutcome);
      });
      const causalState = executionReference?.attempts.find((candidate) => candidate.attempt === attempt);
      const causalUnresolved = hasUnresolvedPossibleAcceptanceThrough(causalState);
      const causalDelivery = causalOutcome === "accepted"
        ? "ACCEPTED"
        : causalOutcome === "conflict"
          ? "POSSIBLY_SENT"
        : ["http-rejected", "rate-limited"].includes(causalOutcome)
          ? "REJECTED"
          : ["cancelled", "closed"].includes(causalOutcome)
            ? (causalUnresolved ? "CANCELLED_UNKNOWN" : "NOT_SENT")
            : (unresolvedFloor ? "POSSIBLY_SENT" : "NOT_SENT");
      const causalStatusClass = Number.isInteger(facts.responseStatus) ? `${Math.floor(facts.responseStatus / 100)}xx` : "none";
      const causalDuration = Number.isInteger(facts.startTime) && Number.isInteger(facts.endTime) ? facts.endTime - facts.startTime : null;
      const causalFailureReason = terminalAction?.failureReason
        ?? (["DNS", "CONNECT", "PROXY", "TLS", "IO", "ATTEMPT_TIMEOUT"].includes(causalCode) ? "UNKNOWN" : "NONE");
      const causalObserverEnd = { duration: causalDuration, outcome: causalOutcome, code: causalCode, failureReason: causalFailureReason, delivery: causalDelivery, statusClass: causalStatusClass };
      const actualObserverEnd = attemptEnd && { duration: attemptEnd.duration, outcome: attemptEnd.outcome, code: attemptEnd.code, failureReason: attemptEnd.failureReason, delivery: attemptEnd.delivery, statusClass: attemptEnd.statusClass };
      if (!causalCode || JSON.stringify(actualObserverEnd) !== JSON.stringify(causalObserverEnd)) {
        fail(`${location}.expected.observerEmissionTrace`, `attempt ${attempt} end must match deterministic terminal action, status, delivery, and duration semantics`);
      }
    }
    const expectedRetryDelays = [...scheduleFacts.entries()]
      .filter(([, facts]) => facts.delay)
      .sort(([left], [right]) => left - right)
      .map(([retryIndex, facts]) => ({ attempt: retryIndex + 1, delay: facts.delay.value }));
    if (JSON.stringify(observerRetryDelays.map(({ attempt, delay }) => ({ attempt, delay }))) !== JSON.stringify(expectedRetryDelays)) {
      fail(`${location}.expected.observerEmissionTrace`, "retry.delay entries must exactly match scheduled retries and delays");
    }
    for (const delay of observerRetryDelays) {
      const priorEnd = observerAttemptEnds.find((entry) => entry.attempt === delay.attempt - 1);
      const nextStart = observerAttemptStarts.find((entry) => entry.attempt === delay.attempt);
      if (priorEnd && priorEnd.traceIndex >= delay.traceIndex) fail(`${location}.expected.observerEmissionTrace`, `retry delay for attempt ${delay.attempt} must follow the prior attempt end`);
      if (nextStart && delay.traceIndex >= nextStart.traceIndex) fail(`${location}.expected.observerEmissionTrace`, `retry delay for attempt ${delay.attempt} must precede its attempt start`);
    }
    const cancellationRequested = script.some((action) => action.action === "cancelSend");
    if (cancellationRequested) {
      if (observerCancels.length !== 1 || observerCancels[0].delivery !== testCase.expected.delivery) fail(`${location}.expected.observerEmissionTrace`, "cancellation requires exactly one matching operation.cancel entry");
      const cancel = observerCancels[0];
      const precedingPositions = [...observerAttemptEnds, ...observerRetryDelays].map(({ traceIndex }) => traceIndex);
      if (cancel && (cancel.traceIndex <= 0 || cancel.traceIndex >= end.traceIndex || precedingPositions.some((position) => position >= cancel.traceIndex))) {
        fail(`${location}.expected.observerEmissionTrace`, "operation.cancel must follow attempt/retry observers and precede operation.end");
      }
    } else if (observerCancels.length !== 0) fail(`${location}.expected.observerEmissionTrace`, "operation.cancel requires a cancellation or close action");
  }
  const observerContext = materializedInput.observerContext;
  if (observerContext === undefined) {
    if (Object.hasOwn(testCase.expected, "contextTrace")) fail(`${location}.expected.contextTrace`, "requires input.observerContext");
  } else {
    const expectedContextTrace = [
      `caller.set|token=${observerContext}`,
      `operation.capture|token=${observerContext}`,
      ...(testCase.expected.observerCallbackTrace || []).map((trace) => `callback|event=${trace.slice(0, trace.indexOf("|"))}|token=${observerContext}`),
      `caller.restore|token=${observerContext}`,
    ];
    if (JSON.stringify(testCase.expected.contextTrace) !== JSON.stringify(expectedContextTrace)) {
      fail(`${location}.expected.contextTrace`, "must prove one operation-start capture, the captured parent on every observer callback, and caller restoration");
    }
  }
  for (const [attempt, entries] of attemptEntries) {
    if (entries[0] !== "start") fail(`${location}.expected.attemptTrace`, `attempt ${attempt} must start before other entries`);
    if (!entries.at(-1)?.startsWith("end:")) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} must end exactly once at its final entry`);
    if (entries.filter((entry) => entry === "start").length !== 1) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} must start exactly once`);
    if (entries.filter((entry) => entry.startsWith("end:")).length !== 1) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} must end exactly once`);
    if (entries.filter((entry) => entry === "commit").length > 1) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} commits more than once`);
    if (entries.filter((entry) => entry.startsWith("response:")).length > 1) fail(`${location}.expected.attemptTrace`, `attempt ${attempt} has more than one response`);
  }
  const closeEvidenceCases = new Set([
    "response-valid-complete-identity",
    "response-malformed-two-hundred-retried",
    "response-corrupt-gzip-trailer",
    "cancellation-during-response-body",
    "response-identity-cap-plus-one-cancels",
  ]);
  if (closeEvidenceCases.has(testCase.caseId) || (executionMode === "custom-one-attempt" && script.some((action) => action.action === "respond"))) {
    for (const state of executionReference?.attempts || []) {
      if (!Number.isInteger(state.responseStatus)) continue;
      const evidence = `fixture:attempt:${state.attempt}:response-close:1`;
      if ((testCase.expected.fixtureTrace || []).filter((entry) => entry === evidence).length !== 1) {
        fail(`${location}.expected.fixtureTrace`, `must contain exactly one ${evidence} production response-ownership proof`);
      }
    }
  }
  for (const [traceIndex, trace] of (testCase.expected.fixtureTrace || []).entries()) {
    const match = trace.match(/^fixture:attempt:([1-9][0-9]*):(.+)$/u);
    if (!match) continue;
    const attempt = Number(match[1]);
    const fact = match[2];
    if (!attemptStarts.includes(attempt)) fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "names an attempt that never starts");
    const disconnect = fact.match(/^disconnect:(.+)$/u);
    if (disconnect && !script.some((action) => action.action === "disconnect" && action.attempt === attempt && action.phase === disconnect[1])) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "requires a matching disconnect action");
    }
    const failure = fact.match(/^failure:(.+)$/u);
    if (failure && !script.some((action) => action.action === "failConnection" && action.attempt === attempt && action.kind === failure[1])) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "requires a matching failConnection action");
    }
    const seamFailure = fact.match(/^seam-failure:([^:]+):([^:]+)$/u);
    if (seamFailure && !script.some((action) => action.action === "failConnection" && action.attempt === attempt
      && action.seamFailure === seamFailure[1] && (action.responseConstructionDefect ?? "none") === seamFailure[2])) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "requires the matching custom transport seam/factory failure action");
    }
    const transferredClose = fact.match(/^transferred-body-close:([01])$/u);
    if (transferredClose && !script.some((action) => action.action === "failConnection" && action.attempt === attempt
      && action.transferredBodyCloseCount === Number(transferredClose[1]))) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "requires matching public response-construction transferred-body close evidence");
    }
    if (fact === "proxy-auth:407" && !script.some((action) => action.action === "failProxyAuthentication" && action.attempt === attempt && action.status === 407)) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "requires a matching proxy authentication action");
    }
    const requestBytes = fact.match(/^request-bytes:([0-9]+)$/u);
    const requestLength = deterministicRequestByteLength(testCase);
    if (requestBytes && (!Number.isInteger(requestLength) || Number(requestBytes[1]) > requestLength)) {
      fail(`${location}.expected.fixtureTrace[${traceIndex}]`, "request byte count exceeds the materialized request body");
    }
  }
  let priorScriptAttempt = 0;
  let priorTerminalAttempt = 0;
  for (const [actionIndex, action] of script.entries()) {
    if (!Number.isInteger(action.attempt)) continue;
    if (action.attempt < priorScriptAttempt) fail(`${location}.script[${actionIndex}].attempt`, "attempt declarations must be nondecreasing");
    priorScriptAttempt = action.attempt;
    if (!attemptStarts.includes(action.attempt)) fail(`${location}.script[${actionIndex}].attempt`, "must name an attempt present in expected.attemptTrace");
    if (terminalActions.has(action.action)) {
      if (action.attempt <= priorTerminalAttempt) fail(`${location}.script[${actionIndex}].attempt`, "terminal action attempts must be strictly increasing");
      priorTerminalAttempt = action.attempt;
    }
  }

  const holds = new Map();
  const releases = new Map();
  for (const [actionIndex, action] of script.entries()) {
    if (action.action === "hold") {
      if (holds.has(action.barrier)) fail(`${location}.script[${actionIndex}].barrier`, "barrier names must be unique within a case");
      holds.set(action.barrier, { action, actionIndex });
      if (["afterRequestBytes", "responseBody"].includes(action.phase) && !Number.isInteger(action.releaseAt)) {
        fail(`${location}.script[${actionIndex}].releaseAt`, `${action.phase} holds require an exact byte threshold`);
      }
      if (Number.isInteger(action.releaseAt) && !["afterRequestBytes", "responseBody"].includes(action.phase)) {
        fail(`${location}.script[${actionIndex}].releaseAt`, "is legal only for afterRequestBytes or responseBody");
      }
      if (action.phase === "afterRequestBytes" && action.releaseAt > deterministicRequestByteLength(testCase)) {
        fail(`${location}.script[${actionIndex}].releaseAt`, "exceeds the deterministic serialized request length");
      }
      if (action.phase === "afterRequestBytes") {
        const requestTrace = `fixture:attempt:${action.attempt}:request-bytes:${action.releaseAt}`;
        if (!testCase.expected.fixtureTrace.includes(requestTrace)) fail(`${location}.expected.fixtureTrace`, `must include ${requestTrace}`);
      }
      if (action.phase === "responseBody") {
        const responseAction = script.find((candidate) => responseActions.has(candidate.action) && candidate.attempt === action.attempt);
        const responseLength = responseAction && responseActionByteLength(responseAction);
        if (!Number.isInteger(responseLength) || action.releaseAt > responseLength) fail(`${location}.script[${actionIndex}].releaseAt`, "exceeds the deterministic response recipe length");
      }
    }
    if (action.action === "release") {
      if (releases.has(action.barrier)) fail(`${location}.script[${actionIndex}].barrier`, "duplicate release");
      releases.set(action.barrier, actionIndex);
      const hold = holds.get(action.barrier);
      if (!hold || hold.actionIndex >= actionIndex) fail(`${location}.script[${actionIndex}].barrier`, "must name exactly one earlier registered hold");
    }
  }
  for (const [barrier, hold] of holds) {
    const laterActions = script.slice(hold.actionIndex + 1);
    const matchedRelease = releases.has(barrier);
    const matchedTerminalRunnerAction = laterActions.some((action) => (action.action === "cancelSend" || action.action === "closeClient") && action.phase === hold.action.phase);
    const timeoutEnd = (testCase.expected.attemptTrace || []).some((trace) => new RegExp(`^attempt:${hold.action.attempt}:end:(?:connect-timeout|attempt-timeout|operation-deadline)@`).test(trace));
    const matchedTimeout = timeoutEnd && laterActions.some((action) => action.action === "advanceTime");
    if (!matchedRelease && !matchedTerminalRunnerAction && !matchedTimeout) fail(`${location}.script[${hold.actionIndex}]`, "hold must terminate via release, timeout, cancellation, or close");
  }
  for (const [barrier, actionIndex] of releases) {
    if (!holds.has(barrier)) fail(`${location}.script[${actionIndex}].barrier`, "release has no registered hold");
  }

  for (const attempt of attemptStarts) {
    const terminal = script.filter((action) => terminalActions.has(action.action) && action.attempt === attempt);
    if (terminal.length > 1) fail(`${location}.script`, `attempt ${attempt} has duplicate terminal fixture actions`);
    const held = [...holds.values()].filter(({ action }) => action.attempt === attempt);
    const runnerTerminated = held.some(({ action, actionIndex }) => script.slice(actionIndex + 1).some((candidate) =>
      ((candidate.action === "cancelSend" || candidate.action === "closeClient") && candidate.phase === action.phase)
      || (candidate.action === "advanceTime" && (testCase.expected.attemptTrace || []).some((trace) => new RegExp(`^attempt:${attempt}:end:(?:connect-timeout|attempt-timeout|operation-deadline)@`).test(trace)))));
    if (terminal.length === 0 && !runnerTerminated) fail(`${location}.script`, `attempt ${attempt} requires one response/connection action or an explicit runner termination`);
  }

  const responseStatusByAttempt = new Map();
  for (const trace of testCase.expected.attemptTrace || []) {
    const match = trace.match(/^attempt:([1-9][0-9]*):response:([2-5][0-9]{2})@/);
    if (match) responseStatusByAttempt.set(Number(match[1]), Number(match[2]));
  }
  for (const [attempt, status] of responseStatusByAttempt) {
    const response = script.filter((action) => (responseActions.has(action.action) || action.action === "failProxyAuthentication") && action.attempt === attempt);
    if (response.length !== 1 || response[0].status !== status) fail(`${location}.expected.attemptTrace`, `response status ${status} for attempt ${attempt} requires one matching response/proxy action`);
  }
  for (const [actionIndex, action] of script.entries()) {
    if (!responseActions.has(action.action)) continue;
    const responseVisible = executionReference?.attempts.find((state) => state.attempt === action.attempt)?.responseStatus === action.status;
    if (responseVisible && responseStatusByAttempt.get(action.attempt) !== action.status) fail(`${location}.script[${actionIndex}].status`, "must have one matching expected response trace");
    if (!responseVisible && responseStatusByAttempt.has(action.attempt)) fail(`${location}.expected.attemptTrace`, "must not expose response status before an unreleased responseHeaders barrier");
    const length = responseActionByteLength(action);
    const progress = (testCase.expected.fixtureTrace || []).flatMap((trace) => {
      const match = trace.match(new RegExp(`^fixture:attempt:${action.attempt}:response-(bytes|cancelled):([0-9]+)$`));
      return match ? [{ kind: match[1], bytes: Number(match[2]) }] : [];
    });
    if (progress.length !== 1) fail(`${location}.expected.fixtureTrace`, `attempt ${action.attempt} must have exactly one response progress trace`);
    else if (progress[0].kind === "bytes" && progress[0].bytes !== length) fail(`${location}.expected.fixtureTrace`, `attempt ${action.attempt} response bytes must equal recipe length ${length}`);
    else if (progress[0].kind === "cancelled" && (progress[0].bytes < 0 || progress[0].bytes > length)) fail(`${location}.expected.fixtureTrace`, `attempt ${action.attempt} cancelled response bytes must be within the recipe length ${length}`);
    const responseHoldIndex = script.findIndex((candidate) => candidate.action === "hold" && candidate.attempt === action.attempt && candidate.phase === "responseBody");
    const responseHold = responseHoldIndex >= 0 ? script[responseHoldIndex] : null;
    const responseHoldTerminated = responseHold && script.slice(responseHoldIndex + 1).some((candidate) =>
      (candidate.action === "cancelSend" || candidate.action === "closeClient") && candidate.phase === "responseBody");
    if (responseHoldTerminated) {
      if (progress.length !== 1 || progress[0].kind !== "cancelled" || progress[0].bytes !== responseHold.releaseAt) {
        fail(`${location}.expected.fixtureTrace`, `attempt ${action.attempt} cancellation progress must equal responseBody releaseAt ${responseHold.releaseAt}`);
      }
      const expectedCancellationBytes = [
        `response-wire:${responseHold.releaseAt}:cancelled`,
        `response-decoded:${responseHold.releaseAt}:cancelled`,
      ];
      const actualCancellationBytes = (testCase.expected.byteTrace || []).filter((trace) => trace.startsWith("response-wire:") || trace.startsWith("response-decoded:"));
      if (JSON.stringify(actualCancellationBytes) !== JSON.stringify(expectedCancellationBytes)) {
        fail(`${location}.expected.byteTrace`, `response cancellation facts must equal responseBody releaseAt ${responseHold.releaseAt}`);
      }
    }
    if (progress[0]?.kind === "cancelled" && !testCase.expected.sideEffectTrace.includes("response.cancel")) fail(`${location}.expected.sideEffectTrace`, "cancelled upstream responses require response.cancel");
  }

  const responseRecipes = script.filter((action) => responseActions.has(action.action)).map((action) => {
    const bytes = responseActionBuffer(action);
    return { action, bytes };
  });
  const byteFacts = (testCase.expected.byteTrace || []).flatMap((trace, traceIndex) => {
    const match = trace.match(/^(request|response-wire|response-decoded|error-discarded):([0-9]+):(accepted|rejected|cancelled)$/u);
    return match ? [{ kind: match[1], count: Number(match[2]), outcome: match[3], traceIndex }] : [];
  });
  for (const fact of byteFacts.filter((candidate) => candidate.kind === "request")) {
    const requestLength = deterministicRequestByteLength(testCase);
    const requestLimit = testCase.options?.requestLimitBytes ?? document.constants.requestLimitBytes;
    const observableCount = Number.isInteger(requestLength) ? Math.min(requestLength, requestLimit + 1) : null;
    if (!Number.isInteger(observableCount) || fact.count !== observableCount) fail(`${location}.expected.byteTrace[${fact.traceIndex}]`, "request byte count must equal the bounded serialized prefix through the first over-limit octet");
  }
  const expectedResponseTrace = [];
  let derivedResponseCancellation = false;
  for (const { action, bytes } of responseRecipes) {
    if (!Buffer.isBuffer(bytes)) {
      fail(`${location}.script`, `response attempt ${action.attempt} must have a deterministic static body recipe`);
      continue;
    }
    const progress = (testCase.expected.fixtureTrace || []).flatMap((trace) => {
      const match = trace.match(new RegExp(`^fixture:attempt:${action.attempt}:response-(bytes|cancelled):([0-9]+)$`));
      return match ? [{ kind: match[1], count: Number(match[2]) }] : [];
    })[0];
    const classification = responseClassifications.get(action.attempt);
    if (!classification) continue;
    if (progress?.kind !== classification.progressKind || progress?.count !== classification.progressCount) {
      fail(`${location}.expected.fixtureTrace`, `attempt ${action.attempt} response progress must be ${classification.progressKind}:${classification.progressCount} from the input-derived response classifier`);
    }
    if (classification.responseCancel) derivedResponseCancellation = true;

    const laterAttempt = attemptStarts.some((attempt) => attempt > action.attempt);
    const actionIndex = script.indexOf(action);
    const laterRunnerTermination = script.slice(actionIndex + 1).some((candidate) => candidate.action === "cancelSend" || candidate.action === "closeClient");
    if (classification.resultEligible && laterAttempt && !laterRunnerTermination) fail(`${location}.script`, `attempt ${action.attempt} has a valid complete 2xx result and must terminate before another attempt`);
    if (classification.resultEligible && action.attempt === attemptStarts.at(-1) && !laterRunnerTermination && testCase.expected.result === null) fail(`${location}.expected.result`, "input-derived valid complete 2xx response requires SendResult");
    if (!classification.resultEligible && action.attempt === attemptStarts.at(-1) && testCase.expected.result !== null) fail(`${location}.expected.result`, "input-derived invalid, incomplete, rejected, or limited response cannot produce SendResult");
    if (!classification.retryable && laterAttempt && !laterRunnerTermination) fail(`${location}.script`, `attempt ${action.attempt} has a permanent response outcome and must not retry`);

    const terminalResponse = testCase.expected.error !== null && action.attempt === attemptStarts.at(-1) && testCase.expected.error.httpStatus === action.status;
    if (terminalResponse && (testCase.expected.error.responseHeaderFields !== (classification.headerCount ?? 0)
      || testCase.expected.error.responseHeaderBytes !== (classification.headerLogicalBytes ?? 0))) {
      fail(`${location}.expected.error`, `header-limit diagnostics must equal fields=${classification.headerCount ?? 0} bytes=${classification.headerLogicalBytes ?? 0}`);
    }
    if (terminalResponse && (testCase.expected.error.compressedBytes !== classification.wireCount
      || testCase.expected.error.decompressedBytes !== classification.decodedCount
      || testCase.expected.error.truncated !== classification.truncated)) {
      fail(`${location}.expected.error`, `response counters/truncation must match input-derived classifier compressed=${classification.wireCount} decompressed=${classification.decodedCount} truncated=${classification.truncated}`);
    }
    const retryTerminatesAtDeadline = classification.retryable && scheduleFacts.get(action.attempt)?.delay && !laterAttempt;
    if (terminalResponse && !scheduleTerminal && !retryTerminatesAtDeadline && (!laterRunnerTermination || classification.explicitCancellation)
      && (testCase.expected.error.code !== classification.code || testCase.expected.error.retryable !== classification.retryable)) {
      fail(`${location}.expected.error`, `terminal response must match input-derived classifier code=${classification.code} retryable=${classification.retryable}`);
    }

    expectedResponseTrace.push(`response-wire:${classification.wireCount}:${classification.wireOutcome}`);
    expectedResponseTrace.push(`response-decoded:${classification.decodedCount}:${classification.decodedOutcome}`);
    if (classification.inspectedCount !== null) expectedResponseTrace.push(`error-discarded:${classification.inspectedCount}:accepted`);
  }
  const actualResponseTrace = (testCase.expected.byteTrace || []).filter((trace) => !trace.startsWith("request:"));
  if (JSON.stringify(actualResponseTrace) !== JSON.stringify(expectedResponseTrace)) {
    fail(`${location}.expected.byteTrace`, `must explicitly equal deterministic response trace ${JSON.stringify(expectedResponseTrace)}`);
  }
  const responseCancelEffects = (testCase.expected.sideEffectTrace || []).filter((trace) => trace === "response.cancel").length;
  if (responseCancelEffects !== (derivedResponseCancellation ? 1 : 0)) {
    fail(`${location}.expected.sideEffectTrace`, "response.cancel must occur exactly once iff a declared hold, decoding, size-limit, or inspection branch cancels a response");
  }
  if (testCase.expected.result !== null && responseClassifications.get(executionReference?.attempts.at(-1)?.attempt)?.responseCancel) {
    fail(`${location}.expected.result`, "the terminal accepted response itself cannot be cancelled");
  }

  const captureActions = script.filter((action) => action.action === "captureRequestMetadata");
  const captureTraces = (testCase.expected.fixtureTrace || []).filter((trace) => /:capture-private:clean$/.test(trace));
  if (captureActions.length !== captureTraces.length) fail(`${location}.expected.fixtureTrace`, "each captureRequestMetadata action requires exactly one capture-private:clean trace");
  for (const action of captureActions) {
    const trace = `fixture:attempt:${action.attempt}:capture-private:clean`;
    if (!testCase.expected.fixtureTrace.includes(trace)) fail(`${location}.expected.fixtureTrace`, `must include ${trace}`);
  }

  if (!deliveries.has(testCase.expected.delivery)) fail(`${location}.expected.delivery`, "unknown delivery state");
  const expectedErrorTrace = testCase.expected.error === null
    ? []
    : [`terminal:${testCase.expected.error?.code}:${testCase.expected.error?.delivery}`];
  if (JSON.stringify(testCase.expected.errorTrace) !== JSON.stringify(expectedErrorTrace)) fail(`${location}.expected.errorTrace`, "must exactly match terminal error code and delivery");
  if (testCase.expected.error !== null) {
    if (exactKeys(`${location}.expected.error`, testCase.expected.error, errorFields)) {
      if (!errorCodes.has(testCase.expected.error.code)) fail(`${location}.expected.error.code`, "unknown error code");
      if (testCase.expected.error.operationId !== null && !/^op-[0-9]{4}$/u.test(testCase.expected.error.operationId)) fail(`${location}.expected.error.operationId`, "must be null or a local deterministic operation ID");
      if (!deliveries.has(testCase.expected.error.delivery)) fail(`${location}.expected.error.delivery`, "unknown delivery state");
      if (testCase.expected.error.delivery !== testCase.expected.delivery) fail(`${location}.expected.error.delivery`, "must match expected.delivery");
      if (!Number.isInteger(testCase.expected.error.attemptCount) || testCase.expected.error.attemptCount < 0) fail(`${location}.expected.error.attemptCount`, "must be a nonnegative integer");
      if (testCase.expected.error.attemptCount !== attemptStarts.length) fail(`${location}.expected.error.attemptCount`, "must equal the number of started attempts");
      if (testCase.expected.error.httpStatus !== null && (!Number.isInteger(testCase.expected.error.httpStatus) || testCase.expected.error.httpStatus < 200 || testCase.expected.error.httpStatus > 599)) fail(`${location}.expected.error.httpStatus`, "must be null or an HTTP response status in the frozen 200..599 range");
      const terminalResponseStatus = responseStatusByAttempt.get(attemptStarts.length) ?? null;
      if (testCase.expected.error.httpStatus !== terminalResponseStatus) fail(`${location}.expected.error.httpStatus`, "must match the terminal attempt response status when known");
      if (typeof testCase.expected.error.retryable !== "boolean") fail(`${location}.expected.error.retryable`, "must be boolean");
      if (testCase.expected.error.idempotencyKey !== null && typeof testCase.expected.error.idempotencyKey !== "string") fail(`${location}.expected.error.idempotencyKey`, "must be null or string");
      if (testCase.expected.error.causeCategory !== null && !causeCategories.has(testCase.expected.error.causeCategory)) fail(`${location}.expected.error.causeCategory`, "must be null or a declared public cause category");
      if (testCase.expected.error.failureReason !== null && !failureReasons.has(testCase.expected.error.failureReason)) fail(`${location}.expected.error.failureReason`, "must be null or a declared closed failure reason");
      const expectedRemediationKey = testCase.expected.error.failureReason === null
        ? null
        : requiredFailureReasonRemediationKeys[testCase.expected.error.failureReason];
      if (testCase.expected.error.remediationKey !== expectedRemediationKey) fail(`${location}.expected.error.remediationKey`, "must exactly match the selected failure reason, or be null with no failure reason");
      const reasonBearingCodes = new Set(["DNS", "CONNECT", "PROXY", "TLS", "IO", "ATTEMPT_TIMEOUT"]);
      if (reasonBearingCodes.has(testCase.expected.error.code) !== (testCase.expected.error.failureReason !== null)) {
        fail(`${location}.expected.error.failureReason`, "must be present only for transport/network/attempt-timeout error codes");
      }
      for (const field of ["responseHeaderFields", "responseHeaderBytes", "compressedBytes", "decompressedBytes"]) if (!Number.isInteger(testCase.expected.error[field]) || testCase.expected.error[field] < 0) fail(`${location}.expected.error.${field}`, "must be a nonnegative integer");
      if (typeof testCase.expected.error.truncated !== "boolean") fail(`${location}.expected.error.truncated`, "must be boolean");
      if (testCase.expected.error.closeFailureCount !== 0 || JSON.stringify(testCase.expected.error.closeFailureCategories) !== "[]") {
        fail(`${location}.expected.error.closeFailureCount`, "shared send vectors must expose zero close failures; the exact aggregate runtime-close mechanics are target overlays");
      }
      const validationIssues = testCase.expected.error.validationIssues;
      const configurationIssues = testCase.expected.error.configurationIssues;
      if (!Array.isArray(validationIssues) || !Array.isArray(configurationIssues)) fail(`${location}.expected.error`, "issue fields must be arrays");
      for (const [issueIndex, issue] of (validationIssues || []).entries()) {
        const issueLocation = `${location}.expected.error.validationIssues[${issueIndex}]`;
        if (!exactKeys(issueLocation, issue, ["code", "path"])) continue;
        if (!validationIssueCodes.has(issue.code)) fail(`${issueLocation}.code`, "must be a closed ValidationIssueCode");
        if (typeof issue.path !== "string" || !/^\$(?:(?:\.[A-Za-z_][A-Za-z0-9_]*)|(?:\[(?:0|[1-9][0-9]*)\])|(?:\[\{\*\}\]))*(?:\.<truncated>)?$/u.test(issue.path)) {
          fail(`${issueLocation}.path`, "must use only $, static .member, [n], [{*}], and optional terminal .<truncated> segments");
        } else if (Buffer.byteLength(issue.path, "utf8") > requiredIssueReporting.maxIssuePathUtf8Bytes) {
          fail(`${issueLocation}.path`, "must fit the 1024-byte UTF-8 issue-path cap");
        }
      }
      for (const [issueIndex, issue] of (configurationIssues || []).entries()) {
        const issueLocation = `${location}.expected.error.configurationIssues[${issueIndex}]`;
        if (!exactKeys(issueLocation, issue, ["code", "optionKeys"])) continue;
        if (!configurationIssueCodes.has(issue.code)) fail(`${issueLocation}.code`, "must be a closed ConfigurationIssueCode");
        if (!Array.isArray(issue.optionKeys) || issue.optionKeys.some((key) => !clientOptionKeys.has(key))) fail(`${issueLocation}.optionKeys`, "must contain only closed ClientOptionKey values");
        const requiredKeyCount = ["CONFLICT", "RESOURCE_MISMATCH"].includes(issue.code) ? 2 : 1;
        if (issue.optionKeys?.length !== requiredKeyCount || new Set(issue.optionKeys).size !== requiredKeyCount) fail(`${issueLocation}.optionKeys`, `must contain exactly ${requiredKeyCount} distinct key(s) for ${issue.code}`);
        const positions = (issue.optionKeys || []).map((key) => requiredClientOptionKeys.indexOf(key));
        if (positions.some((position, keyIndex) => keyIndex > 0 && position <= positions[keyIndex - 1])) fail(`${issueLocation}.optionKeys`, "must preserve ClientOptionKey declaration order");
      }
      const totalValidationPathBytes = (validationIssues || []).reduce((sum, issue) => sum + (typeof issue?.path === "string" ? Buffer.byteLength(issue.path, "utf8") : 0), 0);
      if ((validationIssues?.length ?? 0) > requiredIssueReporting.maxIssues || (configurationIssues?.length ?? 0) > requiredIssueReporting.maxIssues) fail(`${location}.expected.error`, "retained issue lists must contain at most 32 entries");
      if (totalValidationPathBytes > requiredIssueReporting.maxTotalIssuePathUtf8Bytes) fail(`${location}.expected.error.validationIssues`, "retained validation paths must fit the aggregate 16384-byte UTF-8 cap");
      if (!Number.isInteger(testCase.expected.error.issueCount) || testCase.expected.error.issueCount < 0 || testCase.expected.error.issueCount > requiredIssueReporting.maxIssueCount) fail(`${location}.expected.error.issueCount`, "must be a nonnegative saturating portable issue count");
      if (typeof testCase.expected.error.issuesTruncated !== "boolean") fail(`${location}.expected.error.issuesTruncated`, "must be boolean");
      const retainedIssueCount = (validationIssues?.length ?? 0) + (configurationIssues?.length ?? 0);
      if (testCase.expected.error.issuesTruncated !== (testCase.expected.error.issueCount !== retainedIssueCount)) fail(`${location}.expected.error.issuesTruncated`, "must be exactly issueCount != retained issue-list size");
      if (testCase.expected.error.code === "VALIDATION") {
        const expectedIssues = expectedValidationIssuesFor(testCase);
        const issueFixture = requiredIssueFixtures.variants[testCase.input?.send?.payload?.$issueFixture];
        const expectedIssueCount = issueFixture?.issueCount ?? expectedIssues?.length;
        const expectedIssuesTruncated = issueFixture?.issuesTruncated ?? false;
        if (expectedIssues === null || JSON.stringify(validationIssues) !== JSON.stringify(expectedIssues) || configurationIssues.length !== 0
          || testCase.expected.error.issueCount !== expectedIssueCount || testCase.expected.error.issuesTruncated !== expectedIssuesTruncated) {
          fail(`${location}.expected.error.validationIssues`, "must equal the validator-derived validation issue oracle with empty configuration issues and exact total count");
        }
      } else if (testCase.expected.error.code === "CONFIGURATION") {
        const expectedIssue = expectedConfigurationIssueFor(testCase);
        if (expectedIssue === null || JSON.stringify(configurationIssues) !== JSON.stringify([expectedIssue]) || validationIssues.length !== 0 || testCase.expected.error.issueCount !== 1) {
          fail(`${location}.expected.error.configurationIssues`, "must equal the validator-derived configuration issue oracle with empty validation issues and exact total count");
        }
      } else if (validationIssues.length !== 0 || configurationIssues.length !== 0 || testCase.expected.error.issueCount !== 0 || testCase.expected.error.issuesTruncated !== false) {
        fail(`${location}.expected.error`, "ordinary non-validation/configuration errors must expose empty issue lists, issueCount=0, and issuesTruncated=false");
      }
      if (testCase.expected.error.message !== document.errorCatalog?.[testCase.expected.error.code]?.message) fail(`${location}.expected.error.message`, "must be the SDK-owned catalog message");
    }
    if (exactKeys(`${location}.expected.legacyBody`, testCase.expected.legacyBody, legacyBodyFields)) {
      const expectedLegacy = { code: testCase.expected.error.code, message: testCase.expected.error.message, truncated: testCase.expected.error.truncated };
      if (JSON.stringify(testCase.expected.legacyBody) !== JSON.stringify(expectedLegacy)) fail(`${location}.expected.legacyBody`, "must be the exact safe deprecated projection");
    }
  } else if (testCase.expected.legacyBody !== null) {
    fail(`${location}.expected.legacyBody`, "must be null when error is null");
  }
  const evidence = testCase.expected.outcomeEvidence;
  if (testCase.expected.primarySurface === "sendOperation" && exactKeys(`${location}.expected.outcomeEvidence`, evidence, outcomeEvidenceFields)) {
    const callerCancelled = (testCase.script || []).some((action) => ["cancelSend", "cancelQueuedWorker"].includes(action.action));
    const expectedPrimaryKind = callerCancelled ? "native-cancel" : testCase.expected.result !== null ? "success" : "structured-error";
    const operationId = testCase.expected.observerEmissionTrace.some((entry) => entry.startsWith("operation.start|")) ? document.constants.testOperationId : null;
    const finalStatus = testCase.expected.error?.httpStatus ?? [...responseStatusByAttempt.values()].at(-1) ?? null;
    const fixtureKey = testCase.expected.fixtureTrace.flatMap((entry) => entry.match(/^fixture:attempt:[1-9][0-9]*:idempotency:(.+)$/u)?.[1] || [])[0] ?? null;
    const key = testCase.expected.error?.idempotencyKey ?? fixtureKey;
    const code = testCase.expected.error?.code ?? "OK";
    const attemptCount = testCase.expected.error?.attemptCount ?? attemptStarts.length;
    if (evidence.operationId !== operationId || (evidence.operationId !== null && !/^op-[0-9]{4}$/u.test(evidence.operationId))) fail(`${location}.expected.outcomeEvidence.operationId`, "must be null before worker start and the deterministic local operation ID after worker start");
    if (testCase.expected.error && testCase.expected.error.operationId !== operationId) fail(`${location}.expected.error.operationId`, "must match the phase-derived outcome operationId");
    if (evidence.primaryKind !== expectedPrimaryKind || evidence.nativeCancelled !== callerCancelled) fail(`${location}.expected.outcomeEvidence`, "caller cancel alone must use native cancellation; success, close, deadline, and ordinary failures preserve structured primary identity");
    if (evidence.settlements !== 1 || evidence.noGhostWork !== true) fail(`${location}.expected.outcomeEvidence`, "must settle exactly once with no ghost work");
    const failureReason = testCase.expected.error?.failureReason ?? null;
    const remediationKey = testCase.expected.error?.remediationKey ?? null;
    const causeCategory = testCase.expected.error?.causeCategory ?? null;
    if (evidence.code !== code || evidence.delivery !== testCase.expected.delivery || evidence.attemptCount !== attemptCount || evidence.httpStatus !== finalStatus || evidence.idempotencyKey !== key
      || evidence.failureReason !== failureReason || evidence.remediationKey !== remediationKey || evidence.causeCategory !== causeCategory) {
      fail(`${location}.expected.outcomeEvidence`, "must exactly mirror the terminal structured outcome including the advanced reconciliation key");
    }
  }
  if (testCase.expected.primarySurface === "constructionThrow" && exactKeys(`${location}.expected.constructionEvidence`, testCase.expected.constructionEvidence, constructionEvidenceFields)) {
    const construction = testCase.expected.constructionEvidence;
    if (construction.code !== testCase.expected.error?.code || construction.causeCategory !== testCase.expected.error?.causeCategory || construction.message !== testCase.expected.error?.message
      || construction.operationId !== null || construction.idempotencyKey !== null || construction.admitted !== false || construction.settlements !== 0) {
      fail(`${location}.expected.constructionEvidence`, "must exactly describe the synchronous typed construction throw with no admission, operation identity, key, or settlement");
    }
  }
  if (testCase.expected.primarySurface === "constructionThrow" && Object.hasOwn(testCase.expected, "runtimeDiagnostics")) {
    fail(`${location}.expected.runtimeDiagnostics`, "construction throw occurs before a runtime diagnostic snapshot exists");
  }
  if (testCase.expected.primarySurface === "sendOperation" && exactKeys(`${location}.expected.runtimeDiagnostics`, testCase.expected.runtimeDiagnostics, runtimeDiagnosticsFields)) {
    const diagnostics = testCase.expected.runtimeDiagnostics;
    for (const field of runtimeDiagnosticsFields.filter((field) => field !== "closed")) integer(`${location}.expected.runtimeDiagnostics.${field}`, diagnostics[field], 0, document.constants.runtimeDiagnosticsCounterMax);
    if (typeof diagnostics.closed !== "boolean") fail(`${location}.expected.runtimeDiagnostics.closed`, "must be boolean");
    const executorRejected = script.some((action) => action.action === "rejectExecutorSubmission");
    const schedulerRejected = script.some((action) => ["rejectDeadlineScheduler", "rejectRetryScheduler"].includes(action.action));
    const concurrencyRejected = testCase.expected.error?.code === "OVERLOADED" && !executorRejected && !schedulerRejected && !testCase.expected.sideEffectTrace.includes("permit.acquire");
    const expectedDiagnostics = {
      inFlightOperations: 0, bufferedBytes: 0,
      concurrencyOverloadRejections: concurrencyRejected ? 1 : 0,
      requestByteOverloadRejections: 0, responseByteOverloadRejections: 0,
      responseHeaderLimitFailures: (testCase.expected.error?.responseHeaderFields > 0 || testCase.expected.error?.responseHeaderBytes > 0) ? 1 : 0,
      executorOverloadRejections: executorRejected ? 1 : 0,
      schedulerOverloadRejections: schedulerRejected ? 1 : 0,
      responseCloseFailures: script.filter((action) => action.closeThrows === true).length,
      droppedObserverEvents: script.filter((action) => action.action === "observerDrop").length,
      observerFailures: script.filter((action) => action.action === "observerFail").length,
      closed: script.some((action) => ["closeClient", "closeQueuedWorker"].includes(action.action)),
    };
    if (JSON.stringify(diagnostics) !== JSON.stringify(expectedDiagnostics)) fail(`${location}.expected.runtimeDiagnostics`, `must equal the input/script-derived public RuntimeDiagnostics snapshot ${JSON.stringify(expectedDiagnostics)}`);
  }
  if (testCase.expected.result !== null && exactKeys(`${location}.expected.result`, testCase.expected.result, resultFields)) {
    for (const field of resultFields) {
      if (typeof testCase.expected.result[field] !== "string" || testCase.expected.result[field].length === 0) fail(`${location}.expected.result.${field}`, "must be a non-empty string");
    }
    if (testCase.expected.result.type !== materializedInput.send?.eventType) fail(`${location}.expected.result.type`, "must match materialized input eventType");
    if (testCase.expected.result.customerId !== materializedInput.send?.customerId) fail(`${location}.expected.result.customerId`, "must match materialized input customerId");
    const timestamp = testCase.expected.result.timestamp;
    if (typeof timestamp === "string" && !canonicalUtcMillisecondTimestamp(timestamp)) {
      fail(`${location}.expected.result.timestamp`, "must be a calendar-valid UTC RFC3339 timestamp with exactly millisecond precision");
    }
  }
  if (testCase.expected.delivery === "ACCEPTED" && testCase.expected.result === null) fail(`${location}.expected.result`, "accepted delivery requires SendResult");
  if (testCase.expected.delivery !== "ACCEPTED" && testCase.expected.result !== null) fail(`${location}.expected.result`, "terminal delivery must not expose SendResult");
  if (testCase.expected.delivery === "ACCEPTED" && testCase.expected.error !== null) fail(`${location}.expected.error`, "accepted delivery must not expose an error");

  function scanPublicSentinels(value, valueLocation) {
    if (Array.isArray(value)) {
      value.forEach((item, index) => scanPublicSentinels(item, `${valueLocation}[${index}]`));
      return;
    }
    if (object(value)) {
      for (const [key, item] of Object.entries(value)) scanPublicSentinels(item, `${valueLocation}.${key}`);
      return;
    }
    if (typeof value !== "string") return;
    for (const [sentinelName, sentinelValue] of Object.entries(document.sentinels || {})) {
      if (value.includes(sentinelValue)) fail(valueLocation, `public surface contains sentinel ${sentinelName}`);
    }
  }
  function scanPublicExceptionClassNames(value, valueLocation) {
    if (Array.isArray(value)) return value.forEach((item, index) => scanPublicExceptionClassNames(item, `${valueLocation}[${index}]`));
    if (object(value)) return Object.entries(value).forEach(([key, item]) => scanPublicExceptionClassNames(item, `${valueLocation}.${key}`));
    if (typeof value === "string" && /(?:Conformance[A-Za-z0-9_$]*Failure|(?:java|javax|jakarta|com|org|net|io)\.[A-Za-z_$][A-Za-z0-9_$.]*)/u.test(value)) {
      fail(valueLocation, "public surface must not expose exception class or package names");
    }
  }
  const publicExpected = cloneJson(testCase.expected);
  delete publicExpected.fixtureTrace;
  delete publicExpected.contextTrace;
  function scanObserverContext(value, valueLocation) {
    if (Array.isArray(value)) return value.forEach((item, index) => scanObserverContext(item, `${valueLocation}[${index}]`));
    if (object(value)) return Object.entries(value).forEach(([key, item]) => scanObserverContext(item, `${valueLocation}.${key}`));
    if (typeof observerContext === "string" && typeof value === "string" && value.includes(observerContext)) {
      fail(valueLocation, "public surface contains the private observer context token");
    }
  }
  scanObserverContext(publicExpected, `${location}.expected`);
  if (object(publicExpected.error)) delete publicExpected.error.idempotencyKey;
  if (object(publicExpected.outcomeEvidence)) delete publicExpected.outcomeEvidence.idempotencyKey;
  scanPublicSentinels(publicExpected, `${location}.expected`);
  scanPublicExceptionClassNames(publicExpected, `${location}.expected`);
  for (const [traceIndex, trace] of (testCase.expected.fixtureTrace || []).entries()) {
    for (const [sentinelName, sentinelValue] of Object.entries(document.sentinels || {})) {
      if (trace.includes(sentinelValue) && !/^fixture:attempt:[1-9][0-9]*:idempotency:/u.test(trace)) fail(`${location}.expected.fixtureTrace[${traceIndex}]`, `private trace contains sentinel ${sentinelName} outside idempotency capture`);
    }
  }

  function scanTestTokens(value, tokenLocation) {
    if (Array.isArray(value)) return value.forEach((item, itemIndex) => scanTestTokens(item, `${tokenLocation}[${itemIndex}]`));
    if (!object(value)) return;
    for (const [key, item] of Object.entries(value)) {
      if (key.startsWith("$") && !(document.fixtureProtocol.testTokens || []).includes(key)) fail(`${tokenLocation}.${key}`, "unknown test token");
      if (key === "$bytes" && exactKeys(`${tokenLocation}.$bytes`, item, ["serializedRequestBytes"])) integer(`${tokenLocation}.$bytes.serializedRequestBytes`, item.serializedRequestBytes, 0);
      if (key === "$asciiBytes" && exactKeys(`${tokenLocation}.$asciiBytes`, item, ["byte", "length"])) {
        integer(`${tokenLocation}.$asciiBytes.byte`, item.byte, 0);
        integer(`${tokenLocation}.$asciiBytes.length`, item.length, 0);
        if (Number.isInteger(item.byte) && (item.byte < 0x20 || item.byte > 0x7e)) fail(`${tokenLocation}.$asciiBytes.byte`, "must be one printable ASCII octet");
      }
      if (key === "$invalidValue") {
        if (object(item) && exactKeys(`${tokenLocation}.$invalidValue`, item, ["$rawMapFixture"])) {
          if (!["non-string-key", "invalid-unicode-key"].includes(item.$rawMapFixture)) fail(`${tokenLocation}.$invalidValue.$rawMapFixture`, "must name an invalid-key raw-map fixture");
        } else nonEmptyString(`${tokenLocation}.$invalidValue`, item);
      }
      if (key === "$rawMapFixture" && !["order-a", "order-b", "non-string-key", "invalid-unicode-key"].includes(item)) fail(`${tokenLocation}.$rawMapFixture`, "must name a closed raw-map fixture");
      if (key === "$issueFixture" && !Object.hasOwn(requiredIssueFixtures.variants, item)) fail(`${tokenLocation}.$issueFixture`, "must name a closed bounded issue fixture");
      if (key === "$int64Max" && item !== true) fail(`${tokenLocation}.$int64Max`, "must be the boolean true token");
      if (key === "$providerError" && exactKeys(`${tokenLocation}.$providerError`, item, ["causeType", "message"])) {
        if (!syntheticCauseTypes.has(item.causeType)) fail(`${tokenLocation}.$providerError.causeType`, "must be a declared synthetic cause type");
        nonEmptyString(`${tokenLocation}.$providerError.message`, item.message);
      }
      scanTestTokens(item, `${tokenLocation}.${key}`);
    }
  }
  scanTestTokens(testCase.input, `${location}.input`);
  scanTestTokens(testCase.options, `${location}.options`);
  scanTestTokens(testCase.script, `${location}.script`);

  function scanForbidden(value, valueLocation) {
    if (Array.isArray(value)) {
      value.forEach((item, itemIndex) => scanForbidden(item, `${valueLocation}[${itemIndex}]`));
      return;
    }
    if (object(value)) {
      for (const [key, item] of Object.entries(value)) {
        if (["skip", "skipped", "unsupported", "todo"].includes(key.toLowerCase())) fail(`${valueLocation}.${key}`, "skip/unsupported marker is forbidden");
        scanForbidden(item, `${valueLocation}.${key}`);
      }
      return;
    }
    if (typeof value === "string" && ["skip", "skipped", "unsupported", "todo"].includes(value.toLowerCase())) {
      fail(valueLocation, "skip/unsupported outcome is forbidden");
    }
  }
  scanForbidden(testCase, location);
}

function emptyConcurrencySideEffects() {
  return {
    permitAcquires: 0,
    permitRejects: 0,
    permitReleases: 0,
    observerStarts: 0,
    configurationSnapshots: 0,
    credentialResolutions: 0,
    defaultSnapshots: 0,
    idempotencyGenerations: 0,
    serializations: 0,
    networkAttempts: 0,
    byteAcquires: 0,
    byteRejects: 0,
    byteReleases: 0,
    deadlineCallbacks: 0,
    terminalDispatches: 0,
    ghostWorkAttempts: 0,
    reservationAcquires: 0,
    reservationReleases: 0,
  };
}

const legacyOccupiedPermits = (document.cases || [])
  .filter((testCase) => Object.hasOwn(testCase.options || {}, "occupiedPermits"))
  .map((testCase) => [testCase.caseId, testCase.options.occupiedPermits]);
const requiredLegacyOccupiedPermits = [
  ["defaults-max-in-flight-two-hundred-fifty-six", 255],
  ["defaults-max-in-flight-two-hundred-fifty-six-saturated", 256],
  ["admission-overloaded-before-all-side-effects", 256],
];
if (JSON.stringify(legacyOccupiedPermits) !== JSON.stringify(requiredLegacyOccupiedPermits)) {
  fail("$.cases", "occupiedPermits is allowed only in the three pinned legacy single-operation vectors; new admission coverage must use real concurrency scenarios");
}

function validateConcurrencyScenario(scenario, scenarioIndex, target = "SHARED", baseLocation = "$.concurrencyScenarios") {
  const location = `${baseLocation}[${scenarioIndex}]`;
  const scenarioStepActions = target === "JVM" ? jvmConcurrencyStepActions : concurrencyStepActions;
  const scenarioRuntimeFields = target === "JVM" ? jvmConcurrencyRuntimeFields : concurrencyRuntimeFields;
  const scenarioCheckpointFields = target === "JVM" ? jvmConcurrencyCheckpointFields : concurrencyCheckpointFields;
  const scenarioFinalRuntimeFields = target === "JVM" ? jvmConcurrencyFinalRuntimeFields : concurrencyFinalRuntimeFields;
  if (!exactKeys(location, scenario, concurrencyScenarioFields)) return null;
  if (typeof scenario.scenarioId !== "string" || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(scenario.scenarioId)) {
    fail(`${location}.scenarioId`, "must be a lowercase kebab-case scenario ID");
  }
  nonEmptyString(`${location}.description`, scenario.description);
  if (exactKeys(`${location}.runtime`, scenario.runtime, scenarioRuntimeFields)) {
    integer(`${location}.runtime.maxInFlight`, scenario.runtime.maxInFlight, 1, 65536);
    integer(`${location}.runtime.maxBufferedBytes`, scenario.runtime.maxBufferedBytes, 4194304, 1073741824);
    if (target === "JVM") {
      if (!["default", "injected"].includes(scenario.runtime.executorMode)) fail(`${location}.runtime.executorMode`, "must be default or injected");
      if (!["OWNED", "BORROWED"].includes(scenario.runtime.executorOwnership)) fail(`${location}.runtime.executorOwnership`, "must be OWNED or BORROWED");
      if ((scenario.runtime.executorMode === "injected") !== (scenario.runtime.executorOwnership === "BORROWED")) fail(`${location}.runtime`, "only the injected operation executor is BORROWED");
      if (scenario.runtime.executorMode === "default") {
        if (scenario.runtime.workerThreads !== "implementationDefault") fail(`${location}.runtime.workerThreads`, "default-owned runtime must declare implementationDefault");
        if (scenario.runtime.executorQueueCapacity !== scenario.runtime.maxInFlight) fail(`${location}.runtime.executorQueueCapacity`, "default-owned queue capacity must equal maxInFlight");
      } else {
        integer(`${location}.runtime.workerThreads`, scenario.runtime.workerThreads, 1, 1024);
        integer(`${location}.runtime.executorQueueCapacity`, scenario.runtime.executorQueueCapacity, 1, 65536);
      }
    }
  }

  if (!Array.isArray(scenario.operations) || scenario.operations.length === 0) fail(`${location}.operations`, "must be a non-empty array");
  const states = [];
  const stateById = new Map();
  const declaredBarriers = new Map();
  for (const [operationIndex, operation] of (scenario.operations || []).entries()) {
    const operationLocation = `${location}.operations[${operationIndex}]`;
    if (!exactKeys(operationLocation, operation, concurrencyOperationFields)) continue;
    if (typeof operation.operationId !== "string" || !/^op-[a-z][a-z0-9-]{0,31}$/u.test(operation.operationId)) {
      fail(`${operationLocation}.operationId`, "must match the closed concurrency operation ID grammar");
    } else if (stateById.has(operation.operationId)) {
      fail(`${operationLocation}.operationId`, "must be unique within the scenario");
    }
    if (exactKeys(`${operationLocation}.input`, operation.input, ["profile"], ["entropy"])) {
      if (operation.input.profile !== "valid") fail(`${operationLocation}.input.profile`, "must use the valid shared profile");
      if ("entropy" in operation.input) integerArray(`${operationLocation}.input.entropy`, operation.input.entropy);
    }
    if (object(operation.options)) {
      const allowedOptions = new Set(["idempotencyKey", "maxAttempts", "retryBaseMs", "retryMaxMs", "operationTimeoutMs"]);
      for (const key of Object.keys(operation.options)) if (!allowedOptions.has(key)) fail(`${operationLocation}.options.${key}`, "unknown concurrency option");
      if ("idempotencyKey" in operation.options && idempotencyKeyViolation(operation.options.idempotencyKey)) {
        fail(`${operationLocation}.options.idempotencyKey`, idempotencyKeyViolation(operation.options.idempotencyKey));
      }
      if ("maxAttempts" in operation.options) integer(`${operationLocation}.options.maxAttempts`, operation.options.maxAttempts, 1, 10);
      if ("retryBaseMs" in operation.options) integer(`${operationLocation}.options.retryBaseMs`, operation.options.retryBaseMs, 0);
      if ("retryMaxMs" in operation.options) integer(`${operationLocation}.options.retryMaxMs`, operation.options.retryMaxMs, 0);
      if ("operationTimeoutMs" in operation.options) integer(`${operationLocation}.options.operationTimeoutMs`, operation.options.operationTimeoutMs, 1, document.constants.durationMaxMs);
      if ((operation.options.retryBaseMs ?? document.constants.retryBaseMs) > (operation.options.retryMaxMs ?? document.constants.retryMaxMs)) {
        fail(`${operationLocation}.options`, "retryBaseMs must not exceed retryMaxMs");
      }
    } else {
      fail(`${operationLocation}.options`, "must be an object");
    }
    integer(`${operationLocation}.requestBytes`, operation.requestBytes, 1, document.constants.requestLimitBytes);
    if (!Array.isArray(operation.fixtureScript) || operation.fixtureScript.length === 0) {
      fail(`${operationLocation}.fixtureScript`, "must be a non-empty array");
    }
    for (const [actionIndex, action] of (operation.fixtureScript || []).entries()) {
      const actionLocation = `${operationLocation}.fixtureScript[${actionIndex}]`;
      if (!object(action) || !concurrencyFixtureActions.includes(action.action)) {
        fail(`${actionLocation}.action`, "must be a closed concurrency fixture action");
        continue;
      }
      if (!exactKeys(actionLocation, action, concurrencyFixtureFieldSchemas[action.action])) continue;
      integer(`${actionLocation}.attempt`, action.attempt, 1, 10);
      if (action.action === "hold") {
        if (action.phase !== "beforeRequestBytes") fail(`${actionLocation}.phase`, "must equal beforeRequestBytes");
        if (typeof action.barrier !== "string" || !/^[a-z][a-z0-9-]{0,63}$/u.test(action.barrier)) fail(`${actionLocation}.barrier`, "must be a lowercase barrier name");
        else if (declaredBarriers.has(action.barrier)) fail(`${actionLocation}.barrier`, "must be unique within the scenario");
        else declaredBarriers.set(action.barrier, operation.operationId);
      } else if (action.action === "failConnection") {
        if (action.phase !== "beforeRequestBytes") fail(`${actionLocation}.phase`, "must equal beforeRequestBytes");
        if (action.kind !== "dns") fail(`${actionLocation}.kind`, "must equal dns");
      } else {
        integer(`${actionLocation}.status`, action.status, 200, 599);
        integer(`${actionLocation}.responseBytes`, action.responseBytes, 0, document.constants.responseDecompressedLimitBytes);
        if (!["valid", "remoteError", "malformed"].includes(action.bodyKind)) fail(`${actionLocation}.bodyKind`, "must be valid, remoteError, or malformed");
        const validPair = (action.status === 202 && action.bodyKind === "valid")
          || (action.status === 400 && action.bodyKind === "remoteError")
          || (action.status === 500 && action.bodyKind === "remoteError")
          || (action.status === 200 && action.bodyKind === "malformed");
        if (!validPair) fail(actionLocation, "status and bodyKind must be one closed deterministic response pair");
      }
    }
    const state = {
      declarationIndex: operationIndex,
      operation,
      lifecycle: "NEW",
      ownsPermit: false,
      ownsReservation: false,
      fixtureIndex: 0,
      attempts: 0,
      blockedBarrier: null,
      blockedPhase: null,
      retryIndex: 0,
      retryDueMs: null,
      retrySequence: null,
      deadlineAtMs: null,
      requestBytesHeld: 0,
      responseBytesHeld: 0,
      parserScratchBytesHeld: 0,
      primaryDependentBlocked: false,
      outcomeDependentBlocked: false,
      pendingTerminal: null,
      completionRoute: null,
      code: null,
      delivery: null,
      sideEffects: emptyConcurrencySideEffects(),
    };
    states.push(state);
    if (typeof operation.operationId === "string" && !stateById.has(operation.operationId)) stateById.set(operation.operationId, state);
  }

  if (!Array.isArray(scenario.steps) || scenario.steps.length === 0) fail(`${location}.steps`, "must be a non-empty array");
  for (const [stepIndex, step] of (scenario.steps || []).entries()) {
    const stepLocation = `${location}.steps[${stepIndex}]`;
    if (!object(step) || !scenarioStepActions.includes(step.action)) {
      fail(`${stepLocation}.action`, "must be a closed concurrency step action");
      continue;
    }
    exactKeys(stepLocation, step, concurrencyStepFieldSchemas[step.action].requiredFields, concurrencyStepFieldSchemas[step.action].optionalFields);
    if ("operationId" in step && (typeof step.operationId !== "string" || !stateById.has(step.operationId))) fail(`${stepLocation}.operationId`, "must name a declared operation");
    if ("barrier" in step && (typeof step.barrier !== "string" || !declaredBarriers.has(step.barrier))) fail(`${stepLocation}.barrier`, "must name a pre-registered fixture barrier");
    if (step.action === "awaitRetryScheduled") integer(`${stepLocation}.retryIndex`, step.retryIndex, 1);
    if (step.action === "blockWorkers") integer(`${stepLocation}.count`, step.count, 1, scenario.runtime.workerThreads);
    if (step.action === "seedDiagnosticCounter") {
      if (step.counter !== "concurrencyOverloadRejections") fail(`${stepLocation}.counter`, "must equal the pinned portable saturation counter concurrencyOverloadRejections");
      integer(`${stepLocation}.value`, step.value, 0, document.constants.runtimeDiagnosticsCounterMax);
    }
    if (step.action === "advanceTime") integer(`${stepLocation}.byMs`, step.byMs, 0);
    if (step.action === "cancel" && !["beforeRequestBytes", "backoff"].includes(step.phase)) fail(`${stepLocation}.phase`, "must be beforeRequestBytes or backoff");
    if (step.action === "checkpoint" && (typeof step.name !== "string" || !/^[a-z][a-z0-9-]{0,63}$/u.test(step.name))) fail(`${stepLocation}.name`, "must be a lowercase checkpoint name");
  }

  const trace = [];
  const checkpoints = [];
  const runtime = {
    state: "OPEN",
    inFlight: 0,
    maxObservedInFlight: 0,
    completionReservations: 0,
    maxObservedCompletionReservations: 0,
    activeTerminalOperation: null,
    queuedTerminalTasks: 0,
    totalPermitAcquires: 0,
    totalPermitRejects: 0,
    totalPermitReleases: 0,
    totalReservationAcquires: 0,
    totalReservationReleases: 0,
    currentBufferedBytes: 0,
    maxObservedBufferedBytes: 0,
    totalByteAcquires: 0,
    totalByteRejects: 0,
    totalByteReleases: 0,
    deadlineCallbacks: 0,
    terminalDispatches: 0,
    ghostWorkAttempts: 0,
    concurrencyOverloadRejections: 0,
    requestByteOverloadRejections: 0,
    responseByteOverloadRejections: 0,
    queuedWorkerTasks: 0,
    blockedWorkers: 0,
    clockMs: 0,
    retrySequence: 0,
  };

  function releaseBytes(state) {
    for (const field of ["parserScratchBytesHeld", "responseBytesHeld", "requestBytesHeld"]) {
      if (state[field] <= 0) continue;
      runtime.currentBufferedBytes -= state[field];
      runtime.totalByteReleases += 1;
      state.sideEffects.byteReleases += 1;
      state[field] = 0;
    }
    if (runtime.currentBufferedBytes < 0) fail(location, "reference payload-byte count became negative");
  }

  function acquirePortableWorkspaces(state) {
    const requestBytes = document.constants.requestSerializationWorkspaceBytes;
    const responseBytes = document.constants.responseWorkspaceBytes;
    const parserScratchBytes = document.constants.jsonParserScratchReservationBytes;
    const totalBytes = document.constants.portableOperationWorkspaceBytes;
    if (runtime.currentBufferedBytes + totalBytes > scenario.runtime.maxBufferedBytes) {
      runtime.totalByteRejects += 1;
      state.sideEffects.byteRejects += 1;
      runtime.requestByteOverloadRejections += 1;
      return false;
    }
    runtime.currentBufferedBytes += totalBytes;
    runtime.maxObservedBufferedBytes = Math.max(runtime.maxObservedBufferedBytes, runtime.currentBufferedBytes);
    runtime.totalByteAcquires += 3;
    state.sideEffects.byteAcquires += 3;
    state.requestBytesHeld = requestBytes;
    state.responseBytesHeld = responseBytes;
    state.parserScratchBytesHeld = parserScratchBytes;
    return true;
  }

  function convertRequestWorkspaceToRetainedBytes(state) {
    const unusedBytes = state.requestBytesHeld - state.operation.requestBytes;
    if (unusedBytes < 0) {
      fail(`${location}.operations[${state.declarationIndex}].requestBytes`, "cannot exceed the portable request serialization workspace");
      return;
    }
    runtime.currentBufferedBytes -= unusedBytes;
    state.requestBytesHeld = state.operation.requestBytes;
  }

  function releaseReservationAndTerminal(state, code, delivery) {
    const operationId = state.operation.operationId;
    if (state.ownsReservation) {
      state.ownsReservation = false;
      runtime.completionReservations -= 1;
      runtime.totalReservationReleases += 1;
      state.sideEffects.reservationReleases += 1;
      trace.push(`operation:${operationId}:reservation.release:reserved=${runtime.completionReservations}`);
    }
    state.lifecycle = "TERMINAL";
    state.blockedBarrier = null;
    state.blockedPhase = null;
    state.retryDueMs = null;
    state.retrySequence = null;
    state.deadlineAtMs = null;
    state.code = code;
    state.delivery = delivery;
    trace.push(`operation:${operationId}:terminal:${code}:${delivery}:attempts=${state.attempts}`);
    if (runtime.activeTerminalOperation === operationId) {
      runtime.activeTerminalOperation = null;
      drainQueuedCompletions();
    }
  }

  function drainQueuedCompletions() {
    if (runtime.activeTerminalOperation !== null) return;
    const candidate = states.filter((item) => item.lifecycle === "TERMINAL_QUEUED").sort((left, right) => left.declarationIndex - right.declarationIndex)[0];
    if (!candidate) return;
    runtime.queuedTerminalTasks -= 1;
    runtime.activeTerminalOperation = candidate.operation.operationId;
    const pending = candidate.pendingTerminal;
    candidate.pendingTerminal = null;
    trace.push(`operation:${candidate.operation.operationId}:terminal.dequeue`);
    trace.push(`operation:${candidate.operation.operationId}:primary.complete:${pending.code}:${pending.delivery}`);
    if (candidate.primaryDependentBlocked) {
      candidate.lifecycle = "COMPLETION_BLOCKED";
      candidate.pendingTerminal = pending;
      return;
    }
    finalizeAfterPrimary(candidate, pending.code, pending.delivery);
  }

  function finalizeAfterPrimary(state, code, delivery) {
    const operationId = state.operation.operationId;
    trace.push(`operation:${operationId}:outcome.complete:${code}:${delivery}`);
    if (state.outcomeDependentBlocked) {
      state.lifecycle = "OUTCOME_COMPLETION_BLOCKED";
      state.pendingTerminal = { code, delivery };
      return;
    }
    releaseReservationAndTerminal(state, code, delivery);
  }

  function finish(state, code, delivery, route = "OPERATION_EXECUTION") {
    const operationId = state.operation.operationId;
    if (["TERMINAL", "COMPLETION_BLOCKED", "OUTCOME_COMPLETION_BLOCKED"].includes(state.lifecycle)) {
      fail(location, `operation ${operationId} reached terminal selection more than once`);
      return;
    }
    state.completionRoute = route;
    if (route === "DIRECT_PRE_ADMISSION") {
      state.code = code; state.delivery = delivery; state.lifecycle = "TERMINAL";
      trace.push(`operation:${operationId}:terminal:${code}:${delivery}:attempts=${state.attempts}`);
      return;
    }
    releaseBytes(state);
    if (state.lifecycle === "QUEUED") runtime.queuedWorkerTasks -= 1;
    if (state.ownsPermit) {
      state.ownsPermit = false;
      runtime.inFlight -= 1;
      runtime.totalPermitReleases += 1;
      state.sideEffects.permitReleases += 1;
      if (runtime.inFlight < 0) fail(location, "reference permit count became negative");
      trace.push(`operation:${operationId}:permit.release:inFlight=${runtime.inFlight}`);
    }
    if (route === "TERMINAL_DISPATCHER") {
      runtime.terminalDispatches += 1;
      state.sideEffects.terminalDispatches += 1;
    }
    if (route === "TERMINAL_DISPATCHER" && runtime.activeTerminalOperation !== null) {
      state.lifecycle = "TERMINAL_QUEUED";
      state.pendingTerminal = { code, delivery };
      runtime.queuedTerminalTasks += 1;
      trace.push(`operation:${operationId}:terminal.queued:${code}:${delivery}`);
      return;
    }
    if (route === "TERMINAL_DISPATCHER") runtime.activeTerminalOperation = operationId;
    trace.push(`operation:${operationId}:primary.complete:${code}:${delivery}`);
    if (state.primaryDependentBlocked) {
      state.lifecycle = "COMPLETION_BLOCKED";
      state.pendingTerminal = { code, delivery };
      return;
    }
    finalizeAfterPrimary(state, code, delivery);
  }

  function retryDelay(state) {
    const retryBaseMs = state.operation.options.retryBaseMs ?? document.constants.retryBaseMs;
    const retryMaxMs = state.operation.options.retryMaxMs ?? document.constants.retryMaxMs;
    const exponent = Math.max(0, state.attempts - 1);
    const cap = Math.min(retryMaxMs, retryBaseMs * (2 ** exponent));
    const profileEntropy = document.inputProfiles?.[state.operation.input.profile]?.entropy || [];
    const entropy = state.operation.input.entropy ?? profileEntropy;
    const value = entropy[state.retryIndex];
    if (!Number.isSafeInteger(value) || value < 0 || value > cap) {
      fail(`${location}.operations[${state.declarationIndex}].input.entropy`, `retry index ${state.retryIndex + 1} must provide an integer in 0..${cap}`);
      return 0;
    }
    return value;
  }

  function scheduleRetry(state) {
    const delay = retryDelay(state);
    state.retryIndex += 1;
    state.retryDueMs = runtime.clockMs + delay;
    state.retrySequence = runtime.retrySequence++;
    state.lifecycle = "BACKOFF";
    trace.push(`operation:${state.operation.operationId}:retry.schedule:index=${state.retryIndex}:delayMs=${delay}:inFlight=${runtime.inFlight}`);
  }

  function startAttempt(state, action) {
    if (action.attempt === state.attempts + 1) {
      state.attempts += 1;
      state.sideEffects.networkAttempts += 1;
      return true;
    }
    if (action.attempt === state.attempts && state.attempts > 0) return true;
    fail(`${location}.operations[${state.declarationIndex}].fixtureScript[${state.fixtureIndex}]`, `attempt must continue ${state.attempts} after a hold or start ${state.attempts + 1}`);
    return false;
  }

  function runFixture(state) {
    const action = state.operation.fixtureScript[state.fixtureIndex];
    if (!action) {
      fail(`${location}.operations[${state.declarationIndex}].fixtureScript`, "admitted operation exhausted its fixture script without a terminal result");
      return;
    }
    if (!startAttempt(state, action)) return;
    state.fixtureIndex += 1;
    if (action.action === "hold") {
      state.lifecycle = "BLOCKED";
      state.blockedBarrier = action.barrier;
      state.blockedPhase = action.phase;
      trace.push(`barrier:${action.barrier}:reached:operation=${state.operation.operationId}:inFlight=${runtime.inFlight}`);
      return;
    }
    if (action.action === "failConnection") {
      const maxAttempts = state.operation.options.maxAttempts ?? document.constants.maxAttemptsTotal;
      const nextAction = state.operation.fixtureScript[state.fixtureIndex];
      if (state.attempts < maxAttempts && nextAction?.attempt === state.attempts + 1) scheduleRetry(state);
      else finish(state, "DNS", "NOT_SENT");
      return;
    }
    if (action.responseBytes > state.responseBytesHeld) fail(`${location}.operations[${state.declarationIndex}].fixtureScript[${state.fixtureIndex - 1}].responseBytes`, "cannot exceed the response workspace reserved before publication");
    if (action.status === 202 && action.bodyKind === "valid") {
      finish(state, "OK", "ACCEPTED");
      return;
    }
    if (action.status === 400 && action.bodyKind === "remoteError") {
      finish(state, "HTTP_REJECTED", "REJECTED");
      return;
    }
    if (action.status === 500 && action.bodyKind === "remoteError") {
      const maxAttempts = state.operation.options.maxAttempts ?? document.constants.maxAttemptsTotal;
      const nextAction = state.operation.fixtureScript[state.fixtureIndex];
      if (state.attempts < maxAttempts && nextAction?.attempt === state.attempts + 1) {
        scheduleRetry(state);
      }
      else finish(state, "SERVER_FAILURE", "POSSIBLY_SENT");
      return;
    }
    if (action.status === 200 && action.bodyKind === "malformed") {
      const maxAttempts = state.operation.options.maxAttempts ?? document.constants.maxAttemptsTotal;
      const nextAction = state.operation.fixtureScript[state.fixtureIndex];
      if (state.attempts < maxAttempts && nextAction?.attempt === state.attempts + 1) {
        scheduleRetry(state);
      }
      else finish(state, "RESPONSE_PROTOCOL", "POSSIBLY_SENT");
    }
  }

  function startOperation(state) {
    const operationId = state.operation.operationId;
    if (state.lifecycle !== "NEW") {
      fail(location, `operation ${operationId} may be started exactly once`);
      return;
    }
    if (runtime.state !== "OPEN") {
      trace.push(`operation:${operationId}:admission.closed:inFlight=${runtime.inFlight}`);
      finish(state, "CLOSED", "NOT_SENT", "DIRECT_PRE_ADMISSION");
      return;
    }
    if (runtime.inFlight >= scenario.runtime.maxInFlight) {
      runtime.totalPermitRejects += 1;
      runtime.concurrencyOverloadRejections = Math.min(document.constants.runtimeDiagnosticsCounterMax, runtime.concurrencyOverloadRejections + 1);
      state.sideEffects.permitRejects += 1;
      trace.push(`operation:${operationId}:permit.reject:inFlight=${runtime.inFlight}`);
      finish(state, "OVERLOADED", "NOT_SENT", "DIRECT_PRE_ADMISSION");
      return;
    }
    runtime.inFlight += 1;
    runtime.completionReservations += 1;
    runtime.maxObservedCompletionReservations = Math.max(runtime.maxObservedCompletionReservations, runtime.completionReservations);
    runtime.totalReservationAcquires += 1;
    runtime.maxObservedInFlight = Math.max(runtime.maxObservedInFlight, runtime.inFlight);
    runtime.totalPermitAcquires += 1;
    state.ownsPermit = true;
    state.ownsReservation = true;
    state.lifecycle = "RUNNING";
    state.sideEffects.permitAcquires += 1;
    state.sideEffects.reservationAcquires += 1;
    trace.push(`operation:${operationId}:permit.acquire:inFlight=${runtime.inFlight}`);
    trace.push(`operation:${operationId}:reservation.acquire:reserved=${runtime.completionReservations}`);
    if (!acquirePortableWorkspaces(state)) {
      finish(state, "OVERLOADED", "NOT_SENT");
      return;
    }
    state.deadlineAtMs = runtime.clockMs + (state.operation.options.operationTimeoutMs ?? document.constants.operationTimeoutMs);
    if (runtime.blockedWorkers >= scenario.runtime.workerThreads) {
      state.lifecycle = "QUEUED";
      runtime.queuedWorkerTasks += 1;
      trace.push(`operation:${operationId}:worker.queued:deadlineMs=${state.deadlineAtMs}`);
      return;
    }
    state.sideEffects.observerStarts += 1;
    trace.push(`operation:${operationId}:observer.start`);
    state.sideEffects.configurationSnapshots += 1;
    state.sideEffects.credentialResolutions += 1;
    state.sideEffects.defaultSnapshots += 1;
    state.sideEffects.idempotencyGenerations += Object.hasOwn(state.operation.options, "idempotencyKey") ? 0 : 1;
    state.sideEffects.serializations += 1;
    convertRequestWorkspaceToRetainedBytes(state);
    runFixture(state);
  }

  function performRuntimeClose(stepLocation, selfKind = null, selfState = null) {
    if (runtime.state !== "OPEN") {
      fail(stepLocation, "runtime may be closed exactly once from OPEN");
      return;
    }
    runtime.state = "CLOSING";
    trace.push(`runtime:close.begin:inFlight=${runtime.inFlight}`);
    if (selfKind) trace.push(`runtime:close.self:${selfKind}:operation=${selfState.operation.operationId}`);
    if (runtime.activeTerminalOperation !== null || runtime.queuedTerminalTasks > 0) trace.push("runtime:terminal-dispatcher.shutdown-graceful:skip-current-and-queued-wait");
    for (const candidate of states.filter((item) => item.ownsPermit).sort((left, right) => left.declarationIndex - right.declarationIndex)) {
      finish(candidate, "CLOSED", "NOT_SENT", "TERMINAL_DISPATCHER");
    }
    runtime.state = "CLOSED";
    trace.push(`runtime:close.end:inFlight=${runtime.inFlight}`);
  }

  for (const [stepIndex, step] of (scenario.steps || []).entries()) {
    const stepLocation = `${location}.steps[${stepIndex}]`;
    const state = "operationId" in (step || {}) ? stateById.get(step.operationId) : null;
    if (!object(step) || !scenarioStepActions.includes(step.action)) continue;
    if (step.action === "seedDiagnosticCounter") {
      if (runtime.totalPermitRejects !== 0 || states.some((candidate) => candidate.code === "OVERLOADED")) fail(stepLocation, "diagnostic counter must be seeded before its first causal rejection");
      else {
        runtime[step.counter] = step.value;
        trace.push(`runtime:diagnostic.seed:counter=${step.counter}:value=${step.value}`);
      }
    } else if (step.action === "blockWorkers") {
      if (runtime.blockedWorkers !== 0) fail(stepLocation, "worker pool may be blocked exactly once");
      else {
        runtime.blockedWorkers = step.count;
        trace.push(`runtime:workers.blocked:count=${step.count}`);
      }
    } else if (step.action === "releaseWorkers") {
      if (runtime.blockedWorkers === 0) fail(stepLocation, "worker blockers may be released exactly once after the liveness assertion");
      else {
        trace.push(`runtime:workers.released:count=${runtime.blockedWorkers}`);
        runtime.blockedWorkers = 0;
      }
    } else if (step.action === "shutdownBorrowedExecutor") {
      if (scenario.runtime.executorOwnership !== "BORROWED" || runtime.state !== "CLOSED" || runtime.blockedWorkers !== 0 || runtime.queuedWorkerTasks !== 0) fail(stepLocation, "borrowed executor shutdown requires a closed/drained runtime after blocker release");
      else {
        trace.push("runtime:borrowed-executor.shutdown-by-runner");
        trace.push("runtime:borrowed-executor.terminated");
      }
    } else if (step.action === "blockPrimaryDependent") {
      if (!state || state.lifecycle !== "NEW" || state.primaryDependentBlocked) fail(stepLocation, "must register one blocking non-async primary dependent before start");
      else state.primaryDependentBlocked = true;
    } else if (step.action === "releasePrimaryDependent") {
      if (!state || state.lifecycle !== "COMPLETION_BLOCKED" || !state.pendingTerminal) fail(stepLocation, "primary dependent must already block after primary completion");
      else {
        state.primaryDependentBlocked = false;
        const pending = state.pendingTerminal;
        state.pendingTerminal = null;
        finalizeAfterPrimary(state, pending.code, pending.delivery);
        drainQueuedCompletions();
      }
    } else if (step.action === "blockOutcomeDependent") {
      if (!state || state.lifecycle !== "NEW" || state.outcomeDependentBlocked) fail(stepLocation, "must register one blocking non-async outcome dependent before start");
      else state.outcomeDependentBlocked = true;
    } else if (step.action === "releaseOutcomeDependent") {
      if (!state || state.lifecycle !== "OUTCOME_COMPLETION_BLOCKED" || !state.pendingTerminal) fail(stepLocation, "outcome dependent must already block after outcome completion");
      else {
        state.outcomeDependentBlocked = false;
        const pending = state.pendingTerminal;
        state.pendingTerminal = null;
        releaseReservationAndTerminal(state, pending.code, pending.delivery);
        drainQueuedCompletions();
      }
    } else if (step.action === "closeFromPrimaryDependent" || step.action === "closeFromOutcomeDependent") {
      const expectedLifecycle = step.action === "closeFromPrimaryDependent" ? "COMPLETION_BLOCKED" : "OUTCOME_COMPLETION_BLOCKED";
      const selfCloseRoute = state?.completionRoute;
      const isActiveDispatcherCallback = runtime.activeTerminalOperation === state?.operation.operationId;
      const isDirectCompletionCallback = ["OPERATION_EXECUTION", "NATIVE_CANCEL"].includes(selfCloseRoute);
      if (!state || state.lifecycle !== expectedLifecycle || (!isActiveDispatcherCallback && !isDirectCompletionCallback)) fail(stepLocation, "self-close must run from the matching completion callback on its causal completion route");
      else performRuntimeClose(stepLocation, step.action === "closeFromPrimaryDependent" ? "primary" : "outcome", state);
    } else if (step.action === "start") {
      if (state) startOperation(state);
    } else if (step.action === "awaitBarrier") {
      if (!state || state.lifecycle !== "BLOCKED" || state.blockedBarrier !== step.barrier) fail(stepLocation, "operation must already be blocked at the named reached barrier");
    } else if (step.action === "release") {
      const reached = states.filter((candidate) => candidate.lifecycle === "BLOCKED" && candidate.blockedBarrier === step.barrier);
      if (reached.length !== 1) fail(stepLocation, "must release exactly one reached and still-blocked barrier");
      else {
        const released = reached[0];
        released.lifecycle = "RUNNING";
        released.blockedBarrier = null;
        released.blockedPhase = null;
        runFixture(released);
      }
    } else if (step.action === "awaitRetryScheduled") {
      if (!state || state.lifecycle !== "BACKOFF" || state.retryIndex !== step.retryIndex) fail(stepLocation, "operation must already have the named retry registered without running it");
    } else if (step.action === "advanceTime") {
      runtime.clockMs += Number.isSafeInteger(step.byMs) ? step.byMs : 0;
      const expiredQueued = states.filter((candidate) => candidate.lifecycle === "QUEUED" && candidate.deadlineAtMs <= runtime.clockMs)
        .sort((left, right) => left.deadlineAtMs - right.deadlineAtMs || left.declarationIndex - right.declarationIndex);
      for (const candidate of expiredQueued) {
        runtime.deadlineCallbacks += 1;
        candidate.sideEffects.deadlineCallbacks += 1;
        trace.push(`operation:${candidate.operation.operationId}:deadline.fire:executor=independent-timer`);
        trace.push(`operation:${candidate.operation.operationId}:terminal.dispatch:executor=independent-terminal`);
        finish(candidate, "OPERATION_DEADLINE", "NOT_SENT", "TERMINAL_DISPATCHER");
      }
      const due = states.filter((candidate) => candidate.lifecycle === "BACKOFF" && candidate.retryDueMs <= runtime.clockMs)
        .sort((left, right) => left.retryDueMs - right.retryDueMs || left.retrySequence - right.retrySequence);
      for (const candidate of due) {
        candidate.lifecycle = "RUNNING";
        candidate.retryDueMs = null;
        candidate.retrySequence = null;
        runFixture(candidate);
      }
    } else if (step.action === "cancel") {
      const phaseMatches = state?.lifecycle === "BACKOFF"
        ? step.phase === "backoff"
        : state?.lifecycle === "BLOCKED" && step.phase === state.blockedPhase;
      if (!state || !state.ownsPermit || !phaseMatches) fail(stepLocation, "must cancel an admitted operation at its reached phase");
      else finish(state, "CANCELLED", "NOT_SENT", "NATIVE_CANCEL");
    } else if (step.action === "closeRuntime") {
      performRuntimeClose(stepLocation);
    } else if (step.action === "awaitTerminal") {
      if (!state || state.lifecycle !== "TERMINAL") fail(stepLocation, "public operation must already be terminal without advancing time or releasing work");
    } else if (step.action === "checkpoint") {
      const diagnostics = {
        inFlightOperations: runtime.inFlight, bufferedBytes: runtime.currentBufferedBytes,
        concurrencyOverloadRejections: runtime.concurrencyOverloadRejections,
        requestByteOverloadRejections: runtime.requestByteOverloadRejections,
        responseByteOverloadRejections: runtime.responseByteOverloadRejections,
        responseHeaderLimitFailures: 0, executorOverloadRejections: 0, schedulerOverloadRejections: 0,
        responseCloseFailures: 0, droppedObserverEvents: 0, observerFailures: 0,
        closed: runtime.state === "CLOSED",
      };
      const checkpoint = {
        name: step.name, state: runtime.state, inFlight: runtime.inFlight,
        completionReservations: runtime.completionReservations,
        activeTerminalOperation: runtime.activeTerminalOperation,
        queuedTerminalTasks: runtime.queuedTerminalTasks,
        currentBufferedBytes: runtime.currentBufferedBytes, queuedWaiters: 0,
        ...(target === "JVM" ? { queuedWorkerTasks: runtime.queuedWorkerTasks, blockedWorkers: runtime.blockedWorkers } : {}),
        totalPermitAcquires: runtime.totalPermitAcquires, totalPermitRejects: runtime.totalPermitRejects, totalPermitReleases: runtime.totalPermitReleases,
        totalByteAcquires: runtime.totalByteAcquires, totalByteRejects: runtime.totalByteRejects, totalByteReleases: runtime.totalByteReleases,
        deadlineCallbacks: runtime.deadlineCallbacks, terminalDispatches: runtime.terminalDispatches, ghostWorkAttempts: runtime.ghostWorkAttempts,
        diagnostics,
      };
      checkpoints.push(checkpoint);
      trace.push(`checkpoint:${step.name}:state=${runtime.state}:inFlight=${runtime.inFlight}:queuedWaiters=0`);
    }
  }

  for (const state of states) {
    if (state.lifecycle !== "TERMINAL") fail(`${location}.operations[${state.declarationIndex}]`, "every declared operation must be started and reach a public terminal state");
    if (state.ownsPermit) fail(`${location}.operations[${state.declarationIndex}]`, "terminal operation must not retain a permit");
    if (state.ownsReservation) fail(`${location}.operations[${state.declarationIndex}]`, "terminal operation must not retain a completion reservation");
  }
  if (runtime.inFlight !== 0) fail(`${location}.expected.finalRuntime.inFlight`, "scenario must finish with no in-flight operations");
  if (runtime.currentBufferedBytes !== 0) fail(`${location}.expected.finalRuntime.currentBufferedBytes`, "scenario must finish with no retained payload bytes");
  if (runtime.queuedWorkerTasks !== 0) fail(`${location}.expected.finalRuntime.queuedWorkerTasks`, "scenario must finish with no queued operation task");
  if (runtime.totalPermitAcquires !== runtime.totalPermitReleases) fail(`${location}.expected.finalRuntime`, "every acquired permit must be released exactly once");
  if (runtime.completionReservations !== 0 || runtime.totalReservationAcquires !== runtime.totalReservationReleases) fail(`${location}.expected.finalRuntime`, "every terminal completion reservation must release in finally");
  if (runtime.totalByteAcquires !== runtime.totalByteReleases) fail(`${location}.expected.finalRuntime`, "every acquired weighted byte permit must be released exactly once");

  const derivedOperations = states.map((state) => ({
    operationId: state.operation.operationId,
    code: state.code,
    delivery: state.delivery,
    attemptCount: state.attempts,
    completionRoute: state.completionRoute,
    sideEffects: state.sideEffects,
  }));
  const derivedFinalRuntime = {
    state: runtime.state,
    inFlight: runtime.inFlight,
    maxObservedInFlight: runtime.maxObservedInFlight,
    completionReservations: runtime.completionReservations,
    maxObservedCompletionReservations: runtime.maxObservedCompletionReservations,
    activeTerminalOperation: runtime.activeTerminalOperation,
    queuedTerminalTasks: runtime.queuedTerminalTasks,
    currentBufferedBytes: runtime.currentBufferedBytes,
    maxObservedBufferedBytes: runtime.maxObservedBufferedBytes,
    queuedWaiters: 0,
    ...(target === "JVM" ? { queuedWorkerTasks: runtime.queuedWorkerTasks, blockedWorkers: runtime.blockedWorkers } : {}),
    totalPermitAcquires: runtime.totalPermitAcquires,
    totalPermitRejects: runtime.totalPermitRejects,
    totalPermitReleases: runtime.totalPermitReleases,
    totalReservationAcquires: runtime.totalReservationAcquires,
    totalReservationReleases: runtime.totalReservationReleases,
    totalByteAcquires: runtime.totalByteAcquires,
    totalByteRejects: runtime.totalByteRejects,
    totalByteReleases: runtime.totalByteReleases,
    deadlineCallbacks: runtime.deadlineCallbacks,
    terminalDispatches: runtime.terminalDispatches,
    ghostWorkAttempts: runtime.ghostWorkAttempts,
    diagnostics: {
      inFlightOperations: runtime.inFlight, bufferedBytes: runtime.currentBufferedBytes,
      concurrencyOverloadRejections: runtime.concurrencyOverloadRejections,
      requestByteOverloadRejections: runtime.requestByteOverloadRejections,
      responseByteOverloadRejections: runtime.responseByteOverloadRejections,
      responseHeaderLimitFailures: 0, executorOverloadRejections: 0, schedulerOverloadRejections: 0,
      responseCloseFailures: 0, droppedObserverEvents: 0, observerFailures: 0,
      closed: runtime.state === "CLOSED",
    },
  };

  if (exactKeys(`${location}.expected`, scenario.expected, concurrencyExpectedFields)) {
    stringArray(`${location}.expected.orderedTrace`, scenario.expected.orderedTrace);
    if (JSON.stringify(scenario.expected.orderedTrace) !== JSON.stringify(trace)) fail(`${location}.expected.orderedTrace`, `must exactly equal the validator-derived execution trace ${JSON.stringify(trace)}`);
    if (!Array.isArray(scenario.expected.checkpoints)) fail(`${location}.expected.checkpoints`, "must be an array");
    for (const [checkpointIndex, checkpoint] of (scenario.expected.checkpoints || []).entries()) {
      const checkpointLocation = `${location}.expected.checkpoints[${checkpointIndex}]`;
      if (exactKeys(checkpointLocation, checkpoint, scenarioCheckpointFields)) {
        nonEmptyString(`${checkpointLocation}.name`, checkpoint.name);
        if (!concurrencyRuntimeStates.includes(checkpoint.state)) fail(`${checkpointLocation}.state`, "must be a closed runtime state");
        integer(`${checkpointLocation}.inFlight`, checkpoint.inFlight, 0);
        for (const field of scenarioCheckpointFields.filter((field) => !["name", "state", "diagnostics", "activeTerminalOperation"].includes(field))) integer(`${checkpointLocation}.${field}`, checkpoint[field], 0);
        if (checkpoint.activeTerminalOperation !== null && typeof checkpoint.activeTerminalOperation !== "string") fail(`${checkpointLocation}.activeTerminalOperation`, "must be null or a scenario operation alias");
        if (exactKeys(`${checkpointLocation}.diagnostics`, checkpoint.diagnostics, runtimeDiagnosticsFields)) {
          for (const field of runtimeDiagnosticsFields.filter((field) => field !== "closed")) integer(`${checkpointLocation}.diagnostics.${field}`, checkpoint.diagnostics[field], 0, document.constants.runtimeDiagnosticsCounterMax);
          if (typeof checkpoint.diagnostics.closed !== "boolean") fail(`${checkpointLocation}.diagnostics.closed`, "must be boolean");
        }
        if (checkpoint.queuedWaiters !== 0) fail(`${checkpointLocation}.queuedWaiters`, "must equal zero; admission and byte budgets never queue waiters");
      }
    }
    if (JSON.stringify(scenario.expected.checkpoints) !== JSON.stringify(checkpoints)) fail(`${location}.expected.checkpoints`, `must exactly equal validator-derived checkpoints ${JSON.stringify(checkpoints)}`);
    if (!Array.isArray(scenario.expected.operations)) fail(`${location}.expected.operations`, "must be an array");
    for (const [expectedIndex, expectedOperation] of (scenario.expected.operations || []).entries()) {
      const expectedLocation = `${location}.expected.operations[${expectedIndex}]`;
      if (exactKeys(expectedLocation, expectedOperation, concurrencyOperationExpectedFields)) {
        if (!concurrencyOperationCodes.includes(expectedOperation.code)) fail(`${expectedLocation}.code`, "must be a closed concurrency operation code");
        if (!concurrencyCompletionRoutes.includes(expectedOperation.completionRoute)) fail(`${expectedLocation}.completionRoute`, "must name the causal completion executor route");
        if (!["NOT_SENT", "POSSIBLY_SENT", "REJECTED", "ACCEPTED"].includes(expectedOperation.delivery)) fail(`${expectedLocation}.delivery`, "must be a closed delivery state");
        integer(`${expectedLocation}.attemptCount`, expectedOperation.attemptCount, 0);
        if (exactKeys(`${expectedLocation}.sideEffects`, expectedOperation.sideEffects, concurrencySideEffectCounters)) {
          for (const field of concurrencySideEffectCounters) integer(`${expectedLocation}.sideEffects.${field}`, expectedOperation.sideEffects[field], 0);
        }
      }
    }
    if (JSON.stringify(scenario.expected.operations) !== JSON.stringify(derivedOperations)) fail(`${location}.expected.operations`, `must exactly equal validator-derived operation outcomes and side effects ${JSON.stringify(derivedOperations)}`);
    if (exactKeys(`${location}.expected.finalRuntime`, scenario.expected.finalRuntime, scenarioFinalRuntimeFields)) {
      if (!concurrencyRuntimeStates.includes(scenario.expected.finalRuntime.state)) fail(`${location}.expected.finalRuntime.state`, "must be a closed runtime state");
      for (const field of scenarioFinalRuntimeFields.filter((field) => !["state", "diagnostics", "activeTerminalOperation"].includes(field))) integer(`${location}.expected.finalRuntime.${field}`, scenario.expected.finalRuntime[field], 0);
      if (scenario.expected.finalRuntime.activeTerminalOperation !== null) fail(`${location}.expected.finalRuntime.activeTerminalOperation`, "must be null after all settlements drain");
      if (exactKeys(`${location}.expected.finalRuntime.diagnostics`, scenario.expected.finalRuntime.diagnostics, runtimeDiagnosticsFields)) {
        for (const field of runtimeDiagnosticsFields.filter((field) => field !== "closed")) integer(`${location}.expected.finalRuntime.diagnostics.${field}`, scenario.expected.finalRuntime.diagnostics[field], 0, document.constants.runtimeDiagnosticsCounterMax);
        if (typeof scenario.expected.finalRuntime.diagnostics.closed !== "boolean") fail(`${location}.expected.finalRuntime.diagnostics.closed`, "must be boolean");
      }
      if (scenario.expected.finalRuntime.queuedWaiters !== 0) fail(`${location}.expected.finalRuntime.queuedWaiters`, "must equal zero; admission and byte budgets never queue waiters");
    }
    if (JSON.stringify(scenario.expected.finalRuntime) !== JSON.stringify(derivedFinalRuntime)) fail(`${location}.expected.finalRuntime`, `must exactly equal validator-derived runtime diagnostics ${JSON.stringify(derivedFinalRuntime)}`);
  }

  function scanForbiddenScenario(value, valueLocation) {
    if (Array.isArray(value)) return value.forEach((item, index) => scanForbiddenScenario(item, `${valueLocation}[${index}]`));
    if (!object(value)) return;
    for (const [key, item] of Object.entries(value)) {
      if (["skip", "skipped", "unsupported", "todo"].includes(key.toLowerCase())) fail(`${valueLocation}.${key}`, "skip/unsupported marker is forbidden");
      scanForbiddenScenario(item, `${valueLocation}.${key}`);
    }
  }
  scanForbiddenScenario(scenario, location);
  return { scenario, trace, checkpoints, operations: derivedOperations, finalRuntime: derivedFinalRuntime };
}

if (!Array.isArray(document.concurrencyScenarios) || document.concurrencyScenarios.length === 0) fail("$.concurrencyScenarios", "must be a non-empty array");
const concurrencyScenarioIds = (document.concurrencyScenarios || []).map((scenario) => scenario?.scenarioId);
const concurrencyInputs = (document.concurrencyScenarios || []).map((candidate) => {
  if (!object(candidate)) return candidate;
  const { expected, ...scenario } = candidate;
  return scenario;
});
if (crypto.createHash("sha256").update(JSON.stringify(concurrencyInputs)).digest("hex") !== requiredConcurrencyInputsSha256) {
  fail("$.concurrencyScenarios", "scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory");
}
if (JSON.stringify(concurrencyScenarioIds) !== JSON.stringify(requiredConcurrencyScenarioIds)) {
  fail("$.concurrencyScenarios", `scenario IDs and order must equal ${JSON.stringify(requiredConcurrencyScenarioIds)}`);
}
if (JSON.stringify([...(document.cases || []).map((testCase) => testCase?.caseId), ...concurrencyScenarioIds]) !== JSON.stringify(document.caseManifest)) {
  fail("$.caseManifest", "must list all single-operation cases first and all concurrency scenarios second");
}
const concurrencyDerivations = new Map();
for (const [scenarioIndex, scenario] of (document.concurrencyScenarios || []).entries()) {
  const derived = validateConcurrencyScenario(scenario, scenarioIndex);
  if (derived && !concurrencyDerivations.has(scenario.scenarioId)) concurrencyDerivations.set(scenario.scenarioId, derived);
  else if (derived) fail(`$.concurrencyScenarios[${scenarioIndex}].scenarioId`, "must be unique");
}
function validateSuccessReacquisition(scenarioId, completedOperationId, probeOperationId, completedAttempts) {
  const derivation = concurrencyDerivations.get(scenarioId);
  if (!derivation) return;
  const completed = derivation.operations.find((operation) => operation.operationId === completedOperationId);
  const probe = derivation.operations.find((operation) => operation.operationId === probeOperationId);
  const completedTerminal = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${completedOperationId}:terminal:OK:ACCEPTED:`));
  const probeAcquire = derivation.trace.findIndex((entry) => entry === `operation:${probeOperationId}:permit.acquire:inFlight=1`);
  const successfulOperation = (operation, attempts, networkAttempts) => operation?.code === "OK"
    && operation.delivery === "ACCEPTED"
    && operation.attemptCount === attempts
    && operation.sideEffects.permitAcquires === 1
    && operation.sideEffects.permitReleases === 1
    && operation.sideEffects.networkAttempts === networkAttempts;
  if (derivation.scenario.runtime.maxInFlight !== 1
    || derivation.finalRuntime.maxObservedInFlight !== 1
    || derivation.finalRuntime.inFlight !== 0
    || derivation.finalRuntime.totalPermitAcquires !== 2
    || derivation.finalRuntime.totalPermitReleases !== 2
    || !successfulOperation(completed, completedAttempts, completedAttempts)
    || !successfulOperation(probe, 1, 1)
    || completedTerminal < 0
    || probeAcquire <= completedTerminal) {
    fail(`$.concurrencyScenarios.${scenarioId}`, "must release the sole production permit after success, then prove leak-free reacquisition with a real successful same-runtime network probe");
  }
}

validateSuccessReacquisition("concurrency-immediate-success-release-and-probe", "op-immediate", "op-immediate-probe", 1);
validateSuccessReacquisition("concurrency-retry-success-release-and-probe", "op-retry-success", "op-retry-success-probe", 2);

const capacityDerivation = concurrencyDerivations.get("concurrency-exact-capacity-and-overflow");
if (capacityDerivation) {
  const rejected = capacityDerivation.operations.filter((operation) => operation.code === "OVERLOADED");
  const generatedKeyOperation = capacityDerivation.operations.find((operation) => operation.operationId === "op-a");
  const rejectedIndex = capacityDerivation.trace.findIndex((entry) => entry.includes(":terminal:OVERLOADED:"));
  const zeroPrework = rejected.length === 1 && rejected[0].sideEffects.permitRejects === 1
    && concurrencySideEffectCounters.filter((field) => field !== "permitRejects").every((field) => rejected[0].sideEffects[field] === 0);
  if (capacityDerivation.finalRuntime.maxObservedInFlight !== capacityDerivation.scenario.runtime.maxInFlight
    || capacityDerivation.scenario.runtime.maxInFlight !== 2 || generatedKeyOperation?.sideEffects.idempotencyGenerations !== 1
    || !zeroPrework || rejectedIndex < 0
    || capacityDerivation.finalRuntime.diagnostics.concurrencyOverloadRejections !== 1) {
    fail("$.concurrencyScenarios.concurrency-exact-capacity-and-overflow", "must reach exact capacity and reject N+1 before observer/configuration/credential/default/idempotency/serialization/network work");
  }
}

const retryDerivation = concurrencyDerivations.get("concurrency-retry-holds-one-permit");
if (retryDerivation) {
  const retryOperation = retryDerivation.operations.find((operation) => operation.operationId === "op-retry");
  const scheduleIndex = retryDerivation.trace.findIndex((entry) => entry.startsWith("operation:op-retry:retry.schedule:"));
  const rejectIndex = retryDerivation.trace.findIndex((entry) => entry.includes(":terminal:OVERLOADED:"));
  const releaseIndex = retryDerivation.trace.findIndex((entry) => entry.startsWith("operation:op-retry:permit.release:"));
  if (!retryOperation || retryOperation.attemptCount !== 2 || retryOperation.sideEffects.permitAcquires !== 1
    || retryOperation.sideEffects.permitReleases !== 1 || !(scheduleIndex >= 0 && rejectIndex > scheduleIndex && releaseIndex > rejectIndex)) {
    fail("$.concurrencyScenarios.concurrency-retry-holds-one-permit", "must retain one permit across deterministic backoff, reject a competing send, and release only after the retry completes");
  }
}

const successFailureDerivation = concurrencyDerivations.get("concurrency-success-failure-release-once");
if (successFailureDerivation) {
  for (const [operationId, code] of [["op-success", "OK"], ["op-failure", "HTTP_REJECTED"]]) {
    const operation = successFailureDerivation.operations.find((candidate) => candidate.operationId === operationId);
    if (!operation || operation.code !== code || operation.sideEffects.permitAcquires !== 1 || operation.sideEffects.permitReleases !== 1) {
      fail("$.concurrencyScenarios.concurrency-success-failure-release-once", `${operationId} must release exactly once through the shared terminal path`);
    }
  }
}

const cancellationDerivation = concurrencyDerivations.get("concurrency-cancel-releases-once");
if (cancellationDerivation) {
  const operation = cancellationDerivation.operations.find((candidate) => candidate.operationId === "op-cancel");
  if (!operation || operation.code !== "CANCELLED" || operation.sideEffects.permitAcquires !== 1 || operation.sideEffects.permitReleases !== 1) {
    fail("$.concurrencyScenarios.concurrency-cancel-releases-once", "cancellation must release the one acquired permit exactly once");
  }
}

const closeDerivation = concurrencyDerivations.get("concurrency-close-drains-and-releases-once");
if (closeDerivation) {
  const closed = closeDerivation.operations.filter((operation) => operation.code === "CLOSED");
  if (closeDerivation.finalRuntime.state !== "CLOSED" || closeDerivation.finalRuntime.inFlight !== 0 || closed.length !== 2
    || closeDerivation.finalRuntime.diagnostics.closed !== true
    || closed.some((operation) => operation.sideEffects.permitAcquires !== 1 || operation.sideEffects.permitReleases !== 1)) {
    fail("$.concurrencyScenarios.concurrency-close-drains-and-releases-once", "close must settle every admitted send CLOSED/NOT_SENT and release each acquired permit exactly once");
  }
}

const rejectAfterCloseDerivation = concurrencyDerivations.get("concurrency-reject-after-close");
if (rejectAfterCloseDerivation) {
  const operation = rejectAfterCloseDerivation.operations.find((candidate) => candidate.operationId === "op-after-close");
  if (rejectAfterCloseDerivation.finalRuntime.state !== "CLOSED" || !operation || operation.code !== "CLOSED" || operation.delivery !== "NOT_SENT"
    || concurrencySideEffectCounters.some((field) => operation.sideEffects[field] !== 0)) {
    fail("$.concurrencyScenarios.concurrency-reject-after-close", "a send after close must fail CLOSED/NOT_SENT before permit admission and every downstream side effect");
  }
}

const noWaiterDerivation = concurrencyDerivations.get("concurrency-no-queued-waiter");
if (noWaiterDerivation) {
  const overflow = noWaiterDerivation.operations.find((operation) => operation.operationId === "op-no-wait");
  const overflowTerminal = noWaiterDerivation.trace.findIndex((entry) => entry.startsWith("operation:op-no-wait:terminal:OVERLOADED:"));
  const heldRelease = noWaiterDerivation.trace.findIndex((entry) => entry.startsWith("operation:op-held:permit.release:"));
  if (!overflow || overflow.code !== "OVERLOADED" || overflowTerminal < 0 || heldRelease <= overflowTerminal
    || noWaiterDerivation.checkpoints.some((checkpoint) => checkpoint.queuedWaiters !== 0)) {
    fail("$.concurrencyScenarios.concurrency-no-queued-waiter", "overflow must be terminal before capacity is released and production diagnostics must prove no waiter exists");
  }
}

const weightedBytesDerivation = concurrencyDerivations.get("concurrency-weighted-byte-contention-and-reuse");
if (weightedBytesDerivation) {
  const retained = weightedBytesDerivation.checkpoints.find((checkpoint) => checkpoint.name === "bytes-retained");
  const rejected = weightedBytesDerivation.operations.find((operation) => operation.operationId === "op-buffer-overflow");
  const probe = weightedBytesDerivation.operations.find((operation) => operation.operationId === "op-buffer-probe");
  const rejectedPreWorkspaceSideEffects = [
    "observerStarts", "configurationSnapshots", "credentialResolutions", "defaultSnapshots",
    "idempotencyGenerations", "serializations", "networkAttempts", "byteAcquires",
    "byteReleases", "deadlineCallbacks", "terminalDispatches", "ghostWorkAttempts",
  ];
  if (weightedBytesDerivation.scenario.runtime.maxBufferedBytes !== document.constants.maxBufferedBytesRange[0]
    || retained?.currentBufferedBytes !== document.constants.portableOperationWorkspaceBytes
    || retained?.queuedWaiters !== 0
    || rejected?.code !== "OVERLOADED" || rejected?.delivery !== "NOT_SENT" || rejected?.sideEffects.byteRejects !== 1
    || rejectedPreWorkspaceSideEffects.some((field) => rejected?.sideEffects[field] !== 0)
    || rejected?.sideEffects.permitAcquires !== 1 || rejected?.sideEffects.permitReleases !== 1
    || rejected?.sideEffects.reservationAcquires !== 1 || rejected?.sideEffects.reservationReleases !== 1
    || probe?.code !== "OK" || probe?.sideEffects.byteAcquires !== 3 || probe?.sideEffects.byteReleases !== 3
    || weightedBytesDerivation.finalRuntime.currentBufferedBytes !== 0
    || weightedBytesDerivation.finalRuntime.maxObservedBufferedBytes !== document.constants.portableOperationWorkspaceBytes
    || weightedBytesDerivation.finalRuntime.totalByteRejects !== 1
    || weightedBytesDerivation.finalRuntime.diagnostics.requestByteOverloadRejections !== 1
    || weightedBytesDerivation.finalRuntime.diagnostics.responseByteOverloadRejections !== 0
    || runtimeDiagnosticsFields.filter((field) => !["inFlightOperations", "bufferedBytes", "requestByteOverloadRejections", "responseByteOverloadRejections", "closed"].includes(field)).some((field) => weightedBytesDerivation.finalRuntime.diagnostics[field] !== 0)
    || weightedBytesDerivation.finalRuntime.totalByteAcquires !== 6
    || weightedBytesDerivation.finalRuntime.totalByteReleases !== 6) {
    fail("$.concurrencyScenarios.concurrency-weighted-byte-contention-and-reuse", "must prove exact nonblocking complete-set contention, zero waiters, distinct parser-scratch admission, release-once counters, and full reuse");
  }
}

const atomicDiagnosticsDerivation = concurrencyDerivations.get("concurrency-atomic-runtime-diagnostics-snapshots");
if (atomicDiagnosticsDerivation) {
  const snapshots = atomicDiagnosticsDerivation.checkpoints.map((checkpoint) => checkpoint.diagnostics);
  const monotonicCounters = runtimeDiagnosticsFields.filter((field) => !["inFlightOperations", "bufferedBytes", "closed"].includes(field));
  const countersNeverDecrease = snapshots.every((snapshot, index) => index === 0
    || monotonicCounters.every((field) => snapshot[field] >= snapshots[index - 1][field]));
  const noImpossibleClosedEpoch = snapshots.every((snapshot) => !snapshot.closed
    || (snapshot.inFlightOperations === 0 && snapshot.bufferedBytes === 0));
  if (concurrencyProtocol.diagnosticStressIterations !== 1000 || !countersNeverDecrease || !noImpossibleClosedEpoch
    || snapshots.at(-1)?.closed !== true || atomicDiagnosticsDerivation.finalRuntime.totalReservationAcquires !== atomicDiagnosticsDerivation.finalRuntime.totalReservationReleases) {
    fail("$.concurrencyScenarios.concurrency-atomic-runtime-diagnostics-snapshots", "must run the exact schedule 1000 times and reject torn epochs, decreasing counters, impossible closed/resource combinations, or leaked settlement reservations");
  }
}

const counterSaturationDerivation = concurrencyDerivations.get("concurrency-runtime-diagnostics-counter-saturates-at-max-safe-integer");
if (counterSaturationDerivation) {
  const seeded = counterSaturationDerivation.checkpoints.find((checkpoint) => checkpoint.name === "counter-seeded");
  const reached = counterSaturationDerivation.checkpoints.find((checkpoint) => checkpoint.name === "counter-reached-maximum");
  const saturated = counterSaturationDerivation.checkpoints.find((checkpoint) => checkpoint.name === "counter-remained-saturated");
  const probe = counterSaturationDerivation.operations.find((operation) => operation.operationId === "op-counter-probe");
  if (seeded?.diagnostics.concurrencyOverloadRejections !== document.constants.runtimeDiagnosticsCounterMax - 1
    || reached?.diagnostics.concurrencyOverloadRejections !== document.constants.runtimeDiagnosticsCounterMax
    || saturated?.diagnostics.concurrencyOverloadRejections !== document.constants.runtimeDiagnosticsCounterMax
    || counterSaturationDerivation.finalRuntime.diagnostics.concurrencyOverloadRejections !== document.constants.runtimeDiagnosticsCounterMax
    || counterSaturationDerivation.finalRuntime.totalPermitRejects !== 2
    || probe?.code !== "OK" || probe.delivery !== "ACCEPTED") {
    fail("$.concurrencyScenarios.concurrency-runtime-diagnostics-counter-saturates-at-max-safe-integer", "must prove MAX_COUNTER_VALUE-1, MAX_COUNTER_VALUE, saturated MAX_COUNTER_VALUE, and unaffected post-release operation success");
  }
}

const starvedDeadlineDerivation = concurrencyDerivations.get("concurrency-starved-workers-independent-deadline-dispatch");
if (starvedDeadlineDerivation) {
  const operation = starvedDeadlineDerivation.operations.find((candidate) => candidate.operationId === "op-starved");
  const queued = starvedDeadlineDerivation.checkpoints.find((checkpoint) => checkpoint.name === "worker-task-accepted");
  const done = starvedDeadlineDerivation.checkpoints.find((checkpoint) => checkpoint.name === "deadline-dispatched");
  const teardown = starvedDeadlineDerivation.checkpoints.find((checkpoint) => checkpoint.name === "workers-released-clean");
  const timerIndex = starvedDeadlineDerivation.trace.indexOf("operation:op-starved:deadline.fire:executor=independent-timer");
  const dispatcherIndex = starvedDeadlineDerivation.trace.indexOf("operation:op-starved:terminal.dispatch:executor=independent-terminal");
  const downstream = ["observerStarts", "configurationSnapshots", "credentialResolutions", "defaultSnapshots", "idempotencyGenerations", "serializations", "networkAttempts", "byteAcquires", "byteRejects", "byteReleases", "ghostWorkAttempts"];
  if (starvedDeadlineDerivation.scenario.runtime.workerThreads !== 2
    || starvedDeadlineDerivation.scenario.runtime.executorMode !== "injected"
    || starvedDeadlineDerivation.scenario.runtime.executorOwnership !== "BORROWED"
    || starvedDeadlineDerivation.scenario.runtime.executorQueueCapacity < 1
    || queued?.queuedWorkerTasks !== 1 || queued?.blockedWorkers !== 2
    || done?.queuedWorkerTasks !== 0 || done?.blockedWorkers !== 2
    || teardown?.queuedWorkerTasks !== 0 || teardown?.blockedWorkers !== 0 || teardown?.ghostWorkAttempts !== 0
    || operation?.code !== "OPERATION_DEADLINE" || operation?.delivery !== "NOT_SENT" || operation?.attemptCount !== 0
    || operation?.sideEffects.permitAcquires !== 1 || operation?.sideEffects.permitReleases !== 1
    || operation?.sideEffects.deadlineCallbacks !== 1 || operation?.sideEffects.terminalDispatches !== 1
    || downstream.some((field) => operation?.sideEffects[field] !== 0)
    || timerIndex < 0 || dispatcherIndex !== timerIndex + 1
    || starvedDeadlineDerivation.finalRuntime.deadlineCallbacks !== 1
    || starvedDeadlineDerivation.finalRuntime.terminalDispatches !== 1
    || starvedDeadlineDerivation.finalRuntime.ghostWorkAttempts !== 0
    || runtimeDiagnosticsFields.filter((field) => !["inFlightOperations", "bufferedBytes", "closed"].includes(field)).some((field) => starvedDeadlineDerivation.finalRuntime.diagnostics[field] !== 0)
    || starvedDeadlineDerivation.finalRuntime.blockedWorkers !== 0 || starvedDeadlineDerivation.finalRuntime.state !== "CLOSED"
    || starvedDeadlineDerivation.finalRuntime.diagnostics.closed !== true
    || starvedDeadlineDerivation.trace.findIndex((entry) => entry === "runtime:workers.released:count=2") <= dispatcherIndex
    || starvedDeadlineDerivation.trace.findIndex((entry) => entry === "runtime:borrowed-executor.shutdown-by-runner") <= starvedDeadlineDerivation.trace.findIndex((entry) => entry.startsWith("checkpoint:runtime-closed-borrowed-alive:"))
    || starvedDeadlineDerivation.trace.at(-1) !== "runtime:borrowed-executor.terminated") {
    fail("$.concurrencyScenarios.concurrency-starved-workers-independent-deadline-dispatch", "F94 must settle before worker release, then tear down blockers with no queued task, late downstream work, or ghost work");
  }
}

function requireReleasedTerminalAndSuccessfulProbe(scenarioId, terminalOperationId, code, delivery, attempts, probeOperationId) {
  const derivation = concurrencyDerivations.get(scenarioId);
  if (!derivation) return;
  const terminal = derivation.operations.find((operation) => operation.operationId === terminalOperationId);
  const probe = derivation.operations.find((operation) => operation.operationId === probeOperationId);
  const releaseIndex = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${terminalOperationId}:permit.release:`));
  const primaryIndex = derivation.trace.findIndex((entry) => entry === `operation:${terminalOperationId}:primary.complete:${code}:${delivery}`);
  const outcomeIndex = derivation.trace.findIndex((entry) => entry === `operation:${terminalOperationId}:outcome.complete:${code}:${delivery}`);
  const reservationReleaseIndex = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${terminalOperationId}:reservation.release:`));
  const terminalIndex = derivation.trace.findIndex((entry) => entry === `operation:${terminalOperationId}:terminal:${code}:${delivery}:attempts=${attempts}`);
  const probeAcquireIndex = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${probeOperationId}:permit.acquire:`));
  const probeReleaseIndex = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${probeOperationId}:permit.release:`));
  const probePrimaryIndex = derivation.trace.findIndex((entry) => entry === `operation:${probeOperationId}:primary.complete:OK:ACCEPTED`);
  const probeOutcomeIndex = derivation.trace.findIndex((entry) => entry === `operation:${probeOperationId}:outcome.complete:OK:ACCEPTED`);
  const probeReservationReleaseIndex = derivation.trace.findIndex((entry) => entry.startsWith(`operation:${probeOperationId}:reservation.release:`));
  const probeTerminalIndex = derivation.trace.findIndex((entry) => entry === `operation:${probeOperationId}:terminal:OK:ACCEPTED:attempts=1`);
  const terminalShape = terminal?.code === code && terminal?.delivery === delivery && terminal?.attemptCount === attempts
    && terminal.sideEffects.permitAcquires === 1 && terminal.sideEffects.permitReleases === 1;
  const probeShape = probe?.code === "OK" && probe?.delivery === "ACCEPTED" && probe?.attemptCount === 1
    && probe.sideEffects.permitAcquires === 1 && probe.sideEffects.permitReleases === 1 && probe.sideEffects.networkAttempts === 1;
  const orderedTail = primaryIndex === releaseIndex + 1 && outcomeIndex === primaryIndex + 1
    && reservationReleaseIndex === outcomeIndex + 1 && terminalIndex === reservationReleaseIndex + 1;
  const orderedProbeTail = probePrimaryIndex === probeReleaseIndex + 1 && probeOutcomeIndex === probePrimaryIndex + 1
    && probeReservationReleaseIndex === probeOutcomeIndex + 1 && probeTerminalIndex === probeReservationReleaseIndex + 1;
  if (!terminalShape || !probeShape || !orderedTail
    || probeAcquireIndex <= terminalIndex || probeReleaseIndex <= probeAcquireIndex || !orderedProbeTail) {
    fail(`$.concurrencyScenarios.${scenarioId}`, `${terminalOperationId} must release public capacity, publish primary before outcome, release its settlement reservation, then let ${probeOperationId} acquire, perform one network attempt, and settle with the same per-operation tail order`);
  }
}

for (const requirement of [
  ["concurrency-exact-capacity-and-overflow", "op-b", "OK", "ACCEPTED", 1, "op-capacity-probe"],
  ["concurrency-success-failure-release-once", "op-failure", "HTTP_REJECTED", "REJECTED", 1, "op-http-probe"],
  ["concurrency-success-failure-release-once", "op-success", "OK", "ACCEPTED", 1, "op-success-probe"],
  ["concurrency-cancel-releases-once", "op-cancel", "CANCELLED", "NOT_SENT", 1, "op-cancel-probe"],
  ["concurrency-server-failure-release-and-probe", "op-server-failure", "SERVER_FAILURE", "POSSIBLY_SENT", 2, "op-server-probe"],
  ["concurrency-protocol-failure-release-and-probe", "op-protocol-failure", "RESPONSE_PROTOCOL", "POSSIBLY_SENT", 2, "op-protocol-probe"],
]) requireReleasedTerminalAndSuccessfulProbe(...requirement);

const casesById = new Map((document.cases || []).map((testCase) => [testCase.caseId, testCase]));

function matchingReleasedHold(testCase, phase) {
  return (testCase.script || []).some((action, holdIndex) => action.action === "hold" && action.phase === phase
    && testCase.script.some((candidate, releaseIndex) => candidate.action === "release" && candidate.barrier === action.barrier && releaseIndex > holdIndex));
}

function requireSemanticCoverage(name, predicate, description) {
  if (!(document.cases || []).some(predicate)) fail(`$.coverage.${name}`, description);
}

requireSemanticCoverage(
  "released-response-headers",
  (testCase) => testCase.expected?.delivery === "ACCEPTED" && matchingReleasedHold(testCase, "responseHeaders")
    && testCase.expected.attemptTrace.some((trace) => /:response:2[0-9]{2}@/u.test(trace))
    && testCase.expected.attemptTrace.some((trace) => /:end:accepted@/u.test(trace)),
  "requires a successful responseHeaders hold causally matched by a later runner release",
);
requireSemanticCoverage(
  "released-response-body",
  (testCase) => testCase.expected?.delivery === "ACCEPTED" && matchingReleasedHold(testCase, "responseBody")
    && testCase.script.some((action) => action.action === "hold" && action.phase === "responseBody" && Number.isInteger(action.releaseAt))
    && testCase.expected.attemptTrace.some((trace) => /:end:accepted@/u.test(trace)),
  "requires a successful byte-threshold responseBody hold causally matched by a later runner release",
);
requireSemanticCoverage(
  "private-capture",
  (testCase) => testCase.script.some((action) => action.action === "captureRequestMetadata"
    && testCase.expected.fixtureTrace.includes(`fixture:attempt:${action.attempt}:capture-private:clean`))
    && JSON.stringify(document.fixtureProtocol.captureAllowlist) === JSON.stringify(requiredCaptureAllowlist)
    && JSON.stringify(document.fixtureProtocol.captureDenylist) === JSON.stringify(requiredCaptureDenylist),
  "requires captureRequestMetadata with capture-private:clean and the closed private allow/deny policy",
);
for (const phase of ["afterRequestBytes"]) {
  requireSemanticCoverage(
    `disconnect-${phase}`,
    (testCase) => testCase.script.some((action) => action.action === "disconnect" && action.phase === phase)
      && testCase.expected.attemptTrace.some((trace) => trace.includes(phase === "afterRequestBytes" ? ":commit@" : ":end:io@"))
      && testCase.expected.error?.code === "IO",
    `requires a terminal disconnect at ${phase} with matching I/O and commitment semantics`,
  );
}
requireSemanticCoverage(
  "ordered-multi-attempt-responses",
  (testCase) => {
    const responses = testCase.script.filter((action) => ["respond", "respondChunks", "respondEchoSentinels"].includes(action.action));
    return responses.length >= 2 && responses.every((action, index) => index === 0 || action.attempt > responses[index - 1].attempt)
      && testCase.expected.scheduleTrace.length > 0;
  },
  "requires ordered response recipes across multiple attempts with an explicit retry schedule",
);
requireSemanticCoverage(
  "oversized-chunked-response",
  (testCase) => testCase.script.some((action) => action.action === "respondChunks")
    && testCase.expected.error?.code === "RESPONSE_TOO_LARGE"
    && testCase.expected.sideEffectTrace.includes("response.cancel")
    && testCase.expected.byteTrace.some((trace) => /^response-(?:wire|decoded):[0-9]+:rejected$/u.test(trace))
    && testCase.expected.fixtureTrace.some((trace) => /:response-cancelled:[0-9]+$/u.test(trace)),
  "requires chunked response limit rejection with exact upstream cancellation evidence",
);
requireSemanticCoverage(
  "sentinel-echo-redaction",
  (testCase) => testCase.script.some((action) => action.action === "respondEchoSentinels" && (action.status < 200 || action.status >= 300))
    && ["render", "logs", "observer", "metric-tags", "spans"].every((surface) => testCase.expected.redactionTrace.includes(`scan:${surface}:clean`)),
  "requires a non-2xx sentinel echo response with every public redaction surface scanned",
);
requireSemanticCoverage(
  "malformed-2xx-retry",
  (testCase) => testCase.script.some((action, index) => action.action === "respond" && action.status >= 200 && action.status < 300
    && action.body?.asset === "malformed"
    && testCase.script.slice(index + 1).some((candidate) => Number.isInteger(candidate.attempt) && candidate.attempt > action.attempt))
    && testCase.expected.scheduleTrace.length > 0,
  "requires a malformed 2xx response followed by a scheduled retry attempt",
);
requireSemanticCoverage(
  "incomplete-2xx-retry",
  (testCase) => testCase.script.some((action, index) => action.action === "respondChunks" && action.status >= 200 && action.status < 300 && action.complete === false
    && testCase.script.slice(index + 1).some((candidate) => Number.isInteger(candidate.attempt) && candidate.attempt > action.attempt))
    && testCase.expected.scheduleTrace.length > 0,
  "requires an incomplete 2xx response followed by a scheduled retry attempt",
);
requireSemanticCoverage(
  "idempotency-reuse",
  (testCase) => {
    const attempts = testCase.expected.attemptTrace.filter((trace) => /:start@/u.test(trace)).length;
    const capturedKeys = testCase.expected.fixtureTrace.flatMap((trace) => trace.match(/^fixture:attempt:[1-9][0-9]*:idempotency:(.+)$/u)?.[1] || []);
    return attempts >= 2 && capturedKeys.length === attempts && new Set(capturedKeys).size === 1;
  },
  "requires one caller/generated idempotency key captured byte-exact across every retry attempt",
);

function retryAfterCoverageVariant(testCase) {
  const response = (testCase.script || []).find((action) => ["respond", "respondChunks"].includes(action.action)
    && headerValues(action.headers, "retry-after").length > 0);
  if (!response) return null;
  const values = headerValues(response.headers, "retry-after");
  if (values.length > 1) return values.every((value) => value === values[0]) ? "duplicate-identical" : "duplicate-conflicting";
  const value = values[0];
  if (typeof value !== "string") return "generic-invalid";
  if (/^-[0-9]+$/u.test(value)) return "negative-numeric";
  if (/^[0-9]+$/u.test(value)) {
    let milliseconds;
    try { milliseconds = BigInt(value) * 1000n; } catch { return "overflow"; }
    if (milliseconds > ((1n << 63n) - 1n)) return "overflow";
    const maximum = BigInt(testCase.options?.retryAfterMaxMs ?? document.constants.retryAfterMaxMs);
    if (milliseconds > maximum) return "over-cap";
    const operationBudget = BigInt(testCase.options?.operationTimeoutMs ?? document.constants.operationTimeoutMs);
    if (milliseconds > operationBudget) return "beyond-operation-budget";
    return "delta-seconds";
  }
  const absolute = parseImfFixdate(value);
  if (!Number.isSafeInteger(absolute)) return "generic-invalid";
  const wallStart = mergeObjects(document.inputProfiles?.[testCase.input?.profile] || {}, testCase.input || {}).clock?.retryAfterWallUnixMs;
  const priorWallAdvance = (testCase.script || []).slice(0, testCase.script.indexOf(response))
    .filter((action) => action.action === "advanceWallTime")
    .reduce((sum, action) => saturatingAdd(sum, action.byMs), 0);
  const wall = saturatingAdd(wallStart, priorWallAdvance);
  if (!Number.isSafeInteger(wall)) return "generic-invalid";
  if (absolute < wall) return "past-date";
  if (absolute === wall) return "present-date";
  return "future-imf-fixdate";
}

for (const variant of [
  "delta-seconds",
  "future-imf-fixdate",
  "generic-invalid",
  "negative-numeric",
  "duplicate-identical",
  "duplicate-conflicting",
  "past-date",
  "present-date",
  "overflow",
  "over-cap",
  "beyond-operation-budget",
]) {
  requireSemanticCoverage(
    `retry-after-${variant}`,
    (testCase) => retryAfterCoverageVariant(testCase) === variant && testCase.expected.scheduleTrace.some((trace) => trace.startsWith("retry:1:")),
    `requires the distinct Retry-After ${variant} semantics with a deterministic retry schedule`,
  );
}
requireSemanticCoverage(
  "retry-after-mixed-case-field-name",
  (testCase) => (testCase.script || []).some((action) => Object.keys(action.headers || {})
    .some((name) => name.toLowerCase() === "retry-after" && name !== "retry-after"))
    && testCase.expected.scheduleTrace.some((trace) => /:retry-after=[0-9]+@/u.test(trace)),
  "requires a mixed-case Retry-After field name with deterministic parsed-delay evidence",
);

function contentEncodingCoverageVariant(testCase) {
  const response = (testCase.script || []).find((action) => ["respond", "respondChunks"].includes(action.action)
    && Object.keys(action.headers || {}).some((name) => name.toLowerCase() === "content-encoding"));
  if (!response) return null;
  const fields = Object.entries(response.headers || {}).filter(([name]) => name.toLowerCase() === "content-encoding");
  const flattened = fields.flatMap(([, value]) => Array.isArray(value) ? value : [value]);
  if (fields.length > 1) return flattened.every((value) => value === flattened[0]) ? "duplicate-field-identical" : "duplicate-field-conflicting";
  const raw = fields[0][1];
  if (Array.isArray(raw) && raw.length > 1) return raw.every((value) => value === raw[0]) ? "multi-value-identical" : "multi-value-conflicting";
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value === "string" && value.includes(",")) return "comma-stacked";
  if (classifyContentEncoding(response.headers).valid && classifyContentEncoding(response.headers).encoding === "gzip") {
    return fields[0][0] === "content-encoding" && value === "gzip" ? "canonical-gzip" : "mixed-case-gzip";
  }
  return "other";
}

for (const [variant, accepted] of [
  ["canonical-gzip", true],
  ["mixed-case-gzip", true],
  ["duplicate-field-identical", false],
  ["duplicate-field-conflicting", false],
  ["multi-value-identical", false],
  ["multi-value-conflicting", false],
  ["comma-stacked", false],
]) {
  requireSemanticCoverage(
    `content-encoding-${variant}`,
    (testCase) => contentEncodingCoverageVariant(testCase) === variant
      && (accepted
        ? testCase.expected.delivery === "ACCEPTED" && testCase.expected.error === null
        : testCase.expected.error?.code === "RESPONSE_PROTOCOL"
          && testCase.expected.error.retryable === false
          && testCase.expected.byteTrace.includes("response-wire:0:cancelled")
          && testCase.expected.byteTrace.includes("response-decoded:0:cancelled")),
    `requires executable ${variant} Content-Encoding semantics with the complete terminal outcome`,
  );
}

for (const outcome of ["retry", "cancellation"]) {
  requireSemanticCoverage(
    `observer-context-${outcome}`,
    (testCase) => typeof testCase.input?.observerContext === "string"
      && Array.isArray(testCase.expected?.contextTrace)
      && testCase.expected.contextTrace.some((trace) => trace.startsWith("operation.capture|"))
      && testCase.expected.contextTrace.at(-1)?.startsWith("caller.restore|")
      && (outcome === "retry"
        ? testCase.expected.observerEmissionTrace.some((trace) => trace.startsWith("retry.delay|"))
        : testCase.expected.observerEmissionTrace.some((trace) => trace.startsWith("operation.cancel|"))),
    `requires private observer-context capture, callback propagation, and restoration across ${outcome}`,
  );
}

function isProductionHttpFixture(testCase) {
  return testCase.executionMode === "production-http-fixture"
    && testCase.options?.baseUrl === document.fixtureProtocol.fixtureBaseUrlToken
    && testCase.options?.transportMode !== "custom";
}

for (const [coverage, predicate] of [
  ["delayed-headers", (testCase) => matchingReleasedHold(testCase, "responseHeaders") && testCase.expected.delivery === "ACCEPTED"],
  ["delayed-body", (testCase) => matchingReleasedHold(testCase, "responseBody") && testCase.expected.delivery === "ACCEPTED"],
  ["disconnect-after-request", (testCase) => testCase.script.some((action) => action.action === "disconnect" && action.phase === "afterRequestBytes")],
  ["chunk-limit-cancellation", (testCase) => testCase.script.some((action) => action.action === "respondChunks") && testCase.expected.error?.code === "RESPONSE_TOO_LARGE"],
  ["malformed-2xx-retry", (testCase) => testCase.script.some((action) => action.action === "respond" && action.status >= 200 && action.status < 300 && action.body?.asset === "malformed") && testCase.expected.scheduleTrace.length > 0],
  ["incomplete-2xx-retry", (testCase) => testCase.script.some((action) => action.action === "respondChunks" && action.status >= 200 && action.status < 300 && action.complete === false) && testCase.expected.scheduleTrace.length > 0],
  ["sentinel-echo", (testCase) => testCase.script.some((action) => action.action === "respondEchoSentinels") && testCase.expected.redactionTrace.includes("scan:spans:clean")],
  ["private-capture", (testCase) => testCase.script.some((action) => action.action === "captureRequestMetadata") && testCase.expected.fixtureTrace.some((trace) => trace.endsWith(":capture-private:clean"))],
  ["idempotency-reuse", (testCase) => {
    const keys = testCase.expected.fixtureTrace.flatMap((trace) => trace.match(/:idempotency:(.+)$/u)?.[1] || []);
    return testCase.expected.attemptTrace.filter((trace) => /:start@/u.test(trace)).length >= 2 && keys.length >= 2 && new Set(keys).size === 1;
  }],
  ["mixed-case-gzip", (testCase) => testCase.script.some((action) => headerValues(action.headers, "content-encoding")[0] === "gzip"
    && Object.keys(action.headers || {}).includes("Content-Encoding")) && testCase.expected.delivery === "ACCEPTED"],
  ["duplicate-identical-content-encoding", (testCase) => testCase.script.some((action) => {
    const values = headerValues(action.headers, "content-encoding");
    return values.length > 1 && values.every((value) => value === values[0]);
  }) && testCase.expected.error?.code === "RESPONSE_PROTOCOL"],
  ["duplicate-conflicting-content-encoding", (testCase) => testCase.script.some((action) => {
    const values = headerValues(action.headers, "content-encoding");
    return values.length > 1 && new Set(values).size > 1;
  }) && testCase.expected.error?.code === "RESPONSE_PROTOCOL"],
  ["combined-content-encoding", (testCase) => testCase.script.some((action) => headerValues(action.headers, "content-encoding").some((value) => typeof value === "string" && value.includes(",")))
    && testCase.expected.error?.code === "RESPONSE_PROTOCOL"],
]) {
  requireSemanticCoverage(
    `production-http-${coverage}`,
    (testCase) => isProductionHttpFixture(testCase) && predicate(testCase),
    `requires ${coverage} coverage through the real default production HTTP transport and loopback fixture`,
  );
}

const commitmentProbeCase = requireCase("commitment-failure-before-request-body-demand");
if (commitmentProbeCase) {
  const failure = commitmentProbeCase.script.find((action) => action.action === "failConnection");
  if (commitmentProbeCase.executionMode !== "commitment-probe-seam"
    || failure?.attempt !== 1 || failure?.phase !== "beforeRequestBytes" || failure?.kind !== "io"
    || Object.hasOwn(commitmentProbeCase.options || {}, "baseUrl")
    || JSON.stringify(commitmentProbeCase.expected.commitTrace) !== JSON.stringify(["attempt:1:NOT_SENT@0"])
    || JSON.stringify(commitmentProbeCase.expected.fixtureTrace) !== JSON.stringify([
      "fixture:attempt:1:failure:io",
      "fixture:attempt:1:body-subscriptions:0:body-demands:0",
    ])
    || commitmentProbeCase.expected.error?.code !== "IO"
    || commitmentProbeCase.expected.error?.delivery !== "NOT_SENT"
    || commitmentProbeCase.expected.delivery !== "NOT_SENT") {
    fail("$.cases.commitment-failure-before-request-body-demand", "must exercise the unmodified production commitment tracker through the low-level probe seam and prove zero body-publisher subscription/demand before IO/NOT_SENT");
  }
}

for (const [caseId, expectedWireOutcome, expectedDecodedOutcome] of [
  ["response-compressed-cap-plus-one-cancels", "response-wire:1048577:rejected", "response-decoded:0:rejected"],
  ["response-decompressed-cap-plus-one-cancels", "response-wire:1048680:accepted", "response-decoded:1048577:rejected"],
  ["response-expansion-ratio-plus-one-rejected", "response-wire:142:accepted", "response-decoded:14201:rejected"],
]) {
  const testCase = requireCase(caseId);
  const response = testCase?.script.find((action) => ["respond", "respondChunks"].includes(action.action));
  if (testCase && (classifyContentEncoding(response?.headers).encoding !== "gzip"
    || testCase.expected.error?.code !== "RESPONSE_PROTOCOL"
    || testCase.expected.error?.retryable !== false
    || testCase.expected.error?.delivery !== "POSSIBLY_SENT"
    || !testCase.expected.sideEffectTrace.includes("response.cancel")
    || !testCase.expected.byteTrace.includes(expectedWireOutcome)
    || !testCase.expected.byteTrace.includes(expectedDecodedOutcome))) {
    fail(`$.cases.${caseId}`, "gzip compressed/decompressed/expansion limit breaches must cancel immediately as non-retryable RESPONSE_PROTOCOL/POSSIBLY_SENT with exact byte counters");
  }
}

const identityLimitCase = requireCase("response-identity-cap-plus-one-cancels");
if (identityLimitCase) {
  const response = identityLimitCase.script.find((action) => ["respond", "respondChunks"].includes(action.action));
  if (!isProductionHttpFixture(identityLimitCase)
    || classifyContentEncoding(response?.headers).encoding !== "identity"
    || identityLimitCase.expected.error?.code !== "RESPONSE_TOO_LARGE"
    || identityLimitCase.expected.error?.retryable !== false
    || !identityLimitCase.expected.sideEffectTrace.includes("response.cancel")
    || !identityLimitCase.expected.byteTrace.includes("response-wire:1048577:rejected")) {
    fail("$.cases.response-identity-cap-plus-one-cancels", "identity wire-cap overflow must retain RESPONSE_TOO_LARGE and prove production HTTP cancellation at the first rejected octet");
  }
}

for (const action of actions) {
  requireSemanticCoverage(`action-${action}`, (testCase) => testCase.script.some((entry) => entry.action === action), `requires fixture/runner action coverage for ${action}`);
}
for (const phase of phases) {
  requireSemanticCoverage(`phase-${phase}`, (testCase) => testCase.script.some((entry) => entry.phase === phase), `requires fixture phase coverage for ${phase}`);
}

function requireCase(caseId) {
  const testCase = casesById.get(caseId);
  if (!testCase) fail("$.cases", `missing required boundary case ${caseId}`);
  return testCase;
}

function requireOption(caseId, option, value) {
  const testCase = requireCase(caseId);
  if (testCase && testCase.options?.[option] !== value) {
    fail(`$.cases.${caseId}.options.${option}`, `must equal ${value}`);
  }
  return testCase;
}

const requiredEnterpriseNetworkVectors = [
  ["network-custom-ca-success", "trusted-custom-ca", "trustedBaseUrl", 1, 1, 1, "trusted.requests=1", null],
  ["network-default-trust-untrusted", "untrusted-ca-failure", "trustedBaseUrl", 1, 1, 0, "trusted.requests=0", "TLS_UNTRUSTED"],
  ["network-hostname-mismatch", "hostname-mismatch", "hostnameMismatchBaseUrl", 1, 1, 0, "hostnameMismatch.requests=0", "TLS_HOSTNAME_MISMATCH"],
  ["network-tls12-negotiation-success", "tls-1.2-only", "tls12OnlyBaseUrl", 1, 1, 1, "tls12Only.requests=1", null],
  ["network-mtls-success", "mtls-required", "mtlsBaseUrl", 1, 1, 1, "mtls.authorizedRequests=1", null],
  ["network-mtls-required-failure", "mtls-required", "mtlsBaseUrl", 1, 1, 0, "mtls.requests=0", "TLS_NEGOTIATION"],
  ["network-connect-proxy-success", "http-connect-proxy", "trustedBaseUrl", 1, 1, 1, "proxy.tunnels=1;proxy.forwardRequests=0", null],
  ["network-authenticated-proxy-success", "authenticated-http-connect-proxy", "trustedBaseUrl", 1, 1, 1, "authenticatedProxy.tunnels=1;authenticatedProxy.authFailures=0", null],
  ["network-proxy-authentication-required", "proxy-407", "trustedBaseUrl", 1, 1, 0, "authenticatedProxy.authFailures=1;trusted.requests=0", "PROXY_AUTH_REQUIRED"],
  ["network-default-direct-ignores-ambient-proxy", "direct-trap-counter", "trustedBaseUrl", 1, 1, 1, "directTrap.hits=0", null],
  ["network-no-proxy-trap-remains-zero", "no-proxy-trap-counter", "trustedBaseUrl", 1, 1, 1, "noProxyTrap.hits=0", null],
  ["network-reset-before-request-bytes", "reset-before-request-bytes", "resetBeforeRequestBaseUrl", 1, 1, 0, "resetBeforeRequest.resets=1;resetBeforeRequest.requestBytes=0", "CONNECTION_RESET"],
  ["network-reset-after-exact-request-bytes", "reset-after-exact-body-bytes", "resetAfterRequestBaseUrl", 1, 1, 1, "resetAfterRequest.firstBodyBytes=8;resetAfterRequest.resets=1", "CONNECTION_RESET"],
  ["network-response-header-barrier", "explicit-response-header-barrier", "heldResponseBaseUrl", 1, 1, 1, "barriers.responseHeaders.releases=1", null],
  ["network-declared-length-preallocation-rejected", "declared-length-preallocation-trap", "preallocationTrapBaseUrl", 1, 1, 1, "preallocationTrap.requests=1;preallocationTrap.prefixBytesPublished=1;preallocationTrap.clientCancellations=1", null],
  ["network-transparent-decompression-cap-rejected", "transparent-decompression-trap", "transparentDecompressionTrapBaseUrl", 1, 1, 1, "transparentDecompressionTrap.requests=1;transparentDecompressionTrap.rawBytesPublished=limits.decompressionTrapRawBytes;transparentDecompressionTrap.decompressedBytes=1048577", null],
  ["network-stale-pooled-h1-one-shot-retry", "stale-pooled-h1-replay", "staleH1ReplayBaseUrl", 1, 2, 2, "staleH1.primeRequests=1;staleH1.wireRequests=2;staleH1.wireBodyPublications=2;staleH1.reusedRequests=1;staleH1.faults=1;staleH1.successResponses=1", null],
  ["network-alpn-h2-preferred", "alpn-h2-with-http1-fallback", "http2BaseUrl", 1, 1, 1, "http2.sessions=1", null],
  ["network-h2-multiplex-two-operations", "h2-multiplexing", "http2HeldBaseUrl", 2, 2, 2, "http2.maxConcurrentStreams=2;http2.sameSessionOverlapCount=1;http2.sessions=1;http2.singleSessionStreamCount=2;http2.streams=2", null],
  ["network-h2-rst-stream", "h2-rst-stream", "http2ResetBaseUrl", 3, 3, 3, "http2.collateralStreamFailures=0;http2.peerSuccesses=1;http2.postIsolationProbeSuccesses=1;http2.resetStreams=1;http2.sessions=1;http2.singleSessionStreamCount=3;http2.streams=3", "CONNECTION_RESET"],
  ["network-h2-client-cancellation", "h2-client-cancellation", "http2HeldBaseUrl", 3, 3, 3, "http2.cancelledStreams=1;http2.collateralStreamFailures=0;http2.peerSuccesses=1;http2.postIsolationProbeSuccesses=1;http2.sessions=1;http2.singleSessionStreamCount=3;http2.streams=3", null],
  ["network-h2-goaway-metadata", "h2-goaway-metadata", "http2GoawayBaseUrl", 2, 3, 3, "http2.goaway.acceptedStreamId=1;http2.goaway.lastStreamId=1;http2.goaway.retrySessionId=2;http2.goaway.retryStreamId=1;http2.goaway.retrySuccesses=1;http2.goaway.sent=1;http2.goaway.sessionId=1;http2.goaway.unprocessedSessionId=1;http2.goaway.unprocessedStreamId=3;http2.sessions=2", null],
  ["network-http1-fallback", "alpn-h2-with-http1-fallback", "http2FallbackBaseUrl", 1, 1, 1, "http1Only.connections=1;http1Only.negotiatedHttp1=1;http1Only.requests=1;http2.sessions=0", null],
];
const requiredSpecialNetworkSetup = {
  "direct-trap-counter": ["reset", "install-all-target-ambient-proxy-sources-before-client-construction", "clear-all-no-proxy-bypasses"],
  "no-proxy-trap-counter": ["reset", "install-all-target-ambient-proxy-sources-before-client-construction", "set-all-target-no-proxy-bypasses-to-origin"],
  "stale-pooled-h1-replay": ["reset", "prime-stale-h1-connection", "arm-stale-h1-connection"],
  "h2-multiplexing": ["reset", "start-two-operations-before-release", "await-same-session-overlap", "release-http2Streams"],
  "h2-rst-stream": ["reset", "start-reset-peer-and-probe-on-one-session", "await-reset-and-peer-overlap", "release-peer", "probe-same-session-after-reset"],
  "h2-client-cancellation": ["reset", "start-cancel-peer-and-probe-on-one-session", "await-cancel-and-peer-overlap", "cancel-target-with-rst-cancel", "release-peer", "probe-same-session-after-cancel"],
  "h2-goaway-metadata": ["reset", "start-accepted-stream-1", "receive-goaway-last-stream-1", "start-operation-above-last-stream", "retry-unprocessed-operation-on-new-session"],
  "network-http1-fallback": ["reset", "retain-production-h2-preference", "connect-dedicated-http1-only-endpoint"],
};
const requiredSpecialNetworkOutcomes = {
  "h2-multiplexing": [
    "op1:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=1",
    "op2:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=3",
  ],
  "h2-rst-stream": [
    "op1:primary=structured-error:code=IO:delivery=POSSIBLY_SENT:attempts=1:publications=1:session=1:stream=1",
    "op2:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=3",
    "op3:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=5",
  ],
  "h2-client-cancellation": [
    "op1:primary=native-cancel:code=CANCELLED:delivery=CANCELLED_UNKNOWN:attempts=1:publications=1:session=1:stream=1:rst=CANCEL",
    "op2:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=3",
    "op3:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=5",
  ],
  "h2-goaway-metadata": [
    "op1:primary=success:code=OK:delivery=ACCEPTED:attempts=1:publications=1:session=1:stream=1",
    "op2:primary=success:code=OK:delivery=ACCEPTED:attempts=2:publications=2:unprocessed-session=1:unprocessed-stream=3:retry-session=2:retry-stream=1",
  ],
};
const enterpriseCases = (document.cases || []).filter((testCase) => testCase.executionMode === "enterprise-network-fixture");
if (JSON.stringify(enterpriseCases.map((testCase) => testCase.caseId)) !== JSON.stringify(requiredEnterpriseNetworkVectors.map(([caseId]) => caseId))) {
  fail("$.cases", "enterprise-network-fixture case IDs and order must equal the validator-owned capability inventory");
}
for (const [caseId, capability, endpointField, operationCount, coreAttemptCount, wireBodyPublications, expectedState, failureReason] of requiredEnterpriseNetworkVectors) {
  const testCase = requireCase(caseId);
  if (!testCase) continue;
  const proofs = testCase.script.filter((action) => action.action === "proveNetworkCapability");
  const setup = requiredSpecialNetworkSetup[caseId] ?? requiredSpecialNetworkSetup[capability] ?? ["reset"];
  const primary = testCase.expected.error === null ? "success" : testCase.expected.outcomeEvidence?.primaryKind === "native-cancel" ? "native-cancel" : "structured-error";
  const code = testCase.expected.error?.code ?? "OK";
  const attempts = testCase.expected.error?.attemptCount ?? testCase.expected.attemptTrace.filter((entry) => /:start@/u.test(entry)).length;
  const operationOutcomes = requiredSpecialNetworkOutcomes[capability] ?? [`op1:primary=${primary}:code=${code}:delivery=${testCase.expected.delivery}:attempts=${attempts}:publications=${wireBodyPublications}`];
  const expectedProof = { actor: "runner", action: "proveNetworkCapability", capability, endpointField, setup, operationOutcomes, operationCount, coreAttemptCount, wireBodyPublications, expectedState };
  const passEvidence = `fixture:network:${capability}:pass`;
  const setupEvidence = `fixture:network:${capability}:setup:${setup.join(",")}`;
  const outcomesEvidence = `fixture:network:${capability}:outcomes:${operationOutcomes.join("|")}`;
  const countEvidence = `fixture:network:${capability}:operations:${operationCount}:core-attempts:${coreAttemptCount}:wire-body-publications:${wireBodyPublications}:state:${expectedState}`;
  if (testCase.options.baseUrl !== document.fixtureProtocol.networkFixtureUrlToken
    || proofs.length !== 1 || JSON.stringify(proofs[0]) !== JSON.stringify(expectedProof)
    || !testCase.expected.fixtureTrace.includes(passEvidence) || !testCase.expected.fixtureTrace.includes(setupEvidence)
    || !testCase.expected.fixtureTrace.includes(outcomesEvidence) || !testCase.expected.fixtureTrace.includes(countEvidence)
    || (testCase.expected.error?.failureReason ?? null) !== failureReason) {
    fail(`$.cases.${caseId}`, "must equal the executable enterprise network capability, endpoint, publication bound, exact state, and failure-reason contract");
  }
}
const requiredConsumedCapabilities = new Set(document.fixtureProtocol.networkFixture.capabilities.filter((capability) => capability !== "control-close"));
const consumedCapabilities = new Set(requiredEnterpriseNetworkVectors.map(([, capability]) => capability));
if (JSON.stringify([...consumedCapabilities].sort()) !== JSON.stringify([...requiredConsumedCapabilities].sort())) {
  fail("$.cases", "enterprise-network-fixture vectors must consume every advertised send capability; control-close is exercised by fixture lifecycle tests");
}

const surrogateHeaderNameCase = requireCase("response-header-name-unpaired-surrogate-rejected");
const surrogateHeaderValueCase = requireCase("response-header-value-unpaired-surrogate-rejected");
const surrogateNameHeaders = surrogateHeaderNameCase?.script?.find((action) => action.action === "respond")?.headers;
const surrogateValueHeaders = surrogateHeaderValueCase?.script?.find((action) => action.action === "respond")?.headers;
if (JSON.stringify(surrogateNameHeaders) !== JSON.stringify({ "content-type": "application/json", $unpairedSurrogateHeaderName: "value" })) {
  fail("$.cases.response-header-name-unpaired-surrogate-rejected", "must materialize the unpaired header-name surrogate only through the declared post-parse harness token");
}
if (JSON.stringify(surrogateValueHeaders) !== JSON.stringify({ "content-type": "application/json", "x-repost": "$unpairedSurrogateHeaderValue" })) {
  fail("$.cases.response-header-value-unpaired-surrogate-rejected", "must materialize the unpaired header-value surrogate only through the declared post-parse harness token");
}
for (const testCase of document.cases || []) {
  const serialized = JSON.stringify(testCase);
  if (testCase.caseId !== "response-header-name-unpaired-surrogate-rejected" && serialized.includes("$unpairedSurrogateHeaderName")) {
    fail(`$.cases.${testCase.caseId}`, "$unpairedSurrogateHeaderName is legal only in its pinned header-name rejection vector");
  }
  if (testCase.caseId !== "response-header-value-unpaired-surrogate-rejected" && serialized.includes("$unpairedSurrogateHeaderValue")) {
    fail(`$.cases.${testCase.caseId}`, "$unpairedSurrogateHeaderValue is legal only in its pinned header-value rejection vector");
  }
}

const presentRetryAfterCase = requireCase("retry-after-present-date");
if (presentRetryAfterCase) {
  const response = presentRetryAfterCase.script.find((action) => ["respond", "respondChunks"].includes(action.action)
    && headerValues(action.headers, "retry-after").length > 0);
  const materialized = mergeObjects(document.inputProfiles?.[presentRetryAfterCase.input?.profile] || {}, presentRetryAfterCase.input || {});
  const values = headerValues(response?.headers, "retry-after");
  const parsedDate = values.length === 1 && typeof values[0] === "string" ? parseImfFixdate(values[0]) : null;
  if (retryAfterCoverageVariant(presentRetryAfterCase) !== "present-date"
    || !Number.isSafeInteger(materialized.clock?.retryAfterWallUnixMs)
    || parsedDate !== materialized.clock.retryAfterWallUnixMs
    || JSON.stringify(presentRetryAfterCase.input.entropy) !== JSON.stringify([125])
    || presentRetryAfterCase.script.find((action) => action.action === "advanceTime")?.byMs !== 125
    || !presentRetryAfterCase.expected.scheduleTrace.includes("retry:1:retry-after=invalid@0")
    || !presentRetryAfterCase.expected.scheduleTrace.includes("retry:1:delay=125@0")) {
    fail("$.cases.retry-after-present-date", "must use one IMF-fixdate exactly equal to the injected retryAfterWallUnixMs, classify it invalid, and fall back to the fixed 125 ms full-jitter delay");
  }
}

const allSentinelsCase = requireCase("error-safety-all-sentinels-redacted");
if (allSentinelsCase) {
  const expectedEvidence = [
    "scan:render:clean",
    "scan:logs:clean",
    "scan:observer:clean",
    "scan:metric-tags:clean",
    "scan:spans:clean",
    "allow:error.idempotencyKey:1",
  ];
  const echoAction = allSentinelsCase.script.find((action) => action.action === "respondEchoSentinels");
  const declaredInputs = JSON.stringify({ input: allSentinelsCase.input, options: allSentinelsCase.options });
  if (!echoAction || JSON.stringify(echoAction.sentinels) !== JSON.stringify(sentinelFields)
    || sentinelFields.some((field) => !declaredInputs.includes(document.sentinels[field]))
    || JSON.stringify(allSentinelsCase.expected.redactionTrace) !== JSON.stringify(expectedEvidence)
    || allSentinelsCase.expected.error?.idempotencyKey !== document.sentinels.idempotencyKey) {
    fail("$.cases.error-safety-all-sentinels-redacted", "must declare every sentinel and the exact complete redaction evidence with only error.idempotencyKey allowed");
  }
}

const providerConflictCase = requireCase("configuration-provider-and-fixed-key-mutually-exclusive");
if (providerConflictCase && (!hasConstructionOptionConflict(providerConflictCase.options)
  || JSON.stringify(providerConflictCase.expected.configurationTrace) !== JSON.stringify(["snapshot:0"])
  || providerConflictCase.expected.sideEffectTrace.length !== 0
  || providerConflictCase.expected.observerEmissionTrace.length !== 0)) {
  fail("$.cases.configuration-provider-and-fixed-key-mutually-exclusive", "must be a structural construction conflict with no operation lifecycle");
}

const refusedRedirectCase = requireCase("configuration-redirect-refused");
if (refusedRedirectCase) {
  const response = refusedRedirectCase.script.find((action) => action.action === "respond");
  const targetFacts = refusedRedirectCase.expected.fixtureTrace.filter((trace) => trace.startsWith("fixture:redirect-target-hits:"));
  if (refusedRedirectCase.executionMode !== "production-http-fixture"
    || refusedRedirectCase.options?.baseUrl !== document.fixtureProtocol.fixtureBaseUrlToken
    || response?.attempt !== 1 || response?.status !== 302
    || JSON.stringify(response?.headers) !== JSON.stringify({ location: "/__fixture/redirect-target" })
    || response?.body?.asset !== "empty"
    || JSON.stringify(targetFacts) !== JSON.stringify(["fixture:redirect-target-hits:0"])
    || !refusedRedirectCase.expected.configurationTrace.includes("base-url:baseUrl")
    || refusedRedirectCase.expected.error?.code !== "HTTP_REJECTED"
    || refusedRedirectCase.expected.error?.delivery !== "REJECTED"
    || refusedRedirectCase.expected.error?.httpStatus !== 302
    || refusedRedirectCase.expected.error?.attemptCount !== 1
    || refusedRedirectCase.expected.delivery !== "REJECTED"
    || refusedRedirectCase.expected.result !== null) {
    fail("$.cases.configuration-redirect-refused", "must execute the default production HTTP transport against the relative same-loopback 202 redirect target, observe zero target hits, and settle the original 302 as HTTP_REJECTED/REJECTED without following credentials");
  }
}

for (const [caseId, baseUrl] of [
  ["configuration-uri-userinfo-query-fragment-rejected", "https://user:pass@example.invalid/base?q=1#fragment"],
  ["configuration-uri-userinfo-only-rejected", "https://user:pass@example.invalid/base"],
  ["configuration-uri-query-only-rejected", "https://example.invalid/base?q=1"],
  ["configuration-uri-fragment-only-rejected", "https://example.invalid/base#fragment"],
]) {
  const testCase = requireCase(caseId);
  if (testCase && (testCase.options?.baseUrl !== baseUrl || !hasForbiddenBaseUrlComponents(testCase.options)
    || JSON.stringify(testCase.expected.configurationTrace) !== JSON.stringify(["snapshot:0"])
    || testCase.expected.sideEffectTrace.length !== 0
    || testCase.expected.observerEmissionTrace.length !== 0
    || testCase.expected.error?.code !== "CONFIGURATION"
    || testCase.expected.delivery !== "NOT_SENT")) {
    fail(`$.cases.${caseId}`, "must isolate and reject the forbidden base URL component at construction with no operation lifecycle");
  }
}

const authorizationOctetsCase = requireCase("configuration-nonblank-credential-octets-preserved");
if (authorizationOctetsCase) {
  const evidence = "fixture:attempt:1:assertion:authorization-octets-preserved:pass";
  const publicEvidence = cloneJson(authorizationOctetsCase.expected);
  delete publicEvidence.fixtureTrace;
  if (authorizationOctetsCase.executionMode !== "production-http-fixture"
    || authorizationOctetsCase.options?.baseUrl !== document.fixtureProtocol.fixtureBaseUrlToken
    || authorizationOctetsCase.options?.apiKey !== " api-key-with-spaces "
    || JSON.stringify(authorizationOctetsCase.script.find((action) => action.action === "acceptRequest")?.assertions) !== JSON.stringify(["authorization-octets-preserved"])
    || authorizationOctetsCase.expected.fixtureTrace.filter((trace) => trace === evidence).length !== 1
    || JSON.stringify(publicEvidence).includes("Bearer  api-key-with-spaces ")
    || JSON.stringify(publicEvidence).includes(" api-key-with-spaces ")) {
    fail("$.cases.configuration-nonblank-credential-octets-preserved", "must use the real HTTP fixture raw-socket digest assertion with only one private pass token retained");
  }
}

for (const caseId of [
  "configuration-explicit-api-key-wins-environment",
  "configuration-explicit-base-url-wins-environment",
  "configuration-default-base-url",
  "error-safety-all-sentinels-redacted",
]) {
  const testCase = requireCase(caseId);
  if (testCase?.executionMode !== undefined || testCase?.options?.baseUrl === document.fixtureProtocol.fixtureBaseUrlToken) {
    fail(`$.cases.${caseId}.executionMode`, "logical configuration and all-sentinel vectors must remain on the default core-attempt-seam");
  }
}

for (const [caseId, expectedOptions] of [
  ["configuration-custom-transport-built-in-options-conflict", { transportMode: "custom", builtInTransportOptions: true }],
]) {
  const testCase = requireCase(caseId);
  const actualOptions = testCase && Object.fromEntries(Object.keys(expectedOptions).map((field) => [field, testCase.options?.[field]]));
  if (testCase && (JSON.stringify(actualOptions) !== JSON.stringify(expectedOptions) || !hasConstructionOptionConflict(testCase.options))) {
    fail(`$.cases.${caseId}.options`, "must declare the required pairwise construction conflict");
  }
}

const customSuccessCase = requireCase("configuration-custom-transport-success");
const customRetryCase = requireCase("retry-custom-transport-transient-failure-then-success");
for (const testCase of [customSuccessCase, customRetryCase].filter(Boolean)) {
  const location = `$.cases.${testCase.caseId}`;
  const starts = testCase.expected.attemptTrace.filter((entry) => /:start@/.test(entry)).length;
  if (testCase.executionMode !== "custom-one-attempt" || testCase.options.transportMode !== "custom" || Object.hasOwn(testCase.options, "builtInTransportOptions")) {
    fail(`${location}.options`, "must select only the custom transport seam");
  }
  for (const trace of ["permit.acquire", "configuration.snapshot", "credentials.snapshot", "defaults.snapshot", "serialize:1", `network:${starts}`, "permit.release"]) {
    if (!testCase.expected.sideEffectTrace.includes(trace)) fail(`${location}.expected.sideEffectTrace`, `must retain core ${trace} behavior`);
  }
  for (const trace of ["request:113:accepted", "response-wire:104:accepted", "response-decoded:104:accepted"]) {
    if (!testCase.expected.byteTrace.includes(trace)) fail(`${location}.expected.byteTrace`, `must retain core size check ${trace}`);
  }
  if (!testCase.expected.configurationTrace.includes("credential:REPOST_SEND_API_KEY") || testCase.expected.delivery !== "ACCEPTED" || testCase.expected.result === null) {
    fail(`${location}.expected`, "must retain core credential and response validation around the custom transport");
  }
  const key = testCase.options.idempotencyKey;
  const keyFacts = testCase.expected.fixtureTrace.filter((trace) => trace.endsWith(`:idempotency:${key}`));
  if (keyFacts.length !== starts) fail(`${location}.expected.fixtureTrace`, "must prove one stable key on every custom transport attempt");
}
if (customSuccessCase && customSuccessCase.expected.attemptTrace.filter((entry) => /:start@/.test(entry)).length !== 1) {
  fail("$.cases.configuration-custom-transport-success.expected.attemptTrace", "must execute exactly one successful custom transport attempt");
}
if (customRetryCase && (customRetryCase.expected.attemptTrace.filter((entry) => /:start@/.test(entry)).length !== 2
  || customRetryCase.script.filter((action) => action.action === "failConnection" && action.kind === "io" && action.phase === "afterRequestBytes").length !== 1
  || customRetryCase.expected.scheduleTrace.length === 0)) {
  fail("$.cases.retry-custom-transport-transient-failure-then-success", "must prove one core retry across two single-attempt custom transport executions");
}

for (const [caseId, baseUrl, accepted] of [
  ["configuration-http-loopback-accepted", "http://127.0.0.1:4323/base", true],
  ["configuration-http-localhost-accepted", "http://localhost:4323/base", true],
  ["configuration-http-ipv4-loopback-range-accepted", "http://127.0.0.2:4323/base", true],
  ["configuration-http-ipv6-loopback-accepted", "http://[::1]:4323/base", true],
  ["configuration-http-localhost-suffix-rejected", "http://localhost.example:4323/base", false],
  ["configuration-http-ipv4-mapped-ipv6-rejected", "http://[::ffff:127.0.0.1]:4323/base", false],
]) {
  const testCase = requireOption(caseId, "baseUrl", baseUrl);
  if (!testCase) continue;
  if (accepted && (testCase.expected.delivery !== "ACCEPTED" || testCase.expected.result === null || !testCase.expected.configurationTrace.includes("base-url:baseUrl"))) {
    fail(`$.cases.${caseId}.expected`, "must accept the exact plain-HTTP loopback host");
  }
  if (!accepted && (testCase.expected.error?.code !== "CONFIGURATION" || testCase.expected.delivery !== "NOT_SENT" || JSON.stringify(testCase.expected.configurationTrace) !== JSON.stringify(["snapshot:0"]))) {
    fail(`$.cases.${caseId}.expected`, "must reject the plain-HTTP lookalike host");
  }
}

for (const [caseId, phase, delivery] of [
  ["retry-tls-protocol-not-retried", "beforeRequestBytes", "NOT_SENT"],
  ["failure-mapping-tls-protocol-after-commitment", "afterRequestBytes", "POSSIBLY_SENT"],
]) {
  const testCase = requireCase(caseId);
  const action = testCase?.script?.find((candidate) => candidate.action === "failConnection");
  if (testCase && (action?.kind !== "tls-protocol" || action.phase !== phase
    || testCase.expected.error?.code !== "TLS" || testCase.expected.error.delivery !== delivery
    || testCase.expected.error.retryable !== false || testCase.expected.error.causeCategory !== "HTTP_RUNTIME"
    || testCase.expected.scheduleTrace.length !== 0)) {
    fail(`$.cases.${caseId}`, `must prove complete tls-protocol ${phase} failure mapping`);
  }
}

const defaultMaxAttempts = requireCase("defaults-max-attempts-four-total");
if (defaultMaxAttempts && ("maxAttempts" in defaultMaxAttempts.options || defaultMaxAttempts.expected?.error?.attemptCount !== 4)) {
  fail("$.cases.defaults-max-attempts-four-total", "must prove the omitted maxAttempts default is four total attempts");
}

for (const [caseId, value] of [["defaults-max-attempts-minimum", 1]]) {
  const testCase = requireOption(caseId, "maxAttempts", value);
  const starts = testCase?.expected?.attemptTrace?.filter((entry) => entry.endsWith(":start@0")) || [];
  if (testCase && (testCase.expected.delivery !== "ACCEPTED" || starts.length !== 1 || !testCase.expected.sideEffectTrace.includes("network:1"))) {
    fail(`$.cases.${caseId}`, "must accept the configured boundary with one successful attempt");
  }
  if (testCase?.expected?.result && "idempotencyKey" in testCase.expected.result) {
    fail(`$.cases.${caseId}.expected.result`, "successful results must not expose the idempotency key");
  }
}
const maximumAttemptsCase = requireOption("defaults-max-attempts-maximum", "maxAttempts", 10);
if (maximumAttemptsCase) {
  const starts = maximumAttemptsCase.expected.attemptTrace.filter((entry) => /:start@/u.test(entry));
  const keyFacts = maximumAttemptsCase.expected.fixtureTrace.filter((entry) => entry.includes(":idempotency:idem_attempts_10"));
  if (starts.length !== 10 || keyFacts.length !== 10
    || maximumAttemptsCase.expected.error?.code !== "SERVER_FAILURE"
    || maximumAttemptsCase.expected.error?.attemptCount !== 10
    || maximumAttemptsCase.expected.delivery !== "POSSIBLY_SENT"
    || !maximumAttemptsCase.expected.attemptTrace.includes("attempt:5:start@0")
    || !maximumAttemptsCase.expected.attemptTrace.includes("attempt:10:start@0")
    || maximumAttemptsCase.expected.scheduleTrace.filter((entry) => /:delay=/u.test(entry)).length !== 9
    || !maximumAttemptsCase.expected.sideEffectTrace.includes("network:10")) {
    fail("$.cases.defaults-max-attempts-maximum", "must exhaust exactly ten attempts with nine schedules, one stable key, one permit, and pinned attempts five and ten");
  }
}

for (const [caseId, value] of maxAttemptsOutOfRangeCases) {
  const testCase = requireOption(caseId, "maxAttempts", value);
  if (testCase && (testCase.expected?.error?.code !== "CONFIGURATION" || testCase.expected.delivery !== "NOT_SENT" || testCase.expected.error.attemptCount !== 0)) {
    fail(`$.cases.${caseId}.expected`, "must be a CONFIGURATION/NOT_SENT zero-attempt rejection");
  }
  if (testCase && (testCase.expected.configurationTrace.length !== 1 || testCase.expected.configurationTrace[0] !== "snapshot:0" || testCase.expected.sideEffectTrace.length !== 0)) {
    fail(`$.cases.${caseId}.expected`, "must fail structurally before snapshots and side effects");
  }
  if (testCase && (testCase.expected.legacyBody === null || testCase.expected.observerEmissionTrace.length !== 0)) {
    fail(`$.cases.${caseId}.expected`, "must include the full legacy body without starting operation observers");
  }
}

for (const [caseId, value] of [
  ["defaults-max-in-flight-minimum", 1],
  ["defaults-max-in-flight-maximum", 65536],
  ["defaults-max-in-flight-zero-rejected", 0],
  ["defaults-max-in-flight-above-maximum-rejected", 65537],
]) {
  requireOption(caseId, "maxInFlight", value);
}

const descriptorMismatchCase = requireCase("retry-descriptor-version-failure-not-attempted");
if (descriptorMismatchCase && (descriptorMismatchCase.options?.descriptorVersion !== 3
  || document.inputProfiles?.[descriptorMismatchCase.input?.profile]?.send?.descriptorVersion !== 2)) {
  fail("$.cases.retry-descriptor-version-failure-not-attempted", "must exercise descriptor version 3 against the supported version 2 profile");
}
if (descriptorMismatchCase && (JSON.stringify(descriptorMismatchCase.expected?.configurationTrace) !== JSON.stringify(["snapshot:0"])
  || descriptorMismatchCase.expected?.sideEffectTrace.length !== 0
  || descriptorMismatchCase.expected?.observerEmissionTrace.length !== 0
  || descriptorMismatchCase.expected?.attemptTrace.length !== 0
  || descriptorMismatchCase.expected?.fixtureTrace.length !== 0)) {
  fail("$.cases.retry-descriptor-version-failure-not-attempted.expected", "descriptor mismatch must be a construction rejection with no operation, side effect, attempt, or fixture execution");
}

const ascii255Case = requireCase("idempotency-key-exactly-255-ascii-bytes-accepted");
const ascii255Key = ascii255Case?.options?.idempotencyKey;
if (typeof ascii255Key !== "string" || Buffer.byteLength(ascii255Key, "utf8") !== 255 || !/^[\x20-\x7e]+$/.test(ascii255Key)) {
  fail("$.cases.idempotency-key-exactly-255-ascii-bytes-accepted.options.idempotencyKey", "must be exactly 255 ASCII and UTF-8 bytes");
}

const asciiOverCase = requireCase("idempotency-key-256-ascii-bytes-rejected");
const asciiOverKey = asciiOverCase?.options?.idempotencyKey;
if (typeof asciiOverKey !== "string" || asciiOverKey.length !== 256 || !/^[\x20-\x7e]+$/u.test(asciiOverKey)) {
  fail("$.cases.idempotency-key-256-ascii-bytes-rejected.options.idempotencyKey", "must be exactly 256 ASCII octets");
}
if (asciiOverCase && (asciiOverCase.expected?.error?.idempotencyKey !== null || JSON.stringify(asciiOverCase.expected.fixtureTrace).includes(asciiOverKey))) {
  fail("$.cases.idempotency-key-256-ascii-bytes-rejected.expected", "must not expose the rejected key");
}

const nonAsciiCase = requireCase("idempotency-key-non-ascii-rejected");
const nonAsciiKey = nonAsciiCase?.options?.idempotencyKey;
if (typeof nonAsciiKey !== "string" || /^[\x00-\x7f]*$/u.test(nonAsciiKey) || Buffer.byteLength(nonAsciiKey, "utf8") > 255) {
  fail("$.cases.idempotency-key-non-ascii-rejected.options.idempotencyKey", "must contain a non-ASCII value below the 255-octet boundary");
}
const preNetworkInvalidKeySideEffects = [
  "permit.acquire",
  "configuration.snapshot",
  "credentials.snapshot",
  "defaults.snapshot",
  "idempotency.generate:0",
  "permit.release",
];
if (nonAsciiCase && (JSON.stringify(nonAsciiCase.script) !== JSON.stringify([{ actor: "runner", action: "drainRuntimeDiagnostics" }])
  || nonAsciiCase.expected?.error?.code !== "VALIDATION"
  || nonAsciiCase.expected?.delivery !== "NOT_SENT"
  || nonAsciiCase.expected?.error?.attemptCount !== 0
  || nonAsciiCase.expected?.error?.idempotencyKey !== null
  || nonAsciiCase.expected?.attemptTrace.length !== 0
  || nonAsciiCase.expected?.commitTrace.length !== 0
  || nonAsciiCase.expected?.fixtureTrace.length !== 0
  || JSON.stringify(nonAsciiCase.expected?.sideEffectTrace) !== JSON.stringify(preNetworkInvalidKeySideEffects))) {
  fail("$.cases.idempotency-key-non-ascii-rejected.expected", "must reject before serialization, network, fixture execution, or an attempt");
}

const invalidKeyCase = requireCase("idempotency-invalid-key-prevents-attempt");
if (invalidKeyCase && !/[\r\n]/u.test(invalidKeyCase.options?.idempotencyKey || "")) {
  fail("$.cases.idempotency-invalid-key-prevents-attempt.options.idempotencyKey", "must exercise CR or LF rejection rather than a closed ASCII grammar");
}

for (const category of categories) {
  if (!(document.cases || []).some((testCase) => testCase.category === category)) fail("$.cases", `missing category coverage ${category}`);
}
for (const code of errorCodes) {
  if (!(document.cases || []).some((testCase) => testCase.expected?.error?.code === code)) fail("$.cases", `missing terminal error coverage ${code}`);
}
for (const delivery of deliveries) {
  if (!(document.cases || []).some((testCase) => testCase.expected?.delivery === delivery)) fail("$.cases", `missing delivery coverage ${delivery}`);
}

function validateJvmOverlay(overlay) {
  {
    const base = "$jvm";
    const overlayFields = ["contractVersion", "target", "sharedContractBytesSha256", "jvmInputProfiles", "jvmFixtureProtocol", "jvmRawFixtureProtocol", "jvmEngineContract", "jvmTraceVocabulary", "concurrencyProtocol", "caseManifest", "cases", "concurrencyScenarios"];
    exactKeys(base, overlay, overlayFields);
    if (overlay?.contractVersion !== 2) fail(`${base}.contractVersion`, "must equal 2");
    if (overlay?.target !== "JVM") fail(`${base}.target`, "must equal JVM");
    const expectedSharedHash = crypto.createHash("sha256").update(sharedCanonicalBytes).digest("hex");
    if (overlay?.sharedContractBytesSha256 !== expectedSharedHash) fail(`${base}.sharedContractBytesSha256`, "must equal the SHA-256 of the exact canonical v2.json bytes loaded before overlay merge");

    const expectedProfiles = {
      "jvm-owned-default": { jvmCpuCount: 4, jvmMaxInFlight: 256, jvmOwnership: "OWNED", jvmTelemetryMode: "disabled" },
      "jvm-borrowed-runtime": { jvmCpuCount: 4, jvmMaxInFlight: 256, jvmOwnership: "BORROWED", jvmTelemetryMode: "disabled" },
      "jvm-edge-cpu1-n1": { jvmCpuCount: 1, jvmMaxInFlight: 1, jvmOwnership: "OWNED", jvmTelemetryMode: "disabled" },
      "jvm-large-cpu64-n65536": { jvmCpuCount: 64, jvmMaxInFlight: 65536, jvmOwnership: "OWNED", jvmTelemetryMode: "enabled" },
    };
    if (JSON.stringify(overlay?.jvmInputProfiles) !== JSON.stringify(expectedProfiles)) fail(`${base}.jvmInputProfiles`, "must equal the closed JVM ownership/resource profiles");
    for (const profileName of Object.keys(overlay?.jvmInputProfiles || {})) {
      if (!profileName.startsWith("jvm-")) fail(`${base}.jvmInputProfiles.${profileName}`, "JVM profile names must use the jvm- prefix");
      if (Object.hasOwn(document.inputProfiles || {}, profileName)) fail(`${base}.jvmInputProfiles.${profileName}`, "must not override a shared input profile");
    }

    const protocolFields = ["optionSchemas", "actionSchemas", "fatalErrorPolicy", "telemetryProtocol", "oneShotPublicationProtocol", "failureReasonProtocol", "resourceOwnershipProtocol"];
    exactKeys(`${base}.jvmFixtureProtocol`, overlay?.jvmFixtureProtocol, protocolFields);
    const expectedOptionSchemas = {
      jvmCpuCount: { type: "integer", minimum: 1 }, jvmMaxInFlight: { type: "integer", minimum: 1, maximum: 65536 },
      jvmOwnership: { type: "enum", values: ["OWNED", "BORROWED"] },
      jvmTelemetryMode: { type: "enum", values: ["disabled", "enabled"] },
    };
    if (JSON.stringify(overlay?.jvmFixtureProtocol?.optionSchemas) !== JSON.stringify(expectedOptionSchemas)) fail(`${base}.jvmFixtureProtocol.optionSchemas`, "must equal the closed additive JVM option schemas");
    const expectedProtocolText = {
      fatalErrorPolicy: "For every Error except VirtualMachineError and ThreadDeath, allocation-minimal deterministic cleanup atomically releases public bytes/in-flight, completes primary and outcome exceptionally with the exact same Error instance outside Repost normalization/redaction, releases settlement in finally, then hands off and rethrows that same instance on an owned uncaught boundary; never retry or deliberately kill a borrowed/JDK/event-loop thread. VirtualMachineError and ThreadDeath receive allocation-minimal best-effort cleanup only and make no settlement guarantee.",
      telemetryProtocol: "captureContext is the only admission/caller-thread hook; start/makeCurrent/attempt/end run on owned execution with captured parent, all scopes close in finally, every started handle ends once before public publication, nonfatal failures are count-only, and fatal Error follows fatalErrorPolicy.",
      oneShotPublicationProtocol: "One fresh internal async entity producer per core attempt; transport retry and authentication replay are disabled, queued work has no producer assignment, and wireBodyPublications must be <= coreAttemptCount under stale H1, proxy 407, reset, RST, SETTINGS, and GOAWAY stimuli.",
      failureReasonProtocol: "The JVM public type is RepostFailureReason with the exact shared enum/remediation mapping; no public exception class, package, host, certificate, proxy, or remote string is retained.",
      resourceOwnershipProtocol: "The first close linearizes OPEN to CLOSING and creates one 5000ms monotonic deadline; exact owned close order is scheduler, relocated transport pool/connections/reactor including EngineTimerWheel, DNS executor, proxy credential executor, TLS executor with owned session invalidation before shutdown, operation executor, terminal settlement pool, observer dispatcher; injected SSLContext, resolver, proxy credential provider, and custom Transport are borrowed; reentrant close excludes only its current identity and may return CLOSING with closed=false and bytes still counted; current finally wipes/releases and the coordinator publishes CLOSED only at inFlight=0,bufferedBytes=0 before settling the single immutable closeCompletion stage; later close waits only the original remainder and a nonreturning current callback leaves an honest bounded-residual CLOSING state.",
    };
    for (const [field, value] of Object.entries(expectedProtocolText)) if (overlay?.jvmFixtureProtocol?.[field] !== value) fail(`${base}.jvmFixtureProtocol.${field}`, "must equal the frozen JVM contract text");
    const expectedJvmEngineContract = require(path.join(__dirname, "jvm-engine-contract.js"));
    if (JSON.stringify(overlay?.jvmEngineContract) !== JSON.stringify(expectedJvmEngineContract)) {
      fail(`${base}.jvmEngineContract`, "must equal the validator-owned relocated A-prime engine, API, budget, pool, protocol, isolation, observability, lifecycle, vendor-lock, and raw-fixture contract");
    }
    const rawFixtureContract = require(path.join(__dirname, "jvm-raw-fixture-contract.js"));
    const expectedRawFixtureProtocol = { ...rawFixtureContract.protocol, fixtureCases: rawFixtureContract.fixtureCases() };
    if (JSON.stringify(overlay?.jvmRawFixtureProtocol) !== JSON.stringify(expectedRawFixtureProtocol)) {
      fail(`${base}.jvmRawFixtureProtocol`, "must equal the closed executable 33-group JVM raw-fixture protocol, fixture-case inventory, production-consumer mapping, and group-specific observation contract");
    }
    const rawGroupIds = Object.keys(overlay?.jvmRawFixtureProtocol?.groups || {});
    if (JSON.stringify(rawGroupIds) !== JSON.stringify(expectedJvmEngineContract.requiredRawFixtureGroups)) {
      fail(`${base}.jvmRawFixtureProtocol.groups`, "must be an ordered bijection with jvmEngineContract.requiredRawFixtureGroups");
    }
    const rawFixtureCases = overlay?.jvmRawFixtureProtocol?.fixtureCases;
    if (!Array.isArray(rawFixtureCases) || rawFixtureCases.length !== 33) {
      fail(`${base}.jvmRawFixtureProtocol.fixtureCases`, "must contain exactly 33 ordered fixture cases");
    } else if (JSON.stringify(rawFixtureCases.map((fixtureCase) => fixtureCase?.groupId)) !== JSON.stringify(rawGroupIds)) {
      fail(`${base}.jvmRawFixtureProtocol.fixtureCases`, "must be an ordered bijection with the raw fixture groups");
    }
    const fixtureCaseIds = new Set();
    const qualifiedObservedCounterNames = new Set();
    const qualifiedBarrierNames = new Set();
    const rawGroupFields = ["fixtureCaseId", "productionConsumerCaseIds", "requiredEndpointIds", "requiredAssetIds", "requiredCapabilities", "requiredControls", "requiredBarriers", "requiredObservedCounters", "requiredEvidenceFields", "terminalAssertions"];
    for (const [groupId, group] of Object.entries(overlay?.jvmRawFixtureProtocol?.groups || {})) {
      const location = `${base}.jvmRawFixtureProtocol.groups.${groupId}`;
      if (!exactKeys(location, group, rawGroupFields)) continue;
      if (JSON.stringify(Object.keys(group)) !== JSON.stringify(rawGroupFields)) fail(location, "fields must appear in the exact closed order");
      if (group.fixtureCaseId !== `jvm-raw-${groupId}` || fixtureCaseIds.has(group.fixtureCaseId)) fail(`${location}.fixtureCaseId`, "must be the unique jvm-raw-<groupId> fixture self-test ID");
      fixtureCaseIds.add(group.fixtureCaseId);
      uniqueStringArray(`${location}.productionConsumerCaseIds`, group.productionConsumerCaseIds);
      if (group.productionConsumerCaseIds.length === 0) fail(`${location}.productionConsumerCaseIds`, "must name at least one executable production consumer");
      uniqueStringArray(`${location}.requiredEndpointIds`, group.requiredEndpointIds, { nonEmpty: false });
      uniqueStringArray(`${location}.requiredAssetIds`, group.requiredAssetIds, { nonEmpty: false });
      uniqueStringArray(`${location}.requiredCapabilities`, group.requiredCapabilities);
      for (const [index, capability] of (Array.isArray(group.requiredCapabilities) ? group.requiredCapabilities : []).entries()) {
        if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(capability)) fail(`${location}.requiredCapabilities[${index}]`, "must be a safe lower-kebab-case capability name");
        if (!group.terminalAssertions.includes(`capability:${capability}`)) fail(`${location}.terminalAssertions`, "must assert every group-specific capability");
      }
      if (JSON.stringify(group.requiredControls) !== JSON.stringify(rawFixtureContract.actions)) fail(`${location}.requiredControls`, "must drive every frozen control action");
      uniqueStringArray(`${location}.requiredBarriers`, group.requiredBarriers);
      if (group.requiredBarriers.length === 0) fail(`${location}.requiredBarriers`, "must include a group-specific mechanism barrier");
      for (const [index, barrier] of group.requiredBarriers.entries()) {
        if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(barrier)) fail(`${location}.requiredBarriers[${index}]`, "must be a safe lower-kebab-case barrier name");
        const qualifiedBarrier = `${groupId}\u0000${barrier}`;
        if (qualifiedBarrierNames.has(qualifiedBarrier)) fail(`${location}.requiredBarriers`, "must not repeat a group-qualified barrier name");
        qualifiedBarrierNames.add(qualifiedBarrier);
      }
      if (!object(group.requiredObservedCounters) || Object.keys(group.requiredObservedCounters).length < 2) fail(`${location}.requiredObservedCounters`, "must include at least two group-specific observed mechanism counters");
      for (const [counter, value] of Object.entries(group.requiredObservedCounters || {})) {
        if (!/^[a-z][A-Za-z0-9]*$/u.test(counter)) fail(`${location}.requiredObservedCounters.${counter}`, "must be a safe lower-camel-case counter key");
        const qualifiedCounter = `${groupId}\u0000${counter}`;
        if (qualifiedObservedCounterNames.has(qualifiedCounter)) fail(`${location}.requiredObservedCounters`, "must not repeat a group-qualified observed counter key");
        qualifiedObservedCounterNames.add(qualifiedCounter);
        integer(`${location}.requiredObservedCounters.${counter}`, value, 0, Number.MAX_SAFE_INTEGER);
        if (!group.terminalAssertions.includes(`counter:${counter}:${value}`)) fail(`${location}.terminalAssertions`, "must assert the exact observed mechanism counter");
      }
      for (const barrier of group.requiredBarriers) if (!group.terminalAssertions.includes(`barrier:${barrier}:released`)) fail(`${location}.terminalAssertions`, "must assert every group-specific barrier release");
      if (JSON.stringify(group.requiredEvidenceFields) !== JSON.stringify(rawFixtureContract.evidenceFields)) fail(`${location}.requiredEvidenceFields`, "must equal the closed raw fixture evidence fields");
      const contractGroup = rawFixtureContract.groups[groupId];
      if (contractGroup && JSON.stringify(group.requiredBarriers) !== JSON.stringify(contractGroup.requiredBarriers)) fail(`${location}.requiredBarriers`, "must equal the exact ordered group-specific barriers");
      if (contractGroup && JSON.stringify(group.requiredObservedCounters) !== JSON.stringify(contractGroup.requiredObservedCounters)) fail(`${location}.requiredObservedCounters`, "must equal the exact ordered group-qualified observed counters");
      if (contractGroup && JSON.stringify(group.requiredCapabilities) !== JSON.stringify(contractGroup.requiredCapabilities)) fail(`${location}.requiredCapabilities`, "must equal the exact ordered group-specific capabilities");
    }
    if (fixtureCaseIds.size !== 33) {
      fail(`${base}.jvmRawFixtureProtocol.groups`, "must expose exactly 33 unique fixture cases");
    }
    if (JSON.stringify(overlay?.jvmEngineContract?.serialization?.successResponseFieldOrder)
      !== JSON.stringify(document.constants?.successResponseFieldOrder)) {
      fail(`${base}.jvmEngineContract.serialization.successResponseFieldOrder`, "must exactly equal the authoritative shared successful response field set and order");
    }
    if (overlay?.jvmEngineContract?.protocol?.http2?.maxHeaderListBytes !== 73728) fail(`${base}.jvmEngineContract.protocol.http2.maxHeaderListBytes`, "must equal the plan-frozen 73728-byte H2 header-list bound");
    if (overlay?.jvmEngineContract?.protocol?.http1?.bufferBytes !== 8192) fail(`${base}.jvmEngineContract.protocol.http1.bufferBytes`, "must equal the plan-frozen 8192-byte H1 reusable buffer");
    if (overlay?.jvmEngineContract?.protocol?.http1?.maxLineBytes !== 8453) fail(`${base}.jvmEngineContract.protocol.http1.maxLineBytes`, "must equal the plan-frozen 8453-byte H1 line bound");

    function derivedJvmExpected(terminalKind, code, delivery, completionRoute, coreAttemptCount, wireBodyPublications, evidence, overrides = {}) {
      const construction = terminalKind === "CONSTRUCTION_PROOF";
      const fatal = terminalKind === "FATAL_RETHROW";
      return {
        terminalKind,
        code,
        delivery,
        completionRoute,
        coreAttemptCount,
        wireBodyPublications,
        reservationAcquires: construction ? 0 : 1,
        reservationReleases: construction ? 0 : 1,
        ownedCloseCalls: 0,
        borrowedCloseCalls: 0,
        uncaughtFatalSameInstance: fatal,
        closeFailureCount: 0,
        closeFailureCategories: [],
        validationIssues: [],
        configurationIssues: [],
        issueCount: 0,
        issuesTruncated: false,
        evidence,
        ...overrides,
      };
    }
    const expectedJvmCaseDefinitions = [
      ["jvm-transport-null-completion-stage", "jvm-borrowed-runtime", "jvm-null-completion-stage", "A custom Transport returning a null CompletionStage is one sanitized nonretryable IO/UNKNOWN/CUSTOM_TRANSPORT seam defect with no retry.", derivedJvmExpected("STRUCTURED_ERROR", "IO", "POSSIBLY_SENT", "OPERATION_EXECUTION", 1, 1, ["stage:null", "retry-count:0", "cause:CUSTOM_TRANSPORT", "reason:UNKNOWN"])],
      ["jvm-transport-null-response-completion", "jvm-borrowed-runtime", "jvm-null-response-completion", "A non-null CompletionStage completing with null response is the shared no-response defect with exactly one transport invocation.", derivedJvmExpected("STRUCTURED_ERROR", "IO", "POSSIBLY_SENT", "OPERATION_EXECUTION", 1, 1, ["stage:completed-null", "retry-count:0", "reason:UNKNOWN"])],
      ["jvm-response-factory-sync-throw-closes-transferred-body", "jvm-borrowed-runtime", "jvm-response-factory-sync-throw", "TransportResponse.of rejects invalid construction synchronously and closes a transferred non-null body exactly once before the throw escapes execute.", derivedJvmExpected("STRUCTURED_ERROR", "IO", "POSSIBLY_SENT", "OPERATION_EXECUTION", 1, 1, ["factory:sync-throw", "response-acquired:false", "transferred-body-close-count:1"])],
      ["jvm-response-factory-failed-stage-closes-transferred-body", "jvm-borrowed-runtime", "jvm-response-factory-failed-stage", "Asynchronous response construction failure is a failed stage; the transferred body is already closed exactly once and core never acquires a response.", derivedJvmExpected("STRUCTURED_ERROR", "IO", "POSSIBLY_SENT", "OPERATION_EXECUTION", 1, 1, ["factory:failed-stage", "response-acquired:false", "transferred-body-close-count:1"])],
      ["jvm-operation-executor-rejection", "jvm-owned-default", "jvm-operation-executor-rejected", "Operation submission rejection releases admission and settles OVERLOADED/NOT_SENT through the bounded terminal pool.", derivedJvmExpected("STRUCTURED_ERROR", "OVERLOADED", "NOT_SENT", "TERMINAL_DISPATCHER", 0, 0, ["downstream-side-effects:0", "operation-rejections:1"])],
      ["jvm-deadline-scheduler-rejection", "jvm-owned-default", "jvm-deadline-scheduler-rejected", "Initial deadline registration rejection prevents operation work and settles OVERLOADED/NOT_SENT through the bounded terminal pool.", derivedJvmExpected("STRUCTURED_ERROR", "OVERLOADED", "NOT_SENT", "TERMINAL_DISPATCHER", 0, 0, ["downstream-side-effects:0", "scheduler-rejections:1"])],
      ["jvm-retry-scheduler-rejection", "jvm-owned-default", "jvm-retry-scheduler-rejected", "Retry timer rejection preserves unresolved commitment and settles OVERLOADED/POSSIBLY_SENT without a second attempt.", derivedJvmExpected("STRUCTURED_ERROR", "OVERLOADED", "POSSIBLY_SENT", "TERMINAL_DISPATCHER", 1, 1, ["scheduler-rejections:1", "second-attempt:false"])],
      ["jvm-inline-executor-detected-before-side-effects", "jvm-borrowed-runtime", "jvm-inline-executor-detected", "A borrowed executor running inline before submit returns performs no user or network work and terminalizes CONFIGURATION/NOT_SENT on the terminal pool.", derivedJvmExpected("STRUCTURED_ERROR", "CONFIGURATION", "NOT_SENT", "TERMINAL_DISPATCHER", 0, 0, ["submission-context-guard:pass", "downstream-side-effects:0"], { configurationIssues: [{ code: "UNSUPPORTED", optionKeys: ["EXECUTOR"] }], issueCount: 1 })],
      ["jvm-cancel-queued-operation-no-ghost-work", "jvm-borrowed-runtime", "jvm-cancel-queued-operation", "Native Future cancellation removes the exact queued task when possible, prevents ghost work, and publishes the stable outcome once.", derivedJvmExpected("NATIVE_CANCEL", "CANCELLED", "NOT_SENT", "NATIVE_CANCEL", 0, 0, ["queued-task-remove-by-identity:pass", "ghost-work:0"])],
      ["jvm-close-queued-operation-no-ghost-work", "jvm-borrowed-runtime", "jvm-close-queued-operation", "Runtime close cancels queued work by identity, uses structured CLOSED, and preserves borrowed executor ownership.", derivedJvmExpected("STRUCTURED_ERROR", "CLOSED", "NOT_SENT", "TERMINAL_DISPATCHER", 0, 0, ["queued-task-cancel:pass", "borrowed-executor-close-calls:0"])],
      ["jvm-repost-failure-reason-closed-abi", "jvm-owned-default", "jvm-repost-failure-reason-abi", "RepostFailureReason exposes only the closed shared enum/remediation mapping with JSpecify nullness and no platform exception strings.", derivedJvmExpected("CONSTRUCTION_PROOF", null, null, null, 0, 0, ["enum-values:14", "remediation-keys:exact", "platform-strings:none"])],
      ["jvm-telemetry-context-through-retry", "jvm-large-cpu64-n65536", "jvm-telemetry-live-context", "Capture occurs at admission; operation/attempt scopes make the captured parent current around transport and owned continuations and every started handle ends once before publication.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 2, 2, ["capture-thread:caller", "parent:captured", "attempt-handles:2", "operation-end-before-primary:true"])],
      ["jvm-telemetry-capture-nonfatal-isolated", "jvm-owned-default", "jvm-telemetry-nonfatal-capture", "A nonfatal capture failure disables telemetry for the operation, increments telemetryFailures once, and cannot change sending.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 1, 1, ["telemetry-failures:1", "telemetry-disabled:true"])],
      ["jvm-telemetry-start-nonfatal-isolated", "jvm-owned-default", "jvm-telemetry-nonfatal-start", "A nonfatal/null start disables telemetry, increments telemetryFailures once, and cannot change sending.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 1, 1, ["telemetry-failures:1", "operation-handle:none"])],
      ["jvm-telemetry-scope-nonfatal-isolated", "jvm-owned-default", "jvm-telemetry-nonfatal-scope", "A nonfatal/null makeCurrent failure is isolated; retained handles receive best-effort end exactly once.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 1, 1, ["telemetry-failures:1", "operation-end-count:1"])],
      ["jvm-telemetry-attempt-nonfatal-isolated", "jvm-owned-default", "jvm-telemetry-nonfatal-attempt", "A nonfatal attempt start/end failure is isolated with no retry recursion and operation end still executes once.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 1, 1, ["telemetry-failures:1", "operation-end-count:1"])],
      ["jvm-telemetry-end-nonfatal-isolated", "jvm-owned-default", "jvm-telemetry-nonfatal-end", "A nonfatal end failure is counted once after the end attempt and cannot replace the primary terminal result.", derivedJvmExpected("SUCCESS", "OK", "ACCEPTED", "OPERATION_EXECUTION", 1, 1, ["telemetry-failures:1", "primary-preserved:true"])],
      ["jvm-fatal-assertion-error-same-instance", "jvm-owned-default", "jvm-fatal-assertion-error", "A non-VM AssertionError receives allocation-minimal deterministic cleanup, completes primary and outcome exceptionally with the same instance, releases settlement in finally, and reaches an owned-thread uncaught handler as the same instance without retry or Repost normalization.", derivedJvmExpected("FATAL_RETHROW", null, null, "OPERATION_EXECUTION", 1, 0, ["non-vm-error:true", "primary-exception-same-instance:true", "outcome-exception-same-instance:true", "public-bytes-inflight-released:true", "settlement-release-finally:true", "retry-count:0", "uncaught-owned-thread:true", "class-name-publication:none"])],
      ["jvm-fatal-linkage-error-handoff", "jvm-borrowed-runtime", "jvm-fatal-linkage-error", "A non-VM LinkageError from a borrowed/JDK completion is handed off without user code, deterministically settles primary and outcome with the same instance and balanced resources, then is rethrown as that same instance on an owned terminal thread.", derivedJvmExpected("FATAL_RETHROW", null, null, "TERMINAL_DISPATCHER", 1, 1, ["non-vm-error:true", "foreign-thread-killed:false", "owned-thread-handoff:true", "uncaught-owned-thread:true", "primary-exception-same-instance:true", "outcome-exception-same-instance:true", "public-bytes-inflight-released:true", "settlement-release-finally:true", "retry-count:0"])],
      ["jvm-close-failure-aggregate-order", "jvm-owned-default", "jvm-close-aggregate-order", "Nonfatal close failures aggregate in scheduler, transport, DNS, proxy credential, TLS, operation, terminal settlement, observer order while runtime still reaches CLOSED.", derivedJvmExpected("STRUCTURED_ERROR", "IO", "NOT_SENT", "TERMINAL_DISPATCHER", 0, 0, ["close-categories:SCHEDULER_CLOSE,TRANSPORT_CLOSE,DNS_EXECUTOR_CLOSE,PROXY_CREDENTIAL_EXECUTOR_CLOSE,TLS_EXECUTOR_CLOSE,OPERATION_EXECUTOR_CLOSE,TERMINAL_SETTLEMENT_CLOSE,OBSERVER_CLOSE", "runtime-state:CLOSED"], { ownedCloseCalls: 8, closeFailureCount: 8, closeFailureCategories: ["SCHEDULER_CLOSE", "TRANSPORT_CLOSE", "DNS_EXECUTOR_CLOSE", "PROXY_CREDENTIAL_EXECUTOR_CLOSE", "TLS_EXECUTOR_CLOSE", "OPERATION_EXECUTOR_CLOSE", "TERMINAL_SETTLEMENT_CLOSE", "OBSERVER_CLOSE"] })],
    ];
    const expectedJvmCases = rawFixtureContract.attachProductionGroups(expectedJvmCaseDefinitions
      .map(([caseId, profile, action, semantics, expected]) => ({ caseId, description: semantics, input: { profile, action }, expected }))
      .concat(require(path.join(__dirname, "jvm-engine-cases.js"))));

    const expectedCaseIds = expectedJvmCases.map((testCase) => testCase.caseId);
    const caseIds = (overlay?.cases || []).map((testCase) => testCase?.caseId);
    if (JSON.stringify(caseIds) !== JSON.stringify(expectedCaseIds)) fail(`${base}.cases`, "must equal the closed ordered JVM single-operation inventory");
    const actionNames = Object.keys(overlay?.jvmFixtureProtocol?.actionSchemas || {});
    const caseActions = (overlay?.cases || []).map((testCase) => testCase?.input?.action);
    if (JSON.stringify([...new Set(caseActions)]) !== JSON.stringify(actionNames)) fail(`${base}.jvmFixtureProtocol.actionSchemas`, "must contain each JVM case action exactly once in first-use order");
    const expectedActionSchemas = Object.fromEntries(expectedJvmCases.map((testCase) => [testCase.input.action, { semantics: testCase.description }]));
    if (JSON.stringify(overlay?.jvmFixtureProtocol?.actionSchemas) !== JSON.stringify(expectedActionSchemas)) {
      fail(`${base}.jvmFixtureProtocol.actionSchemas`, "must equal the validator-owned exact JVM action semantics in first-use order");
    }
    const expectedCaseFields = ["terminalKind", "code", "delivery", "completionRoute", "coreAttemptCount", "wireBodyPublications", "reservationAcquires", "reservationReleases", "ownedCloseCalls", "borrowedCloseCalls", "uncaughtFatalSameInstance", "closeFailureCount", "closeFailureCategories", "validationIssues", "configurationIssues", "issueCount", "issuesTruncated", "evidence"];
    for (const [index, testCase] of (overlay?.cases || []).entries()) {
      const location = `${base}.cases[${index}]`;
      const derivedCase = expectedJvmCases[index];
      if (!exactKeys(location, testCase, ["caseId", "description", "input", "expected"])) continue;
      if (!testCase.caseId?.startsWith("jvm-")) fail(`${location}.caseId`, "must use the jvm- prefix");
      if ((document.caseManifest || []).includes(testCase.caseId)) fail(`${location}.caseId`, "must not collide with the shared manifest");
      const expectedInputFields = derivedCase?.input?.rawFixtureGroups ? ["profile", "action", "rawFixtureGroups"] : ["profile", "action"];
      if (!exactKeys(`${location}.input`, testCase.input, expectedInputFields)) continue;
      if (!Object.hasOwn(expectedProfiles, testCase.input.profile)) fail(`${location}.input.profile`, "must name a closed JVM profile");
      const actionSchema = overlay?.jvmFixtureProtocol?.actionSchemas?.[testCase.input.action];
      if (!actionSchema || !exactKeys(`${base}.jvmFixtureProtocol.actionSchemas.${testCase.input.action}`, actionSchema, ["semantics"])) fail(`${location}.input.action`, "must name one closed JVM action");
      else if (testCase.description !== actionSchema.semantics) fail(`${location}.description`, "must equal the action's frozen semantics");
      if (!exactKeys(`${location}.expected`, testCase.expected, expectedCaseFields)) continue;
      if (!["SUCCESS", "STRUCTURED_ERROR", "NATIVE_CANCEL", "FATAL_RETHROW", "CONSTRUCTION_PROOF", "EXECUTABLE_PROOF"].includes(testCase.expected.terminalKind)) fail(`${location}.expected.terminalKind`, "must use a closed JVM terminal kind");
      for (const field of ["coreAttemptCount", "wireBodyPublications", "reservationAcquires", "reservationReleases", "ownedCloseCalls", "borrowedCloseCalls"]) integer(`${location}.expected.${field}`, testCase.expected[field], 0, 65536 * 4);
      if (testCase.expected.wireBodyPublications > testCase.expected.coreAttemptCount) fail(`${location}.expected.wireBodyPublications`, "must prove one core attempt has at most one wire body publication");
      if (testCase.expected.reservationReleases > testCase.expected.reservationAcquires) fail(`${location}.expected.reservationReleases`, "cannot exceed acquired settlement reservations");
      uniqueStringArray(`${location}.expected.evidence`, testCase.expected.evidence);
      if (testCase.expected.terminalKind === "CONSTRUCTION_PROOF" && (testCase.expected.code !== null || testCase.expected.delivery !== null || testCase.expected.completionRoute !== null || testCase.expected.reservationAcquires !== 0)) fail(`${location}.expected`, "construction proof cannot invent operation terminal state");
      if (testCase.expected.terminalKind === "EXECUTABLE_PROOF" && (testCase.expected.code !== null || testCase.expected.delivery !== null || testCase.expected.completionRoute !== null || testCase.expected.coreAttemptCount !== 0 || testCase.expected.wireBodyPublications !== 0 || testCase.expected.reservationAcquires !== 0 || !Array.isArray(testCase.input.rawFixtureGroups) || testCase.input.rawFixtureGroups.length === 0)) fail(`${location}.expected`, "executable raw proof must name its production raw groups and cannot invent one aggregate public send outcome");
      if (testCase.expected.terminalKind === "FATAL_RETHROW" && (testCase.expected.uncaughtFatalSameInstance !== true || testCase.expected.code !== null || testCase.expected.delivery !== null || testCase.expected.reservationAcquires !== testCase.expected.reservationReleases)) fail(`${location}.expected`, "fatal proof must preserve exact-instance uncaught delivery outside Repost normalization and balance settlement reservations");
      if (!Array.isArray(testCase.expected.evidence) || testCase.expected.evidence.length < 2) fail(`${location}.expected.evidence`, "must contain multiple executable JVM evidence facts");
      if (derivedCase) {
        if (testCase.caseId !== derivedCase.caseId) fail(`${location}.caseId`, "must equal the validator-derived JVM case ID");
        if (testCase.description !== derivedCase.description) fail(`${location}.description`, "must equal the validator-derived JVM action semantics");
        for (const field of expectedInputFields) {
          if (JSON.stringify(testCase.input?.[field]) !== JSON.stringify(derivedCase.input[field])) fail(`${location}.input.${field}`, "must equal the validator-derived JVM action/profile mapping");
        }
        for (const field of expectedCaseFields) {
          if (JSON.stringify(testCase.expected?.[field]) !== JSON.stringify(derivedCase.expected[field])) {
            fail(`${location}.expected.${field}`, "must equal the validator-derived exact JVM semantic oracle");
          }
        }
      }
    }
    for (const [groupId, group] of Object.entries(overlay?.jvmRawFixtureProtocol?.groups || {})) {
      if ((overlay?.caseManifest || []).includes(group.fixtureCaseId)) fail(`${base}.caseManifest`, "fixture self-test IDs must not enter the production JVM case manifest");
      for (const consumerCaseId of group.productionConsumerCaseIds || []) {
        const consumer = (overlay?.cases || []).find((testCase) => testCase.caseId === consumerCaseId);
        if (!consumer) {
          fail(`${base}.jvmRawFixtureProtocol.groups.${groupId}.productionConsumerCaseIds`, `missing production consumer ${consumerCaseId}`);
          continue;
        }
        if (consumer.expected?.terminalKind === "CONSTRUCTION_PROOF" || consumer.expected?.terminalKind !== "EXECUTABLE_PROOF") {
          fail(`${base}.cases.${consumerCaseId}.expected.terminalKind`, "raw production consumer must be an executable proof, never a construction declaration");
        }
        if (!consumer.input?.rawFixtureGroups?.includes(groupId)) fail(`${base}.cases.${consumerCaseId}.input.rawFixtureGroups`, `must reference raw group ${groupId}`);
      }
    }
    const requiredCaseEvidence = new Map([
      ["jvm-engine-shared-budget-reservations", ["http-bootstrap-establishment:1048576", "built-in-operation-reservation:2621440", "json-parser-scratch:262144-distinct-atomic-pre-admission", "minimum-bootstrap-plus-operation:3670016", "pre-hook-overload-side-effects:0"]],
      ["jvm-engine-h2-goaway-classification-and-replay", ["last-stream-mask:0x7fffffff", "above-last:replayed-new-session"]],
      ["jvm-engine-hpack-secret-and-retention-bounds", ["authorization:NEVER_INDEXED", "encoder-table-max:16384"]],
      ["jvm-telemetry-context-through-retry", ["parent:captured", "operation-end-before-primary:true"]],
      ["jvm-fatal-assertion-error-same-instance", ["primary-exception-same-instance:true", "settlement-release-finally:true"]],
      ["jvm-engine-single-monotonic-close-budget", ["close-budget-ms:5000", "nested-grace:none"]],
    ]);
    for (const [caseId, evidence] of requiredCaseEvidence) {
      const actual = overlay?.cases?.find((testCase) => testCase.caseId === caseId)?.expected?.evidence || [];
      for (const item of evidence) if (!actual.includes(item)) fail(`${base}.cases.${caseId}.expected.evidence`, `must include ${item}`);
    }

    const expectedTraceVocabulary = {
      "jvm-completion-stage": "jvm-completion-stage:<returned|null|completed-null|failed>",
      "jvm-response-factory": "jvm-response-factory:<sync-throw|failed-stage|transferred-body-close-count>",
      "jvm-runtime-resource": "jvm-runtime-resource:<operation|io|dns|tls|credential|timer|terminal|observer>:<owned|borrowed>:<created|closed|terminated>",
      "jvm-body-publication": "jvm-body-publication:core-attempts:<n>:entity-producers:<n>:wire-publications:<n>",
      "jvm-telemetry": "jvm-telemetry:<capture|start|scope|attempt-start|attempt-end|operation-end>:<ok|nonfatal|fatal>:context:<token>",
      "jvm-fatal": "jvm-fatal:same-instance:<true>:source:<owned|foreign>:rethrown-on:<operation|terminal>",
      "jvm-lock": "jvm-lock:<AdmissionState|WeightedByteBudget|DiagnosticState>:<acquire|release>",
      "jvm-lifecycle": "jvm-lifecycle:self-close:<reactor|operation|terminal|native-cancel|observer|dns-provider|proxy-credential-provider|tls-provider>:return:<CLOSING|CLOSED>:closed:<false|true>:waited:false:interrupted:false:late-return:<none|epoch-rejected>",
    };
    if (JSON.stringify(overlay?.jvmTraceVocabulary) !== JSON.stringify(expectedTraceVocabulary)) fail(`${base}.jvmTraceVocabulary`, "must equal the closed additive JVM trace vocabulary");

    const expectedFormulas = { operationWorkers: "min(32,max(4,cpu*2))", operationQueue: "N", connectionCap: "C=min(N,32) ready-or-establishing pool entries", transientCandidateSockets: "P<=2C complete readiness pipelines", h2SessionCap: "H=min(C,4)", ioWorkers: "min(4,C)", dnsWorkers: "min(2,C)", dnsTotalCapacity: "C", tlsWorkers: "min(4,C)", tlsTotalCapacity: "C", proxyCredentialWorkers: "min(2,C)", proxyCredentialTotalCapacity: "C", timerWorkers: "min(8,max(2,(cpu+1)/2 integer division))", coreOperationDeadlineTasks: "exactly one per admitted operation, capacity N", corePhaseTasks: "at most one reusable connect/attempt/retry task per admitted operation, capacity N", timerQueue: "at most 2N", engineTimerWheelCandidateSlots: "C", engineTimerWheelSweepSlots: "exactly 1", engineTimerWheelTotalSlots: "C+1, reactor-owned with no thread or core scheduler entry", terminalDefaultWorkers: "min(8,max(2,cpu))", terminalWorkers: "min(terminalDefaultWorkers,2N)", terminalQueue: "SynchronousQueue when 2N-terminalWorkers=0; otherwise ArrayBlockingQueue(2N-terminalWorkers)", settlementReservations: "exactly 2N" };
    exactKeys(`${base}.concurrencyProtocol`, overlay?.concurrencyProtocol, ["protocolVersion", "resourceFormulas", "completionRule", "lockRule", "lifecycleRule", "stressRule"]);
    if (overlay?.concurrencyProtocol?.protocolVersion !== 2 || JSON.stringify(overlay?.concurrencyProtocol?.resourceFormulas) !== JSON.stringify(expectedFormulas)) fail(`${base}.concurrencyProtocol.resourceFormulas`, "must equal the exact JVM worker/queue/reservation formulas");
    for (const field of ["completionRule", "lockRule", "lifecycleRule", "stressRule"]) nonEmptyString(`${base}.concurrencyProtocol.${field}`, overlay?.concurrencyProtocol?.[field]);
    const expectedLifecycleRule = "First close linearizes OPEN to CLOSING and uses one original five-second monotonic deadline with exact scheduler -> relocated transport pool/connections/reactor including EngineTimerWheel -> DNS executor -> proxy credential executor -> TLS executor with owned session enumeration/invalidation submitted before shutdown -> operation executor -> terminal settlement pool -> observer dispatcher order; attempt all eight stages and emit at most one ordered close category per failed stage; reactor, operation, terminal, native-cancel, observer, DNS-provider, proxy-credential-provider, and TLS-provider reentrant close skips only current, preserves interrupt status, closes peers, and returns CLOSING with closed=false while current counted ownership remains; current finally epoch-rejects/wipes/releases, and only the coordinator may publish CLOSED at inFlight=0,bufferedBytes=0 and settle the one immutable closeCompletion stage; later close waits only the original remainder, and nonreturning current work leaves honest CLOSING with bounded residual; accepted terminal work is never discarded.";
    if (overlay?.concurrencyProtocol?.lifecycleRule !== expectedLifecycleRule) fail(`${base}.concurrencyProtocol.lifecycleRule`, "must equal the engine close rule's exact eight-stage owned-resource order and single monotonic budget");
    if (/serial dispatcher|cross-operation completion order is promised/iu.test(JSON.stringify(overlay?.concurrencyProtocol))) fail(`${base}.concurrencyProtocol`, "must specify the bounded multi-worker terminal pool and no cross-operation ordering");

    const requiredScenarioSteps = new Map([
      ["jvm-resource-formulas-cpu1-n1", ["derive-all-formulas", "assert-no-prestart", "assert-bounded-queues"]],
      ["jvm-resource-formulas-cpu4-n256", ["derive-all-formulas", "operation-io-dns-tls-credential-pools-distinct", "assert-bounded-queues"]],
      ["jvm-resource-formulas-cpu64-n65536", ["derive-all-formulas", "assert-overflow-safe", "assert-connection-cap-32"]],
      ["jvm-completion-reservation-2n-saturation-reuse", ["block-two-publication-tails", "reject-third-direct-pre-admission", "release-tails", "probe-success"]],
      ["jvm-terminal-primary-self-close-multiworker", ["block-all-terminal-workers", "queue-one-terminal", "release-one-worker", "primary-callback-self-close", "assert-no-self-wait-or-interrupt", "drain-all"]],
      ["jvm-terminal-outcome-self-close-multiworker", ["block-all-terminal-workers", "queue-one-terminal", "release-one-worker", "outcome-callback-self-close", "assert-no-self-wait-or-interrupt", "drain-all"]],
      ["jvm-operation-worker-self-close", ["operation-worker-primary-before-outcome", "self-close", "assert-current-identity-skipped", "drain-others"]],
      ["jvm-native-cancel-self-close", ["native-cancel-primary", "self-close", "assert-native-cancel-identity-skipped", "drain-others"]],
      ["jvm-provider-worker-self-close", ["capture-close-completion-identity", "dns-provider-callback-self-close", "assert-dns-return:CLOSING,closed=false,bytes-counted", "proxy-credential-provider-callback-self-close", "assert-credential-return:CLOSING,closed=false,bytes-counted", "tls-provider-callback-self-close", "assert-tls-return:CLOSING,closed=false,bytes-counted", "assert-only-current-task-skipped", "assert-other-owned-resources-attempted", "assert-interrupt-preserved", "return-provider-results", "assert-late-epochs-rejected-and-wiped", "await-same-close-completion", "assert-runtime:CLOSED,inflight0,buffered0"]],
      ["jvm-timer-storm-65536", ["admit-65536", "register-operation-deadline-and-one-phase-task", "assert-core-timer-queue<=131072", "reuse-phase-connect-attempt-retry", "cancel-all", "assert-exact-remove-on-cancel"]],
      ["jvm-core-timer-phase-reuse-and-ties", ["register-operation-deadline-task", "establishment-phase-due-min-connect-attempt", "assert-connect-wins-exact-tie", "connection-ready-cancel-remove-phase", "reuse-phase-for-attempt", "retry-settle-cancel-remove-phase", "reuse-phase-for-backoff", "assert-stale-generations-noop", "terminal-remove-both"]],
      ["jvm-engine-timer-wheel-c-plus-one-close", ["fill-candidate-slots:C", "assert-sweep-slot:1", "assert-total-slots:C+1", "assert-thread-count:0", "assert-core-scheduler-entries:0", "advance-candidate-cadence:250", "advance-expiry-sweep:5000", "transport-close-clear-all"]],
      ["jvm-terminal-storm-65536", ["offer-131072-terminal-tasks", "assert-running-plus-queued=131072", "assert-each-primary-before-outcome", "assert-no-cross-operation-order", "drain-all"]],
      ["jvm-split-lock-contention-no-global-cas", ["stress-workers:1,8,32", "assert-lock-order:AdmissionState>WeightedByteBudget>DiagnosticState", "assert-no-reverse-acquire", "assert-zero-immutable-state-per-byte", "assert-linearizable-diagnostics"]],
      ["jvm-redeploy-engine-collectability-1000-cycles", ["repeat-context-start-stop:1000", "assert-operation-io-dns-tls-credential-timer-terminal-observer-baseline", "assert-relocated-engine-classloader-collectable-cooperative-paths", "assert-application-classloader-unretained-cooperative-paths"]],
    ]);
    const scenarioIds = (overlay?.concurrencyScenarios || []).map((scenario) => scenario?.scenarioId);
    if (JSON.stringify(scenarioIds) !== JSON.stringify([...requiredScenarioSteps.keys()])) fail(`${base}.concurrencyScenarios`, "must equal the closed ordered JVM concurrency inventory");
    function derivedResources(cpu, n) {
      const operationWorkers = Math.min(32, Math.max(4, cpu * 2));
      const timerWorkers = Math.min(8, Math.max(2, Math.floor((cpu + 1) / 2)));
      const terminalDefaultWorkers = Math.min(8, Math.max(2, cpu));
      const terminalWorkers = Math.min(terminalDefaultWorkers, 2 * n);
      const terminalQueueCapacity = 2 * n - terminalWorkers;
      const connectionCap = Math.min(n, 32);
      return { cpu, maxInFlight: n, settlementReservations: 2 * n, operationWorkers, operationQueueCapacity: n,
        ioWorkers: Math.min(4, connectionCap), dnsWorkers: Math.min(2, connectionCap), dnsTotalCapacity: connectionCap,
        tlsWorkers: Math.min(4, connectionCap), tlsTotalCapacity: connectionCap,
        proxyCredentialWorkers: Math.min(2, connectionCap), proxyCredentialTotalCapacity: connectionCap,
        connectionCap, transientCandidateSocketCap: 2 * connectionCap, h2SessionCap: Math.min(connectionCap, 4),
        timerWorkers, coreOperationDeadlineTaskCapacity: n, corePhaseTaskCapacity: n, timerQueueCapacity: 2 * n,
        engineTimerWheelCandidateSlots: connectionCap, engineTimerWheelSweepSlots: 1, engineTimerWheelTotalSlots: connectionCap + 1,
        terminalDefaultWorkers, terminalWorkers, terminalQueueType: terminalQueueCapacity === 0 ? "SynchronousQueue" : "ArrayBlockingQueue", terminalQueueCapacity };
    }
    for (const [index, scenario] of (overlay?.concurrencyScenarios || []).entries()) {
      const location = `${base}.concurrencyScenarios[${index}]`;
      if (!exactKeys(location, scenario, ["scenarioId", "description", "input", "steps", "expected"])) continue;
      if (!exactKeys(`${location}.input`, scenario.input, ["cpu", "maxInFlight"])) continue;
      integer(`${location}.input.cpu`, scenario.input.cpu, 1, 65536);
      integer(`${location}.input.maxInFlight`, scenario.input.maxInFlight, 1, 65536);
      const steps = requiredScenarioSteps.get(scenario.scenarioId);
      if (JSON.stringify(scenario.steps) !== JSON.stringify(steps)) fail(`${location}.steps`, "must equal the validator-owned deterministic JVM steps");
      if (exactKeys(`${location}.expected`, scenario.expected, ["resources", "assertions", "final"])) {
        const resources = derivedResources(scenario.input.cpu, scenario.input.maxInFlight);
        if (JSON.stringify(scenario.expected.resources) !== JSON.stringify(resources)) fail(`${location}.expected.resources`, "must equal the validator-derived exact JVM resource formulas");
        if (JSON.stringify(scenario.expected.assertions) !== JSON.stringify(steps?.map((step) => `pass:${step}`))) fail(`${location}.expected.assertions`, "must prove every deterministic step");
        const expectedFinal = { inFlight: 0, bufferedBytes: 0, reservations: 0, ownedThreads: 0, borrowedCloseCalls: 0, primaryBeforeOutcomePerOperation: true, crossOperationOrderRequired: false };
        if (JSON.stringify(scenario.expected.final) !== JSON.stringify(expectedFinal)) fail(`${location}.expected.final`, "must prove balanced resources, pool exit, per-operation ordering, and no cross-operation ordering promise");
      }
    }
    const manifest = [...expectedCaseIds, ...requiredScenarioSteps.keys()];
    uniqueStringArray(`${base}.caseManifest`, overlay?.caseManifest);
    if (JSON.stringify(overlay?.caseManifest) !== JSON.stringify(manifest)) fail(`${base}.caseManifest`, "must equal JVM cases followed by JVM concurrency scenarios");
    for (const id of overlay?.caseManifest || []) if ((document.caseManifest || []).includes(id)) fail(`${base}.caseManifest`, "JVM overlay IDs must not collide with shared vector IDs");
  }
  return;
}

function validateJvmRawEvidence(evidencePath, overlay) {
  const base = "$jvmRawEvidence";
  let evidence;
  try {
    evidence = parseCanonicalJsonFile(evidencePath, {
      maxBytes: 1_048_576,
      maxDepth: 16,
      maxTokens: 50_000,
    });
  }
  catch (error) { fail(base, `must be strict scalar-valid unique-key JSON: ${error.message}`); return; }
  const raw = require(path.join(__dirname, "jvm-raw-fixture-contract.js"));
  if (!exactKeys(base, evidence, raw.protocol.evidenceSchema.fields)) return;
  if (evidence.schema !== raw.protocol.evidenceSchema.name) fail(`${base}.schema`, `must equal ${raw.protocol.evidenceSchema.name}`);
  if (evidence.protocolVersion !== 1) fail(`${base}.protocolVersion`, "must equal 1");
  if (exactKeys(`${base}.summary`, evidence.summary, raw.protocol.evidenceSchema.summaryFields)) {
    const expectedSummary = { total: 33, success: 33, skipped: 0, undeclared: 0 };
    if (JSON.stringify(evidence.summary) !== JSON.stringify(expectedSummary)) fail(`${base}.summary`, "must prove all 33 groups SUCCESS with zero skipped or undeclared groups");
  }
  if (!object(evidence.groups)) { fail(`${base}.groups`, "must be an object"); return; }
  const expectedGroupIds = overlay?.jvmEngineContract?.requiredRawFixtureGroups || [];
  if (JSON.stringify(Object.keys(evidence.groups)) !== JSON.stringify(expectedGroupIds)) fail(`${base}.groups`, "must be an exact ordered bijection with requiredRawFixtureGroups");
  for (const groupId of expectedGroupIds) {
    const group = evidence.groups[groupId];
    const contractGroup = raw.groups[groupId];
    const location = `${base}.groups.${groupId}`;
    if (!exactKeys(location, group, raw.evidenceFields)) continue;
    if (group.schema !== raw.protocol.evidenceSchema.name || group.protocolVersion !== 1 || group.groupId !== groupId) fail(location, "must carry the exact evidence schema, protocol version, and group ID");
    if (group.fixtureCaseId !== contractGroup.fixtureCaseId || group.outcome !== "SUCCESS" || group.skipped !== false) fail(location, "must prove the exact fixture case SUCCESS without skip");
    if (JSON.stringify(group.controls) !== JSON.stringify(raw.protocol.selfDriverControls)) fail(`${location}.controls`, "must equal the executable self-driver control trace");
    const expectedBarrierCount = contractGroup.requiredBarriers.length * 3;
    if (!Array.isArray(group.barriers) || group.barriers.length !== expectedBarrierCount) fail(`${location}.barriers`, "must retain an ordered WAITING, RELEASED, RELEASED triple for every required group barrier");
    else {
      const statuses = ["WAITING", "RELEASED", "RELEASED"];
      const times = [0, 1, 1];
      for (const [index, barrier] of group.barriers.entries()) {
        const barrierLocation = `${location}.barriers[${index}]`;
        const requiredBarrier = contractGroup.requiredBarriers[Math.floor(index / 3)];
        const tripleIndex = index % 3;
        if (!exactKeys(barrierLocation, barrier, raw.protocol.barrierSchema.fields)) continue;
        if (barrier.schema !== raw.protocol.barrierSchema.name || barrier.protocolVersion !== 1 || barrier.groupId !== groupId
          || barrier.barrier !== requiredBarrier || barrier.status !== statuses[tripleIndex] || barrier.monotonicTimeMs !== times[tripleIndex]) {
          fail(barrierLocation, "must equal the group-specific deterministic barrier observation");
        }
      }
    }
    if (JSON.stringify(group.observedCounters) !== JSON.stringify(contractGroup.requiredObservedCounters)) fail(`${location}.observedCounters`, "must equal the group-specific independently observed raw counters");
    if (group.state !== "CLOSED") fail(`${location}.state`, "must equal CLOSED without retaining the raw scenario descriptor");
    if (JSON.stringify(group.endpointIds) !== JSON.stringify(contractGroup.requiredEndpointIds)) fail(`${location}.endpointIds`, "must retain only the ordered advertised endpoint IDs");
    if (JSON.stringify(group.assetIds) !== JSON.stringify(contractGroup.requiredAssetIds)) fail(`${location}.assetIds`, "must retain only the ordered advertised asset IDs");
    if (JSON.stringify(group.capabilities) !== JSON.stringify(contractGroup.requiredCapabilities)) fail(`${location}.capabilities`, "must retain only the group-specific safe capability names");
    if (JSON.stringify(group.terminalAssertions) !== JSON.stringify(contractGroup.terminalAssertions)) fail(`${location}.terminalAssertions`, "must equal the closed group-specific terminal assertions");
  }
  const serialized = JSON.stringify(evidence);
  if (/(?:https?|tcp):\/\/|127\.0\.0\.1|\[?::1\]?|\/Users\/|BEGIN (?:CERTIFICATE|PRIVATE KEY)|"(?:controlEndpoint|connectHost|authorityHost|basePath|path|port|password|secret)"/iu.test(serialized)) {
    fail(base, "must retain no ephemeral endpoint, filesystem path, certificate/key material, password, or secret-bearing field");
  }
}

if (overlayDocument) validateJvmOverlay(overlayDocument);
if (rawEvidencePath !== null && overlayDocument) validateJvmRawEvidence(rawEvidencePath, overlayDocument);

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

if (inventoryTarget) {
  const inventory = inventoryTarget === "JVM" ? [...document.caseManifest, ...overlayDocument.caseManifest] : [...document.caseManifest];
  console.log(JSON.stringify(inventory));
} else if (overlayDocument) {
  console.log(`transport contract v${document.contractVersion}: shared ${document.caseManifest.length} vectors plus JVM ${overlayDocument.caseManifest.length} vectors valid`);
} else {
  console.log(`transport contract v${document.contractVersion}: ${document.cases.length} single-operation cases and ${document.concurrencyScenarios.length} concurrency scenarios valid`);
}
