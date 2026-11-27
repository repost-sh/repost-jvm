#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const http = require("node:http");
const http2 = require("node:http2");
const https = require("node:https");
const net = require("node:net");
const path = require("node:path");
const tls = require("node:tls");

const CONTRACT_VERSION = 2;
const FIXTURE_PROTOCOL = "repost-enterprise-network-fixture";
const DATA_PATH = "/v1/messages";
const HELD_PATH = "/held/v1/messages";
const PREALLOCATION_PATH = "/traps/preallocation/v1/messages";
const TRANSPARENT_DECOMPRESSION_PATH = "/traps/transparent-decompression/v1/messages";
const STALE_H1_PRIME_PATH = "/h1/stale/prime/v1/messages";
const STALE_H1_REPLAY_PATH = "/h1/stale/replay/v1/messages";
const CONTROL_PATH = "/__fixture";
const CONTRACT_DECOMPRESSION_TRAP_BASE64 = JSON.parse(
  fs.readFileSync(path.join(__dirname, "v2.json"), "utf8"),
).fixtureProtocol.bodyAssets.networkDecompressionTrap.data;
const PROXY_USERNAME = "fixture-user";

const {
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
} = require("./network-fixture-support.js");

const CAPABILITIES = Object.freeze([
  "trusted-custom-ca",
  "untrusted-ca-failure",
  "hostname-mismatch",
  "tls-1.2-only",
  "mtls-required",
  "http-connect-proxy",
  "authenticated-http-connect-proxy",
  "proxy-407",
  "direct-trap-counter",
  "no-proxy-trap-counter",
  "reset-before-request-bytes",
  "reset-after-exact-body-bytes",
  "explicit-response-header-barrier",
  "alpn-h2-with-http1-fallback",
  "h2-multiplexing",
  "h2-rst-stream",
  "h2-client-cancellation",
  "h2-goaway-metadata",
  "control-close",
  "declared-length-preallocation-trap",
  "transparent-decompression-trap",
  "stale-pooled-h1-replay",
]);


function createNetworkFixture(options = {}) {
  const emitHandshake = options.emitHandshake === true;
  const output = options.output || process.stdout;
  const ca = readAsset("ca-cert.pem");
  const serverCert = readAsset("server-cert.pem");
  const serverKey = readAsset("server-key.pem");
  const mismatchCert = readAsset("mismatch-cert.pem");
  const proxyPassword = readSecret("proxy-password.txt");
  const decompressionTrapBody = Buffer.from(CONTRACT_DECOMPRESSION_TRAP_BASE64, "base64");
  const expectedProxyAuthorization = `Basic ${Buffer.from(`${PROXY_USERNAME}:${proxyPassword}`, "utf8").toString("base64")}`;
  const tlsOptions = { key: serverKey, cert: serverCert };
  const operationalSockets = new Set();
  const controlSockets = new Set();
  const upstreamSockets = new Set();
  const heldResponses = new Set();
  const heldHttp2Streams = new Set();
  const heldPreallocationResponses = new Set();
  const http2Sessions = new Set();
  const pendingHttp2ResetStreams = new Set();
  const pendingGoawayAccepted = new Map();
  const pendingGoawayUnprocessed = new Map();
  const staleConnectionIds = new Set();
  let http2SessionIds = new WeakMap();
  let nextHttp2SessionId = 1;
  let socketIds = new WeakMap();
  let nextSocketId = 1;
  let primedStaleSocket = null;
  let armedStaleSocket = null;
  let heldResponseIdentities = new WeakMap();
  let heldHttp2Identities = new WeakMap();
  let releasedPreallocationResponses = new WeakSet();
  let counters = createCounterState();
  let stateGeneration = 0;
  let handshake = null;
  let listenPromise = null;
  let closePromise = null;

  function snapshot() {
    return {
      ...JSON.parse(JSON.stringify(counters)),
      active: {
        operationalSockets: operationalSockets.size,
        upstreamSockets: upstreamSockets.size,
        heldResponses: heldResponses.size,
        heldHttp2Streams: heldHttp2Streams.size,
        heldPreallocationResponses: heldPreallocationResponses.size,
        http2Sessions: http2Sessions.size,
      },
    };
  }

  function releaseHeldResponses() {
    if (heldResponses.size === 0) return false;
    const responses = [...heldResponses];
    heldResponses.clear();
    counters.barriers.responseHeaders.waiting = 0;
    counters.barriers.responseHeaders.releases += 1;
    for (const response of responses) {
      const identity = heldResponseIdentities.get(response);
      if (!response.destroyed && identity !== undefined) writeJson(response, 202, identity);
    }
    return true;
  }

  function releaseHeldHttp2Streams() {
    if (heldHttp2Streams.size === 0) return false;
    const streams = [...heldHttp2Streams];
    heldHttp2Streams.clear();
    counters.http2Barriers.streams.waiting = 0;
    counters.http2Barriers.streams.releases += 1;
    for (const stream of streams) {
      if (stream.closed || stream.destroyed) continue;
      const held = heldHttp2Identities.get(stream);
      if (held === undefined) continue;
      stream.end(held.body.subarray(held.prefixBytes));
      counters.http2.successfulStreams += 1;
      if (held.role === "peer") counters.http2.peerSuccesses += 1;
    }
    return true;
  }

  function releasePreallocationResponses() {
    if (heldPreallocationResponses.size === 0) return false;
    const responses = [...heldPreallocationResponses];
    heldPreallocationResponses.clear();
    counters.trapBarriers.preallocationBody.waiting = 0;
    counters.trapBarriers.preallocationBody.releases += 1;
    for (const response of responses) {
      releasedPreallocationResponses.add(response);
      response.destroy();
    }
    return true;
  }

  function armStaleConnection() {
    if (primedStaleSocket === null || primedStaleSocket.destroyed || armedStaleSocket !== null) return false;
    armedStaleSocket = primedStaleSocket;
    counters.trapBarriers.staleConnection.waiting = 0;
    counters.trapBarriers.staleConnection.releases += 1;
    return true;
  }

  function socketId(socket) {
    let id = socketIds.get(socket);
    if (id === undefined) {
      id = nextSocketId;
      nextSocketId += 1;
      socketIds.set(socket, id);
    }
    return id;
  }

  function resetOperationalState() {
    stateGeneration += 1;
    for (const response of heldResponses) response.destroy();
    heldResponses.clear();
    for (const stream of heldHttp2Streams) stream.close(http2.constants.NGHTTP2_CANCEL);
    for (const stream of pendingHttp2ResetStreams) stream.close(http2.constants.NGHTTP2_CANCEL);
    heldHttp2Streams.clear();
    pendingHttp2ResetStreams.clear();
    pendingGoawayAccepted.clear();
    pendingGoawayUnprocessed.clear();
    for (const response of heldPreallocationResponses) {
      releasedPreallocationResponses.add(response);
      response.destroy();
    }
    heldPreallocationResponses.clear();
    for (const session of http2Sessions) session.destroy();
    http2Sessions.clear();
    destroySockets(operationalSockets);
    destroySockets(upstreamSockets);
    staleConnectionIds.clear();
    http2SessionIds = new WeakMap();
    nextHttp2SessionId = 1;
    socketIds = new WeakMap();
    nextSocketId = 1;
    primedStaleSocket = null;
    armedStaleSocket = null;
    heldResponseIdentities = new WeakMap();
    heldHttp2Identities = new WeakMap();
    releasedPreallocationResponses = new WeakSet();
    counters = createCounterState();
  }

  async function handleStaleReplay(request, response) {
    const connectionId = socketId(request.socket);
    staleConnectionIds.add(connectionId);
    counters.staleH1.uniqueConnections = staleConnectionIds.size;
    counters.staleH1.wireRequests += 1;
    const reusesPrimedConnection = request.socket === primedStaleSocket;
    if (reusesPrimedConnection) counters.staleH1.reusedRequests += 1;
    const faultThisRequest = request.socket === armedStaleSocket;
    if (faultThisRequest) armedStaleSocket = null;
    let publicationObserved = false;
    const observePublication = (chunk, maximumBytes) => {
      if (chunk.length === 0) return;
      if (!publicationObserved) {
        publicationObserved = true;
        counters.staleH1.wireBodyPublications += 1;
      }
      counters.staleH1.wireBodyBytesObserved += Math.min(chunk.length, maximumBytes);
    };
    if (faultThisRequest) {
      request.once("data", (chunk) => {
        observePublication(chunk, 1);
        counters.staleH1.faults += 1;
        request.socket.destroy();
      });
      request.once("end", () => {
        if (!publicationObserved) {
          counters.staleH1.faults += 1;
          request.socket.destroy();
        }
      });
      request.once("error", () => {});
      return;
    }
    try {
      const identity = await readRequestIdentity(request, (chunk) => observePublication(chunk, chunk.length));
      counters.staleH1.successResponses += 1;
      writeJson(response, 202, identity);
    } catch (error) {
      if (!response.destroyed) writeJson(response, error.code === "REQUEST_ENVELOPE_TOO_LARGE" ? 413 : 400, { error: { code: error.code || "INVALID_REQUEST_ENVELOPE" } });
      return;
    }
  }

  async function trustedHandler(request, response) {
    if (request.method !== "POST") {
      writeJson(response, 404, { error: { code: "NOT_FOUND" } });
      return;
    }
    if (request.url === PREALLOCATION_PATH) {
      counters.preallocationTrap.requests += 1;
      request.resume();
      response.writeHead(202, {
        "content-type": "application/json",
        "content-length": DECLARED_OVERSIZE_BYTES,
      });
      response.write(Buffer.from([0x50]));
      counters.preallocationTrap.prefixBytesPublished += 1;
      heldPreallocationResponses.add(response);
      counters.trapBarriers.preallocationBody.waiting = heldPreallocationResponses.size;
      response.once("close", () => {
        heldPreallocationResponses.delete(response);
        counters.trapBarriers.preallocationBody.waiting = heldPreallocationResponses.size;
        if (!releasedPreallocationResponses.has(response)) counters.preallocationTrap.clientCancellations += 1;
      });
      return;
    }
    if (request.url === TRANSPARENT_DECOMPRESSION_PATH) {
      counters.transparentDecompressionTrap.requests += 1;
      request.resume();
      counters.transparentDecompressionTrap.rawBytesPublished += decompressionTrapBody.length;
      response.writeHead(202, {
        "content-type": "application/json",
        "content-encoding": "gzip",
        "content-length": decompressionTrapBody.length,
      });
      response.end(decompressionTrapBody);
      return;
    }
    if (request.url === STALE_H1_PRIME_PATH) {
      counters.staleH1.primeRequests += 1;
      primedStaleSocket = request.socket;
      socketId(request.socket);
      counters.trapBarriers.staleConnection.waiting = 1;
      request.resume();
      response.writeHead(204, {
        connection: "keep-alive",
        "keep-alive": "timeout=60",
        "content-length": 0,
      });
      response.end();
      return;
    }
    if (request.url === STALE_H1_REPLAY_PATH) {
      await handleStaleReplay(request, response);
      return;
    }
    if (request.url !== DATA_PATH && request.url !== HELD_PATH) {
      writeJson(response, 404, { error: { code: "NOT_FOUND" } });
      return;
    }
    counters.trusted.requests += 1;
    let identity;
    try {
      identity = await readRequestIdentity(request, (chunk) => {
        counters.trusted.requestBodyBytes += chunk.length;
      });
    } catch (error) {
      if (!response.destroyed) writeJson(response, error.code === "REQUEST_ENVELOPE_TOO_LARGE" ? 413 : 400, { error: { code: error.code || "INVALID_REQUEST_ENVELOPE" } });
      return;
    }
    if (request.url === HELD_PATH) {
      heldResponses.add(response);
      heldResponseIdentities.set(response, identity);
      counters.barriers.responseHeaders.waiting = heldResponses.size;
      response.once("close", () => {
        heldResponses.delete(response);
        counters.barriers.responseHeaders.waiting = heldResponses.size;
      });
      return;
    }
    writeJson(response, 202, identity);
  }

  const trustedServer = https.createServer(tlsOptions, trustedHandler);
  trustedServer.keepAliveTimeout = 0;
  const mismatchServer = https.createServer({ key: serverKey, cert: mismatchCert }, async (request, response) => {
    counters.hostnameMismatch.requests += 1;
    await respondWithRequestIdentity(request, response);
  });
  const tls12Server = https.createServer({ ...tlsOptions, minVersion: "TLSv1.2", maxVersion: "TLSv1.2" }, async (request, response) => {
    counters.tls12Only.requests += 1;
    await respondWithRequestIdentity(request, response);
  });
  const mtlsServer = https.createServer({ ...tlsOptions, ca, requestCert: true, rejectUnauthorized: true }, async (request, response) => {
    counters.mtls.requests += 1;
    if (request.client.authorized) counters.mtls.authorizedRequests += 1;
    await respondWithRequestIdentity(request, response);
  });
  const resetBeforeServer = tls.createServer(tlsOptions, (socket) => {
    counters.resetBeforeRequest.connections += 1;
    counters.resetBeforeRequest.resets += 1;
    socket.destroy();
  });
  const resetAfterServer = tls.createServer(tlsOptions, (socket) => {
    counters.resetAfterRequest.connections += 1;
    let headerBuffer = Buffer.alloc(0);
    let headersComplete = false;
    let bodyBytes = 0;
    socket.on("data", (chunk) => {
      if (!headersComplete) {
        const combined = Buffer.concat([headerBuffer, chunk]);
        const boundary = combined.indexOf("\r\n\r\n");
        if (boundary === -1) {
          headerBuffer = combined.length > 64 * 1024 ? combined.subarray(combined.length - 3) : combined;
          return;
        }
        headersComplete = true;
        counters.resetAfterRequest.headersCompleted += 1;
        chunk = combined.subarray(boundary + 4);
        headerBuffer = Buffer.alloc(0);
      }
      if (chunk.length === 0) return;
      const remaining = RESET_AFTER_BODY_BYTES - bodyBytes;
      const observed = Math.min(remaining, chunk.length);
      bodyBytes += observed;
      counters.resetAfterRequest.firstBodyBytes += observed;
      if (bodyBytes === RESET_AFTER_BODY_BYTES) {
        counters.resetAfterRequest.resets += 1;
        socket.destroy();
      }
    });
  });
  const directTrapServer = http.createServer((request, response) => {
    counters.directTrap.hits += 1;
    request.resume();
    writeJson(response, 421, { error: { code: "DIRECT_TRAP_HIT" } });
  });
  const noProxyTrapServer = http.createServer((request, response) => {
    counters.noProxyTrap.hits += 1;
    request.resume();
    writeJson(response, 421, { error: { code: "NO_PROXY_TRAP_HIT" } });
  });
  const http2Server = http2.createSecureServer({ ...tlsOptions, allowHTTP1: true }, async (request, response) => {
    if (request.httpVersionMajor !== 1) return;
    counters.http2.http1FallbackRequests += 1;
    await respondWithRequestIdentity(request, response);
  });
  http2Server.on("session", (session) => {
    http2Sessions.add(session);
    const sessionId = nextHttp2SessionId;
    nextHttp2SessionId += 1;
    http2SessionIds.set(session, sessionId);
    counters.http2.sessions += 1;
    counters.http2.sessionIds.push(sessionId);
    counters.http2.streamsBySession[String(sessionId)] = 0;
    session.once("close", () => http2Sessions.delete(session));
    session.on("error", () => {});
  });
  http2Server.on("stream", (stream, headers) => {
    const generation = stateGeneration;
    const sessionId = http2SessionIds.get(stream.session);
    counters.http2.streams += 1;
    counters.http2.streamIds.push(`${sessionId}:${stream.id}`);
    counters.http2.streamsBySession[String(sessionId)] += 1;
    if (counters.http2.sessions === 1) counters.http2.singleSessionStreamCount = counters.http2.streamsBySession[String(sessionId)];
    counters.http2.activeStreams += 1;
    counters.http2.maxConcurrentStreams = Math.max(counters.http2.maxConcurrentStreams, counters.http2.activeStreams);
    let closed = false;
    let identityLength = 0;
    let identityTooLarge = false;
    const identityChunks = [];
    stream.on("error", () => {});
    stream.on("data", (chunk) => {
      if (generation !== stateGeneration) return;
      counters.http2.requestBodyBytes += chunk.length;
      identityLength += chunk.length;
      if (identityLength > MAX_IDENTITY_REQUEST_BYTES) {
        identityTooLarge = true;
        return;
      }
      identityChunks.push(chunk);
    });
    stream.once("close", () => {
      if (closed || generation !== stateGeneration) return;
      closed = true;
      counters.http2.activeStreams -= 1;
      heldHttp2Streams.delete(stream);
      pendingHttp2ResetStreams.delete(stream);
      counters.http2Barriers.streams.waiting = heldHttp2Streams.size;
      if (stream.rstCode === http2.constants.NGHTTP2_CANCEL) counters.http2.cancelledStreams += 1;
    });
    stream.once("end", () => {
      if (generation !== stateGeneration || stream.closed || stream.destroyed) return;
      let identity;
      try {
        if (identityTooLarge) throw Object.assign(new Error("request envelope too large"), { code: "REQUEST_ENVELOPE_TOO_LARGE" });
        identity = parseRequestIdentity(Buffer.concat(identityChunks));
      } catch (error) {
        stream.respond({ ":status": error.code === "REQUEST_ENVELOPE_TOO_LARGE" ? 413 : 400, "content-type": "application/json" });
        stream.end(JSON.stringify({ error: { code: error.code || "INVALID_REQUEST_ENVELOPE" } }));
        return;
      }
      const requestPath = headers[":path"];
      if (requestPath === "/h2/reset/v1/messages") {
        pendingHttp2ResetStreams.add(stream);
        const peerIsHeld = [...heldHttp2Streams].some((heldStream) => {
          const held = heldHttp2Identities.get(heldStream);
          return held?.sessionId === sessionId && held.role === "peer";
        });
        if (peerIsHeld) {
          pendingHttp2ResetStreams.delete(stream);
          counters.http2.resetStreams += 1;
          stream.close(http2.constants.NGHTTP2_INTERNAL_ERROR);
        }
        return;
      }
      if (["/h2/held/v1/messages", "/h2/peer/v1/messages"].includes(requestPath)) {
        const body = Buffer.from(JSON.stringify(identity), "utf8");
        const prefixBytes = Math.min(6, body.length);
        const role = requestPath === "/h2/peer/v1/messages" ? "peer" : "held";
        stream.respond({ ":status": 202, "content-type": "application/json", "content-length": body.length });
        stream.write(body.subarray(0, prefixBytes));
        heldHttp2Streams.add(stream);
        heldHttp2Identities.set(stream, { body, prefixBytes, sessionId, role });
        counters.http2Barriers.streams.waiting = heldHttp2Streams.size;
        const heldSessionIds = new Set([...heldHttp2Streams].map((heldStream) => heldHttp2Identities.get(heldStream)?.sessionId));
        if (heldHttp2Streams.size >= 2 && heldSessionIds.size === 1) counters.http2.sameSessionOverlapCount += 1;
        if (role === "peer") {
          for (const resetStream of pendingHttp2ResetStreams) {
            if (http2SessionIds.get(resetStream.session) !== sessionId) continue;
            pendingHttp2ResetStreams.delete(resetStream);
            counters.http2.resetStreams += 1;
            resetStream.close(http2.constants.NGHTTP2_INTERNAL_ERROR);
          }
        }
        return;
      }
      if (requestPath === "/h2/goaway/v1/messages") {
        pendingGoawayAccepted.set(sessionId, { stream, identity });
        completeGoawayPair(sessionId);
        return;
      }
      if (requestPath === "/h2/goaway/retry/v1/messages") {
        if (sessionId === counters.http2.goaway.sessionId || pendingGoawayAccepted.has(sessionId)) {
          pendingGoawayUnprocessed.set(sessionId, stream);
          completeGoawayPair(sessionId);
          return;
        }
        stream.respond({ ":status": 202, "content-type": "application/json" });
        stream.end(JSON.stringify(identity));
        counters.http2.successfulStreams += 1;
        counters.http2.goaway.retrySessionId = sessionId;
        counters.http2.goaway.retryStreamId = stream.id;
        counters.http2.goaway.retrySuccesses += 1;
        return;
      }
      if (!["/h2/v1/messages", "/h2/probe/v1/messages"].includes(requestPath)) {
        stream.respond({ ":status": 404, "content-type": "application/json" });
        stream.end('{"error":{"code":"NOT_FOUND"}}');
        return;
      }
      stream.respond({ ":status": 202, "content-type": "application/json" });
      stream.end(JSON.stringify(identity));
      counters.http2.successfulStreams += 1;
      if (requestPath === "/h2/peer/v1/messages") counters.http2.peerSuccesses += 1;
      if (requestPath === "/h2/probe/v1/messages") counters.http2.postIsolationProbeSuccesses += 1;
    });
  });

  function completeGoawayPair(sessionId) {
    const accepted = pendingGoawayAccepted.get(sessionId);
    const unprocessed = pendingGoawayUnprocessed.get(sessionId);
    if (accepted === undefined || unprocessed === undefined) return;
    pendingGoawayAccepted.delete(sessionId);
    pendingGoawayUnprocessed.delete(sessionId);
    accepted.stream.respond({ ":status": 202, "content-type": "application/json" });
    accepted.stream.end(JSON.stringify(accepted.identity));
    counters.http2.successfulStreams += 1;
    counters.http2.goaway.sent += 1;
    counters.http2.goaway.sessionId = sessionId;
    counters.http2.goaway.lastStreamId = accepted.stream.id;
    counters.http2.goaway.acceptedStreamId = accepted.stream.id;
    counters.http2.goaway.unprocessedSessionId = sessionId;
    counters.http2.goaway.unprocessedStreamId = unprocessed.id;
    counters.http2.goaway.errorCode = http2.constants.NGHTTP2_NO_ERROR;
    accepted.stream.session.goaway(http2.constants.NGHTTP2_NO_ERROR, accepted.stream.id);
    unprocessed.close(http2.constants.NGHTTP2_REFUSED_STREAM);
  }

  const http1OnlyServer = https.createServer({ ...tlsOptions, ALPNProtocols: ["http/1.1"] }, async (request, response) => {
    counters.http1Only.requests += 1;
    const protocol = request.socket.alpnProtocol || "http/1.1";
    if (!counters.http1Only.negotiatedProtocols.includes(protocol)) counters.http1Only.negotiatedProtocols.push(protocol);
    if (protocol === "http/1.1") counters.http1Only.negotiatedHttp1 += 1;
    await respondWithRequestIdentity(request, response);
  });
  http1OnlyServer.on("secureConnection", () => { counters.http1Only.connections += 1; });

  for (const server of [trustedServer, mismatchServer, tls12Server, mtlsServer, resetBeforeServer, resetAfterServer, directTrapServer, noProxyTrapServer, http2Server, http1OnlyServer]) {
    trackConnections(server, operationalSockets);
    if (typeof server.on === "function") server.on("tlsClientError", () => {});
  }

  const proxyServer = http.createServer();
  const authenticatedProxyServer = http.createServer();
  trackConnections(proxyServer, operationalSockets);
  trackConnections(authenticatedProxyServer, operationalSockets);

  function configureProxy(server, stateKey, authenticationRequired) {
    server.on("request", (request, response) => {
      counters[stateKey].forwardRequests += 1;
      request.resume();
      writeJson(response, 405, { error: { code: "CONNECT_REQUIRED" } });
    });
    server.on("connect", (request, clientSocket, head) => {
      const state = counters[stateKey];
      state.connectAttempts += 1;
      const authorizationHeader = request.headers["proxy-authorization"];
      const suppliedAuthorization = typeof authorizationHeader === "string" ? authorizationHeader : "";
      if (authenticationRequired && !safeEqual(suppliedAuthorization, expectedProxyAuthorization)) {
        state.authFailures += 1;
        clientSocket.end(
          "HTTP/1.1 407 Proxy Authentication Required\r\n"
          + "Proxy-Authenticate: Basic realm=\"repost-fixture\"\r\n"
          + "Content-Length: 0\r\n"
          + "Connection: close\r\n\r\n",
        );
        return;
      }
      const target = parseAuthority(request.url);
      const allowedPorts = handshake === null ? new Set() : new Set([
        new URL(handshake.trustedBaseUrl).port,
        new URL(handshake.mtlsBaseUrl).port,
        new URL(handshake.tls12OnlyBaseUrl).port,
        new URL(handshake.http2BaseUrl).port,
      ].map(Number));
      if (target === null || !allowedPorts.has(target.port)) {
        state.deniedAuthorities += 1;
        clientSocket.end("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
      }
      const upstream = net.connect({ host: HOST, port: target.port });
      upstreamSockets.add(upstream);
      upstream.once("close", () => upstreamSockets.delete(upstream));
      upstream.once("connect", () => {
        state.tunnels += 1;
        clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
        if (head.length > 0) upstream.write(head);
        clientSocket.pipe(upstream);
        upstream.pipe(clientSocket);
      });
      upstream.once("error", () => {
        if (!clientSocket.destroyed) clientSocket.end("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
      });
      clientSocket.once("error", () => upstream.destroy());
    });
  }

  configureProxy(proxyServer, "proxy", false);
  configureProxy(authenticatedProxyServer, "authenticatedProxy", true);

  const controlServer = http.createServer(async (request, response) => {
    try {
      if (request.method === "GET" && request.url === `${CONTROL_PATH}/state`) {
        writeJson(response, 200, snapshot());
        return;
      }
      if (request.method !== "POST") {
        writeJson(response, 404, { error: { code: "NOT_FOUND" } });
        return;
      }
      const body = await readJsonBody(request);
      if (request.url === `${CONTROL_PATH}/reset`) {
        if (!exactKeys(body, [])) {
          writeJson(response, 400, { error: { code: "INVALID_CONTROL_REQUEST" } });
          return;
        }
        resetOperationalState();
        writeJson(response, 200, { reset: true });
        return;
      }
      if (request.url === `${CONTROL_PATH}/release`) {
        if (!exactKeys(body, ["barrier"]) || !["responseHeaders", "http2Streams", "preallocationBody", "staleConnection"].includes(body.barrier)) {
          writeJson(response, 400, { error: { code: "INVALID_BARRIER" } });
          return;
        }
        const released = body.barrier === "responseHeaders"
          ? releaseHeldResponses()
          : body.barrier === "http2Streams"
            ? releaseHeldHttp2Streams()
            : body.barrier === "preallocationBody"
              ? releasePreallocationResponses()
              : armStaleConnection();
        if (!released) {
          writeJson(response, 409, { error: { code: "BARRIER_NOT_WAITING" } });
          return;
        }
        writeJson(response, 200, { released: body.barrier });
        return;
      }
      if (request.url === `${CONTROL_PATH}/close`) {
        if (!exactKeys(body, [])) {
          writeJson(response, 400, { error: { code: "INVALID_CONTROL_REQUEST" } });
          return;
        }
        response.once("close", () => fixtureClose().catch(() => {}));
        writeJson(response, 200, { closed: true });
        return;
      }
      writeJson(response, 404, { error: { code: "NOT_FOUND" } });
    } catch (error) {
      if (!response.destroyed) writeJson(response, error.code === "CONTROL_BODY_TOO_LARGE" ? 413 : 400, { error: { code: error.code || "INVALID_CONTROL_REQUEST" } });
    }
  });
  trackConnections(controlServer, controlSockets);

  const servers = [
    trustedServer,
    mismatchServer,
    tls12Server,
    mtlsServer,
    resetBeforeServer,
    resetAfterServer,
    directTrapServer,
    noProxyTrapServer,
    http2Server,
    http1OnlyServer,
    proxyServer,
    authenticatedProxyServer,
    controlServer,
  ];

  async function start() {
    const addresses = [];
    const startedServers = [];
    try {
      for (const server of servers) {
        addresses.push(await listen(server));
        startedServers.push(server);
      }
    } catch (error) {
      destroySockets(operationalSockets);
      destroySockets(upstreamSockets);
      destroySockets(controlSockets);
      await Promise.all(startedServers.map(closeServer));
      throw error;
    }
    const [trusted, mismatch, tls12, mtls, resetBefore, resetAfter, directTrap, noProxyTrap, http2Address, http1Only, proxy, authenticatedProxy, control] = addresses;
    handshake = Object.freeze({
      protocol: FIXTURE_PROTOCOL,
      contractVersion: CONTRACT_VERSION,
      controlUrl: `http://${HOST}:${control.port}${CONTROL_PATH}`,
      trustedBaseUrl: `https://localhost:${trusted.port}`,
      trustedIpBaseUrl: `https://${HOST}:${trusted.port}`,
      heldResponseBaseUrl: `https://localhost:${trusted.port}/held`,
      preallocationTrapBaseUrl: `https://localhost:${trusted.port}/traps/preallocation`,
      transparentDecompressionTrapBaseUrl: `https://localhost:${trusted.port}/traps/transparent-decompression`,
      staleH1PrimeBaseUrl: `https://localhost:${trusted.port}/h1/stale/prime`,
      staleH1ReplayBaseUrl: `https://localhost:${trusted.port}/h1/stale/replay`,
      hostnameMismatchBaseUrl: `https://${HOST}:${mismatch.port}`,
      tls12OnlyBaseUrl: `https://localhost:${tls12.port}`,
      mtlsBaseUrl: `https://localhost:${mtls.port}`,
      // The reset servers are addressed at the one address they listen on:
      // through localhost, a client that also tries ::1 could report that
      // address's refusal instead of the reset under test.
      resetBeforeRequestBaseUrl: `https://${HOST}:${resetBefore.port}`,
      resetAfterRequestBaseUrl: `https://${HOST}:${resetAfter.port}`,
      directTrapBaseUrl: `http://${HOST}:${directTrap.port}`,
      noProxyTrapBaseUrl: `http://${HOST}:${noProxyTrap.port}`,
      http2BaseUrl: `https://localhost:${http2Address.port}/h2`,
      http2HeldBaseUrl: `https://localhost:${http2Address.port}/h2/held`,
      http2ResetBaseUrl: `https://localhost:${http2Address.port}/h2/reset`,
      http2GoawayBaseUrl: `https://localhost:${http2Address.port}/h2/goaway`,
      http2GoawayRetryBaseUrl: `https://localhost:${http2Address.port}/h2/goaway/retry`,
      http2FallbackBaseUrl: `https://localhost:${http1Only.port}`,
      proxyUrl: `http://${HOST}:${proxy.port}`,
      authenticatedProxyUrl: `http://${HOST}:${authenticatedProxy.port}`,
      proxyUsername: PROXY_USERNAME,
      assets: ASSET_PATHS,
      capabilities: CAPABILITIES,
      control: Object.freeze({
        state: "GET /state",
        reset: "POST /reset {}",
        release: "POST /release {barrier}",
        close: "POST /close {}",
        barriers: Object.freeze(["responseHeaders", "http2Streams", "preallocationBody", "staleConnection"]),
      }),
      limits: Object.freeze({
        declaredOversizeBytes: DECLARED_OVERSIZE_BYTES,
        decompressionTrapBytes: DECOMPRESSION_TRAP_BYTES,
        decompressionTrapRawBytes: decompressionTrapBody.length,
        resetAfterBodyBytes: RESET_AFTER_BODY_BYTES,
        maxIdentityRequestBytes: MAX_IDENTITY_REQUEST_BYTES,
      }),
    });
    if (emitHandshake) output.write(`${JSON.stringify(handshake)}\n`);
    return handshake;
  }

  async function fixtureListen() {
    if (listenPromise === null) listenPromise = start();
    return listenPromise;
  }

  async function fixtureClose() {
    if (closePromise !== null) return closePromise;
    closePromise = (async () => {
      for (const response of heldResponses) response.destroy();
      heldResponses.clear();
      for (const stream of heldHttp2Streams) stream.close(http2.constants.NGHTTP2_CANCEL);
      heldHttp2Streams.clear();
      for (const response of heldPreallocationResponses) {
        releasedPreallocationResponses.add(response);
        response.destroy();
      }
      heldPreallocationResponses.clear();
      for (const session of http2Sessions) session.destroy();
      http2Sessions.clear();
      destroySockets(operationalSockets);
      destroySockets(upstreamSockets);
      destroySockets(controlSockets);
      await Promise.all(servers.map(closeServer));
    })();
    return closePromise;
  }

  return {
    listen: fixtureListen,
    close: fixtureClose,
    snapshot,
    servers: Object.freeze({
      trustedServer,
      mismatchServer,
      tls12Server,
      mtlsServer,
      resetBeforeServer,
      resetAfterServer,
      directTrapServer,
      noProxyTrapServer,
      http2Server,
      http1OnlyServer,
      proxyServer,
      authenticatedProxyServer,
      controlServer,
    }),
  };
}

async function main() {
  const fixture = createNetworkFixture({ emitHandshake: true });
  await fixture.listen();
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await fixture.close();
  };
  const exitAfterClose = () => close().then(
    () => process.exit(0),
    () => process.exit(1),
  );
  process.once("SIGINT", exitAfterClose);
  process.once("SIGTERM", exitAfterClose);
}

if (require.main === module) {
  main().catch(() => {
    process.stderr.write("enterprise network fixture failed\n");
    process.exitCode = 1;
  });
}

module.exports = {
  ASSET_PATHS,
  CAPABILITIES,
  CONTRACT_VERSION,
  FIXTURE_PROTOCOL,
  PROXY_USERNAME,
  RESET_AFTER_BODY_BYTES,
  createNetworkFixture,
};
