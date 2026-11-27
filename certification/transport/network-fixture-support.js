"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const HOST = "127.0.0.1";
const RESET_AFTER_BODY_BYTES = 8;
const DECLARED_OVERSIZE_BYTES = 1024 * 1024 * 1024;
const DECOMPRESSION_TRAP_BYTES = (1024 * 1024) + 1;
const MAX_CONTROL_BODY_BYTES = 16 * 1024;
const MAX_IDENTITY_REQUEST_BYTES = 1024 * 1024;
const RESPONSE_ID = "msg_1";
const STRICT_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const ASSET_DIRECTORY = path.join(__dirname, "network-fixture");
const ASSET_PATHS = Object.freeze({
  caCertPath: path.join(ASSET_DIRECTORY, "ca-cert.pem"),
  clientCertPath: path.join(ASSET_DIRECTORY, "client-cert.pem"),
  clientKeyPath: path.join(ASSET_DIRECTORY, "client-key.pem"),
  clientKeyPasswordPath: path.join(ASSET_DIRECTORY, "client-key-password.txt"),
  proxyCredentialPath: path.join(ASSET_DIRECTORY, "proxy-password.txt"),
});

function readAsset(name) {
  return fs.readFileSync(path.join(ASSET_DIRECTORY, name));
}

function readSecret(name) {
  return fs.readFileSync(path.join(ASSET_DIRECTORY, name), "utf8").trimEnd();
}

function exactKeys(value, expected) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  return JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function safeEqual(left, right) {
  const leftBytes = Buffer.from(left, "utf8");
  const rightBytes = Buffer.from(right, "utf8");
  return leftBytes.length === rightBytes.length && crypto.timingSafeEqual(leftBytes, rightBytes);
}

function createCounterState() {
  return {
    trusted: { requests: 0, requestBodyBytes: 0 },
    hostnameMismatch: { requests: 0 },
    tls12Only: { requests: 0 },
    mtls: { requests: 0, authorizedRequests: 0 },
    resetBeforeRequest: { connections: 0, requestBytes: 0, resets: 0 },
    resetAfterRequest: {
      connections: 0,
      headersCompleted: 0,
      firstBodyBytes: 0,
      resetAfterBodyBytes: RESET_AFTER_BODY_BYTES,
      resets: 0,
    },
    directTrap: { hits: 0 },
    noProxyTrap: { hits: 0 },
    proxy: {
      connectAttempts: 0,
      tunnels: 0,
      authFailures: 0,
      deniedAuthorities: 0,
      forwardRequests: 0,
    },
    authenticatedProxy: {
      connectAttempts: 0,
      tunnels: 0,
      authFailures: 0,
      deniedAuthorities: 0,
      forwardRequests: 0,
    },
    barriers: { responseHeaders: { waiting: 0, releases: 0 } },
    http2: {
      sessions: 0,
      sessionIds: [],
      streams: 0,
      streamIds: [],
      streamsBySession: {},
      singleSessionStreamCount: 0,
      activeStreams: 0,
      maxConcurrentStreams: 0,
      sameSessionOverlapCount: 0,
      requestBodyBytes: 0,
      http1FallbackRequests: 0,
      resetStreams: 0,
      cancelledStreams: 0,
      successfulStreams: 0,
      peerSuccesses: 0,
      collateralStreamFailures: 0,
      postIsolationProbeSuccesses: 0,
      goaway: {
        sent: 0,
        sessionId: 0,
        lastStreamId: 0,
        acceptedStreamId: 0,
        unprocessedSessionId: 0,
        unprocessedStreamId: 0,
        retrySessionId: 0,
        retryStreamId: 0,
        retrySuccesses: 0,
        errorCode: 0,
      },
    },
    http1Only: { connections: 0, requests: 0, negotiatedProtocols: [], negotiatedHttp1: 0 },
    http2Barriers: { streams: { waiting: 0, releases: 0 } },
    preallocationTrap: {
      requests: 0,
      declaredLength: DECLARED_OVERSIZE_BYTES,
      prefixBytesPublished: 0,
      clientCancellations: 0,
    },
    transparentDecompressionTrap: {
      requests: 0,
      rawBytesPublished: 0,
      decompressedBytes: DECOMPRESSION_TRAP_BYTES,
    },
    staleH1: {
      primeRequests: 0,
      wireRequests: 0,
      wireBodyPublications: 0,
      wireBodyBytesObserved: 0,
      reusedRequests: 0,
      faults: 0,
      successResponses: 0,
      uniqueConnections: 0,
    },
    trapBarriers: {
      preallocationBody: { waiting: 0, releases: 0 },
      staleConnection: { waiting: 0, releases: 0 },
    },
  };
}

function closeServer(server) {
  return new Promise((resolve, reject) => {
    if (!server.listening) return resolve();
    server.close((error) => error ? reject(error) : resolve());
  });
}

function listen(server) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      resolve(server.address());
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(0, HOST);
  });
}

function trackConnections(server, sockets) {
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.once("close", () => sockets.delete(socket));
  });
}

function destroySockets(sockets) {
  for (const socket of sockets) socket.destroy();
  sockets.clear();
}

function writeJson(response, statusCode, value) {
  const body = Buffer.from(JSON.stringify(value), "utf8");
  response.writeHead(statusCode, {
    "content-type": "application/json",
    "content-length": body.length,
    connection: "close",
  });
  response.end(body);
}

function consumeBody(request, onChunk) {
  return new Promise((resolve, reject) => {
    request.on("data", (chunk) => onChunk(chunk));
    request.once("end", resolve);
    request.once("aborted", () => reject(Object.assign(new Error("request aborted"), { code: "REQUEST_ABORTED" })));
    request.once("error", reject);
  });
}

function readJsonBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    request.on("data", (chunk) => {
      length += chunk.length;
      if (length > MAX_CONTROL_BODY_BYTES) {
        reject(Object.assign(new Error("control body too large"), { code: "CONTROL_BODY_TOO_LARGE" }));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.once("end", () => {
      try {
        resolve(length === 0 ? {} : JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        reject(Object.assign(new Error("invalid control JSON"), { code: "INVALID_CONTROL_JSON" }));
      }
    });
    request.once("error", reject);
  });
}

function parseRequestIdentity(body) {
  let parsed;
  try {
    parsed = JSON.parse(body.toString("utf8"));
  } catch {
    throw Object.assign(new Error("invalid request envelope"), { code: "INVALID_REQUEST_ENVELOPE" });
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)
      || typeof parsed.type !== "string" || parsed.type.length === 0
      || typeof parsed.customerId !== "string" || parsed.customerId.length === 0
      || typeof parsed.timestamp !== "string" || !STRICT_TIMESTAMP.test(parsed.timestamp)) {
    throw Object.assign(new Error("invalid request envelope"), { code: "INVALID_REQUEST_ENVELOPE" });
  }
  return Object.freeze({
    id: RESPONSE_ID,
    type: parsed.type,
    customerId: parsed.customerId,
    timestamp: parsed.timestamp,
  });
}

function readRequestIdentity(request, onChunk = () => {}) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    let tooLarge = false;
    request.on("data", (chunk) => {
      onChunk(chunk);
      length += chunk.length;
      if (length > MAX_IDENTITY_REQUEST_BYTES) {
        tooLarge = true;
        return;
      }
      chunks.push(chunk);
    });
    request.once("end", () => {
      try {
        if (tooLarge) throw Object.assign(new Error("request envelope too large"), { code: "REQUEST_ENVELOPE_TOO_LARGE" });
        resolve(parseRequestIdentity(Buffer.concat(chunks)));
      } catch (error) {
        reject(error);
      }
    });
    request.once("aborted", () => reject(Object.assign(new Error("request aborted"), { code: "REQUEST_ABORTED" })));
    request.once("error", reject);
  });
}

async function respondWithRequestIdentity(request, response, onChunk) {
  try {
    const identity = await readRequestIdentity(request, onChunk);
    writeJson(response, 202, identity);
    return identity;
  } catch (error) {
    if (!response.destroyed) writeJson(response, error.code === "REQUEST_ENVELOPE_TOO_LARGE" ? 413 : 400, { error: { code: error.code || "INVALID_REQUEST_ENVELOPE" } });
    return null;
  }
}

function parseAuthority(authority) {
  const match = /^(localhost|127\.0\.0\.1):(\d{1,5})$/.exec(authority);
  if (!match) return null;
  const port = Number(match[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return { host: match[1], port };
}

module.exports = {
  ASSET_PATHS,
  DECLARED_OVERSIZE_BYTES,
  DECOMPRESSION_TRAP_BYTES,
  HOST,
  MAX_IDENTITY_REQUEST_BYTES,
  RESET_AFTER_BODY_BYTES,
  closeServer,
  consumeBody,
  createCounterState,
  destroySockets,
  exactKeys,
  listen,
  parseAuthority,
  parseRequestIdentity,
  readAsset,
  readJsonBody,
  readRequestIdentity,
  readSecret,
  respondWithRequestIdentity,
  safeEqual,
  trackConnections,
  writeJson,
};
