"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const http = require("node:http");
const http2 = require("node:http2");
const https = require("node:https");
const net = require("node:net");
const tls = require("node:tls");
const { once } = require("node:events");

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
  id: "msg_1",
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

module.exports = {
  EXPECTED_IDENTITY,
  REQUEST_AUTHORIZATION_SENTINEL,
  REQUEST_BODY,
  REQUEST_PAYLOAD_SENTINEL,
  assertTlsFailure,
  closeHttp2Session,
  connectHttp2,
  connectProxy,
  control,
  envelopeBody,
  http2OperationUrl,
  jsonRequest,
  messageUrl,
  request,
  runNodeChild,
  startHeldResponse,
  startHttp2Request,
  state,
  tunneledRequest,
  until,
};
