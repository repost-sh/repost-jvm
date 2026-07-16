#!/usr/bin/env node

"use strict";

const childProcess = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const { TextDecoder } = require("node:util");
const contract = require("./jvm-raw-fixture-contract");

const ROOT = path.resolve(__dirname, "../../../..");
const SOURCE_ROOT = path.join(__dirname, "jvm-raw-fixture", "src");
const EVIDENCE_ROOT = path.join(ROOT, ".superpowers", "prototypes", "2026-07-13-jvm-sdk-task-2-evidence");
const MAIN_CLASS = contract.protocol.javaMainClass;
const STATE_FIELDS = contract.protocol.stateSchema.fields;
const BARRIER_FIELDS = contract.protocol.barrierSchema.fields;

function usage() {
  return "usage: node sdk/jvm/certification/transport/jvm-raw-fixture-runner.js (--group <groupId> | --all) [--evidence <path>] | --internal-canary";
}

function parseArguments(argv) {
  if (argv.length === 1 && argv[0] === "--internal-canary") {
    return { groupIds: [], evidencePath: null, internalCanary: true };
  }
  let groupId = null;
  let all = false;
  let evidencePath = null;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--group" && index + 1 < argv.length) groupId = argv[++index];
    else if (argument === "--all") all = true;
    else if (argument === "--evidence" && index + 1 < argv.length) evidencePath = path.resolve(argv[++index]);
    else throw new Error(usage());
  }
  if (all === (groupId !== null)) throw new Error(usage());
  if (groupId !== null && !Object.hasOwn(contract.groups, groupId)) throw new Error(`undeclared JVM raw fixture group: ${groupId}`);
  return { groupIds: all ? Object.keys(contract.groups) : [groupId], evidencePath, internalCanary: false };
}

function exactKeys(location, value, fields) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`${location}: must be an object`);
  const actual = Object.keys(value);
  if (JSON.stringify(actual) !== JSON.stringify(fields)) {
    throw new Error(`${location}: fields must be exactly ${fields.join(",")}; received ${actual.join(",")}`);
  }
}

function parseJson(text, location) {
  assertUniqueScalarJson(text, location);
  let value;
  try { value = JSON.parse(text); } catch { throw new Error(`${location}: invalid JSON`); }
  return value;
}

function parseJsonBytes(bytes, location) {
  let textValue;
  try { textValue = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { throw new Error(`${location}: invalid UTF-8`); }
  return parseJson(textValue, location);
}

function assertUniqueScalarJson(textValue, location) {
  let index = 0;
  const whitespace = () => { while (/[\x20\x09\x0a\x0d]/u.test(textValue[index] || "")) index += 1; };
  const stringToken = () => {
    const start = index;
    index += 1;
    while (index < textValue.length) {
      if (textValue[index] === "\\") index += 2;
      else if (textValue[index] === '"') {
        index += 1;
        let decoded;
        try { decoded = JSON.parse(textValue.slice(start, index)); }
        catch { throw new Error(`${location}: invalid JSON string`); }
        for (let offset = 0; offset < decoded.length; offset += 1) {
          const unit = decoded.charCodeAt(offset);
          if (unit >= 0xd800 && unit <= 0xdbff) {
            const next = decoded.charCodeAt(offset + 1);
            if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error(`${location}: invalid Unicode scalar`);
            offset += 1;
          } else if (unit >= 0xdc00 && unit <= 0xdfff) throw new Error(`${location}: invalid Unicode scalar`);
        }
        return decoded;
      } else index += 1;
    }
    throw new Error(`${location}: unterminated JSON string`);
  };
  const value = (pathValue) => {
    whitespace();
    if (textValue[index] === "{") {
      index += 1; whitespace();
      const keys = new Set();
      if (textValue[index] === "}") { index += 1; return; }
      while (index < textValue.length) {
        whitespace();
        if (textValue[index] !== '"') throw new Error(`${pathValue}: object key must be a string`);
        const key = stringToken();
        if (keys.has(key)) throw new Error(`${pathValue}: duplicate object key ${key}`);
        keys.add(key);
        whitespace();
        if (textValue[index++] !== ":") throw new Error(`${pathValue}.${key}: missing colon`);
        value(`${pathValue}.${key}`);
        whitespace();
        if (textValue[index] === "}") { index += 1; return; }
        if (textValue[index++] !== ",") throw new Error(`${pathValue}: missing comma`);
      }
      throw new Error(`${pathValue}: unterminated object`);
    }
    if (textValue[index] === "[") {
      index += 1; whitespace();
      if (textValue[index] === "]") { index += 1; return; }
      let item = 0;
      while (index < textValue.length) {
        value(`${pathValue}[${item++}]`); whitespace();
        if (textValue[index] === "]") { index += 1; return; }
        if (textValue[index++] !== ",") throw new Error(`${pathValue}: missing comma`);
      }
      throw new Error(`${pathValue}: unterminated array`);
    }
    if (textValue[index] === '"') { stringToken(); return; }
    const remainder = textValue.slice(index);
    const token = remainder.match(/^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/u)?.[0];
    if (!token) throw new Error(`${pathValue}: invalid JSON value`);
    index += token.length;
  };
  value(location);
  whitespace();
  if (index !== textValue.length) throw new Error(`${location}: trailing JSON data`);
}

function compileJava(outputDirectory) {
  const sources = [
    path.join(SOURCE_ROOT, "sh", "repost", "conformance", "transport", "RawFixtureMain.java"),
    path.join(SOURCE_ROOT, "sh", "repost", "conformance", "transport", "JvmRawFixtureRunner.java"),
  ];
  const result = childProcess.spawnSync("javac", ["--release", "11", "-d", outputDirectory, ...sources], {
    cwd: ROOT,
    encoding: "utf8",
    timeout: 30_000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`javac failed (${result.status}): ${(result.stderr || result.stdout).trim()}`);
}

function createKeyStore(outputDirectory) {
  const keyStore = path.join(outputDirectory, "loopback.p12");
  const password = "repost-raw-fixture";
  const result = childProcess.spawnSync("keytool", [
    "-genkeypair", "-alias", "raw", "-keyalg", "RSA", "-keysize", "2048",
    "-storetype", "PKCS12", "-keystore", keyStore, "-storepass", password, "-keypass", password,
    "-dname", "CN=localhost", "-validity", "1", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt",
  ], { cwd: ROOT, encoding: "utf8", timeout: 30_000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`keytool failed (${result.status}): ${(result.stderr || result.stdout).trim()}`);
  return { keyStore, password };
}

function firstLine(stream, timeoutMs) {
  return new Promise((resolve, reject) => {
    let pending = Buffer.alloc(0);
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("JVM raw fixture handshake timeout"));
    }, timeoutMs);
    const cleanup = () => {
      clearTimeout(timer);
      stream.off("data", onData);
      stream.off("error", onError);
      stream.off("end", onEnd);
    };
    const onError = (error) => {
      cleanup();
      reject(error);
    };
    const onEnd = () => {
      cleanup();
      reject(new Error("JVM raw fixture ended before handshake"));
    };
    const onData = (chunk) => {
      pending = Buffer.concat([pending, chunk]);
      if (pending.length > 65_536) { cleanup(); reject(new Error("JVM raw fixture handshake too large")); return; }
      const newline = pending.indexOf(0x0a);
      if (newline < 0) return;
      const line = pending.subarray(0, newline);
      const tail = pending.subarray(newline + 1);
      cleanup();
      if (tail.length !== 0 || line.includes(0x0d)) { reject(new Error("JVM raw fixture handshake must be exactly one LF-terminated line")); return; }
      try { resolve(new TextDecoder("utf-8", { fatal: true }).decode(line)); }
      catch { reject(new Error("JVM raw fixture handshake invalid UTF-8")); }
    };
    stream.on("data", onData);
    stream.once("error", onError);
    stream.once("end", onEnd);
  });
}

function exitResult(process, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      process.kill("SIGKILL");
      reject(new Error("JVM raw fixture process exit timeout"));
    }, timeoutMs);
    process.once("exit", (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
    process.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

function validateHandshake(value, groupId) {
  exactKeys("handshake", value, contract.protocol.handshakeSchema.fields);
  if (value.protocolVersion !== 1) throw new Error("handshake.protocolVersion: must equal 1");
  if (value.groupId !== groupId) throw new Error("handshake.groupId: must equal requested group");
  if (typeof value.controlEndpoint !== "string" || !/^http:\/\/127\.0\.0\.1:(?:[1-9][0-9]{0,4})\/control$/u.test(value.controlEndpoint)) {
    throw new Error("handshake.controlEndpoint: must be the exact loopback HTTP control URL");
  }
  const port = Number(new URL(value.controlEndpoint).port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("handshake.controlEndpoint: port out of range");
  if (!Number.isSafeInteger(value.pid) || value.pid <= 0) throw new Error("handshake.pid: must be a positive safe integer");
}

function postControl(endpoint, request) {
  const body = Buffer.from(JSON.stringify(request), "utf8");
  return new Promise((resolve, reject) => {
    const parsed = new URL(endpoint);
    const message = http.request({
      method: "POST",
      hostname: "127.0.0.1",
      port: Number(parsed.port),
      path: "/control",
      headers: { "Content-Type": "application/json", "Content-Length": String(body.length) },
      agent: false,
      timeout: 5_000,
    }, (response) => {
      const chunks = [];
      let length = 0;
      response.on("data", (chunk) => {
        length += chunk.length;
        if (length > 65_536) response.destroy(new Error("control response too large"));
        else chunks.push(chunk);
      });
      response.on("end", () => {
        const bytes = Buffer.concat(chunks);
        let value;
        try { value = parseJsonBytes(bytes, `control.${request.action}`); }
        catch (error) { reject(error); return; }
        if (response.statusCode !== 200) reject(new Error(`control ${request.action} failed with ${response.statusCode}: ${JSON.stringify(value)}`));
        else resolve(value);
      });
    });
    message.on("timeout", () => message.destroy(new Error(`control ${request.action} timeout`)));
    message.on("error", reject);
    message.end(body);
  });
}

function postRawControlResult(endpoint, body) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(endpoint);
    const message = http.request({
      method: "POST",
      hostname: "127.0.0.1",
      port: Number(parsed.port),
      path: "/control",
      headers: { "Content-Type": "application/json", "Content-Length": String(body.length) },
      agent: false,
      timeout: 5_000,
    }, (response) => {
      const chunks = [];
      let length = 0;
      response.on("data", (chunk) => {
        length += chunk.length;
        if (length > 65_536) response.destroy(new Error("control response too large"));
        else chunks.push(chunk);
      });
      response.on("end", () => {
        const bytes = Buffer.concat(chunks);
        let value;
        try { value = parseJsonBytes(bytes, "control.result"); }
        catch (error) { reject(error); return; }
        resolve({ status: response.statusCode, value });
      });
    });
    message.on("timeout", () => message.destroy(new Error("control request timeout")));
    message.on("error", reject);
    message.end(body);
  });
}

function postControlResult(endpoint, request) {
  return postRawControlResult(endpoint, Buffer.from(JSON.stringify(request), "utf8"));
}

function validateInternalState(value) {
  exactKeys("state", value, STATE_FIELDS);
  if (value.schema !== contract.protocol.stateSchema.name
      || value.protocolVersion !== 1
      || value.groupId !== "__internal-h1-canary") {
    throw new Error("internal state: schema, protocol version, or group mismatch");
  }
  if (!["RESET", "LOADED", "STARTED", "CLOSED"].includes(value.lifecycle)) throw new Error("internal state.lifecycle: invalid");
  if (!Number.isSafeInteger(value.monotonicTimeMs) || value.monotonicTimeMs < 0) throw new Error("internal state.monotonicTimeMs: invalid");
  if (!Array.isArray(value.controls) || !value.controls.every((action) => contract.actions.includes(action))) throw new Error("internal state.controls: invalid");
  const allowedCounters = [
    "h1CanaryConnectionsAccepted", "h1CanaryRequestsObserved",
    "h1CanaryResponsesWritten", "h1CanaryPeerFailures", "peerCloseFailures",
  ];
  if (value.observedCounters === null || typeof value.observedCounters !== "object" || Array.isArray(value.observedCounters)) throw new Error("internal state.observedCounters: invalid");
  const actualCounterNames = Object.keys(value.observedCounters);
  const expectedCounterOrder = allowedCounters.filter((name) => actualCounterNames.includes(name));
  if (JSON.stringify(actualCounterNames) !== JSON.stringify(expectedCounterOrder)) throw new Error("internal state.observedCounters: invalid order or key");
  for (const [name, count] of Object.entries(value.observedCounters)) {
    if (!allowedCounters.includes(name) || !Number.isSafeInteger(count) || count < 0) throw new Error("internal state.observedCounters: invalid");
  }
  for (const field of ["loaded", "started", "closed"]) if (typeof value[field] !== "boolean") throw new Error(`internal state.${field}: invalid`);
  for (const field of ["waitingBarriers", "releasedBarriers"]) {
    if (!Array.isArray(value[field]) || value[field].length > 1 || value[field].some((barrier) => barrier !== "h1-canary-request")) {
      throw new Error(`internal state.${field}: invalid`);
    }
  }
  if (value.waitingBarriers.length > 0 && value.releasedBarriers.length > 0) throw new Error("internal state: barrier cannot wait and be released");
  exactKeys("state.scenario", value.scenario, contract.protocol.scenarioSchema.fields);
  if (!Array.isArray(value.scenario.endpoints) || !Array.isArray(value.scenario.assets)) {
    throw new Error("internal state.scenario: endpoints and assets must be arrays");
  }
  for (const endpoint of value.scenario.endpoints) {
    exactKeys("state.scenario.endpoint", endpoint, contract.protocol.scenarioSchema.endpointFields);
    if (endpoint.id !== "canary-origin" || endpoint.role !== "ORIGIN" || endpoint.scheme !== "http"
        || endpoint.connectHost !== "127.0.0.1" || !Number.isInteger(endpoint.port)
        || endpoint.port < 1 || endpoint.port > 65_535 || endpoint.authorityHost !== "127.0.0.1"
        || endpoint.basePath !== "/canary" || JSON.stringify(endpoint.alpnProtocols) !== JSON.stringify(["http/1.1"])) {
      throw new Error("internal state.scenario.endpoint: unsafe address");
    }
  }
  if (value.scenario.endpoints.length > 1 || value.scenario.assets.length !== 0) throw new Error("internal state.scenario: invalid canary descriptor cardinality");
  return value;
}

function validateInternalBarrier(value, status) {
  exactKeys("barrier", value, BARRIER_FIELDS);
  if (value.schema !== contract.protocol.barrierSchema.name || value.protocolVersion !== 1
      || value.groupId !== "__internal-h1-canary" || value.barrier !== "h1-canary-request"
      || value.status !== status || !Number.isSafeInteger(value.monotonicTimeMs) || value.monotonicTimeMs < 0) {
    throw new Error("internal barrier: invalid closed-schema response");
  }
  return value;
}

function canaryRequest(endpoint) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      method: "GET",
      hostname: endpoint.connectHost,
      port: endpoint.port,
      path: endpoint.basePath,
      headers: { Host: endpoint.authorityHost, Connection: "close" },
      agent: false,
      timeout: 5_000,
    }, (response) => {
      response.resume();
      response.on("end", () => resolve(response.statusCode));
    });
    request.on("timeout", () => request.destroy(new Error("canary request timeout")));
    request.on("error", reject);
    request.end();
  });
}

async function awaitInternalBarrier(controlEndpoint, barrier, expected, attempts = 100) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const value = await postControl(controlEndpoint, { action: "awaitBarrier", barrier });
    if (value.status === expected) return validateInternalBarrier(value, expected);
    if (value.status !== "WAITING") throw new Error(`internal barrier unexpected status ${value.status}`);
    validateInternalBarrier(value, "WAITING");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(`internal barrier did not reach ${expected}`);
}

async function runInternalCloseProbe(classesDirectory, connectFirst) {
  const groupId = "__internal-h1-canary";
  const child = childProcess.spawn("java", ["-cp", classesDirectory, MAIN_CLASS, "--group", groupId], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let socket = null;
  try {
    const handshake = parseJson(await firstLine(child.stdout, 10_000), "close-probe.handshake");
    validateHandshake(handshake, groupId);
    validateInternalState(await postControl(handshake.controlEndpoint, { action: "reset" }));
    const loaded = validateInternalState(await postControl(handshake.controlEndpoint, { action: "load" }));
    validateInternalState(await postControl(handshake.controlEndpoint, { action: "start" }));
    if (connectFirst) {
      const endpoint = loaded.scenario.endpoints[0];
      socket = net.createConnection({ host: endpoint.connectHost, port: endpoint.port });
      socket.on("error", () => {});
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("close probe connect timeout")), 5_000);
        socket.once("connect", () => { clearTimeout(timer); resolve(); });
        socket.once("error", (error) => { clearTimeout(timer); reject(error); });
      });
      socket.write("GET ");
    }
    const closing = validateInternalState(await postControl(handshake.controlEndpoint, { action: "close" }));
    if (closing.observedCounters.h1CanaryPeerFailures !== undefined
        || closing.observedCounters.peerCloseFailures !== undefined) {
      throw new Error("close probe recorded an intentional shutdown as a peer failure");
    }
    const exit = await exitResult(child, 10_000);
    return closing.lifecycle === "CLOSED" && exit.code === 0 && exit.signal === null;
  } catch (error) {
    child.kill("SIGKILL");
    throw error;
  } finally {
    if (socket) socket.destroy();
  }
}

async function runInternalCanary(classesDirectory) {
  const groupId = "__internal-h1-canary";
  const barrier = "h1-canary-request";
  const child = childProcess.spawn("java", ["-cp", classesDirectory, MAIN_CLASS, "--group", groupId], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr.on("data", (chunk) => { if (stderr.length < 64) stderr.push(chunk); });
  try {
    const handshake = parseJson(await firstLine(child.stdout, 10_000), "handshake");
    validateHandshake(handshake, groupId);
    const parserMutations = [
      Buffer.from("{", "utf8"),
      Buffer.from('{"action":"reset",}', "utf8"),
      Buffer.from('{"action":"reset","action":"reset"}', "utf8"),
      Buffer.from('{"action":"\\ud800"}', "utf8"),
      Buffer.from([0x7b, 0x22, 0x61, 0x22, 0x3a, 0xc3, 0x28, 0x7d]),
    ];
    const parserStatuses = [];
    for (const body of parserMutations) parserStatuses.push((await postRawControlResult(handshake.controlEndpoint, body)).status);
    const loadBeforeReset = await postControlResult(handshake.controlEndpoint, { action: "load" });
    const escapedReset = await postRawControlResult(
      handshake.controlEndpoint,
      Buffer.from('{"action":"\\u0072eset"}', "utf8"),
    );
    if (escapedReset.status !== 200) throw new Error("escaped reset control was rejected");
    validateInternalState(escapedReset.value);
    const loaded = validateInternalState(await postControl(handshake.controlEndpoint, { action: "load" }));
    validateInternalState(await postControl(handshake.controlEndpoint, { action: "start" }));
    const endpoint = loaded.scenario.endpoints.find((candidate) => candidate.id === "canary-origin");
    if (!endpoint) throw new Error("internal canary did not advertise canary-origin");
    const initial = validateInternalBarrier(
      await postControl(handshake.controlEndpoint, { action: "awaitBarrier", barrier }),
      "WAITING",
    );
    const earlyRelease = await postControlResult(handshake.controlEndpoint, { action: "releaseBarrier", barrier });
    const request = canaryRequest(endpoint);
    await awaitInternalBarrier(handshake.controlEndpoint, barrier, "ARRIVED");
    const released = await postControl(handshake.controlEndpoint, { action: "releaseBarrier", barrier });
    if (released.status !== "RELEASED") throw new Error("internal barrier release failed");
    if (await request !== 204) throw new Error("internal canary response status mismatch");
    const snapshot = validateInternalState(await postControl(handshake.controlEndpoint, { action: "snapshot" }));
    validateInternalState(await postControl(handshake.controlEndpoint, { action: "close" }));
    const exit = await exitResult(child, 10_000);
    if (exit.code !== 0 || exit.signal !== null) {
      throw new Error(`internal JVM raw fixture exited ${exit.code ?? exit.signal}: ${Buffer.concat(stderr).toString("utf8").trim()}`);
    }
    const expectedCounters = {
      h1CanaryConnectionsAccepted: 1,
      h1CanaryRequestsObserved: 1,
      h1CanaryResponsesWritten: 1,
    };
    if (JSON.stringify(snapshot.observedCounters) !== JSON.stringify(expectedCounters)) {
      throw new Error("internal canary observations did not originate from the H1 peer");
    }
    const closeBeforeAcceptTerminated = await runInternalCloseProbe(classesDirectory, false);
    const acceptCloseRaceTerminated = await runInternalCloseProbe(classesDirectory, true);
    return {
      schema: "jvm-raw-fixture-foundation-canary-v1",
      groupId,
      endpointIds: loaded.scenario.endpoints.map((candidate) => candidate.id),
      assetIds: loaded.scenario.assets.map((candidate) => candidate.id),
      capabilities: ["http/1.1", "two-phase-barrier"],
      observedCounters: { h1CanaryRequestsObserved: snapshot.observedCounters.h1CanaryRequestsObserved },
      checks: {
        advertisedEndpointConsumed: endpoint.port > 0,
        releaseBeforeArrivalRejected: earlyRelease.status === 400
          && earlyRelease.value.error === "barrier has not arrived",
        earlyReleaseWithoutEventRejected: initial.status === "WAITING" && earlyRelease.status === 400,
        peerEventRequired: snapshot.observedCounters.h1CanaryConnectionsAccepted === 1,
        loadBeforeResetRejected: loadBeforeReset.status === 400,
        strictControlParser: parserStatuses.every((status) => status === 400),
        closeBeforeAcceptTerminated,
        acceptCloseRaceTerminated,
        closeTerminatedResources: true,
      },
    };
  } catch (error) {
    child.kill("SIGKILL");
    throw error;
  }
}

function validateState(value, groupId) {
  exactKeys("state", value, STATE_FIELDS);
  if (value.schema !== contract.protocol.stateSchema.name || value.protocolVersion !== 1 || value.groupId !== groupId) {
    throw new Error("state: schema, protocol version, or group mismatch");
  }
  if (!Number.isSafeInteger(value.monotonicTimeMs) || value.monotonicTimeMs < 0) throw new Error("state.monotonicTimeMs: invalid");
  if (!Array.isArray(value.controls) || !value.controls.every((action) => contract.actions.includes(action))) throw new Error("state.controls: invalid");
  if (value.observedCounters === null || typeof value.observedCounters !== "object" || Array.isArray(value.observedCounters)) throw new Error("state.observedCounters: invalid");
  for (const field of ["loaded", "started", "closed"]) if (typeof value[field] !== "boolean") throw new Error(`state.${field}: must be boolean`);
  const requiredBarrier = contract.groups[groupId].requiredBarriers[0];
  for (const field of ["waitingBarriers", "releasedBarriers"]) if (!Array.isArray(value[field]) || !value[field].every((barrier) => barrier === requiredBarrier)) throw new Error(`state.${field}: invalid`);
  return value;
}

function validateBarrier(value, groupId, status) {
  exactKeys("barrier", value, BARRIER_FIELDS);
  if (value.schema !== contract.protocol.barrierSchema.name || value.protocolVersion !== 1 || value.groupId !== groupId
      || value.barrier !== contract.groups[groupId].requiredBarriers[0] || value.status !== status || !Number.isSafeInteger(value.monotonicTimeMs) || value.monotonicTimeMs < 0) {
    throw new Error("barrier: invalid closed-schema response");
  }
  return value;
}

async function runGroup(groupId, classesDirectory, keyStore) {
  const child = childProcess.spawn("java", ["-cp", classesDirectory, MAIN_CLASS, "--group", groupId], {
    cwd: ROOT,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, REPOST_RAW_FIXTURE_KEYSTORE: keyStore.keyStore, REPOST_RAW_FIXTURE_KEYSTORE_PASSWORD: keyStore.password },
  });
  const stderr = [];
  child.stderr.on("data", (chunk) => { if (stderr.length < 64) stderr.push(chunk); });
  try {
    const handshake = parseJson(await firstLine(child.stdout, 10_000), "handshake");
    validateHandshake(handshake, groupId);
    const states = [];
    const barriers = [];
    const required = contract.groups[groupId];
    const barrier = required.requiredBarriers[0];
    states.push(validateState(await postControl(handshake.controlEndpoint, { action: "reset" }), groupId));
    states.push(validateState(await postControl(handshake.controlEndpoint, { action: "load" }), groupId));
    states.push(validateState(await postControl(handshake.controlEndpoint, { action: "start" }), groupId));
    barriers.push(validateBarrier(await postControl(handshake.controlEndpoint, { action: "awaitBarrier", barrier }), groupId, "WAITING"));
    states.push(validateState(await postControl(handshake.controlEndpoint, { action: "advanceMonotonicTime", deltaMs: 1 }), groupId));
    states.push(validateState(await postControl(handshake.controlEndpoint, { action: "snapshot" }), groupId));
    barriers.push(validateBarrier(await postControl(handshake.controlEndpoint, { action: "releaseBarrier", barrier }), groupId, "RELEASED"));
    barriers.push(validateBarrier(await postControl(handshake.controlEndpoint, { action: "awaitBarrier", barrier }), groupId, "RELEASED"));
    const state = validateState(await postControl(handshake.controlEndpoint, { action: "close" }), groupId);
    states.push(state);
    const exit = await exitResult(child, 10_000);
    if (exit.code !== 0 || exit.signal !== null) throw new Error(`JVM raw fixture exited ${exit.code ?? exit.signal}: ${Buffer.concat(stderr).toString("utf8").trim()}`);
    const controls = state.controls;
    for (const action of required.requiredControls) if (!controls.includes(action)) throw new Error(`${groupId}: missing required control ${action}`);
    if (state.lifecycle !== "CLOSED" || state.closed !== true || state.releasedBarriers.join(",") !== barrier) throw new Error(`${groupId}: terminal state is not CLOSED with the mechanism barrier released`);
    if (JSON.stringify(state.observedCounters) !== JSON.stringify(required.requiredObservedCounters)) throw new Error(`${groupId}: mechanism counter mismatch`);
    return {
      schema: contract.protocol.evidenceSchema.name,
      protocolVersion: 1,
      groupId,
      fixtureCaseId: required.fixtureCaseId,
      outcome: "SUCCESS",
      skipped: false,
      controls,
      barriers,
      observedCounters: state.observedCounters,
      state,
      terminalAssertions: [...required.terminalAssertions],
    };
  } catch (error) {
    child.kill("SIGKILL");
    throw error;
  }
}

function evidenceDocument(groups) {
  return {
    schema: contract.protocol.evidenceSchema.name,
    protocolVersion: 1,
    summary: { total: groups.length, success: groups.filter((group) => group.outcome === "SUCCESS").length, skipped: groups.filter((group) => group.skipped).length, undeclared: 0 },
    groups: Object.fromEntries(groups.map((group) => [group.groupId, group])),
  };
}

function writeEvidence(file, document) {
  const temporary = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(temporary, `${JSON.stringify(document, null, 2)}\n`, { encoding: "utf8", flag: "wx", mode: 0o600 });
  fs.renameSync(temporary, file);
}

async function main() {
  const { groupIds, evidencePath, internalCanary } = parseArguments(process.argv.slice(2));
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  const temporaryDirectory = fs.mkdtempSync(path.join(EVIDENCE_ROOT, "classes-"));
  try {
    compileJava(temporaryDirectory);
    if (internalCanary) {
      process.stdout.write(`${JSON.stringify(await runInternalCanary(temporaryDirectory))}\n`);
      return;
    }
    if (groupIds.length > 0) {
      throw new Error(`UNSUPPORTED JVM raw fixture production group: ${groupIds[0]}; foundation implements only __internal-h1-canary`);
    }
    const keyStore = createKeyStore(temporaryDirectory);
    const groups = [];
    for (const groupId of groupIds) groups.push(await runGroup(groupId, temporaryDirectory, keyStore));
    const evidence = evidenceDocument(groups);
    if (evidencePath) writeEvidence(evidencePath, evidence);
    else process.stdout.write(`${JSON.stringify(evidence, null, 2)}\n`);
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}

module.exports = Object.freeze({
  evidenceDocument,
  exactKeys,
  parseArguments,
  parseJson,
  parseJsonBytes,
  validateBarrier,
  validateHandshake,
  validateState,
});
