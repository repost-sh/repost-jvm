"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const http2 = require("node:http2");
const https = require("node:https");
const net = require("node:net");
const path = require("node:path");
const readline = require("node:readline");
const tls = require("node:tls");
const zlib = require("node:zlib");
const { once } = require("node:events");
const test = require("node:test");

const {
  ASSET_PATHS,
  CAPABILITIES,
  CONTRACT_VERSION,
  FIXTURE_PROTOCOL,
  PROXY_USERNAME,
  RESET_AFTER_BODY_BYTES,
  createNetworkFixture,
} = require("./network-fixture.js");

const contract = JSON.parse(fs.readFileSync(path.join(__dirname, "v2.json"), "utf8"));
const networkContract = contract.fixtureProtocol.networkFixture;
const PROXY_PASSWORD_SENTINEL = fs.readFileSync(ASSET_PATHS.proxyCredentialPath, "utf8").trimEnd();
const CLIENT_KEY_PASSWORD_SENTINEL = fs.readFileSync(ASSET_PATHS.clientKeyPasswordPath, "utf8").trimEnd();
const REQUEST_PAYLOAD_SENTINEL = "SENTINEL_NETWORK_PAYLOAD_92c0b1";
const REQUEST_AUTHORIZATION_SENTINEL = "SENTINEL_NETWORK_AUTHORIZATION_50dc2a";
const REQUEST_ENVELOPE = Object.freeze({
  type: "contract.sent",
  customerId: "cus_contract",
  timestamp: "2026-01-01T00:00:00.000Z",
  data: { value: REQUEST_PAYLOAD_SENTINEL },
});
const REQUEST_BODY = JSON.stringify(REQUEST_ENVELOPE);
const EXPECTED_IDENTITY = Object.freeze({
  id: "msg_network_fixture",
  type: REQUEST_ENVELOPE.type,
  customerId: REQUEST_ENVELOPE.customerId,
  timestamp: REQUEST_ENVELOPE.timestamp,
});

function messageUrl(baseUrl) {
  return `${baseUrl.replace(/\/+$/, "")}/v1/messages`;
}

function http2OperationUrl(baseUrl, operation) {
  return `${baseUrl.replace(/\/+$/, "")}/${operation}/v1/messages`;
}

function envelopeBody(value) {
  return JSON.stringify({ ...REQUEST_ENVELOPE, data: { value } });
}

function request(url, options = {}) {
  const target = new URL(url);
  const body = options.body === undefined
    ? null
    : Buffer.isBuffer(options.body) ? options.body : Buffer.from(options.body, "utf8");
  const client = target.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const requestObject = client.request({
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: options.method || "GET",
      headers: {
        ...(body === null ? {} : { "content-length": body.length }),
        ...(options.headers || {}),
      },
      agent: options.agent === undefined ? false : options.agent,
      ca: options.ca,
      cert: options.cert,
      key: options.key,
      passphrase: options.passphrase,
      minVersion: options.minVersion,
      maxVersion: options.maxVersion,
      servername: options.servername,
    });
    requestObject.once("error", reject);
    requestObject.once("response", (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.once("aborted", () => reject(Object.assign(new Error("response aborted"), { code: "RESPONSE_ABORTED" })));
      response.once("error", reject);
      response.once("end", () => resolve({
        status: response.statusCode,
        headers: response.headers,
        body: Buffer.concat(chunks),
      }));
    });
    requestObject.end(body);
  });
}

function startHeldResponse(url, options = {}) {
  const target = new URL(url);
  const body = Buffer.from(options.body || "", "utf8");
  let resolveFirstChunk;
  const firstChunk = new Promise((resolve) => { resolveFirstChunk = resolve; });
  let requestObject;
  let responseObject;
  const completed = new Promise((resolve, reject) => {
    requestObject = https.request({
      hostname: target.hostname,
      port: target.port,
      path: target.pathname,
      method: "POST",
      headers: { "content-length": body.length, ...(options.headers || {}) },
      agent: false,
      ca: options.ca,
    });
    requestObject.once("error", reject);
    requestObject.once("response", (response) => {
      responseObject = response;
      response.once("data", (chunk) => resolveFirstChunk(chunk));
      response.once("aborted", () => reject(Object.assign(new Error("response aborted"), { code: "RESPONSE_ABORTED" })));
      response.once("error", reject);
      response.once("end", resolve);
    });
    requestObject.end(body);
  });
  return {
    firstChunk,
    completed,
    destroy: () => {
      responseObject?.destroy();
      requestObject?.destroy();
    },
  };
}

async function jsonRequest(url, method = "GET", value) {
  const body = value === undefined ? undefined : JSON.stringify(value);
  const response = await request(url, {
    method,
    body,
    headers: body === undefined ? {} : { "content-type": "application/json" },
  });
  return {
    ...response,
    json: response.body.length === 0 ? null : JSON.parse(response.body.toString("utf8")),
  };
}

async function state(handshake) {
  const response = await jsonRequest(`${handshake.controlUrl}/state`);
  assert.equal(response.status, 200);
  return response.json;
}

async function control(handshake, action, value) {
  return jsonRequest(`${handshake.controlUrl}/${action}`, "POST", value);
}

async function until(check, label) {
  for (let turn = 0; turn < 500; turn += 1) {
    const result = await check();
    if (result) return result;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`network fixture did not reach ${label}`);
}

function connectProxy(proxyUrl, authority, proxyAuthorization) {
  const proxy = new URL(proxyUrl);
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: proxy.hostname, port: proxy.port });
    let response = Buffer.alloc(0);
    const fail = (error) => {
      cleanup();
      socket.destroy();
      reject(error);
    };
    const cleanup = () => {
      socket.off("error", fail);
      socket.off("data", onData);
    };
    const onData = (chunk) => {
      response = Buffer.concat([response, chunk]);
      const boundary = response.indexOf("\r\n\r\n");
      if (boundary === -1) return;
      cleanup();
      socket.pause();
      const head = response.subarray(0, boundary + 4).toString("latin1");
      const statusMatch = /^HTTP\/1\.1 (\d{3})/.exec(head);
      if (statusMatch === null) return fail(new Error("proxy returned a malformed response"));
      const remainder = response.subarray(boundary + 4);
      if (remainder.length > 0) socket.unshift(remainder);
      resolve({ socket, status: Number(statusMatch[1]), head });
    };
    socket.once("error", fail);
    socket.on("data", onData);
    socket.once("connect", () => {
      socket.write(
        `CONNECT ${authority} HTTP/1.1\r\n`
        + `Host: ${authority}\r\n`
        + (proxyAuthorization === undefined ? "" : `Proxy-Authorization: ${proxyAuthorization}\r\n`)
        + "Connection: close\r\n\r\n",
      );
    });
  });
}

function requestThroughTunnel(socket, targetUrl, tlsOptions) {
  const target = new URL(targetUrl);
  return new Promise((resolve, reject) => {
    const secureSocket = tls.connect({
      socket,
      servername: target.hostname,
      ca: tlsOptions.ca,
      cert: tlsOptions.cert,
      key: tlsOptions.key,
      passphrase: tlsOptions.passphrase,
    });
    const chunks = [];
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      secureSocket.destroy();
      reject(error);
    };
    secureSocket.once("error", fail);
    secureSocket.once("secureConnect", () => {
      const body = REQUEST_BODY;
      secureSocket.write(
        `POST ${target.pathname} HTTP/1.1\r\n`
        + `Host: ${target.host}\r\n`
        + `Content-Length: ${Buffer.byteLength(body)}\r\n`
        + `Authorization: ${REQUEST_AUTHORIZATION_SENTINEL}\r\n`
        + "Connection: close\r\n\r\n"
        + body,
      );
    });
    secureSocket.on("data", (chunk) => chunks.push(chunk));
    secureSocket.once("end", () => {
      if (settled) return;
      settled = true;
      const response = Buffer.concat(chunks).toString("utf8");
      const statusMatch = /^HTTP\/1\.1 (\d{3})/.exec(response);
      if (statusMatch === null) return reject(new Error("tunneled target returned a malformed response"));
      const boundary = response.indexOf("\r\n\r\n");
      resolve({
        status: Number(statusMatch[1]),
        raw: response,
        body: boundary === -1 ? Buffer.alloc(0) : Buffer.from(response.slice(boundary + 4), "utf8"),
      });
    });
  });
}

async function tunneledRequest(proxyUrl, targetUrl, options = {}) {
  const target = new URL(targetUrl);
  const authority = `${target.hostname}:${target.port}`;
  const connected = await connectProxy(proxyUrl, authority, options.proxyAuthorization);
  if (connected.status !== 200) {
    connected.socket.destroy();
    return { status: connected.status, proxyHead: connected.head };
  }
  return requestThroughTunnel(connected.socket, targetUrl, options);
}

function assertTlsFailure(error) {
  assert.ok(error instanceof Error);
  assert.ok(
    typeof error.code === "string" && error.code.length > 0,
    `expected a stable Node TLS error code, received ${error.code}`,
  );
  return true;
}

function runNodeChild(script, environment, execArguments = []) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [...execArguments, "-e", script], {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...environment },
    });
    const stdout = [];
    const stderr = [];
    let outputBytes = 0;
    const collect = (target) => (chunk) => {
      outputBytes += chunk.length;
      if (outputBytes > 64 * 1024) {
        child.kill("SIGKILL");
        return;
      }
      target.push(chunk);
    };
    child.stdout.on("data", collect(stdout));
    child.stderr.on("data", collect(stderr));
    child.once("error", reject);
    child.once("exit", (code, signal) => resolve({
      code,
      signal,
      stdout: Buffer.concat(stdout).toString("utf8"),
      stderr: Buffer.concat(stderr).toString("utf8"),
    }));
  });
}

async function connectHttp2(url, ca) {
  const target = new URL(url);
  const session = http2.connect(`${target.protocol}//${target.host}`, { ca });
  session.on("error", () => {});
  await once(session, "connect");
  return session;
}

function startHttp2Request(session, url, body = REQUEST_BODY) {
  const target = new URL(url);
  const stream = session.request({
    ":method": "POST",
    ":path": target.pathname,
    "content-length": Buffer.byteLength(body),
  });
  let status;
  const chunks = [];
  const completed = new Promise((resolve, reject) => {
    stream.once("response", (headers) => { status = headers[":status"]; });
    stream.on("data", (chunk) => chunks.push(chunk));
    stream.once("error", reject);
    stream.once("end", () => resolve({ status, body: Buffer.concat(chunks) }));
  });
  stream.end(body);
  return { stream, completed };
}

async function closeHttp2Session(session) {
  if (session.closed || session.destroyed) return;
  const closed = once(session, "close");
  session.close();
  await closed;
}

test("enterprise network fixture certifies real TLS, mTLS, proxy, commitment, and lifecycle behavior", async (t) => {
  const output = [];
  const fixture = createNetworkFixture({ emitHandshake: true, output: { write: (value) => output.push(value) } });
  const handshake = await fixture.listen();
  assert.deepEqual(await fixture.listen(), handshake);
  t.after(async () => fixture.close());

  const ca = fs.readFileSync(handshake.assets.caCertPath);
  const clientCert = fs.readFileSync(handshake.assets.clientCertPath);
  const clientKey = fs.readFileSync(handshake.assets.clientKeyPath);

  await t.test("handshake is one secret-free line with loopback-only endpoints and checked-in assets", () => {
    assert.equal(output.length, 1);
    assert.equal(output[0].endsWith("\n"), true);
    assert.equal(output[0].slice(0, -1).includes("\n"), false);
    assert.deepEqual(JSON.parse(output[0]), handshake);
    assert.equal(handshake.protocol, FIXTURE_PROTOCOL);
    assert.equal(handshake.contractVersion, CONTRACT_VERSION);
    assert.equal(networkContract.protocol, FIXTURE_PROTOCOL);
    assert.deepEqual(Object.keys(handshake), networkContract.handshakeFields);
    assert.deepEqual(handshake.capabilities, CAPABILITIES);
    assert.deepEqual(handshake.capabilities, networkContract.capabilities);
    assert.deepEqual(handshake.control, {
      state: "GET /state",
      reset: "POST /reset {}",
      release: "POST /release {barrier}",
      close: "POST /close {}",
      barriers: ["responseHeaders", "http2Streams", "preallocationBody", "staleConnection"],
    });
    assert.deepEqual(handshake.limits, {
      declaredOversizeBytes: 1024 * 1024 * 1024,
      decompressionTrapBytes: (1024 * 1024) + 1,
      decompressionTrapRawBytes: handshake.limits.decompressionTrapRawBytes,
      resetAfterBodyBytes: RESET_AFTER_BODY_BYTES,
      maxIdentityRequestBytes: 1024 * 1024,
    });
    assert.ok(handshake.limits.decompressionTrapRawBytes > 0 && handshake.limits.decompressionTrapRawBytes < 16 * 1024);
    for (const [key, value] of Object.entries(handshake)) {
      if (key.endsWith("Url")) assert.ok(["127.0.0.1", "localhost"].includes(new URL(value).hostname));
    }
    for (const assetPath of Object.values(handshake.assets)) {
      assert.equal(path.isAbsolute(assetPath), true);
      assert.equal(fs.existsSync(assetPath), true);
    }
    const sdkBaseFields = [
      "trustedBaseUrl",
      "trustedIpBaseUrl",
      "heldResponseBaseUrl",
      "preallocationTrapBaseUrl",
      "transparentDecompressionTrapBaseUrl",
      "staleH1PrimeBaseUrl",
      "staleH1ReplayBaseUrl",
      "hostnameMismatchBaseUrl",
      "tls12OnlyBaseUrl",
      "mtlsBaseUrl",
      "resetBeforeRequestBaseUrl",
      "resetAfterRequestBaseUrl",
      "directTrapBaseUrl",
      "noProxyTrapBaseUrl",
      "http2BaseUrl",
      "http2HeldBaseUrl",
      "http2ResetBaseUrl",
      "http2GoawayBaseUrl",
      "http2GoawayRetryBaseUrl",
      "http2FallbackBaseUrl",
    ];
    for (const field of sdkBaseFields) {
      const base = handshake[field];
      assert.equal(typeof base, "string", `${field} must be advertised`);
      const basePath = new URL(base).pathname.replace(/\/+$/, "");
      const endpoint = new URL(messageUrl(base));
      assert.equal(endpoint.pathname, `${basePath}/v1/messages`, `${field} must use the core append algorithm`);
      assert.equal(endpoint.pathname.match(/\/v1\/messages/g)?.length, 1, `${field} must append exactly once`);
    }
    const serialized = JSON.stringify(handshake);
    assert.equal(serialized.includes(PROXY_PASSWORD_SENTINEL), false);
    assert.equal(serialized.includes(CLIENT_KEY_PASSWORD_SENTINEL), false);
    assert.equal(Object.prototype.hasOwnProperty.call(handshake.assets, "serverKeyPath"), false);
  });

  await t.test("custom CA succeeds while the platform truststore rejects the fixture CA", async () => {
    const trusted = await request(messageUrl(handshake.trustedBaseUrl), { method: "POST", body: REQUEST_BODY, ca, headers: { authorization: REQUEST_AUTHORIZATION_SENTINEL } });
    assert.equal(trusted.status, 202);
    const identity = JSON.parse(trusted.body);
    assert.deepEqual(identity, EXPECTED_IDENTITY);
    assert.deepEqual(Object.keys(identity), ["id", "type", "customerId", "timestamp"]);
    const exposed = JSON.stringify({ state: await state(handshake), output });
    assert.equal(exposed.includes(REQUEST_PAYLOAD_SENTINEL), false);
    assert.equal(exposed.includes(REQUEST_AUTHORIZATION_SENTINEL), false);
    await assert.rejects(
      request(messageUrl(handshake.trustedBaseUrl), { method: "POST", body: REQUEST_BODY }),
      assertTlsFailure,
    );
  });

  await t.test("success identity parsing is bounded and rejects malformed envelopes without retention", async () => {
    const invalid = await request(messageUrl(handshake.trustedBaseUrl), {
      method: "POST",
      body: "not-json",
      ca,
    });
    assert.equal(invalid.status, 400);
    assert.deepEqual(JSON.parse(invalid.body), { error: { code: "INVALID_REQUEST_ENVELOPE" } });

    const oversizedSentinel = "SENTINEL_OVERSIZED_IDENTITY_6ff681";
    const oversized = await request(messageUrl(handshake.trustedBaseUrl), {
      method: "POST",
      body: `${oversizedSentinel}${"x".repeat((1024 * 1024) + 1)}`,
      ca,
    });
    assert.equal(oversized.status, 413);
    assert.deepEqual(JSON.parse(oversized.body), { error: { code: "REQUEST_ENVELOPE_TOO_LARGE" } });
    const exposed = JSON.stringify({ state: await state(handshake), output });
    assert.equal(exposed.includes(oversizedSentinel), false);
    assert.equal(exposed.includes("not-json"), false);
  });

  await t.test("hostname verification remains enabled with a trusted CA", async () => {
    await assert.rejects(
      request(messageUrl(handshake.hostnameMismatchBaseUrl), { method: "POST", body: REQUEST_BODY, ca }),
      (error) => {
        assert.equal(error.code, "ERR_TLS_CERT_ALTNAME_INVALID");
        return true;
      },
    );
    assert.equal((await state(handshake)).hostnameMismatch.requests, 0);
  });

  await t.test("TLS protocol mismatch fails and an explicitly compatible client succeeds", async () => {
    await assert.rejects(
      request(messageUrl(handshake.tls12OnlyBaseUrl), { method: "POST", body: REQUEST_BODY, ca, minVersion: "TLSv1.3", maxVersion: "TLSv1.3" }),
      assertTlsFailure,
    );
    const compatible = await request(messageUrl(handshake.tls12OnlyBaseUrl), {
      method: "POST",
      body: REQUEST_BODY,
      ca,
      minVersion: "TLSv1.2",
      maxVersion: "TLSv1.2",
    });
    assert.equal(compatible.status, 202);
    assert.deepEqual(JSON.parse(compatible.body), EXPECTED_IDENTITY);
  });

  await t.test("mTLS server rejects a missing client certificate and accepts the encrypted test key", async () => {
    await assert.rejects(
      request(messageUrl(handshake.mtlsBaseUrl), { method: "POST", body: REQUEST_BODY, ca }),
      assertTlsFailure,
    );
    const mutual = await request(messageUrl(handshake.mtlsBaseUrl), {
      method: "POST",
      body: REQUEST_BODY,
      ca,
      cert: clientCert,
      key: clientKey,
      passphrase: CLIENT_KEY_PASSWORD_SENTINEL,
    });
    assert.equal(mutual.status, 202);
    assert.deepEqual(JSON.parse(mutual.body), EXPECTED_IDENTITY);
    const snapshot = await state(handshake);
    assert.equal(snapshot.mtls.requests, 1);
    assert.equal(snapshot.mtls.authorizedRequests, 1);
  });

  await t.test("response header release is controlled by an explicit barrier", async () => {
    let settled = false;
    const pending = request(messageUrl(handshake.heldResponseBaseUrl), { method: "POST", body: REQUEST_BODY, ca })
      .finally(() => { settled = true; });
    await until(async () => (await state(handshake)).barriers.responseHeaders.waiting === 1, "response header barrier");
    assert.equal(settled, false);
    const released = await control(handshake, "release", { barrier: "responseHeaders" });
    assert.equal(released.status, 200);
    const held = await pending;
    assert.equal(held.status, 202);
    assert.deepEqual(JSON.parse(held.body), EXPECTED_IDENTITY);
    assert.equal((await state(handshake)).barriers.responseHeaders.releases, 1);
  });

  await t.test("declared-length trap cannot eagerly allocate the advertised one-gigabyte response", async () => {
    const script = String.raw`
      const fs = require("node:fs");
      const https = require("node:https");
      const body = process.env.FIXTURE_BODY;
      const request = https.request(process.env.FIXTURE_URL, {
        method: "POST",
        ca: fs.readFileSync(process.env.FIXTURE_CA),
        headers: {
          authorization: process.env.FIXTURE_AUTHORIZATION,
          "content-length": Buffer.byteLength(body),
          "content-type": "application/json",
        },
        agent: false,
      });
      request.once("error", () => process.exit(2));
      request.once("response", (response) => {
        response.once("data", (chunk) => {
          const result = {
            status: response.statusCode,
            declaredLength: Number(response.headers["content-length"]),
            firstChunkBytes: chunk.length,
          };
          response.destroy();
          process.stdout.write(JSON.stringify(result) + "\n", () => process.exit(0));
        });
      });
      request.end(body);
    `;
    const child = await runNodeChild(script, {
      FIXTURE_URL: messageUrl(handshake.preallocationTrapBaseUrl),
      FIXTURE_CA: handshake.assets.caCertPath,
      FIXTURE_BODY: REQUEST_BODY,
      FIXTURE_AUTHORIZATION: REQUEST_AUTHORIZATION_SENTINEL,
    }, ["--max-old-space-size=32"]);
    assert.equal(child.code, 0, child.stderr);
    assert.equal(child.signal, null);
    assert.equal(child.stderr, "");
    assert.deepEqual(JSON.parse(child.stdout), {
      status: 202,
      declaredLength: 1024 * 1024 * 1024,
      firstChunkBytes: 1,
    });
    await until(async () => (await state(handshake)).preallocationTrap.clientCancellations === 1, "preallocation client cancellation");
    let snapshot = await state(handshake);
    assert.deepEqual(snapshot.preallocationTrap, {
      requests: 1,
      declaredLength: 1024 * 1024 * 1024,
      prefixBytesPublished: 1,
      clientCancellations: 1,
    });
    assert.equal(snapshot.trapBarriers.preallocationBody.waiting, 0);
    assert.equal(JSON.stringify(snapshot).includes(REQUEST_PAYLOAD_SENTINEL), false);
    assert.equal(JSON.stringify(snapshot).includes(REQUEST_AUTHORIZATION_SENTINEL), false);

    const held = startHeldResponse(messageUrl(handshake.preallocationTrapBaseUrl), {
      body: REQUEST_BODY,
      ca,
      headers: { authorization: REQUEST_AUTHORIZATION_SENTINEL },
    });
    const heldOutcome = held.completed.then(
      () => ({ completed: true }),
      (error) => ({ error }),
    );
    assert.equal((await held.firstChunk).length, 1);
    await until(async () => (await state(handshake)).trapBarriers.preallocationBody.waiting === 1, "preallocation cleanup barrier");
    const released = await control(handshake, "release", { barrier: "preallocationBody" });
    assert.equal(released.status, 200);
    const outcome = await heldOutcome;
    assert.ok(outcome.error instanceof Error);
    snapshot = await state(handshake);
    assert.deepEqual(snapshot.preallocationTrap, {
      requests: 2,
      declaredLength: 1024 * 1024 * 1024,
      prefixBytesPublished: 2,
      clientCancellations: 1,
    });
    assert.equal(snapshot.trapBarriers.preallocationBody.releases, 1);
  });

  await t.test("transparent-decompression trap distinguishes raw gzip bytes from convenience-client output", async () => {
    const trapUrl = messageUrl(handshake.transparentDecompressionTrapBaseUrl);
    const raw = await request(trapUrl, {
      method: "POST",
      body: REQUEST_BODY,
      ca,
      headers: { authorization: REQUEST_AUTHORIZATION_SENTINEL },
    });
    assert.equal(raw.status, 202);
    assert.equal(raw.headers["content-encoding"], "gzip");
    assert.equal(Number(raw.headers["content-length"]), raw.body.length);
    assert.equal(raw.body.length, handshake.limits.decompressionTrapRawBytes);
    assert.ok(raw.body.length < 16 * 1024);
    assert.equal(zlib.gunzipSync(raw.body).length, (1024 * 1024) + 1);

    const script = String.raw`
      const body = process.env.FIXTURE_BODY;
      fetch(process.env.FIXTURE_URL, {
        method: "POST",
        headers: {
          authorization: process.env.FIXTURE_AUTHORIZATION,
          "content-type": "application/json",
        },
        body,
      }).then(async (response) => {
        const bytes = Buffer.from(await response.arrayBuffer());
        const result = {
          status: response.status,
          contentEncoding: response.headers.get("content-encoding"),
          declaredRawLength: Number(response.headers.get("content-length")),
          deliveredBytes: bytes.length,
        };
        process.stdout.write(JSON.stringify(result) + "\n", () => process.exit(0));
      }).catch(() => process.exit(2));
    `;
    const child = await runNodeChild(script, {
      NODE_EXTRA_CA_CERTS: handshake.assets.caCertPath,
      FIXTURE_URL: trapUrl,
      FIXTURE_BODY: REQUEST_BODY,
      FIXTURE_AUTHORIZATION: REQUEST_AUTHORIZATION_SENTINEL,
    });
    assert.equal(child.code, 0, child.stderr);
    assert.equal(child.signal, null);
    assert.equal(child.stderr, "");
    assert.deepEqual(JSON.parse(child.stdout), {
      status: 202,
      contentEncoding: "gzip",
      declaredRawLength: raw.body.length,
      deliveredBytes: (1024 * 1024) + 1,
    });
    const snapshot = await state(handshake);
    assert.deepEqual(snapshot.transparentDecompressionTrap, {
      requests: 2,
      rawBytesPublished: raw.body.length * 2,
      decompressedBytes: (1024 * 1024) + 1,
    });
    assert.equal(JSON.stringify(snapshot).includes(REQUEST_PAYLOAD_SENTINEL), false);
  });

  await t.test("stale pooled H1 fault cannot replay outside explicit core attempts", async (t) => {
    const agent = new https.Agent({ keepAlive: true, maxSockets: 1, maxFreeSockets: 1, ca });
    t.after(() => agent.destroy());
    const prime = await request(messageUrl(handshake.staleH1PrimeBaseUrl), {
      method: "POST",
      body: REQUEST_BODY,
      ca,
      agent,
    });
    assert.equal(prime.status, 204);
    await until(async () => (await state(handshake)).trapBarriers.staleConnection.waiting === 1, "stale pooled connection barrier");
    const released = await control(handshake, "release", { barrier: "staleConnection" });
    assert.equal(released.status, 200);

    await assert.rejects(
      request(messageUrl(handshake.staleH1ReplayBaseUrl), {
        method: "POST",
        body: REQUEST_BODY,
        ca,
        agent,
      }),
      (error) => error instanceof Error,
    );
    await until(async () => (await state(handshake)).staleH1.faults === 1, "stale pooled first-body-byte fault");
    let snapshot = await state(handshake);
    assert.deepEqual(snapshot.staleH1, {
      primeRequests: 1,
      wireRequests: 1,
      wireBodyPublications: 1,
      wireBodyBytesObserved: 1,
      reusedRequests: 1,
      faults: 1,
      successResponses: 0,
      uniqueConnections: 1,
    });

    const secondCoreAttempt = await request(messageUrl(handshake.staleH1ReplayBaseUrl), {
      method: "POST",
      body: REQUEST_BODY,
      ca,
      agent,
    });
    assert.equal(secondCoreAttempt.status, 202);
    assert.deepEqual(JSON.parse(secondCoreAttempt.body), EXPECTED_IDENTITY);
    snapshot = await state(handshake);
    assert.deepEqual(snapshot.staleH1, {
      primeRequests: 1,
      wireRequests: 2,
      wireBodyPublications: 2,
      wireBodyBytesObserved: 1 + Buffer.byteLength(REQUEST_BODY),
      reusedRequests: 1,
      faults: 1,
      successResponses: 1,
      uniqueConnections: 2,
    });
    assert.equal(snapshot.staleH1.wireBodyPublications, 2, "one body publication per explicit core attempt");
    assert.equal(snapshot.trapBarriers.staleConnection.releases, 1);
  });

  await t.test("ALPN negotiates h2, multiplexes streams on one session, and falls back to a dedicated HTTP/1.1 endpoint", async () => {
    assert.equal((await control(handshake, "reset", {})).status, 200);
    const h2Target = new URL(messageUrl(handshake.http2BaseUrl));
    const negotiated = tls.connect({
      host: "127.0.0.1",
      port: h2Target.port,
      servername: h2Target.hostname,
      ca,
      ALPNProtocols: ["http/1.1", "h2"],
    });
    await once(negotiated, "secureConnect");
    assert.equal(negotiated.alpnProtocol, "h2");
    negotiated.destroy();

    const session = await connectHttp2(messageUrl(handshake.http2BaseUrl), ca);
    assert.equal(session.alpnProtocol, "h2");
    const first = await startHttp2Request(session, messageUrl(handshake.http2BaseUrl)).completed;
    assert.equal(first.status, 202);
    assert.deepEqual(JSON.parse(first.body), EXPECTED_IDENTITY);

    const left = startHttp2Request(session, messageUrl(handshake.http2HeldBaseUrl), envelopeBody("left"));
    const right = startHttp2Request(session, messageUrl(handshake.http2HeldBaseUrl), envelopeBody("right"));
    await until(async () => (await state(handshake)).http2Barriers.streams.waiting === 2, "two multiplexed h2 streams");
    let snapshot = await state(handshake);
    assert.equal(snapshot.http2.sessions, 1);
    assert.deepEqual(snapshot.http2.sessionIds, [1]);
    assert.deepEqual(snapshot.http2.streamIds, ["1:1", "1:3", "1:5"]);
    assert.equal(snapshot.http2.activeStreams, 2);
    assert.equal(snapshot.http2.maxConcurrentStreams, 2);
    assert.equal(snapshot.http2.sameSessionOverlapCount, 1);
    assert.equal(snapshot.http2.singleSessionStreamCount, 3);
    const released = await control(handshake, "release", { barrier: "http2Streams" });
    assert.equal(released.status, 200);
    assert.deepEqual(JSON.parse((await left.completed).body), EXPECTED_IDENTITY);
    assert.deepEqual(JSON.parse((await right.completed).body), EXPECTED_IDENTITY);
    await closeHttp2Session(session);

    const fallbackTarget = new URL(messageUrl(handshake.http2FallbackBaseUrl));
    const fallbackTls = tls.connect({
      host: "127.0.0.1",
      port: fallbackTarget.port,
      servername: fallbackTarget.hostname,
      ca,
      ALPNProtocols: ["h2", "http/1.1"],
    });
    await once(fallbackTls, "secureConnect");
    assert.equal(fallbackTls.alpnProtocol, "http/1.1");
    fallbackTls.destroy();
    const fallback = await request(fallbackTarget, { method: "POST", body: REQUEST_BODY, ca });
    assert.equal(fallback.status, 202);
    assert.deepEqual(JSON.parse(fallback.body), EXPECTED_IDENTITY);
    snapshot = await state(handshake);
    assert.equal(snapshot.http2.http1FallbackRequests, 0);
    assert.deepEqual(snapshot.http1Only.negotiatedProtocols, ["http/1.1"]);
    assert.equal(snapshot.http1Only.negotiatedHttp1, 1);
    assert.equal(snapshot.http1Only.requests, 1);
    assert.equal(snapshot.http2Barriers.streams.releases, 1);
  });

  await t.test("h2 RST_STREAM is isolated from a same-session peer and post-reset probe", async () => {
    assert.equal((await control(handshake, "reset", {})).status, 200);
    const session = await connectHttp2(messageUrl(handshake.http2ResetBaseUrl), ca);
    const reset = startHttp2Request(session, messageUrl(handshake.http2ResetBaseUrl), envelopeBody("SENTINEL_H2_RESET_PAYLOAD_0433"));
    const peer = startHttp2Request(session, http2OperationUrl(handshake.http2BaseUrl, "peer"), envelopeBody("peer"));
    await assert.rejects(
      reset.completed,
      (error) => {
        assert.equal(error.code, "ERR_HTTP2_STREAM_ERROR");
        return true;
      },
    );
    await until(async () => (await state(handshake)).http2Barriers.streams.waiting === 1, "same-session peer held after reset");
    assert.equal((await control(handshake, "release", { barrier: "http2Streams" })).status, 200);
    assert.equal((await peer.completed).status, 202);
    const probe = await startHttp2Request(session, http2OperationUrl(handshake.http2BaseUrl, "probe"), envelopeBody("probe")).completed;
    assert.equal(probe.status, 202);
    await closeHttp2Session(session);
    const snapshot = await state(handshake);
    assert.equal(snapshot.http2.resetStreams, 1);
    assert.equal(snapshot.http2.sessions, 1);
    assert.equal(snapshot.http2.singleSessionStreamCount, 3);
    assert.deepEqual(snapshot.http2.streamIds, ["1:1", "1:3", "1:5"]);
    assert.equal(snapshot.http2.peerSuccesses, 1);
    assert.equal(snapshot.http2.postIsolationProbeSuccesses, 1);
    assert.equal(snapshot.http2.collateralStreamFailures, 0);
    assert.equal(JSON.stringify(snapshot).includes("SENTINEL_H2_RESET_PAYLOAD_0433"), false);
  });

  await t.test("client RST_STREAM cancellation is isolated from a same-session peer and probe", async () => {
    assert.equal((await control(handshake, "reset", {})).status, 200);
    const session = await connectHttp2(messageUrl(handshake.http2HeldBaseUrl), ca);
    const pending = startHttp2Request(session, messageUrl(handshake.http2HeldBaseUrl), envelopeBody("cancel-me"));
    const peer = startHttp2Request(session, http2OperationUrl(handshake.http2BaseUrl, "peer"), envelopeBody("peer"));
    const outcome = pending.completed.then(
      (value) => ({ value }),
      (error) => ({ error }),
    );
    await until(async () => (await state(handshake)).http2Barriers.streams.waiting === 2, "held h2 cancellation and peer streams");
    const streamClosed = once(pending.stream, "close");
    pending.stream.close(http2.constants.NGHTTP2_CANCEL);
    await streamClosed;
    assert.equal(pending.stream.rstCode, http2.constants.NGHTTP2_CANCEL);
    await outcome;
    await until(async () => (await state(handshake)).http2.cancelledStreams === 1, "server-observed h2 cancellation");
    assert.equal((await control(handshake, "release", { barrier: "http2Streams" })).status, 200);
    assert.equal((await peer.completed).status, 202);
    const probe = await startHttp2Request(session, http2OperationUrl(handshake.http2BaseUrl, "probe"), envelopeBody("probe")).completed;
    assert.equal(probe.status, 202);
    const snapshot = await state(handshake);
    assert.equal(snapshot.http2Barriers.streams.waiting, 0);
    assert.equal(snapshot.http2.activeStreams, 0);
    assert.equal(snapshot.http2.sessions, 1);
    assert.equal(snapshot.http2.singleSessionStreamCount, 3);
    assert.deepEqual(snapshot.http2.streamIds, ["1:1", "1:3", "1:5"]);
    assert.equal(snapshot.http2.peerSuccesses, 1);
    assert.equal(snapshot.http2.postIsolationProbeSuccesses, 1);
    assert.equal(snapshot.http2.collateralStreamFailures, 0);
    await closeHttp2Session(session);
  });

  await t.test("GOAWAY rejects the stream above last-stream and retries it on a new session", async () => {
    assert.equal((await control(handshake, "reset", {})).status, 200);
    const session = await connectHttp2(messageUrl(handshake.http2GoawayBaseUrl), ca);
    const goaway = once(session, "goaway");
    const accepted = startHttp2Request(session, messageUrl(handshake.http2GoawayBaseUrl), envelopeBody("accepted"));
    const unprocessed = startHttp2Request(session, messageUrl(handshake.http2GoawayRetryBaseUrl), envelopeBody("retry-me"));
    const unprocessedRejected = assert.rejects(unprocessed.completed, (error) => error.code === "ERR_HTTP2_STREAM_ERROR");
    const response = await accepted.completed;
    assert.equal(response.status, 202);
    assert.deepEqual(JSON.parse(response.body), EXPECTED_IDENTITY);
    const [errorCode, lastStreamId, opaqueData] = await goaway;
    assert.equal(errorCode, http2.constants.NGHTTP2_NO_ERROR);
    assert.equal(lastStreamId, 1);
    assert.equal(opaqueData, undefined);
    await unprocessedRejected;
    await closeHttp2Session(session);
    const retrySession = await connectHttp2(messageUrl(handshake.http2GoawayRetryBaseUrl), ca);
    const retried = await startHttp2Request(retrySession, messageUrl(handshake.http2GoawayRetryBaseUrl), envelopeBody("retry-me")).completed;
    assert.equal(retried.status, 202);
    assert.deepEqual(JSON.parse(retried.body), EXPECTED_IDENTITY);
    await closeHttp2Session(retrySession);
    const snapshot = await state(handshake);
    assert.deepEqual(snapshot.http2.sessionIds, [1, 2]);
    assert.deepEqual(snapshot.http2.streamIds, ["1:1", "1:3", "2:1"]);
    assert.deepEqual(snapshot.http2.goaway, {
      sent: 1,
      sessionId: 1,
      lastStreamId: 1,
      acceptedStreamId: 1,
      unprocessedSessionId: 1,
      unprocessedStreamId: 3,
      retrySessionId: 2,
      retryStreamId: 1,
      retrySuccesses: 1,
      errorCode,
    });
  });

  await t.test("HTTP CONNECT proxy tunnels only allowlisted loopback authorities", async () => {
    const tunneled = await tunneledRequest(handshake.proxyUrl, messageUrl(handshake.trustedBaseUrl), { ca });
    assert.equal(tunneled.status, 202);
    assert.deepEqual(JSON.parse(tunneled.body), EXPECTED_IDENTITY);
    const forbidden = await connectProxy(handshake.proxyUrl, "example.com:443");
    assert.equal(forbidden.status, 403);
    forbidden.socket.destroy();
    const snapshot = await state(handshake);
    assert.equal(snapshot.proxy.connectAttempts, 2);
    assert.equal(snapshot.proxy.tunnels, 1);
    assert.equal(snapshot.proxy.deniedAuthorities, 1);
  });

  await t.test("authenticated CONNECT proxy returns 407 without credentials and tunnels exact credentials without retention", async () => {
    const missing = await connectProxy(handshake.authenticatedProxyUrl, new URL(messageUrl(handshake.trustedBaseUrl)).host);
    assert.equal(missing.status, 407);
    assert.match(missing.head, /Proxy-Authenticate: Basic realm="repost-fixture"/i);
    missing.socket.destroy();

    const wrong = await connectProxy(
      handshake.authenticatedProxyUrl,
      new URL(messageUrl(handshake.trustedBaseUrl)).host,
      `Basic ${Buffer.from(`${PROXY_USERNAME}:wrong-${PROXY_PASSWORD_SENTINEL}`, "utf8").toString("base64")}`,
    );
    assert.equal(wrong.status, 407);
    wrong.socket.destroy();

    const proxyAuthorization = `Basic ${Buffer.from(`${PROXY_USERNAME}:${PROXY_PASSWORD_SENTINEL}`, "utf8").toString("base64")}`;
    const tunneled = await tunneledRequest(handshake.authenticatedProxyUrl, messageUrl(handshake.trustedBaseUrl), { ca, proxyAuthorization });
    assert.equal(tunneled.status, 202);
    assert.deepEqual(JSON.parse(tunneled.body), EXPECTED_IDENTITY);

    const snapshot = await state(handshake);
    assert.equal(snapshot.authenticatedProxy.connectAttempts, 3);
    assert.equal(snapshot.authenticatedProxy.authFailures, 2);
    assert.equal(snapshot.authenticatedProxy.tunnels, 1);
    const exposed = JSON.stringify({ snapshot, output });
    assert.equal(exposed.includes(PROXY_PASSWORD_SENTINEL), false);
    assert.equal(exposed.includes(Buffer.from(PROXY_PASSWORD_SENTINEL).toString("base64")), false);
    assert.equal(exposed.includes(CLIENT_KEY_PASSWORD_SENTINEL), false);
  });

  await t.test("direct and no-proxy traps have independent observable counters", async () => {
    assert.equal((await request(messageUrl(handshake.directTrapBaseUrl))).status, 421);
    assert.equal((await request(messageUrl(handshake.noProxyTrapBaseUrl))).status, 421);
    const snapshot = await state(handshake);
    assert.equal(snapshot.directTrap.hits, 1);
    assert.equal(snapshot.noProxyTrap.hits, 1);
  });

  await t.test("connection reset is observable before request bytes and after the exact commitment prefix", async () => {
    await assert.rejects(
      request(messageUrl(handshake.resetBeforeRequestBaseUrl), { method: "POST", body: "must-not-be-observed", ca }),
      (error) => error instanceof Error,
    );
    await until(async () => (await state(handshake)).resetBeforeRequest.resets === 1, "pre-request reset");
    let snapshot = await state(handshake);
    assert.deepEqual(snapshot.resetBeforeRequest, { connections: 1, requestBytes: 0, resets: 1 });

    await assert.rejects(
      request(messageUrl(handshake.resetAfterRequestBaseUrl), { method: "POST", body: "0123456789abcdef", ca }),
      (error) => error instanceof Error,
    );
    await until(async () => (await state(handshake)).resetAfterRequest.resets === 1, "post-commit reset");
    snapshot = await state(handshake);
    assert.equal(snapshot.resetAfterRequest.headersCompleted, 1);
    assert.equal(snapshot.resetAfterRequest.firstBodyBytes, RESET_AFTER_BODY_BYTES);
    assert.equal(snapshot.resetAfterRequest.resets, 1);
  });

  await t.test("reset cancels held work, closes operational sockets, and clears every counter", async () => {
    const pending = request(messageUrl(handshake.heldResponseBaseUrl), { method: "POST", body: REQUEST_BODY, ca });
    const rejected = assert.rejects(pending, (error) => error instanceof Error);
    const h2Session = await connectHttp2(messageUrl(handshake.http2HeldBaseUrl), ca);
    const h2Pending = startHttp2Request(h2Session, messageUrl(handshake.http2HeldBaseUrl), envelopeBody("cancel-h2-on-reset"));
    const h2Outcome = h2Pending.completed.then(
      (value) => ({ value }),
      (error) => ({ error }),
    );
    const h2Closed = new Promise((resolve) => h2Session.once("close", resolve));
    await until(async () => (await state(handshake)).barriers.responseHeaders.waiting === 1, "held response before reset");
    await until(async () => (await state(handshake)).http2Barriers.streams.waiting === 1, "held h2 response before reset");
    const reset = await control(handshake, "reset", {});
    assert.equal(reset.status, 200);
    await rejected;
    await h2Outcome;
    await h2Closed;
    const snapshot = await state(handshake);
    assert.deepEqual(snapshot.trusted, { requests: 0, requestBodyBytes: 0 });
    assert.deepEqual(snapshot.proxy, {
      connectAttempts: 0,
      tunnels: 0,
      authFailures: 0,
      deniedAuthorities: 0,
      forwardRequests: 0,
    });
    assert.equal(snapshot.barriers.responseHeaders.waiting, 0);
    assert.equal(snapshot.active.operationalSockets, 0);
    assert.equal(snapshot.active.upstreamSockets, 0);
    assert.equal(snapshot.active.heldResponses, 0);
    assert.equal(snapshot.active.heldHttp2Streams, 0);
    assert.equal(snapshot.active.heldPreallocationResponses, 0);
    assert.equal(snapshot.active.http2Sessions, 0);
    assert.deepEqual(snapshot.http2, {
      sessions: 0,
      streams: 0,
      sessionIds: [],
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
    });
    assert.deepEqual(snapshot.http1Only, { connections: 0, requests: 0, negotiatedProtocols: [], negotiatedHttp1: 0 });
    assert.deepEqual(snapshot.preallocationTrap, {
      requests: 0,
      declaredLength: 1024 * 1024 * 1024,
      prefixBytesPublished: 0,
      clientCancellations: 0,
    });
    assert.deepEqual(snapshot.transparentDecompressionTrap, {
      requests: 0,
      rawBytesPublished: 0,
      decompressedBytes: (1024 * 1024) + 1,
    });
    assert.deepEqual(snapshot.staleH1, {
      primeRequests: 0,
      wireRequests: 0,
      wireBodyPublications: 0,
      wireBodyBytesObserved: 0,
      reusedRequests: 0,
      faults: 0,
      successResponses: 0,
      uniqueConnections: 0,
    });
  });
});

test("network fixture executable emits one handshake and exits cleanly on SIGTERM", async (t) => {
  const child = spawn(process.execPath, [path.join(__dirname, "network-fixture.js")], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  });
  const stdoutLines = [];
  const stderrChunks = [];
  child.stderr.on("data", (chunk) => stderrChunks.push(chunk));
  const lines = readline.createInterface({ input: child.stdout });
  lines.on("line", (line) => stdoutLines.push(line));
  await once(lines, "line");
  const handshake = JSON.parse(stdoutLines[0]);
  assert.equal(handshake.protocol, FIXTURE_PROTOCOL);
  assert.equal((await jsonRequest(`${handshake.controlUrl}/state`)).status, 200);
  child.kill("SIGTERM");
  const [exitCode, signal] = await once(child, "exit");
  assert.equal(exitCode, 0);
  assert.equal(signal, null);
  assert.deepEqual(stdoutLines.length, 1);
  assert.equal(Buffer.concat(stderrChunks).toString("utf8"), "");
});

test("network fixture control close returns once and releases the executable", async (t) => {
  const child = spawn(process.execPath, [path.join(__dirname, "network-fixture.js")], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  });
  const stdoutLines = [];
  const stderrChunks = [];
  child.stderr.on("data", (chunk) => stderrChunks.push(chunk));
  const lines = readline.createInterface({ input: child.stdout });
  lines.on("line", (line) => stdoutLines.push(line));
  await once(lines, "line");
  const handshake = JSON.parse(stdoutLines[0]);
  const exit = once(child, "exit");
  const response = await control(handshake, "close", {});
  assert.equal(response.status, 200);
  assert.deepEqual(response.json, { closed: true });
  const [exitCode, signal] = await exit;
  assert.equal(exitCode, 0);
  assert.equal(signal, null);
  assert.equal(stdoutLines.length, 1);
  assert.equal(Buffer.concat(stderrChunks).toString("utf8"), "");
});
