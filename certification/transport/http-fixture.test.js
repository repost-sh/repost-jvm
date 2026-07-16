"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const readline = require("node:readline");
const { once } = require("node:events");
const test = require("node:test");
const zlib = require("node:zlib");

const {
  CAPTURE_KEYS,
  FIXTURE_PROTOCOL,
  bodyBuffer,
  createFixtureServer,
  gzipStored,
} = require("./http-fixture.js");

const contractPath = path.join(__dirname, "v2.json");
const contract = JSON.parse(fs.readFileSync(contractPath, "utf8"));

function request(url, options = {}) {
  const target = new URL(url);
  const body = options.body === undefined
    ? null
    : Buffer.isBuffer(options.body) ? options.body : Buffer.from(options.body);
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: options.method || "GET",
      headers: {
        ...(body === null ? {} : { "content-length": body.length }),
        ...(options.headers || {}),
      },
      agent: options.agent === undefined ? false : options.agent,
    });
    req.once("error", reject);
    req.once("response", (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.once("aborted", () => {
        const error = new Error("response aborted");
        error.code = "RESPONSE_ABORTED";
        reject(error);
      });
      res.once("end", () => resolve({
        status: res.statusCode,
        headers: res.headers,
        rawHeaders: res.rawHeaders,
        body: Buffer.concat(chunks),
      }));
      res.once("error", reject);
    });
    if (body !== null) req.end(body);
    else req.end();
  });
}

async function jsonRequest(url, method = "GET", value) {
  const body = value === undefined ? undefined : JSON.stringify(value);
  const response = await request(url, {
    method,
    body,
    headers: body === undefined ? {} : { "content-type": "application/json" },
  });
  const parsed = response.body.length === 0 ? null : JSON.parse(response.body.toString("utf8"));
  return { ...response, json: parsed };
}

async function control(handshake, endpoint, value) {
  return jsonRequest(`${handshake.controlUrl}/${endpoint}`, "POST", value);
}

async function state(handshake) {
  const response = await jsonRequest(`${handshake.controlUrl}/state`);
  assert.equal(response.status, 200);
  return response.json;
}

async function requestWithRedirectPolicy(url, options = {}) {
  const { redirect = "manual", ...requestOptions } = options;
  const initial = await request(url, requestOptions);
  if (redirect === "manual" || initial.status < 300 || initial.status >= 400) {
    return { followed: false, initial, final: initial };
  }
  if (redirect !== "follow") throw new Error(`unsupported redirect policy: ${redirect}`);
  if (typeof initial.headers.location !== "string") throw new Error("redirect response omitted Location");
  const final = await request(new URL(initial.headers.location, url), requestOptions);
  return { followed: true, initial, final };
}

async function until(check, label) {
  for (let turn = 0; turn < 200; turn += 1) {
    const result = await check();
    if (result) return result;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`fixture did not reach ${label}`);
}

function startStreamingRequest(url, body, headers = {}) {
  const target = new URL(url);
  const chunks = [];
  let responseHeaders;
  let requestObject;
  const completed = new Promise((resolve, reject) => {
    const req = http.request({
      hostname: target.hostname,
      port: target.port,
      path: target.pathname,
      method: "POST",
      headers: { "content-length": Buffer.byteLength(body), ...headers },
      agent: false,
    });
    requestObject = req;
    req.once("error", reject);
    req.once("response", (res) => {
      responseHeaders = res.headers;
      res.on("data", (chunk) => chunks.push(chunk));
      res.once("aborted", () => reject(Object.assign(new Error("response aborted"), { code: "RESPONSE_ABORTED" })));
      res.once("error", reject);
      res.once("end", () => resolve({
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks),
      }));
    });
    req.end(body);
  });
  return {
    completed,
    abort: () => requestObject.destroy(),
    byteCount: () => chunks.reduce((total, chunk) => total + chunk.length, 0),
    responseHeaders: () => responseHeaders,
  };
}

test("body recipes materialize deterministically", () => {
  const assets = contract.fixtureProtocol.bodyAssets;
  const identity = bodyBuffer({ asset: "validIdentity" }, assets);
  assert.equal(identity.length, 104);
  assert.equal(identity.toString("base64"), assets.validIdentity.data);

  const padded = bodyBuffer({
    kind: "paddedAsset",
    asset: "validIdentity",
    totalLength: 128,
  }, assets);
  assert.equal(padded.length, 128);
  assert.ok(padded.subarray(104).every((byte) => byte === 0x20));

  const stored = gzipStored(padded);
  assert.deepEqual([...stored.subarray(0, 10)], [31, 139, 8, 0, 0, 0, 0, 0, 0, 255]);
  assert.deepEqual(zlib.gunzipSync(stored), padded);
  assert.deepEqual(
    bodyBuffer({ asset: "concatenatedGzip" }, assets),
    Buffer.concat([
      bodyBuffer({ asset: "validGzip" }, assets),
      bodyBuffer({ asset: "validGzip" }, assets),
    ]),
  );
});

test("scripted HTTP fixture executes the canonical wire protocol", async (t) => {
  const output = [];
  const fixture = createFixtureServer({ emitHandshake: true, output: { write: (value) => output.push(value) } });
  const handshake = await fixture.listen();
  await fixture.listen();
  t.after(async () => fixture.close());

  assert.equal(output.length, 1);
  assert.deepEqual(JSON.parse(output[0]), handshake);
  assert.equal(handshake.protocol, FIXTURE_PROTOCOL);
  assert.equal(contract.fixtureProtocol.httpFixture.protocol, FIXTURE_PROTOCOL);
  assert.deepEqual(Object.keys(handshake), contract.fixtureProtocol.httpFixture.handshakeFields);
  assert.equal(new URL(handshake.baseUrl).hostname, "127.0.0.1");
  assert.notEqual(new URL(handshake.baseUrl).port, "0");
  assert.equal(fixture.server.address().address, "127.0.0.1");

  await t.test("load and identity success", async () => {
    const loaded = await control(handshake, "load", { caseId: "configuration-explicit-api-key-wins-environment" });
    assert.equal(loaded.status, 200);
    assert.equal(loaded.json.ready, true);
    assert.equal(loaded.json.caseId, "configuration-explicit-api-key-wins-environment");
    assert.equal(loaded.json.redirectTargetHits, 0);
    const response = await request(handshake.dataUrl, {
      method: "POST",
      body: "request-body-is-not-retained",
      headers: { authorization: "Bearer explicit_key", "idempotency-key": "idem_success" },
    });
    assert.equal(response.status, 202);
    assert.equal(response.body.length, 104);
    assert.equal(JSON.parse(response.body).id, "msg_1");
    const snapshot = await state(handshake);
    assert.deepEqual(snapshot.attempts, [{
      attempt: 1,
      phase: "complete",
      requestBytes: 28,
      responseBytes: 104,
      terminal: "response-202",
    }]);
  });

  await t.test("redirect target is case-scoped, method-agnostic, counted, and secret-free", async () => {
    const redirectUrl = `${handshake.controlUrl}/redirect-target`;
    await control(handshake, "reset", {});
    const unloaded = await request(redirectUrl, { method: "GET" });
    assert.equal(unloaded.status, 409);
    assert.equal(JSON.parse(unloaded.body).error.code, "REDIRECT_TARGET_NOT_ARMED");
    assert.equal((await state(handshake)).redirectTargetHits, 0);

    const loaded = await control(handshake, "load", { caseId: "configuration-redirect-refused" });
    assert.equal(loaded.status, 200);
    assert.equal(loaded.json.redirectTargetHits, 0);
    for (const [index, method] of ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"].entries()) {
      const response = await request(redirectUrl, {
        method,
        body: method === "GET" ? undefined : `discarded-${method}`,
        headers: { authorization: `SENTINEL_DIRECT_AUTH_${method}` },
      });
      assert.equal(response.status, 202);
      assert.equal(response.headers["content-type"], "application/json");
      assert.deepEqual(response.body, bodyBuffer({ asset: "validIdentity" }, contract.fixtureProtocol.bodyAssets));
      const snapshot = await state(handshake);
      assert.equal(snapshot.redirectTargetHits, index + 1);
      assert.equal(JSON.stringify(snapshot).includes(`SENTINEL_DIRECT_AUTH_${method}`), false);
      assert.equal(JSON.stringify(snapshot).includes(`discarded-${method}`), false);
    }

    const reset = await control(handshake, "reset", {});
    assert.equal(reset.status, 200);
    assert.equal(reset.json.redirectTargetHits, 0);
    await control(handshake, "load", { caseId: "configuration-explicit-api-key-wins-environment" });
    const wrongCase = await request(redirectUrl, { method: "POST", body: "must-not-count" });
    assert.equal(wrongCase.status, 409);
    assert.equal(JSON.parse(wrongCase.body).error.code, "REDIRECT_TARGET_NOT_ARMED");
    assert.equal((await state(handshake)).redirectTargetHits, 0);

    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
    t.after(() => agent.destroy());
    await control(handshake, "load", { caseId: "configuration-redirect-refused" });
    const target = await request(redirectUrl, {
      method: "POST",
      body: "SENTINEL_REDIRECT_TARGET_BODY_360dbc",
      headers: { authorization: "SENTINEL_REDIRECT_TARGET_AUTH_08ebc7" },
      agent,
    });
    assert.equal(target.status, 202);
    await control(handshake, "load", { caseId: "configuration-nonblank-credential-octets-preserved" });
    const nextRequest = await request(handshake.dataUrl, {
      method: "POST",
      body: "request",
      headers: { authorization: "Bearer  api-key-with-spaces " },
      agent,
    });
    assert.equal(nextRequest.status, 202);
  });

  await t.test("redirect refusal observes 302 and detects an unsafe follower without retaining secrets", async () => {
    const secretAuthorization = "SENTINEL_REDIRECT_AUTHORIZATION_9d223d";
    const secretBody = "SENTINEL_REDIRECT_BODY_948f5a";
    let loaded = await control(handshake, "load", { caseId: "configuration-redirect-refused" });
    assert.equal(loaded.json.redirectTargetHits, 0);

    const manual = await requestWithRedirectPolicy(handshake.dataUrl, {
      redirect: "manual",
      method: "POST",
      body: secretBody,
      headers: {
        authorization: secretAuthorization,
        "idempotency-key": "idem_redirect",
      },
    });
    assert.equal(manual.followed, false);
    assert.equal(manual.final.status, 302);
    assert.equal(manual.final.headers.location, "/__fixture/redirect-target");
    assert.equal((await state(handshake)).redirectTargetHits, 0);

    loaded = await control(handshake, "load", { caseId: "configuration-redirect-refused" });
    assert.equal(loaded.json.redirectTargetHits, 0);
    const following = await requestWithRedirectPolicy(handshake.dataUrl, {
      redirect: "follow",
      method: "POST",
      body: secretBody,
      headers: {
        authorization: secretAuthorization,
        "idempotency-key": "idem_redirect",
      },
    });
    assert.equal(following.followed, true);
    assert.equal(following.initial.status, 302);
    assert.equal(following.initial.headers.location, "/__fixture/redirect-target");
    assert.equal(following.final.status, 202);
    assert.deepEqual(following.final.body, bodyBuffer({ asset: "validIdentity" }, contract.fixtureProtocol.bodyAssets));

    const snapshot = await state(handshake);
    assert.equal(snapshot.redirectTargetHits, 1);
    assert.deepEqual(snapshot.captures, []);
    const serialized = JSON.stringify(snapshot);
    assert.equal(serialized.includes(secretAuthorization), false);
    assert.equal(serialized.includes(secretBody), false);
    for (const key of contract.fixtureProtocol.captureDenylist) {
      assert.equal(serialized.includes(`"${key}"`), false);
    }

    const reset = await control(handshake, "reset", {});
    assert.equal(reset.json.redirectTargetHits, 0);
  });

  await t.test("authorization assertion compares raw field-value octets without retaining them", async () => {
    await control(handshake, "load", { caseId: "configuration-nonblank-credential-octets-preserved" });
    const response = await request(handshake.dataUrl, {
      method: "POST",
      body: "request",
      headers: {
        authorization: "Bearer  api-key-with-spaces ",
        "idempotency-key": "idem_preserve",
      },
    });
    assert.equal(response.status, 202);
    const serializedState = JSON.stringify(await state(handshake));
    assert.equal(serializedState.includes("api-key-with-spaces"), false);

    await control(handshake, "load", { caseId: "configuration-nonblank-credential-octets-preserved" });
    const rewritten = await request(handshake.dataUrl, {
      method: "POST",
      body: "request",
      headers: { authorization: "Bearer api-key-with-spaces" },
    });
    assert.equal(rewritten.status, 422);
    assert.equal(JSON.parse(rewritten.body).error.code, "REQUEST_ASSERTION_FAILED");
  });

  await t.test("delayed headers require an explicit barrier release", async () => {
    await control(handshake, "load", { caseId: "response-delayed-headers-release-success" });
    const pending = startStreamingRequest(handshake.dataUrl, "request-body");
    await until(async () => {
      const snapshot = await state(handshake);
      return snapshot.barriers.find((item) => item.barrier === "delayed-headers")?.state === "reached";
    }, "delayed-headers barrier");
    assert.equal(pending.responseHeaders(), undefined);
    const released = await control(handshake, "release", { barrier: "delayed-headers" });
    assert.equal(released.status, 200);
    assert.equal((await pending.completed).body.length, 104);
    const duplicate = await control(handshake, "release", { barrier: "delayed-headers" });
    assert.equal(duplicate.status, 409);
    assert.equal(duplicate.json.error.code, "BARRIER_ALREADY_RELEASED");
  });

  await t.test("delayed body emits the exact prefix and resumes without a timer", async () => {
    await control(handshake, "load", { caseId: "response-delayed-body-release-success" });
    const pending = startStreamingRequest(handshake.dataUrl, "request-body");
    await until(async () => {
      const snapshot = await state(handshake);
      return snapshot.barriers.find((item) => item.barrier === "delayed-body")?.state === "reached";
    }, "delayed-body barrier");
    await until(() => pending.byteCount() === 16, "16 response bytes");
    assert.equal(pending.responseHeaders()["transfer-encoding"], "chunked");
    await control(handshake, "release", { barrier: "delayed-body" });
    assert.equal((await pending.completed).body.length, 104);
  });

  await t.test("disconnects after request commitment", async () => {
    await control(handshake, "load", { caseId: "commitment-disconnect-after-request-bytes" });
    await assert.rejects(
      request(handshake.dataUrl, { method: "POST", body: Buffer.alloc(128, 0x61) }),
      (error) => ["ECONNRESET", "RESPONSE_ABORTED", "EPIPE"].includes(error.code),
    );
    const snapshot = await state(handshake);
    assert.equal(snapshot.attempts[0].terminal, "disconnect-after-request");
    assert.equal(snapshot.attempts[0].requestBytes, 1);
  });

  await t.test("ordered incomplete and successful responses use consecutive attempts", async () => {
    await control(handshake, "load", { caseId: "response-incomplete-two-hundred-retried" });
    await assert.rejects(
      request(handshake.dataUrl, { method: "POST", body: "first" }),
      (error) => ["RESPONSE_ABORTED", "ECONNRESET"].includes(error.code),
    );
    const second = await request(handshake.dataUrl, { method: "POST", body: "second" });
    assert.equal(second.status, 202);
    assert.equal(second.body.length, 104);
    const snapshot = await state(handshake);
    assert.deepEqual(snapshot.attempts.map((attempt) => attempt.attempt), [1, 2]);
    assert.deepEqual(snapshot.attempts.map((attempt) => attempt.terminal), ["incomplete-response", "response-202"]);
  });

  await t.test("malformed, gzip, and repeated headers are emitted byte-for-byte", async () => {
    await control(handshake, "load", { caseId: "response-malformed-two-hundred-retried" });
    const malformed = await request(handshake.dataUrl, { method: "POST", body: "first" });
    assert.equal(malformed.status, 200);
    assert.equal(malformed.body.toString("base64"), contract.fixtureProtocol.bodyAssets.malformed.data);
    const recovered = await request(handshake.dataUrl, { method: "POST", body: "second" });
    assert.equal(recovered.status, 202);

    await control(handshake, "load", { caseId: "response-valid-complete-gzip" });
    const compressed = await request(handshake.dataUrl, { method: "POST", body: "request" });
    assert.equal(compressed.status, 202);
    assert.equal(compressed.body.length, 108);
    assert.equal(zlib.gunzipSync(compressed.body).length, 104);

    await control(handshake, "load", { caseId: "retry-after-duplicate-conflicting" });
    const duplicated = await request(handshake.dataUrl, { method: "POST", body: "request" });
    const retryAfter = [];
    for (let index = 0; index < duplicated.rawHeaders.length; index += 2) {
      if (duplicated.rawHeaders[index].toLowerCase() === "retry-after") retryAfter.push(duplicated.rawHeaders[index + 1]);
    }
    assert.deepEqual(retryAfter, ["5", "6"]);
  });

  await t.test("oversized body is chunked and byte exact", async () => {
    await control(handshake, "load", { caseId: "response-identity-cap-plus-one-cancels" });
    const response = await request(handshake.dataUrl, { method: "POST", body: "request" });
    assert.equal(response.status, 202);
    assert.equal(response.headers["transfer-encoding"], "chunked");
    assert.equal(response.body.length, 1048577);
    assert.ok(response.body.every((byte) => byte === 0x20));
  });

  await t.test("private capture has the exact allowlist and no request secrets", async () => {
    await control(handshake, "load", { caseId: "response-private-capture-allowlist-only" });
    const forbidden = {
      authorization: "SENTINEL_AUTHORIZATION_TEST_ONLY",
      apiKey: "SENTINEL_API_KEY_TEST_ONLY",
      payload: "SENTINEL_PAYLOAD_TEST_ONLY",
      customerId: "SENTINEL_CUSTOMER_TEST_ONLY",
      eventType: "SENTINEL_EVENT_TEST_ONLY",
      schema: "SENTINEL_SCHEMA_TEST_ONLY",
    };
    const response = await request(handshake.dataUrl, {
      method: "POST",
      body: JSON.stringify(forbidden),
      headers: {
        authorization: forbidden.authorization,
        "x-api-key": forbidden.apiKey,
        "idempotency-key": "idem_capture_private",
      },
    });
    assert.equal(response.status, 202);
    const snapshot = await state(handshake);
    assert.equal(snapshot.captures.length, 1);
    assert.deepEqual(Object.keys(snapshot.captures[0]), CAPTURE_KEYS);
    assert.deepEqual(snapshot.captures[0], {
      attempt: 1,
      commitment: true,
      responseProgress: { phase: "complete", bytes: 104, complete: true },
      idempotencyKey: "idem_capture_private",
    });
    const serialized = JSON.stringify(snapshot);
    for (const value of Object.values(forbidden)) assert.equal(serialized.includes(value), false);
    for (const key of contract.fixtureProtocol.captureDenylist) {
      assert.equal(Object.prototype.hasOwnProperty.call(snapshot.captures[0], key), false);
    }
  });

  await t.test("sentinel echo is ephemeral and never enters fixture state", async () => {
    await control(handshake, "load", { caseId: "error-safety-all-sentinels-redacted" });
    const response = await request(handshake.dataUrl, {
      method: "POST",
      body: contract.sentinels.payload,
      headers: {
        authorization: contract.sentinels.authorization,
        "idempotency-key": contract.sentinels.idempotencyKey,
      },
    });
    assert.equal(response.status, 400);
    const echoed = JSON.parse(response.body);
    assert.equal(echoed.message, contract.sentinels.remoteMessage);
    assert.deepEqual(Object.keys(echoed.echo), contract.cases
      .find((testCase) => testCase.caseId === "error-safety-all-sentinels-redacted")
      .script[0].sentinels);
    const snapshot = JSON.stringify(await state(handshake));
    for (const sentinel of Object.values(contract.sentinels)) assert.equal(snapshot.includes(sentinel), false);
  });

  await t.test("reset aborts a blocked request and clears all case state", async () => {
    await control(handshake, "load", { caseId: "response-delayed-headers-release-success" });
    const pending = startStreamingRequest(handshake.dataUrl, "request-body");
    const resetRejection = assert.rejects(
      pending.completed,
      (error) => ["ECONNRESET", "RESPONSE_ABORTED"].includes(error.code),
    );
    await until(async () => {
      const snapshot = await state(handshake);
      return snapshot.barriers[0]?.state === "reached";
    }, "blocked request");
    const reset = await control(handshake, "reset", {});
    assert.equal(reset.status, 200);
    assert.deepEqual(reset.json, {
      status: "idle",
      ready: false,
      caseId: null,
      nextAttempt: 1,
      activeRequests: 0,
      barriers: [],
      attempts: [],
      captures: [],
      redirectTargetHits: 0,
    });
    await resetRejection;
  });

  await t.test("client cancellation releases a reached response barrier", async () => {
    await control(handshake, "load", { caseId: "response-delayed-body-release-success" });
    const pending = startStreamingRequest(handshake.dataUrl, "request-body");
    const cancellation = assert.rejects(
      pending.completed,
      (error) => ["ECONNRESET", "RESPONSE_ABORTED"].includes(error.code),
    );
    await until(async () => {
      const snapshot = await state(handshake);
      return snapshot.barriers[0]?.state === "reached";
    }, "response barrier before client cancellation");
    pending.abort();
    await cancellation;
    await until(async () => (await state(handshake)).activeRequests === 0, "cancelled request cleanup");
    const reset = await control(handshake, "reset", {});
    assert.equal(reset.status, 200);
  });

  await t.test("control API rejects unknown cases and arbitrary action or body injection", async () => {
    const unknown = await control(handshake, "load", { caseId: "does-not-exist" });
    assert.equal(unknown.status, 404);
    assert.equal(unknown.json.error.code, "UNKNOWN_CASE");
    const injected = await control(handshake, "load", {
      caseId: "configuration-explicit-api-key-wins-environment",
      script: [{ actor: "fixture", action: "respond", body: "injected" }],
    });
    assert.equal(injected.status, 400);
    assert.equal(injected.json.error.code, "INVALID_LOAD_REQUEST");
  });
});

test("load rejects an unsupported canonical action", async (t) => {
  const invalidContract = structuredClone(contract);
  invalidContract.cases = [{
    caseId: "invalid-action",
    input: { profile: "valid" },
    options: {},
    script: [{ actor: "fixture", action: "arbitraryBody", attempt: 1 }],
    expected: {},
  }];
  const fixture = createFixtureServer({ contract: invalidContract });
  const handshake = await fixture.listen();
  t.after(async () => fixture.close());
  const response = await control(handshake, "load", { caseId: "invalid-action" });
  assert.equal(response.status, 400);
  assert.equal(response.json.error.code, "INVALID_ACTION");
});

test("CLI emits one handshake and exits cleanly through control close", async () => {
  const child = spawn(process.execPath, [path.join(__dirname, "http-fixture.js")], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr.on("data", (chunk) => stderr.push(chunk));
  const lines = readline.createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const handshake = JSON.parse(line);
  assert.equal(handshake.protocol, FIXTURE_PROTOCOL);
  assert.equal(new URL(handshake.baseUrl).hostname, "127.0.0.1");
  const response = await control(handshake, "close", {});
  assert.equal(response.status, 200);
  assert.deepEqual(response.json, { status: "closing" });
  const [exitCode, signal] = await once(child, "exit");
  lines.close();
  assert.equal(exitCode, 0);
  assert.equal(signal, null);
  assert.equal(stderr.length, 0);
});
