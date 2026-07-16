#!/usr/bin/env node
"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");

const HOST = "127.0.0.1";
const DATA_PATH = "/v1/messages";
const CONTROL_PATH = "/__fixture";
const REDIRECT_TARGET_PATH = `${CONTROL_PATH}/redirect-target`;
const MAX_CONTROL_BODY_BYTES = 16 * 1024;
const FIXTURE_PROTOCOL = "repost-transport-fixture";

const FIXTURE_ACTIONS = new Set([
  "acceptRequest",
  "captureRequestMetadata",
  "disconnect",
  "failConnection",
  "failProxyAuthentication",
  "hold",
  "respond",
  "respondChunks",
  "respondEchoSentinels",
]);
const RUNNER_ACTIONS = new Set([
  "advanceTime",
  "advanceWallTime",
  "cancelSend",
  "cancelQueuedWorker",
  "closeClient",
  "closeQueuedWorker",
  "drainRuntimeDiagnostics",
  "rejectDeadlineScheduler",
  "rejectExecutorSubmission",
  "rejectRetryScheduler",
  "starveQueuedWorkerPastDeadline",
  "observerFail",
  "release",
]);
const TERMINAL_ACTIONS = new Set([
  "disconnect",
  "failConnection",
  "failProxyAuthentication",
  "respond",
  "respondChunks",
  "respondEchoSentinels",
]);
const CAPTURE_KEYS = [
  "attempt",
  "commitment",
  "responseProgress",
  "idempotencyKey",
];

class FixtureProtocolError extends Error {
  constructor(code, message, statusCode = 400) {
    super(message);
    this.name = "FixtureProtocolError";
    this.code = code;
    this.statusCode = statusCode;
  }
}

function protocolError(code, message, statusCode) {
  return new FixtureProtocolError(code, message, statusCode);
}

function exactKeys(value, expected) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return JSON.stringify(actual) === JSON.stringify(wanted);
}

function integer(value, minimum, label) {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw protocolError("INVALID_CONTRACT", `${label} must be an integer >= ${minimum}.`);
  }
  return value;
}

function bodyBuffer(recipe, assets, seen = new Set()) {
  if (recipe === null || typeof recipe !== "object" || Array.isArray(recipe)) {
    throw protocolError("INVALID_BODY_RECIPE", "A fixture body recipe must be an object.");
  }
  if (recipe.kind === undefined && typeof recipe.asset === "string") {
    if (!Object.prototype.hasOwnProperty.call(assets, recipe.asset)) {
      throw protocolError("INVALID_BODY_RECIPE", "A fixture body references an unknown asset.");
    }
    if (seen.has(recipe.asset)) {
      throw protocolError("INVALID_BODY_RECIPE", "A fixture body asset cycle is not allowed.");
    }
    const nextSeen = new Set(seen);
    nextSeen.add(recipe.asset);
    return bodyBuffer(assets[recipe.asset], assets, nextSeen);
  }

  switch (recipe.kind) {
    case "base64": {
      if (typeof recipe.data !== "string") {
        throw protocolError("INVALID_BODY_RECIPE", "A base64 body requires string data.");
      }
      const decoded = Buffer.from(recipe.data, "base64");
      if (decoded.toString("base64") !== recipe.data) {
        throw protocolError("INVALID_BODY_RECIPE", "A base64 body is not canonical RFC 4648 data.");
      }
      return decoded;
    }
    case "repeat": {
      integer(recipe.byte, 0, "repeat.byte");
      integer(recipe.length, 0, "repeat.length");
      if (recipe.byte > 255) {
        throw protocolError("INVALID_BODY_RECIPE", "repeat.byte must be <= 255.");
      }
      return Buffer.alloc(recipe.length, recipe.byte);
    }
    case "paddedAsset": {
      integer(recipe.totalLength, 0, "paddedAsset.totalLength");
      const source = bodyBuffer({ asset: recipe.asset }, assets, seen);
      if (source.length > recipe.totalLength) {
        throw protocolError("INVALID_BODY_RECIPE", "paddedAsset totalLength is smaller than its asset.");
      }
      return Buffer.concat([
        source,
        Buffer.alloc(recipe.totalLength - source.length, 0x20),
      ]);
    }
    case "gzipStored":
      return gzipStored(bodyBuffer(recipe.source, assets, seen));
    case "concatenated": {
      if (!Array.isArray(recipe.members) || recipe.members.length === 0) {
        throw protocolError("INVALID_BODY_RECIPE", "A concatenated body requires members.");
      }
      return Buffer.concat(recipe.members.map((member) => {
        if (typeof member === "string") return bodyBuffer({ asset: member }, assets, seen);
        return bodyBuffer(member, assets, seen);
      }));
    }
    case "jsonRecipe": {
      integer(recipe.count, 0, "jsonRecipe.count");
      if (recipe.shape === "nestedArrays") {
        return Buffer.from(`${"[".repeat(recipe.count)}0${"]".repeat(recipe.count)}`, "utf8");
      }
      if (recipe.shape === "flatObject") {
        return Buffer.from(`{${Array.from({ length: recipe.count }, (_, index) => `"k${index}":0`).join(",")}}`, "utf8");
      }
      throw protocolError("INVALID_BODY_RECIPE", "A jsonRecipe shape must be nestedArrays or flatObject.");
    }
    default:
      throw protocolError("INVALID_BODY_RECIPE", "The fixture body kind is unsupported.");
  }
}

let crcTable;
function crc32(buffer) {
  if (!crcTable) {
    crcTable = Array.from({ length: 256 }, (_, index) => {
      let value = index;
      for (let bit = 0; bit < 8; bit += 1) {
        value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
      }
      return value >>> 0;
    });
  }
  let crc = 0xffffffff;
  for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function gzipStored(source) {
  const blocks = [];
  if (source.length === 0) blocks.push(Buffer.from([1, 0, 0, 255, 255]));
  for (let offset = 0; offset < source.length; offset += 65535) {
    const length = Math.min(65535, source.length - offset);
    const header = Buffer.alloc(5);
    header[0] = offset + length === source.length ? 1 : 0;
    header.writeUInt16LE(length, 1);
    header.writeUInt16LE((~length) & 0xffff, 3);
    blocks.push(header, source.subarray(offset, offset + length));
  }
  const trailer = Buffer.alloc(8);
  trailer.writeUInt32LE(crc32(source), 0);
  trailer.writeUInt32LE(source.length >>> 0, 4);
  return Buffer.concat([
    Buffer.from([0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff]),
    ...blocks,
    trailer,
  ]);
}

function sha256(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function createRawHeaderProbe() {
  let phase = "request-line";
  let headerName = "";
  let selectedHeader = null;
  let authorizationHash = null;
  let authorizationDigests = [];
  let contentLengthDigits = "";
  let transferEncoding = "";
  let requestContentLength = null;
  let bodyRemaining = 0;
  const requests = [];

  function resetHeaderLine() {
    headerName = "";
    selectedHeader = null;
    authorizationHash = null;
    contentLengthDigits = "";
  }

  function beginHeaderValue() {
    selectedHeader = headerName === "authorization"
      || headerName === "content-length"
      || headerName === "transfer-encoding"
      ? headerName
      : null;
    if (selectedHeader === "authorization") authorizationHash = crypto.createHash("sha256");
  }

  function consumeHeaderValueByte(byte) {
    if (selectedHeader === "authorization") authorizationHash.update(Buffer.from([byte]));
    if (selectedHeader === "content-length") {
      if (byte >= 0x30 && byte <= 0x39 && contentLengthDigits.length < 20) {
        contentLengthDigits += String.fromCharCode(byte);
      } else {
        contentLengthDigits = "invalid";
      }
    }
    if (selectedHeader === "transfer-encoding" && transferEncoding.length < 64) {
      transferEncoding += String.fromCharCode(byte).toLowerCase();
    }
  }

  function finishHeaderValue() {
    if (selectedHeader === "authorization") authorizationDigests.push(authorizationHash.digest("hex"));
    if (selectedHeader === "content-length" && /^[0-9]+$/.test(contentLengthDigits)) {
      const parsed = Number(contentLengthDigits);
      if (Number.isSafeInteger(parsed)) requestContentLength = parsed;
    }
  }

  function finishHeaders() {
    requests.push({ authorizationDigests });
    authorizationDigests = [];
    bodyRemaining = transferEncoding.split(",").some((value) => value.trim() === "chunked")
      ? -1
      : requestContentLength ?? 0;
    requestContentLength = null;
    transferEncoding = "";
    resetHeaderLine();
    phase = bodyRemaining === 0 ? "request-line" : "body";
  }

  function consume(buffer) {
    for (let index = 0; index < buffer.length; index += 1) {
      const byte = buffer[index];
      if (phase === "body") {
        if (bodyRemaining < 0) return;
        const available = buffer.length - index;
        const consumed = Math.min(bodyRemaining, available);
        bodyRemaining -= consumed;
        index += consumed - 1;
        if (bodyRemaining === 0) phase = "request-line";
        continue;
      }
      if (phase === "request-line") {
        if (byte === 0x0d) phase = "request-line-lf";
        continue;
      }
      if (phase === "request-line-lf") {
        phase = byte === 0x0a ? "header-name" : "request-line";
        continue;
      }
      if (phase === "header-name") {
        if (byte === 0x0d && headerName.length === 0) {
          phase = "headers-end-lf";
        } else if (byte === 0x3a) {
          beginHeaderValue();
          phase = "header-leading-ows";
        } else if (headerName.length < 256) {
          headerName += String.fromCharCode(byte).toLowerCase();
        }
        continue;
      }
      if (phase === "header-leading-ows") {
        if (byte === 0x20 || byte === 0x09) continue;
        if (byte === 0x0d) {
          finishHeaderValue();
          phase = "header-line-lf";
        } else {
          consumeHeaderValueByte(byte);
          phase = "header-value";
        }
        continue;
      }
      if (phase === "header-value") {
        if (byte === 0x0d) {
          finishHeaderValue();
          phase = "header-line-lf";
        } else {
          consumeHeaderValueByte(byte);
        }
        continue;
      }
      if (phase === "header-line-lf") {
        resetHeaderLine();
        phase = byte === 0x0a ? "header-name" : "request-line";
        continue;
      }
      if (phase === "headers-end-lf") {
        if (byte === 0x0a) finishHeaders();
        else phase = "request-line";
      }
    }
  }

  return {
    consume,
    take() {
      return requests.shift() || { authorizationDigests: [] };
    },
  };
}

function selectedCredential(testCase, inputProfiles) {
  const input = testCase.input || {};
  const profile = inputProfiles[input.profile] || {};
  const env = { ...(profile.env || {}), ...(input.env || {}) };
  const options = testCase.options || {};
  const nonblank = (value) => typeof value === "string" && value.length > 0 && !/^[ \t]*$/.test(value);
  if (nonblank(options.apiKey)) return options.apiKey;
  if (nonblank(env.REPOST_SEND_API_KEY)) {
    return env.REPOST_SEND_API_KEY;
  }
  if (nonblank(env.REPOST_TOKEN)) return env.REPOST_TOKEN;
  return null;
}

function compileCase(testCase, document) {
  if (!testCase || typeof testCase.caseId !== "string" || !Array.isArray(testCase.script)) {
    throw protocolError("INVALID_CONTRACT", "A fixture case has an invalid shape.");
  }
  const attempts = new Map();
  const barriers = new Map();
  const assets = document.fixtureProtocol.bodyAssets;
  const credential = selectedCredential(testCase, document.inputProfiles);
  const expectedAuthorizationDigest = credential === null ? null : sha256(`Bearer ${credential}`);

  for (const action of testCase.script) {
    if (action === null || typeof action !== "object" || Array.isArray(action)) {
      throw protocolError("INVALID_ACTION", "A fixture action must be an object.");
    }
    if (action.actor === "runner") {
      if (!RUNNER_ACTIONS.has(action.action)) {
        throw protocolError("INVALID_ACTION", "A canonical case contains an unsupported runner action.");
      }
      continue;
    }
    if (action.actor !== "fixture" || !FIXTURE_ACTIONS.has(action.action)) {
      throw protocolError("INVALID_ACTION", "A canonical case contains an unsupported fixture action.");
    }
    const attempt = integer(action.attempt, 1, "action.attempt");
    const plan = attempts.get(attempt) || { actions: [], terminal: null, holds: [] };
    plan.actions.push(action);
    if (action.action === "hold") {
      if (typeof action.barrier !== "string" || barriers.has(action.barrier)) {
        throw protocolError("INVALID_ACTION", "Fixture barriers must have unique string names.");
      }
      if (!["beforeRequestBytes", "afterRequestBytes", "responseHeaders", "responseBody"].includes(action.phase)) {
        throw protocolError("UNREPRESENTABLE_ACTION", "The HTTP fixture cannot execute this hold phase.");
      }
      const barrier = {
        name: action.barrier,
        attempt,
        phase: action.phase,
        releaseAt: action.releaseAt ?? null,
        state: "registered",
        waiters: new Set(),
      };
      if (barrier.releaseAt !== null) integer(barrier.releaseAt, 1, "hold.releaseAt");
      barriers.set(action.barrier, barrier);
      plan.holds.push(barrier);
    }
    if (TERMINAL_ACTIONS.has(action.action)) {
      if (plan.terminal !== null) {
        throw protocolError("INVALID_ACTION", "An attempt has more than one terminal fixture action.");
      }
      plan.terminal = action;
      if (action.action === "respond") bodyBuffer(action.body, assets);
      if (action.action === "respondChunks") {
        if (!Array.isArray(action.chunks) || action.chunks.length === 0) {
          throw protocolError("INVALID_ACTION", "respondChunks requires at least one chunk.");
        }
        action.chunks.forEach((chunk) => bodyBuffer(chunk, assets));
      }
    }
    attempts.set(attempt, plan);
  }
  if (attempts.size === 0) {
    throw protocolError("UNREPRESENTABLE_CASE", "The canonical case has no HTTP fixture actions.");
  }
  return {
    caseId: testCase.caseId,
    attempts,
    barriers,
    expectedAuthorizationDigest,
  };
}

function stateBarrier(barrier) {
  return {
    barrier: barrier.name,
    attempt: barrier.attempt,
    phase: barrier.phase,
    releaseAt: barrier.releaseAt,
    state: barrier.state,
  };
}

function assertCapture(capture, denylist) {
  if (JSON.stringify(Object.keys(capture)) !== JSON.stringify(CAPTURE_KEYS)) {
    throw protocolError("CAPTURE_POLICY_VIOLATION", "Private capture did not match the closed allowlist.", 500);
  }
  const forbidden = new Set(denylist);
  const visit = (value) => {
    if (value === null || typeof value !== "object") return;
    for (const [key, child] of Object.entries(value)) {
      if (forbidden.has(key)) {
        throw protocolError("CAPTURE_POLICY_VIOLATION", "Private capture retained a forbidden field.", 500);
      }
      visit(child);
    }
  };
  visit(capture);
}

function safeState(runtime, captureDenylist) {
  const captures = runtime.captures.map((capture) => {
    assertCapture(capture, captureDenylist);
    return {
      attempt: capture.attempt,
      commitment: capture.commitment,
      responseProgress: { ...capture.responseProgress },
      idempotencyKey: capture.idempotencyKey,
    };
  });
  return {
    status: runtime.status,
    ready: runtime.status === "loaded",
    caseId: runtime.plan ? runtime.plan.caseId : null,
    nextAttempt: runtime.nextAttempt,
    activeRequests: runtime.contexts.size,
    barriers: runtime.plan
      ? [...runtime.plan.barriers.values()].map(stateBarrier).sort((a, b) => a.barrier.localeCompare(b.barrier))
      : [],
    attempts: runtime.attempts.map((attempt) => ({ ...attempt })),
    captures,
    redirectTargetHits: runtime.redirectTargetHits,
  };
}

function jsonResponse(res, status, value, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, {
    "content-type": "application/json",
    "content-length": body.length,
    ...extraHeaders,
  });
  res.end(body);
}

function safeError(res, error) {
  const known = error instanceof FixtureProtocolError;
  jsonResponse(res, known ? error.statusCode : 500, {
    error: {
      code: known ? error.code : "FIXTURE_INTERNAL_ERROR",
      message: known ? error.message : "The fixture encountered an internal error.",
    },
  });
}

async function readControlJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_CONTROL_BODY_BYTES) {
      throw protocolError("CONTROL_BODY_TOO_LARGE", "The control body exceeds 16384 bytes.", 413);
    }
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw protocolError("INVALID_CONTROL_JSON", "The control body must be valid JSON.");
  }
}

function createFixtureServer(options = {}) {
  const contractPath = options.contractPath || path.join(__dirname, "v2.json");
  const document = options.contract || JSON.parse(fs.readFileSync(contractPath, "utf8"));
  if (document.contractVersion !== 2 || !document.fixtureProtocol || !Array.isArray(document.cases)) {
    throw protocolError("INVALID_CONTRACT", "The transport fixture requires contract version 2.");
  }
  if (JSON.stringify(document.fixtureProtocol.captureAllowlist) !== JSON.stringify(CAPTURE_KEYS)) {
    throw protocolError("INVALID_CONTRACT", "The transport fixture capture allowlist does not match version 2.");
  }
  const caseIndex = new Map(document.cases.map((testCase) => [testCase.caseId, testCase]));
  if (caseIndex.size !== document.cases.length) {
    throw protocolError("INVALID_CONTRACT", "Fixture case IDs must be unique.");
  }
  const runtime = {
    status: "idle",
    plan: null,
    nextAttempt: 1,
    contexts: new Set(),
    attempts: [],
    captures: [],
    redirectTargetHits: 0,
  };
  const sockets = new Set();
  const rawHeaderProbes = new WeakMap();
  let listening = false;
  let closePromise = null;
  let handshake = null;
  let emittedHandshake = false;

  function abortContexts() {
    for (const context of runtime.contexts) {
      context.aborted = true;
      for (const resolve of context.abortWaiters) resolve(false);
      context.abortWaiters.clear();
      if (!context.res.destroyed) context.res.destroy();
    }
    runtime.contexts.clear();
  }

  function reset() {
    abortContexts();
    if (runtime.plan) {
      for (const barrier of runtime.plan.barriers.values()) {
        for (const resolve of barrier.waiters) resolve(false);
        barrier.waiters.clear();
      }
    }
    runtime.status = "idle";
    runtime.plan = null;
    runtime.nextAttempt = 1;
    runtime.attempts = [];
    runtime.captures = [];
    runtime.redirectTargetHits = 0;
  }

  function load(caseId) {
    if (runtime.contexts.size !== 0) {
      throw protocolError("FIXTURE_BUSY", "Reset the active fixture request before loading another case.", 409);
    }
    const testCase = caseIndex.get(caseId);
    if (!testCase) throw protocolError("UNKNOWN_CASE", "The requested canonical case does not exist.", 404);
    reset();
    runtime.plan = compileCase(testCase, document);
    runtime.status = "loaded";
    return safeState(runtime, document.fixtureProtocol.captureDenylist);
  }

  function release(barrierName) {
    if (!runtime.plan) throw protocolError("NO_CASE_LOADED", "Load a case before releasing a barrier.", 409);
    const barrier = runtime.plan.barriers.get(barrierName);
    if (!barrier) throw protocolError("UNKNOWN_BARRIER", "The named barrier is not registered.", 404);
    if (barrier.state === "registered") {
      throw protocolError("BARRIER_NOT_REACHED", "The named barrier has not been reached.", 409);
    }
    if (barrier.state === "released") {
      throw protocolError("BARRIER_ALREADY_RELEASED", "The named barrier was already released.", 409);
    }
    barrier.state = "released";
    for (const resolve of barrier.waiters) resolve(true);
    barrier.waiters.clear();
    return safeState(runtime, document.fixtureProtocol.captureDenylist);
  }

  async function waitForBarrier(barrier, context) {
    if (!barrier) return true;
    if (barrier.state === "released") return true;
    barrier.state = "reached";
    let waiter;
    return new Promise((resolve) => {
      waiter = resolve;
      barrier.waiters.add(resolve);
      context.abortWaiters.add(resolve);
    }).finally(() => {
      barrier.waiters.delete(waiter);
      context.abortWaiters.delete(waiter);
    });
  }

  function holdFor(plan, phase) {
    return plan.holds.find((barrier) => barrier.phase === phase) || null;
  }

  function captureFor(plan, attempt, req) {
    if (!plan.actions.some((action) => action.action === "captureRequestMetadata")) return null;
    const value = req.headers["idempotency-key"];
    const idempotencyKey = Array.isArray(value) ? value[0] ?? null : value ?? null;
    const capture = {
      attempt,
      commitment: false,
      responseProgress: { phase: "not-started", bytes: 0, complete: false },
      idempotencyKey,
    };
    assertCapture(capture, document.fixtureProtocol.captureDenylist);
    runtime.captures.push(capture);
    return capture;
  }

  function validateRequestAssertions(plan, rawRequest, req, context) {
    const assertions = plan.actions.flatMap((action) => action.action === "acceptRequest" ? action.assertions : []);
    if (assertions.includes("authorization-octets-preserved")) {
      if (runtime.plan.expectedAuthorizationDigest === null) {
        throw protocolError("REQUEST_ASSERTION_FAILED", "The canonical authorization assertion has no expected credential.", 500);
      }
      if (rawRequest.authorizationDigests.length !== 1
        || rawRequest.authorizationDigests[0] !== runtime.plan.expectedAuthorizationDigest) {
        throw protocolError("REQUEST_ASSERTION_FAILED", "The request did not satisfy the canonical authorization assertion.", 422);
      }
    }
    const rawHeaderValues = (target) => {
      const values = [];
      for (let index = 0; index < req.rawHeaders.length; index += 2) {
        if (req.rawHeaders[index].toLowerCase() === target) values.push(req.rawHeaders[index + 1]);
      }
      return values;
    };
    if (assertions.includes("request-shape-headers")) {
      const rawHeaderNames = [];
      for (let index = 0; index < req.rawHeaders.length; index += 2) rawHeaderNames.push(req.rawHeaders[index].toLowerCase());
      const requiredOrder = ["host", "content-length", "authorization", "content-type", "accept-encoding", "user-agent", "idempotency-key"];
      const hasTraceparent = rawHeaderNames.at(-1) === "traceparent";
      const expectedOrder = hasTraceparent ? [...requiredOrder, "traceparent"] : requiredOrder;
      const host = rawHeaderValues("host");
      const contentLength = rawHeaderValues("content-length");
      const authorization = rawHeaderValues("authorization");
      const contentType = rawHeaderValues("content-type");
      const acceptEncoding = rawHeaderValues("accept-encoding");
      const userAgent = rawHeaderValues("user-agent");
      const idempotencyKey = rawHeaderValues("idempotency-key");
      const traceparent = rawHeaderValues("traceparent");
      const expectedHost = `${HOST}:${server.address().port}`;
      const canonicalContentLength = contentLength.length === 1 && /^(?:0|[1-9][0-9]*)$/u.test(contentLength[0]);
      const validTraceparent = traceparent.length === 0
        || (traceparent.length === 1 && /^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$/u.test(traceparent[0])
          && traceparent[0].slice(3, 35) !== "0".repeat(32) && traceparent[0].slice(36, 52) !== "0".repeat(16));
      if (req.method !== "POST" || req.url !== DATA_PATH || req.httpVersion !== "1.1"
        || JSON.stringify(rawHeaderNames) !== JSON.stringify(expectedOrder)
        || JSON.stringify(host) !== JSON.stringify([expectedHost])
        || !canonicalContentLength
        || authorization.length !== 1
        || JSON.stringify(contentType) !== JSON.stringify(["application/json"])
        || JSON.stringify(acceptEncoding) !== JSON.stringify(["gzip"])
        || userAgent.length !== 1
        || !/^repost-[a-z0-9.-]+\/[0-9A-Za-z.+-]+ contract-suite\/1$/u.test(userAgent[0])
        || JSON.stringify(idempotencyKey) !== JSON.stringify(["idem_request_shape"])
        || !validTraceparent) {
        throw protocolError("REQUEST_ASSERTION_FAILED", "The request did not satisfy the canonical method/path and header-shape assertion.", 422);
      }
      context.expectedRequestContentLength = Number(contentLength[0]);
    }
    if (assertions.includes("user-agent-suffix-boundary-256")) {
      const userAgent = rawHeaderValues("user-agent");
      if (userAgent.length !== 1 || !new RegExp(`^repost-[a-z0-9.-]+\\/[0-9A-Za-z.+-]+ ${"u".repeat(256)}$`, "u").test(userAgent[0])) {
        throw protocolError("REQUEST_ASSERTION_FAILED", "The request did not preserve the exact 256-octet User-Agent suffix boundary.", 422);
      }
    }
  }

  function markAttempt(context, terminal) {
    context.attemptState.terminal = terminal;
    context.attemptState.phase = "complete";
  }

  async function consumeRequest(req, context, afterBytesBarrier, disconnectAfterFirstByte) {
    let held = false;
    for await (const chunk of req) {
      if (context.aborted) return false;
      if (chunk.length === 0) continue;
      if (!context.capture?.commitment) {
        if (context.capture) context.capture.commitment = true;
      }
      const threshold = disconnectAfterFirstByte ? 1 : afterBytesBarrier?.releaseAt;
      if (threshold && !held && context.attemptState.requestBytes < threshold) {
        context.attemptState.requestBytes = Math.min(threshold, context.attemptState.requestBytes + chunk.length);
      } else {
        context.attemptState.requestBytes += chunk.length;
      }
      if (disconnectAfterFirstByte) return "disconnect";
      if (afterBytesBarrier && !held && context.attemptState.requestBytes >= afterBytesBarrier.releaseAt) {
        held = true;
        req.pause();
        context.attemptState.phase = "afterRequestBytes";
        const released = await waitForBarrier(afterBytesBarrier, context);
        if (!released || context.aborted) return false;
        req.resume();
      }
    }
    return !context.aborted;
  }

  function consumeFirstRequestByte(req, context) {
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        req.off("data", onData);
        req.off("end", onEnd);
        req.off("error", onError);
        req.off("aborted", onAborted);
      };
      const onData = (chunk) => {
        if (chunk.length === 0) return;
        req.pause();
        context.attemptState.requestBytes = 1;
        if (context.capture) context.capture.commitment = true;
        cleanup();
        resolve(true);
      };
      const onEnd = () => {
        cleanup();
        resolve(false);
      };
      const onError = (error) => {
        cleanup();
        reject(error);
      };
      const onAborted = () => {
        cleanup();
        resolve(false);
      };
      req.on("data", onData);
      req.once("end", onEnd);
      req.once("error", onError);
      req.once("aborted", onAborted);
      req.resume();
    });
  }

  function responseChunks(action) {
    if (action.action === "respond") return [bodyBuffer(action.body, document.fixtureProtocol.bodyAssets)];
    if (action.action === "respondChunks") {
      return action.chunks.map((chunk) => bodyBuffer(chunk, document.fixtureProtocol.bodyAssets));
    }
    if (action.action === "respondEchoSentinels") {
      const echo = {};
      for (const name of action.sentinels) echo[name] = document.sentinels[name];
      const value = {
        code: "remote",
        message: document.sentinels.remoteMessage,
        requestId: "req_echo",
        echo,
      };
      return [Buffer.from(JSON.stringify(value), "utf8")];
    }
    return [];
  }

  function rawResponseHeaders(headers) {
    const pairs = [];
    for (const [name, value] of Object.entries(headers)) {
      const values = Array.isArray(value) ? value : [value];
      for (const item of values) {
        if (typeof item !== "string") {
          throw protocolError("INVALID_RESPONSE_HEADER", "Canonical response header values must be strings.", 500);
        }
        pairs.push(name, item);
      }
    }
    return pairs;
  }

  async function sendResponse(action, plan, context) {
    const { res, capture, attemptState } = context;
    const headersBarrier = holdFor(plan, "responseHeaders");
    if (headersBarrier) {
      attemptState.phase = "responseHeaders";
      if (!await waitForBarrier(headersBarrier, context)) return;
    }
    const headers = action.action === "respondEchoSentinels"
      ? { "content-type": "application/json" }
      : action.headers;
    res.writeHead(action.status, rawResponseHeaders(headers));
    if (capture) capture.responseProgress.phase = "headers";
    attemptState.phase = "responseBody";

    const chunks = responseChunks(action);
    const bodyBarrier = holdFor(plan, "responseBody");
    let emitted = 0;
    let barrierReached = false;
    for (const chunk of chunks) {
      if (context.aborted) return;
      if (bodyBarrier && !barrierReached && emitted < bodyBarrier.releaseAt && emitted + chunk.length >= bodyBarrier.releaseAt) {
        const split = bodyBarrier.releaseAt - emitted;
        if (split > 0) {
          res.write(chunk.subarray(0, split));
          emitted += split;
          attemptState.responseBytes += split;
          if (capture) capture.responseProgress.bytes += split;
        }
        barrierReached = true;
        if (!await waitForBarrier(bodyBarrier, context)) return;
        const suffix = chunk.subarray(split);
        if (suffix.length > 0) {
          res.write(suffix);
          emitted += suffix.length;
          attemptState.responseBytes += suffix.length;
          if (capture) capture.responseProgress.bytes += suffix.length;
        }
      } else {
        res.write(chunk);
        emitted += chunk.length;
        attemptState.responseBytes += chunk.length;
        if (capture) capture.responseProgress.bytes += chunk.length;
      }
    }
    if (bodyBarrier && !barrierReached) {
      throw protocolError("INVALID_BODY_BARRIER", "The response-body barrier exceeds the materialized body.", 500);
    }

    if (action.action === "respondChunks" && action.complete === false) {
      markAttempt(context, "incomplete-response");
      if (res.socket) res.socket.end();
      return;
    }
    if (capture) {
      capture.responseProgress.phase = "complete";
      capture.responseProgress.complete = true;
    }
    markAttempt(context, `response-${action.status}`);
    res.end();
  }

  function handleRedirectTarget(req, res) {
    const redirectCases = new Set(["configuration-redirect-refused", "configuration-redirect-301-refused", "configuration-redirect-303-refused", "configuration-redirect-307-refused", "configuration-redirect-308-refused"]);
    if (runtime.status !== "loaded" || !redirectCases.has(runtime.plan?.caseId)) {
      throw protocolError("REDIRECT_TARGET_NOT_ARMED", "Load a canonical redirect-refusal case before using the redirect target.", 409);
    }
    runtime.redirectTargetHits += 1;
    req.resume();
    const body = bodyBuffer({ asset: "validIdentity" }, document.fixtureProtocol.bodyAssets);
    res.writeHead(202, {
      "content-type": "application/json",
      "content-length": body.length,
    });
    res.end(body);
  }

  async function handleData(req, res, rawRequest) {
    if (req.method !== "POST" || req.url !== DATA_PATH) {
      throw protocolError("UNKNOWN_DATA_ENDPOINT", "The fixture data endpoint is POST /v1/messages.", 404);
    }
    if (runtime.status !== "loaded" || !runtime.plan) {
      throw protocolError("NO_CASE_LOADED", "Load a canonical fixture case before sending.", 409);
    }
    const attempt = runtime.nextAttempt;
    const plan = runtime.plan.attempts.get(attempt);
    if (!plan) throw protocolError("UNEXPECTED_ATTEMPT", "The canonical case has no action for this attempt.", 409);
    runtime.nextAttempt += 1;
    const attemptState = {
      attempt,
      phase: "beforeRequestBytes",
      requestBytes: 0,
      responseBytes: 0,
      terminal: null,
    };
    runtime.attempts.push(attemptState);
    const context = {
      req,
      res,
      attemptState,
      capture: captureFor(plan, attempt, req),
      aborted: false,
      abortWaiters: new Set(),
    };
    runtime.contexts.add(context);
    const cleanup = () => runtime.contexts.delete(context);
    const abortContext = () => {
      context.aborted = true;
      for (const resolve of context.abortWaiters) resolve(false);
      context.abortWaiters.clear();
    };
    res.once("close", () => {
      if (!res.writableEnded) abortContext();
      cleanup();
    });
    req.once("aborted", abortContext);

    try {
      validateRequestAssertions(plan, rawRequest, req, context);
      const terminal = plan.terminal;
      if (terminal && ["disconnect", "failConnection"].includes(terminal.action) && terminal.phase === "beforeRequestBytes") {
        markAttempt(context, terminal.action === "disconnect" ? "disconnect-before-request" : "connection-failure-before-request");
        req.socket.destroy();
        return;
      }
      const beforeBarrier = holdFor(plan, "beforeRequestBytes");
      if (beforeBarrier && !await waitForBarrier(beforeBarrier, context)) return;

      const afterBarrier = holdFor(plan, "afterRequestBytes");
      const disconnectAfter = terminal
        && ["disconnect", "failConnection"].includes(terminal.action)
        && terminal.phase === "afterRequestBytes";
      if (disconnectAfter) {
        const committed = await consumeFirstRequestByte(req, context);
        if (!committed) {
          throw protocolError("EXPECTED_REQUEST_BYTE", "The connection ended before the committed disconnect point.", 422);
        }
        markAttempt(context, terminal.action === "disconnect" ? "disconnect-after-request" : "connection-failure-after-request");
        req.socket.destroy();
        return;
      }
      const consumed = await consumeRequest(req, context, afterBarrier, false);
      if (!consumed) return;
      if (context.expectedRequestContentLength !== undefined && context.expectedRequestContentLength !== context.attemptState.requestBytes) {
        throw protocolError("REQUEST_ASSERTION_FAILED", "Content-Length did not equal the actual request-body byte count.", 422);
      }
      if (!terminal) {
        const headersBarrier = holdFor(plan, "responseHeaders");
        if (headersBarrier) {
          attemptState.phase = "responseHeaders";
          if (!await waitForBarrier(headersBarrier, context)) return;
        }
        throw protocolError("NO_TERMINAL_ACTION", "The released HTTP attempt has no terminal fixture action.", 500);
      }
      await sendResponse(terminal, plan, context);
      if (context.capture) assertCapture(context.capture, document.fixtureProtocol.captureDenylist);
    } catch (error) {
      if (res.destroyed || res.headersSent) {
        if (!res.destroyed) res.destroy();
        return;
      }
      safeError(res, error);
    }
  }

  async function handleControl(req, res, pathname) {
    if (req.method === "GET" && pathname === `${CONTROL_PATH}/state`) {
      jsonResponse(res, 200, safeState(runtime, document.fixtureProtocol.captureDenylist));
      return;
    }
    if (req.method !== "POST") {
      throw protocolError("UNKNOWN_CONTROL_ENDPOINT", "The fixture control endpoint is not available.", 404);
    }
    const body = await readControlJson(req);
    if (pathname === `${CONTROL_PATH}/load`) {
      if (!exactKeys(body, ["caseId"]) || typeof body.caseId !== "string") {
        throw protocolError("INVALID_LOAD_REQUEST", "Load accepts exactly one string caseId.");
      }
      jsonResponse(res, 200, load(body.caseId));
      return;
    }
    if (pathname === `${CONTROL_PATH}/release`) {
      if (!exactKeys(body, ["barrier"]) || typeof body.barrier !== "string") {
        throw protocolError("INVALID_RELEASE_REQUEST", "Release accepts exactly one string barrier.");
      }
      jsonResponse(res, 200, release(body.barrier));
      return;
    }
    if (pathname === `${CONTROL_PATH}/reset`) {
      if (!exactKeys(body, [])) throw protocolError("INVALID_RESET_REQUEST", "Reset accepts an empty object.");
      reset();
      jsonResponse(res, 200, safeState(runtime, document.fixtureProtocol.captureDenylist));
      return;
    }
    if (pathname === `${CONTROL_PATH}/close`) {
      if (!exactKeys(body, [])) throw protocolError("INVALID_CLOSE_REQUEST", "Close accepts an empty object.");
      runtime.status = "closing";
      jsonResponse(res, 200, { status: "closing" }, { connection: "close" });
      res.once("finish", () => setImmediate(() => close()));
      return;
    }
    throw protocolError("UNKNOWN_CONTROL_ENDPOINT", "The fixture control endpoint is not available.", 404);
  }

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, `http://${HOST}`);
      const rawRequest = rawHeaderProbes.get(req.socket)?.take() || { authorizationDigests: [] };
      if (url.search !== "") throw protocolError("QUERY_NOT_ALLOWED", "Fixture endpoints do not accept query parameters.");
      if (url.pathname === REDIRECT_TARGET_PATH) handleRedirectTarget(req, res);
      else if (url.pathname.startsWith(CONTROL_PATH)) await handleControl(req, res, url.pathname);
      else await handleData(req, res, rawRequest);
    } catch (error) {
      if (!res.destroyed && !res.headersSent) safeError(res, error);
      else if (!res.destroyed) res.destroy();
    }
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    const probe = createRawHeaderProbe();
    rawHeaderProbes.set(socket, probe);
    socket.prependListener("data", probe.consume);
    socket.once("close", () => sockets.delete(socket));
  });

  async function listen() {
    if (listening) return handshake;
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, HOST, () => {
        server.off("error", reject);
        resolve();
      });
    });
    listening = true;
    const address = server.address();
    const baseUrl = `http://${HOST}:${address.port}`;
    handshake = {
      protocol: FIXTURE_PROTOCOL,
      contractVersion: 2,
      baseUrl,
      dataUrl: `${baseUrl}${DATA_PATH}`,
      controlUrl: `${baseUrl}${CONTROL_PATH}`,
    };
    if (options.emitHandshake && !emittedHandshake) {
      (options.output || process.stdout).write(`${JSON.stringify(handshake)}\n`);
      emittedHandshake = true;
    }
    return handshake;
  }

  async function close() {
    if (closePromise) return closePromise;
    runtime.status = "closing";
    abortContexts();
    closePromise = new Promise((resolve) => {
      if (!listening) {
        resolve();
        return;
      }
      server.close(() => {
        listening = false;
        runtime.status = "closed";
        resolve();
      });
      for (const socket of sockets) socket.destroy();
    });
    return closePromise;
  }

  return {
    listen,
    close,
    load,
    release,
    reset,
    state: () => safeState(runtime, document.fixtureProtocol.captureDenylist),
    server,
  };
}

async function main() {
  const fixture = createFixtureServer({ emitHandshake: true });
  await fixture.listen();
  const stop = async () => {
    await fixture.close();
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}

if (require.main === module) {
  main().catch(() => {
    process.exitCode = 1;
  });
}

module.exports = {
  CAPTURE_KEYS,
  FIXTURE_PROTOCOL,
  FixtureProtocolError,
  bodyBuffer,
  compileCase,
  createFixtureServer,
  gzipStored,
};
