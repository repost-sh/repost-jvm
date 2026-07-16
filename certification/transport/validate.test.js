#!/usr/bin/env node

"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const crypto = require("node:crypto");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const directory = __dirname;
const validator = path.join(directory, "validate.js");
const source = JSON.parse(fs.readFileSync(path.join(directory, "v2.json"), "utf8"));
const jvmSource = JSON.parse(fs.readFileSync(path.join(directory, "jvm-v2.json"), "utf8"));
const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "repost-transport-validation-"));
const validatorSupportFiles = [
  "jvm-engine-contract.js",
  "jvm-engine-cases.js",
  "jvm-raw-fixture-contract.js",
];
let mutationCount = 0;

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function run(file) {
  return spawnSync(process.execPath, [validator, file], {
    encoding: "utf8",
    timeout: 30_000,
  });
}

function runArgs(...args) {
  return spawnSync(process.execPath, [validator, ...args], {
    encoding: "utf8",
    timeout: 30_000,
  });
}

function output(result) {
  return `${result.stdout}${result.stderr}`;
}

function copyValidatorSuite(targetDirectory) {
  fs.copyFileSync(validator, path.join(targetDirectory, "validate.js"));
  for (const file of validatorSupportFiles) {
    fs.copyFileSync(path.join(directory, file), path.join(targetDirectory, file));
  }
}

function findCase(document, predicate) {
  const testCase = document.cases.find(predicate);
  assert.ok(testCase, "test mutation requires a matching pristine case");
  return testCase;
}

function findScenario(document, scenarioId) {
  const scenario = document.concurrencyScenarios.find((candidate) => candidate.scenarioId === scenarioId);
  assert.ok(scenario, `test mutation requires concurrency scenario ${scenarioId}`);
  return scenario;
}

function reclassifyResponseTooLargeAsProtocol(testCase, errorCatalog) {
  testCase.expected.attemptTrace = testCase.expected.attemptTrace.map((trace) => trace.replace("end:response-too-large", "end:response-protocol"));
  testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace
    .replace("outcome=response-too-large|code=RESPONSE_TOO_LARGE", "outcome=response-protocol|code=RESPONSE_PROTOCOL")
    .replace("code=RESPONSE_TOO_LARGE", "code=RESPONSE_PROTOCOL"));
  testCase.expected.errorTrace = ["terminal:RESPONSE_PROTOCOL:POSSIBLY_SENT"];
  testCase.expected.error.code = "RESPONSE_PROTOCOL";
  testCase.expected.error.message = errorCatalog.RESPONSE_PROTOCOL.message;
  testCase.expected.legacyBody.code = "RESPONSE_PROTOCOL";
  testCase.expected.legacyBody.message = errorCatalog.RESPONSE_PROTOCOL.message;
  testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:", "response-bytes:"));
  testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":rejected", ":accepted"));
  testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
}

function reclassifyResponseProtocolAsTooLarge(testCase, errorCatalog) {
  testCase.expected.attemptTrace = testCase.expected.attemptTrace.map((trace) => trace.replace("end:response-protocol", "end:response-too-large"));
  testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace
    .replace("outcome=response-protocol|code=RESPONSE_PROTOCOL", "outcome=response-too-large|code=RESPONSE_TOO_LARGE")
    .replace("code=RESPONSE_PROTOCOL", "code=RESPONSE_TOO_LARGE"));
  testCase.expected.errorTrace = ["terminal:RESPONSE_TOO_LARGE:POSSIBLY_SENT"];
  testCase.expected.error.code = "RESPONSE_TOO_LARGE";
  testCase.expected.error.message = errorCatalog.RESPONSE_TOO_LARGE.message;
  testCase.expected.legacyBody.code = "RESPONSE_TOO_LARGE";
  testCase.expected.legacyBody.message = errorCatalog.RESPONSE_TOO_LARGE.message;
}

function makeCorruptGzipRetryableMalformed(testCase) {
  const response = testCase.script.find((action) => action.action === "respond");
  response.headers["content-encoding"] = "identity";
  response.body.asset = "malformed";
  testCase.expected.error.compressedBytes = 6;
  testCase.expected.error.decompressedBytes = 6;
  testCase.expected.error.retryable = true;
  testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:108", "response-bytes:6"));
  testCase.expected.byteTrace = ["response-wire:6:accepted", "response-decoded:6:accepted"];
  testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
}

function rewriteRetryAfterOutcome(testCase, retryAfterFact, delayMs) {
  const advance = testCase.script.find((action) => action.action === "advanceTime");
  assert.ok(advance, `${testCase.caseId}: retry mutation requires an advanceTime action`);
  advance.byMs = delayMs;
  testCase.expected.attemptTrace = testCase.expected.attemptTrace.map((trace) => trace.startsWith("attempt:2:")
    ? trace.replace(/@[0-9]+$/u, `@${delayMs}`)
    : trace);
  testCase.expected.commitTrace = testCase.expected.commitTrace.map((trace) => trace.startsWith("attempt:2:")
    ? trace.replace(/@[0-9]+$/u, `@${delayMs}`)
    : trace);
  testCase.expected.scheduleTrace = testCase.expected.scheduleTrace.map((trace) => {
    if (trace.startsWith("retry:1:retry-after=")) return `retry:1:retry-after=${retryAfterFact}@0`;
    if (trace.startsWith("retry:1:delay=")) return `retry:1:delay=${delayMs}@0`;
    return trace;
  });
  testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => {
    if (trace.startsWith("retry.delay|")) return trace.replace(/delayMs=[0-9]+$/u, `delayMs=${delayMs}`);
    if (trace.startsWith("operation.end|")) return trace.replace(/durationMs=[0-9]+/u, `durationMs=${delayMs}`);
    return trace;
  });
}

function assertMutation(name, mutate, diagnostic) {
  const document = clone(source);
  mutate(document);
  const file = path.join(temporaryDirectory, `${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(document)}\n`);
  const result = run(file);
  assert.notEqual(result.status, 0, `${name}: validator unexpectedly accepted the mutation`);
  assert.match(output(result), diagnostic, `${name}: diagnostic was not useful`);
  mutationCount += 1;
}

function assertJvmMutation(name, mutate, diagnostic) {
  const document = clone(jvmSource);
  mutate(document);
  const file = path.join(temporaryDirectory, `${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(document)}\n`);
  const result = run(file);
  assert.notEqual(result.status, 0, `${name}: validator unexpectedly accepted the JVM mutation`);
  assert.match(output(result), diagnostic, `${name}: diagnostic was not useful`);
  mutationCount += 1;
}

function assertRawMutation(name, contents, diagnostic) {
  const file = path.join(temporaryDirectory, `${name}.json`);
  fs.writeFileSync(file, contents);
  const result = run(file);
  assert.notEqual(result.status, 0, `${name}: validator unexpectedly accepted the raw mutation`);
  assert.match(output(result), diagnostic, `${name}: diagnostic was not useful`);
  mutationCount += 1;
}

try {
  const pristineFile = path.join(temporaryDirectory, "pristine.json");
  fs.writeFileSync(pristineFile, `${JSON.stringify(source)}\n`);
  const pristine = run(pristineFile);
  assert.equal(pristine.status, 0, `pristine contract failed:\n${output(pristine)}`);
  assert.match(output(pristine), new RegExp(`${source.cases.length} single-operation cases and ${source.concurrencyScenarios.length} concurrency scenarios valid`));

  const pristineJvmFile = path.join(temporaryDirectory, "pristine-jvm.json");
  fs.writeFileSync(pristineJvmFile, `${JSON.stringify(jvmSource)}\n`);
  const pristineJvm = run(pristineJvmFile);
  assert.equal(pristineJvm.status, 0, `pristine JVM overlay failed:\n${output(pristineJvm)}`);
  assert.match(output(pristineJvm), new RegExp(`shared ${source.caseManifest.length} vectors plus JVM ${jvmSource.caseManifest.length} vectors valid`));

  for (const target of ["TYPESCRIPT", "GO", "PYTHON"]) {
    const inventory = runArgs("--inventory", target);
    assert.equal(inventory.status, 0, `${target} inventory failed:\n${output(inventory)}`);
    assert.deepEqual(JSON.parse(inventory.stdout), source.caseManifest, `${target} must consume only the complete shared manifest`);
  }
  const jvmInventory = runArgs("--inventory", "JVM");
  assert.equal(jvmInventory.status, 0, `JVM inventory failed:\n${output(jvmInventory)}`);
  assert.deepEqual(JSON.parse(jvmInventory.stdout), [...source.caseManifest, ...jvmSource.caseManifest], "JVM must consume shared plus overlay manifests in canonical order");
  const lowerCaseInventory = runArgs("--inventory", "jvm");
  assert.equal(lowerCaseInventory.status, 2, "lowercase target must be rejected rather than normalized by a runner");
  assert.match(output(lowerCaseInventory), /TYPESCRIPT\|GO\|PYTHON\|JVM/);

  assertMutation("typescript-native-cancellation-mapping-weakened", (document) => {
    document.fixtureProtocol.targetPublicMappings.TYPESCRIPT.primaryCancellation = "wrap in RepostPublishError";
  }, /targetPublicMappings: must equal the frozen TypeScript, Go, and Python public cancellation\/outcome mappings/);

  assertMutation("go-errors-is-mapping-weakened", (document) => {
    document.fixtureProtocol.targetPublicMappings.GO.primaryCancellation = "return a generic SDK error";
  }, /targetPublicMappings: must equal the frozen TypeScript, Go, and Python public cancellation\/outcome mappings/);

  assertMutation("python-cancelled-error-mapping-weakened", (document) => {
    document.fixtureProtocol.targetPublicMappings.PYTHON.primaryCancellation = "replace asyncio cancellation identity";
  }, /targetPublicMappings: must equal the frozen TypeScript, Go, and Python public cancellation\/outcome mappings/);

  assertMutation("canonical-json-unpaired-surrogate", (document) => {
    document.cases[0].description = `invalid${String.fromCharCode(0xd800)}`;
  }, /canonical contract strings must contain Unicode scalar values only/);

  assertMutation("surrogate-header-token-replaced-with-nonscalar-key", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-header-name-unpaired-surrogate-rejected");
    const response = testCase.script.find((action) => action.action === "respond");
    delete response.headers.$unpairedSurrogateHeaderName;
    response.headers[`x-repost${String.fromCharCode(0xd800)}`] = "value";
  }, /canonical contract strings must contain Unicode scalar values only/);

  assertMutation("provider-token-public-class-retention-reintroduced", (document) => {
    document.fixtureProtocol.testTokenSemantics.$providerError = "only the provider class name crosses the public boundary";
  }, /must pin private synthetic provider injection and public closed cause-category semantics/);

  assertMutation("issue-reporting-contract-weakened", (document) => {
    document.fixtureProtocol.issueReporting.maxIssues = 128;
  }, /must equal the frozen bounded issue-path, traversal, ordering, counting, and redaction contract/);

  assertMutation("configuration-issue-forged-option", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-provider-and-fixed-key-mutually-exclusive");
    testCase.expected.error.configurationIssues[0].optionKeys = ["API_KEY", "TRANSPORT"];
  }, /must equal the validator-derived configuration issue oracle/);

  assertMutation("validation-issue-raw-map-key-leaked", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-validation-failure-not-attempted");
    testCase.expected.error.validationIssues[0].path = "$.raw[customer-secret]";
  }, /must use only \$, static \.member, \[n\], \[\{\*\}\], and optional terminal/);

  assertMutation("validation-issue-truncation-flag-forged", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-validation-failure-not-attempted");
    testCase.expected.error.issuesTruncated = true;
  }, /must be exactly issueCount != retained issue-list size/);

  assertMutation("ordinary-error-invents-validation-issue", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.error?.code === "SERVER_FAILURE");
    testCase.expected.error.validationIssues = [{ code: "INVALID_JSON", path: "$" }];
    testCase.expected.error.issueCount = 1;
  }, /ordinary non-validation\/configuration errors must expose empty issue lists/);

  assertMutation("shared-send-invents-close-failure", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.error?.code === "IO" && candidate.expected.error.delivery === "NOT_SENT");
    testCase.expected.error.closeFailureCount = 1;
    testCase.expected.error.closeFailureCategories = ["SCHEDULER_CLOSE"];
  }, /shared send vectors must expose zero close failures/);

  assertMutation("issue-first-32-retained-order-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "issue-reporting-first-32-of-33");
    testCase.expected.error.validationIssues.reverse();
  }, /must equal the validator-derived validation issue oracle/);

  assertMutation("issue-path-truncation-marker-removed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "issue-reporting-path-truncation");
    testCase.expected.error.validationIssues[0].path = testCase.expected.error.validationIssues[0].path.replace(".<truncated>", "");
  }, /must equal the validator-derived validation issue oracle/);

  assertMutation("issue-aggregate-cap-retains-extra-path", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "issue-reporting-aggregate-path-cap");
    testCase.expected.error.validationIssues.push({ code: "REQUIRED", path: "$.extra" });
  }, /must equal the validator-derived validation issue oracle/);

  assertMutation("raw-map-order-b-diverges-from-canonical-bytes", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "raw-map-order-b-canonical");
    testCase.expected.rawMapEvidence[1] = `fixture:raw-map:canonical-request-sha256:${"0".repeat(64)}`;
  }, /must equal the validator-derived raw-map canonical-byte\/key-rejection\/redaction oracle/);

  assertMutation("raw-map-invalid-key-publishes-entry", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "raw-map-non-string-key-container-issue");
    testCase.expected.rawMapEvidence[2] = "fixture:raw-map:entry-traversal:1";
  }, /must equal the validator-derived raw-map canonical-byte\/key-rejection\/redaction oracle/);

  {
    const raw = fs.readFileSync(path.join(directory, "v2.json"), "utf8");
    assertRawMutation("raw-duplicate-top-level-key", raw.replace(/^\{/u, '{"contractVersion":2,'), /duplicate object key "contractVersion"/);
    assertRawMutation("raw-duplicate-nested-action-key", raw.replace('"actor": "fixture",', '"actor":"fixture","actor":"fixture",'), /duplicate object key "actor"/);
    assertRawMutation("raw-duplicate-header-key", raw.replace('"content-type": "application/json"', '"content-type":"application/json","content-type":"application/json"'), /duplicate object key "content-type"/);
    assertRawMutation("raw-duplicate-expected-error-key", raw.replace('"code": "CONFIGURATION",', '"code":"CONFIGURATION","code":"CONFIGURATION",'), /duplicate object key "code"/);
    const invalidUtf8 = Buffer.concat([Buffer.from(raw.slice(0, -2), "utf8"), Buffer.from([0xff]), Buffer.from("}\n", "utf8")]);
    assertRawMutation("raw-invalid-utf8", invalidUtf8, /canonical contract must be scalar-valid UTF-8/);
    assertRawMutation("raw-utf8-bom", Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(raw, "utf8")]), /canonical contract must not begin with a UTF-8 BOM/);
  }

  assertMutation("enterprise-network-proof-removed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-custom-ca-success");
    testCase.script = testCase.script.filter((action) => action.action !== "proveNetworkCapability");
  }, /enterprise-network-fixture requires exactly one proveNetworkCapability action/);

  assertMutation("enterprise-network-wire-publications-exceed-core-attempts", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-stale-pooled-h1-one-shot-retry");
    testCase.script.find((action) => action.action === "proveNetworkCapability").wireBodyPublications = 3;
  }, /wireBodyPublications must prove at most one publication per core attempt/);

  assertMutation("enterprise-network-endpoint-not-in-handshake", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-transparent-decompression-cap-rejected");
    testCase.script.find((action) => action.action === "proveNetworkCapability").endpointField = "missingBaseUrl";
  }, /network proof endpointField must name an SDK base URL in the enterprise fixture handshake/);

  assertMutation("enterprise-network-state-expression-injects-operator", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-stale-pooled-h1-one-shot-retry");
    testCase.script.find((action) => action.action === "proveNetworkCapability").expectedState = "staleH1.wireBodyPublications<=2";
  }, /expectedState must be unique semicolon-delimited safe state-path equality predicates/);

  assertMutation("enterprise-network-mode-removed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-declared-length-preallocation-rejected");
    delete testCase.executionMode;
  }, /\$networkFixtureUrl is legal only in enterprise-network-fixture mode/);

  assertMutation("enterprise-network-direct-proxy-setup-weakened", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-default-direct-ignores-ambient-proxy");
    testCase.script.find((action) => action.action === "proveNetworkCapability").setup.pop();
  }, /must equal the executable enterprise network capability/);

  assertMutation("enterprise-network-multiplex-session-identity-forged", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-h2-multiplex-two-operations");
    testCase.script.find((action) => action.action === "proveNetworkCapability").operationOutcomes[1] = testCase.script
      .find((action) => action.action === "proveNetworkCapability").operationOutcomes[1].replace("session=1", "session=2");
  }, /must equal the executable enterprise network capability/);

  assertMutation("enterprise-network-h2-reset-collateral-failure-hidden", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-h2-rst-stream");
    testCase.script.find((action) => action.action === "proveNetworkCapability").expectedState = testCase.script
      .find((action) => action.action === "proveNetworkCapability").expectedState.replace("collateralStreamFailures=0", "collateralStreamFailures=1");
  }, /must equal the executable enterprise network capability/);

  assertMutation("enterprise-network-goaway-retry-session-forged", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-h2-goaway-metadata");
    testCase.script.find((action) => action.action === "proveNetworkCapability").operationOutcomes[1] = testCase.script
      .find((action) => action.action === "proveNetworkCapability").operationOutcomes[1].replace("retry-session=2", "retry-session=1");
  }, /must equal the executable enterprise network capability/);

  assertMutation("enterprise-network-fallback-reuses-h2-listener", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "network-http1-fallback");
    testCase.script.find((action) => action.action === "proveNetworkCapability").endpointField = "http2BaseUrl";
  }, /must equal the executable enterprise network capability/);

  assertJvmMutation("jvm-shared-contract-byte-link-corrupted", (document) => {
    document.sharedContractBytesSha256 = "0".repeat(64);
  }, /sharedContractBytesSha256: must equal the SHA-256 of the exact canonical v2\.json bytes/);

  assertJvmMutation("jvm-engine-bootstrap-reservation-weakened", (document) => {
    document.jvmEngineContract.byteBudget.connectionBootstrapHeadroomBytes = 0;
  }, /jvmEngineContract: must equal the validator-owned relocated A-prime engine/);

  assertJvmMutation("jvm-engine-goaway-parity-bug-reintroduced", (document) => {
    document.jvmEngineContract.protocol.http2.goaway = "fail only peer-side streams above last";
  }, /jvmEngineContract: must equal the validator-owned relocated A-prime engine/);

  assertJvmMutation("jvm-engine-secret-hpack-indexing-enabled", (document) => {
    document.jvmEngineContract.protocol.requestHeaders.alwaysNeverIndexed = [];
  }, /jvmEngineContract: must equal the validator-owned relocated A-prime engine/);

  assertJvmMutation("jvm-engine-builtin-hostname-verification-overridable", (document) => {
    document.jvmEngineContract.publicApi.httpTransportOptions.hostnameVerification = "custom verifier permitted";
  }, /jvmEngineContract: must equal the validator-owned relocated A-prime engine/);

  assertJvmMutation("jvm-engine-success-response-field-drift", (document) => {
    document.jvmEngineContract.serialization.successResponseFieldOrder[3] = "createdAt";
  }, /jvmEngineContract\.serialization\.successResponseFieldOrder: must exactly equal the authoritative shared successful response field set and order/);

  assertJvmMutation("jvm-raw-fixture-case-omitted", (document) => {
    document.jvmRawFixtureProtocol.fixtureCases.pop();
  }, /jvmRawFixtureProtocol\.fixtureCases: must contain exactly 33 ordered fixture cases/);

  assertJvmMutation("jvm-raw-capabilities-field-omitted", (document) => {
    delete Object.values(document.jvmRawFixtureProtocol.groups)[0].requiredCapabilities;
  }, /missing required field requiredCapabilities/);

  assertJvmMutation("jvm-raw-capabilities-field-reordered", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    const capabilities = group.requiredCapabilities;
    delete group.requiredCapabilities;
    group.requiredCapabilities = capabilities;
  }, /fields must appear in the exact closed order/);

  assertJvmMutation("jvm-raw-capabilities-empty", (document) => {
    Object.values(document.jvmRawFixtureProtocol.groups)[0].requiredCapabilities = [];
  }, /requiredCapabilities: must be a non-empty array/);

  assertJvmMutation("jvm-raw-capability-unsafe", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    group.requiredCapabilities[0] = "unsafe capability";
  }, /requiredCapabilities\[0\]: must be a safe lower-kebab-case capability name/);

  assertJvmMutation("jvm-raw-capability-duplicate", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    group.requiredCapabilities.push(group.requiredCapabilities[0]);
  }, /requiredCapabilities: must not contain duplicates/);

  assertJvmMutation("jvm-raw-capability-assertion-omitted", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    group.terminalAssertions = group.terminalAssertions.filter((assertion) => assertion !== `capability:${group.requiredCapabilities[0]}`);
  }, /terminalAssertions: must assert every group-specific capability/);

  assertJvmMutation("jvm-raw-barrier-duplicate-within-group", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups).find((candidate) => candidate.requiredBarriers.length > 1);
    group.requiredBarriers[1] = group.requiredBarriers[0];
  }, /requiredBarriers: must not contain duplicates/);

  assertJvmMutation("jvm-raw-counter-count-below-two", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    group.requiredObservedCounters = Object.fromEntries(Object.entries(group.requiredObservedCounters).slice(0, 1));
  }, /requiredObservedCounters: must include at least two group-specific observed mechanism counters/);

  assertJvmMutation("jvm-raw-counter-key-unsafe", (document) => {
    const group = Object.values(document.jvmRawFixtureProtocol.groups)[0];
    const [[, value], ...rest] = Object.entries(group.requiredObservedCounters);
    group.requiredObservedCounters = Object.fromEntries([["unsafe-counter", value], ...rest]);
  }, /requiredObservedCounters\.unsafe-counter: must be a safe lower-camel-case counter key/);

  {
    const sameIds = clone(source);
    findCase(sameIds, (candidate) => candidate.caseId === "configuration-provider-and-fixed-key-mutually-exclusive")
      .expected.error.configurationIssues[0].optionKeys = ["API_KEY", "TRANSPORT"];
    const sharedText = `${JSON.stringify(sameIds, null, 2)}\n`;
    const sharedFile = path.join(temporaryDirectory, "same-id-mutated-v2.json");
    fs.writeFileSync(sharedFile, sharedText);
    const staleOverlayFile = path.join(temporaryDirectory, "stale-overlay.json");
    fs.writeFileSync(staleOverlayFile, `${JSON.stringify(jvmSource, null, 2)}\n`);
    const stale = runArgs("--shared", sharedFile, staleOverlayFile);
    assert.notEqual(stale.status, 0, "full-byte link must reject a stale overlay against same-ID changed shared bytes");
    assert.match(output(stale), /sharedContractBytesSha256: must equal the SHA-256 of the exact canonical v2\.json bytes/);
    mutationCount += 1;

    const recomputedOverlay = clone(jvmSource);
    recomputedOverlay.sharedContractBytesSha256 = crypto.createHash("sha256").update(sharedText).digest("hex");
    const recomputedOverlayFile = path.join(temporaryDirectory, "recomputed-overlay.json");
    fs.writeFileSync(recomputedOverlayFile, `${JSON.stringify(recomputedOverlay, null, 2)}\n`);
    const semantic = runArgs("--shared", sharedFile, recomputedOverlayFile);
    assert.notEqual(semantic.status, 0, "a recomputed link must not mask an invalid same-ID shared semantic mutation");
    assert.match(output(semantic), /must equal the validator-derived configuration issue oracle/);
    mutationCount += 1;
  }

  assertJvmMutation("jvm-profile-collides-with-shared", (document) => {
    document.jvmInputProfiles.valid = { jvmExecutorOwnership: "OWNED", jvmExecutorMode: "default" };
  }, /jvmInputProfiles: must equal the closed JVM ownership\/resource profiles/);

  assertJvmMutation("jvm-action-collides-with-shared", (document) => {
    document.jvmFixtureProtocol.actionSchemas.respond = { semantics: "collision" };
  }, /actionSchemas: must contain each JVM case action exactly once/);

  assertJvmMutation("jvm-option-name-not-prefixed", (document) => {
    document.jvmFixtureProtocol.optionSchemas.apiKey = { type: "string" };
  }, /optionSchemas: must equal the closed additive JVM option schemas/);

  assertJvmMutation("jvm-case-semantic-evidence-weakened", (document) => {
    document.cases[0].expected.wireBodyPublications = 2;
  }, /wireBodyPublications: must prove one core attempt has at most one wire body publication/);

  assertJvmMutation("jvm-terminal-formula-weakened", (document) => {
    document.concurrencyProtocol.resourceFormulas.terminalWorkers = "1";
  }, /resourceFormulas: must equal the exact JVM worker\/queue\/reservation formulas/);

  assertJvmMutation("jvm-terminal-cross-operation-order-invented", (document) => {
    document.concurrencyScenarios.find((scenario) => scenario.scenarioId === "jvm-terminal-storm-65536").expected.final.crossOperationOrderRequired = true;
  }, /expected\.final: must prove balanced resources, pool exit, per-operation ordering, and no cross-operation ordering promise/);

  assertJvmMutation("jvm-fatal-same-instance-weakened", (document) => {
    findCase(document, (testCase) => testCase.caseId === "jvm-fatal-assertion-error-same-instance").expected.uncaughtFatalSameInstance = false;
  }, /fatal proof must preserve exact-instance uncaught delivery/);

  assertJvmMutation("jvm-telemetry-parent-proof-removed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "jvm-telemetry-context-through-retry");
    testCase.expected.evidence = testCase.expected.evidence.filter((entry) => entry !== "parent:captured");
  }, /must include parent:captured/);

  for (const pristineCase of jvmSource.cases) {
    assertJvmMutation(`jvm-action-oracle-${pristineCase.input.action}`, (document) => {
      findCase(document, (testCase) => testCase.caseId === pristineCase.caseId).expected.evidence.push("forged:pass");
    }, /expected\.evidence: must equal the validator-derived exact JVM semantic oracle/);
  }

  for (const [name, caseId, field, value] of [
    ["terminal-kind", "jvm-transport-null-completion-stage", "terminalKind", "SUCCESS"],
    ["code", "jvm-transport-null-completion-stage", "code", "DNS"],
    ["delivery", "jvm-transport-null-completion-stage", "delivery", "NOT_SENT"],
    ["completion-route", "jvm-transport-null-completion-stage", "completionRoute", "TERMINAL_DISPATCHER"],
    ["core-attempt-count", "jvm-transport-null-completion-stage", "coreAttemptCount", 2],
    ["wire-body-publications", "jvm-transport-null-completion-stage", "wireBodyPublications", 0],
    ["reservation-acquires", "jvm-transport-null-completion-stage", "reservationAcquires", 2],
    ["reservation-releases", "jvm-transport-null-completion-stage", "reservationReleases", 0],
    ["owned-close-calls", "jvm-close-failure-aggregate-order", "ownedCloseCalls", 5],
    ["borrowed-close-calls", "jvm-close-queued-operation-no-ghost-work", "borrowedCloseCalls", 1],
    ["fatal-identity", "jvm-transport-null-completion-stage", "uncaughtFatalSameInstance", true],
  ]) {
    assertJvmMutation(`jvm-derived-field-${name}`, (document) => {
      findCase(document, (testCase) => testCase.caseId === caseId).expected[field] = value;
    }, new RegExp(`expected\\.${field}: must equal the validator-derived exact JVM semantic oracle`));
  }

  {
    const suiteDirectory = path.join(temporaryDirectory, "mutated-linked-suite");
    fs.mkdirSync(suiteDirectory);
    copyValidatorSuite(suiteDirectory);
    const shared = clone(source);
    const overlay = clone(jvmSource);
    shared.constants.followRedirects = true;
    const sharedText = `${JSON.stringify(shared, null, 2)}\n`;
    overlay.sharedContractBytesSha256 = crypto.createHash("sha256").update(sharedText).digest("hex");
    fs.writeFileSync(path.join(suiteDirectory, "v2.json"), sharedText);
    fs.writeFileSync(path.join(suiteDirectory, "jvm-v2.json"), `${JSON.stringify(overlay, null, 2)}\n`);
    const result = spawnSync(process.execPath, [path.join(suiteDirectory, "validate.js"), path.join(suiteDirectory, "jvm-v2.json")], { encoding: "utf8", timeout: 30_000 });
    assert.notEqual(result.status, 0, "shared semantic mutation unexpectedly passed after recomputing the overlay link");
    assert.match(output(result), /constants\.followRedirects: must be false/, "shared semantic failure must remain distinct from overlay link corruption");
    assert.doesNotMatch(output(result), /sharedContractBytesSha256/, "recomputed exact-byte overlay link must remain valid for the semantic mutation test");
    mutationCount += 1;
  }

  assertMutation("concurrency-immediate-success-probe-removed", (document) => {
    const scenario = findScenario(document, "concurrency-immediate-success-release-and-probe");
    scenario.operations = scenario.operations.filter((operation) => operation.operationId !== "op-immediate-probe");
    scenario.steps = scenario.steps.filter((step) => step.operationId !== "op-immediate-probe");
    scenario.expected.operations = scenario.expected.operations.filter((operation) => operation.operationId !== "op-immediate-probe");
    scenario.expected.orderedTrace = scenario.expected.orderedTrace.filter((trace) => !trace.startsWith("operation:op-immediate-probe:"));
    scenario.expected.finalRuntime.totalPermitAcquires -= 1;
    scenario.expected.finalRuntime.totalPermitReleases -= 1;
  }, /concurrencyScenarios: scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory/);

  assertMutation("concurrency-retry-success-probe-removed", (document) => {
    const scenario = findScenario(document, "concurrency-retry-success-release-and-probe");
    scenario.operations = scenario.operations.filter((operation) => operation.operationId !== "op-retry-success-probe");
    scenario.steps = scenario.steps.filter((step) => step.operationId !== "op-retry-success-probe");
    scenario.expected.operations = scenario.expected.operations.filter((operation) => operation.operationId !== "op-retry-success-probe");
    scenario.expected.orderedTrace = scenario.expected.orderedTrace.filter((trace) => !trace.startsWith("operation:op-retry-success-probe:"));
    scenario.expected.finalRuntime.totalPermitAcquires -= 1;
    scenario.expected.finalRuntime.totalPermitReleases -= 1;
  }, /concurrencyScenarios: scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory/);

  assertMutation("unknown-top-nested-field", (document) => {
    document.units.bytes = "octets";
  }, /\$\.units\.bytes: unknown field/);

  assertMutation("missing-constant", (document) => {
    delete document.constants.connectTimeoutMs;
  }, /\$\.constants: missing required field connectTimeoutMs/);

  assertMutation("unknown-profile-send-field", (document) => {
    document.inputProfiles.valid.send.unknown = true;
  }, /\$\.inputProfiles\.valid\.send\.unknown: unknown field/);

  assertMutation("unknown-case-send-field", (document) => {
    document.cases[0].input.send = { unknown: true };
  }, /\$\.cases\[0\]\.input\.send\.unknown: unknown field/);

  assertMutation("irrelevant-action-field", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "respond"));
    testCase.script.find((action) => action.action === "respond").phase = "connect";
  }, /\.script\[[0-9]+\]\.phase: unknown field/);

  assertMutation("missing-action-required-field", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "respond"));
    delete testCase.script.find((action) => action.action === "respond").status;
  }, /\.script\[[0-9]+\]: missing required field status/);

  assertMutation("bad-body-field", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "respond"));
    testCase.script.find((action) => action.action === "respond").body.unknown = true;
  }, /\.body\.unknown: unknown field/);

  assertMutation("bad-body-member", (document) => {
    document.fixtureProtocol.bodyAssets.concatenatedGzip.members[0] = "missingAsset";
  }, /bodyAssets\.concatenatedGzip\.members\[0\]: unknown body asset/);

  assertMutation("mismatched-error-trace", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.error !== null);
    testCase.expected.errorTrace = ["terminal:CANCELLED:NOT_SENT"];
  }, /expected\.errorTrace: must exactly match terminal error/);

  assertMutation("null-accepted-result", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.delivery === "ACCEPTED");
    testCase.expected.result = null;
  }, /expected\.result: accepted delivery requires/);

  assertMutation("empty-accepted-result", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.delivery === "ACCEPTED");
    testCase.expected.result.id = "";
  }, /expected\.result\.id: must be a non-empty string/);

  assertMutation("mismatched-result-customer", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.delivery === "ACCEPTED");
    testCase.expected.result.customerId = "cus_wrong";
  }, /expected\.result\.customerId: must match materialized input/);

  assertMutation("mismatched-result-type", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.delivery === "ACCEPTED");
    testCase.expected.result.type = "wrong.type";
  }, /expected\.result\.type: must match materialized input/);

  assertMutation("incorrect-gzip-wire-bytes", (document) => {
    document.fixtureProtocol.bodyAssets.validGzip.wireBytes += 1;
  }, /bodyAssets\.validGzip\.wireBytes: must equal decoded base64 length/);

  assertMutation("incorrect-gzip-decoded-bytes", (document) => {
    document.fixtureProtocol.bodyAssets.validGzip.decodedBytes += 1;
  }, /bodyAssets\.validGzip\.decodedBytes: must equal inflated length/);

  assertMutation("response-trace-without-action", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "respond"));
    testCase.script = testCase.script.filter((action) => action.action !== "respond");
  }, /expected\.attemptTrace: response status [0-9]+ for attempt [0-9]+ requires one matching response\/proxy action/);

  assertMutation("unmatched-release", (document) => {
    document.cases[0].script.push({ actor: "runner", action: "release", barrier: "missing-barrier" });
  }, /\.barrier: release has no registered hold/);

  assertMutation("duplicate-release", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "release"));
    const release = testCase.script.find((action) => action.action === "release");
    testCase.script.push(clone(release));
  }, /\.barrier: duplicate release/);

  assertMutation("unterminated-hold", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "hold") && candidate.script.some((action) => action.action === "release"));
    testCase.script = testCase.script.filter((action) => action.action !== "release");
  }, /\.script\[[0-9]+\]: hold must terminate/);

  assertMutation("sentinel-in-observer", (document) => {
    document.cases[0].expected.observerEmissionTrace[0] += document.sentinels.apiKey;
  }, /expected\.observerEmissionTrace\[0\]: public surface contains sentinel apiKey/);

  assertMutation("skip-marker", (document) => {
    document.cases[0].skip = true;
  }, /\.skip: skip\/unsupported marker is forbidden/);

  assertMutation("invalid-transport-mode", (document) => {
    findCase(document, (candidate) => candidate.caseId === "configuration-custom-transport-success").options.transportMode = "invalid";
  }, /options\.transportMode: must be one of default, custom/);

  assertMutation("provider-conflict-starts-lifecycle", (document) => {
    findCase(document, (candidate) => candidate.caseId === "configuration-provider-and-fixed-key-mutually-exclusive").expected.configurationTrace = ["snapshot:1"];
  }, /expected\.configurationTrace: construction failure must use snapshot:0/);

  assertMutation("weakened-transport-conflict", (document) => {
    findCase(document, (candidate) => candidate.caseId === "configuration-custom-transport-built-in-options-conflict").options.builtInTransportOptions = false;
  }, /options: must declare the required pairwise construction conflict/);

  assertMutation("missing-tls-protocol-after-commitment", (document) => {
    document.cases = document.cases.filter((candidate) => candidate.caseId !== "failure-mapping-tls-protocol-after-commitment");
  }, /missing required boundary case failure-mapping-tls-protocol-after-commitment/);

  assertMutation("wrong-localhost-vector", (document) => {
    findCase(document, (candidate) => candidate.caseId === "configuration-http-localhost-accepted").options.baseUrl = "http:\/\/localhost.example:4323\/base";
  }, /configuration-http-localhost-accepted\.options\.baseUrl: must equal http:\/\/localhost:4323\/base/);

  assertMutation("missing-operation-start", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.observerEmissionTrace.some((trace) => trace.startsWith("operation.start|")));
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.filter((trace) => !trace.startsWith("operation.start|"));
  }, /expected\.observerEmissionTrace: must begin with exactly one operation\.start entry/);

  assertMutation("missing-attempt-start-observer", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.observerEmissionTrace.some((trace) => trace.startsWith("attempt.start|")));
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.filter((trace) => !trace.startsWith("attempt.start|"));
  }, /expected\.observerEmissionTrace: attempt\.start entries must exactly match attempted attempts/);

  assertMutation("missing-retry-delay-observer", (document) => {
    const testCase = findCase(document, (candidate) => candidate.expected.observerEmissionTrace.some((trace) => trace.startsWith("retry.delay|")));
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.filter((trace) => !trace.startsWith("retry.delay|"));
  }, /expected\.observerEmissionTrace: retry\.delay entries must exactly match scheduled retries and delays/);

  assertMutation("observer-context-trace-omitted", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "observer-low-cardinality-order-on-retry");
    delete testCase.expected.contextTrace;
  }, /expected\.contextTrace: must prove one operation-start capture, the captured parent on every observer callback, and caller restoration/);

  assertMutation("observer-context-callback-order-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "observer-low-cardinality-order-on-retry");
    const firstCallback = 2;
    [testCase.expected.contextTrace[firstCallback], testCase.expected.contextTrace[firstCallback + 1]] = [
      testCase.expected.contextTrace[firstCallback + 1],
      testCase.expected.contextTrace[firstCallback],
    ];
  }, /expected\.contextTrace: must prove one operation-start capture, the captured parent on every observer callback, and caller restoration/);

  assertMutation("observer-context-callback-token-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "observer-low-cardinality-order-on-retry");
    const callback = testCase.expected.contextTrace.findIndex((trace) => trace.startsWith("callback|"));
    assert.notEqual(callback, -1, "test mutation requires an observer callback context trace");
    testCase.expected.contextTrace[callback] = testCase.expected.contextTrace[callback].replace("ctx-parent-retry", "ctx-wrong");
  }, /expected\.contextTrace: must prove one operation-start capture, the captured parent on every observer callback, and caller restoration/);

  assertMutation("missing-gzip-byte-trace", (document) => {
    findCase(document, (candidate) => candidate.caseId === "response-valid-complete-gzip").expected.byteTrace = [];
  }, /expected\.byteTrace: must explicitly equal deterministic response trace/);

  assertMutation("content-encoding-case-variant-duplicate-rejects-stale-success", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-gzip");
    testCase.script.find((action) => action.action === "respond").headers["content-encoding"] = "gzip";
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("content-encoding-mixed-case-comma-stack-rejects-stale-success", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-content-encoding-mixed-case-gzip");
    testCase.script.find((action) => action.action === "respond").headers["CoNtEnT-EnCoDiNg"] = "GZip, identity";
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("response-cancel-six-to-five", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "cancellation-during-response-body");
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":6:cancelled", ":5:cancelled"));
  }, /expected\.byteTrace: must explicitly equal deterministic response trace/);

  assertMutation("public-exception-class-name-forbidden", (document) => {
    const vendorCause = "java.io.IOException";
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-io-before-commitment");
    testCase.expected.error.causeCategory = vendorCause;
    testCase.expected.outcomeEvidence.causeCategory = vendorCause;
  }, /public surface must not expose exception class or package names/);

  assertMutation("missing-all-sentinel-redaction-evidence", (document) => {
    findCase(document, (candidate) => candidate.caseId === "error-safety-all-sentinels-redacted").expected.redactionTrace = [];
  }, /expected\.redactionTrace: reachable sentinel data requires the exact five public scans and only the explicit error\.idempotencyKey allowance/);

  assertMutation("missing-terminal-response-redaction-evidence", (document) => {
    findCase(document, (candidate) => candidate.caseId === "error-safety-remote-message-discarded").expected.redactionTrace = [];
  }, /expected\.redactionTrace: reachable sentinel data requires the exact five public scans and only the explicit error\.idempotencyKey allowance/);

  assertMutation("missing-retry-success-redaction-evidence", (document) => {
    findCase(document, (candidate) => candidate.caseId === "retry-http-429-then-success").expected.redactionTrace = [];
  }, /expected\.redactionTrace: reachable sentinel data requires the exact five public scans and only the explicit error\.idempotencyKey allowance/);

  assertMutation("missing-cause-message-redaction-evidence", (document) => {
    findCase(document, (candidate) => candidate.caseId === "observer-failure-does-not-affect-terminal-failure").expected.redactionTrace = [];
  }, /expected\.redactionTrace: reachable sentinel data requires the exact five public scans and only the explicit error\.idempotencyKey allowance/);

  assertMutation("descriptor-mismatch-starts-operation", (document) => {
    findCase(document, (candidate) => candidate.caseId === "retry-descriptor-version-failure-not-attempted").expected.configurationTrace = ["snapshot:1"];
  }, /expected\.configurationTrace: construction failure must use snapshot:0/);

  assertMutation("non-ascii-idempotency-made-ascii", (document) => {
    findCase(document, (candidate) => candidate.caseId === "idempotency-key-non-ascii-rejected").options.idempotencyKey = "idem_ascii";
  }, /options\.idempotencyKey: must exercise an invalid key/);

  assertMutation("attempt-end-causality-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace.startsWith("attempt.end|")
      ? trace.replace(/\|outcome=.*$/u, "|outcome=banana|code=DNS|failureReason=UNKNOWN|delivery=NOT_SENT|statusClass=5xx")
      : trace);
  }, /expected\.observerEmissionTrace: attempt 1 end must match deterministic terminal action, status, delivery, and duration semantics/);

  assertMutation("operation-end-outcome-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace.startsWith("operation.end|")
      ? trace.replace("|outcome=accepted|", "|outcome=failed|")
      : trace);
  }, /operation\.end outcome must match the terminal result\/error semantics/);

  assertMutation("observer-durations-arbitrary", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace.replace("durationMs=0", "durationMs=999"));
  }, /operation\.end durationMs must equal deterministic monotonic elapsed time/);

  assertMutation("response-cancel-progress-six-to-five", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "cancellation-during-response-body");
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:6", "response-cancelled:5"));
  }, /expected\.fixtureTrace: attempt 1 cancellation progress must equal responseBody releaseAt 6/);

  assertMutation("response-limit-plus-one-made-inclusive", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-compressed-cap-plus-one-cancels");
    testCase.expected.error.compressedBytes = document.constants.responseCompressedLimitBytes;
    testCase.expected.byteTrace[0] = `response-wire:${document.constants.responseCompressedLimitBytes}:accepted`;
  }, /expected\.error: response counters\/truncation must match input-derived classifier/);

  assertMutation("inspection-over-window-shrunk-to-limit", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-inspection-cap-sixty-four-kib");
    const count = document.constants.errorInspectionLimitBytes;
    testCase.expected.error.compressedBytes = count;
    testCase.expected.error.decompressedBytes = count;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:65537", `response-cancelled:${count}`));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":65537:cancelled", `:${count}:cancelled`));
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=65537 decompressed=65537 truncated=true/);

  assertMutation("inspection-over-window-shrunk-below-limit", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-inspection-cap-sixty-four-kib");
    const count = document.constants.errorInspectionLimitBytes - 1;
    testCase.expected.error.compressedBytes = count;
    testCase.expected.error.decompressedBytes = count;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:65537", `response-cancelled:${count}`));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(/:(?:65537|65536):(cancelled|accepted)$/u, `:${count}:$1`));
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=65537 decompressed=65537 truncated=true/);

  assertMutation("accepted-response-reclassified-cancelled", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-bytes:104", "response-cancelled:104"));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":104:accepted", ":104:cancelled"));
    testCase.expected.sideEffectTrace.splice(-1, 0, "response.cancel");
  }, /expected\.fixtureTrace: attempt 1 response progress must be bytes:104 from the input-derived response classifier/);

  assertMutation("small-non2xx-reclassified-truncated", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-remote-message-discarded");
    testCase.expected.error.truncated = true;
    testCase.expected.legacyBody.truncated = true;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-bytes:90", "response-cancelled:90"));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":90:accepted", ":90:cancelled"));
    testCase.expected.sideEffectTrace.splice(-1, 0, "response.cancel");
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=90 decompressed=90 truncated=false/);

  assertMutation("inspection-over-window-shrunk-to-one", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-inspection-cap-sixty-four-kib");
    testCase.expected.error.compressedBytes = 1;
    testCase.expected.error.decompressedBytes = 1;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:65537", "response-cancelled:1"));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(/:(?:65537|65536):(cancelled|accepted)$/u, ":1:$1"));
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=65537 decompressed=65537 truncated=true/);

  assertMutation("inspection-exact-limit-reclassified-truncated", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-inspection-cap-sixty-four-kib");
    const count = document.constants.errorInspectionLimitBytes;
    testCase.script.find((action) => action.action === "respond").body.totalLength = count;
    testCase.expected.error.compressedBytes = count;
    testCase.expected.error.decompressedBytes = count;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:65537", `response-cancelled:${count}`));
    testCase.expected.byteTrace = testCase.expected.byteTrace.map((trace) => trace.replace(":65537:cancelled", `:${count}:cancelled`));
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=65536 decompressed=65536 truncated=false/);

  assertMutation("response-identity-cap-reclassified-protocol", (document) => {
    reclassifyResponseTooLargeAsProtocol(findCase(document, (candidate) => candidate.caseId === "response-identity-cap-plus-one-cancels"), document.errorCatalog);
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  for (const caseId of [
    "response-compressed-cap-plus-one-cancels",
    "response-decompressed-cap-plus-one-cancels",
    "response-expansion-ratio-plus-one-rejected",
  ]) {
    assertMutation(`${caseId}-reclassified-too-large`, (document) => {
      reclassifyResponseProtocolAsTooLarge(findCase(document, (candidate) => candidate.caseId === caseId), document.errorCatalog);
    }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);
  }

  assertMutation("unsupported-encoding-made-valid-identity", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-unsupported-content-encoding");
    testCase.script.find((action) => action.action === "respond").headers["content-encoding"] = "identity";
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:0", "response-bytes:104"));
    testCase.expected.byteTrace = ["response-wire:104:accepted", "response-decoded:104:accepted"];
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("corrupt-gzip-made-valid", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-corrupt-gzip-trailer");
    testCase.script.find((action) => action.action === "respond").body.asset = "validGzip";
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:108", "response-bytes:108"));
    testCase.expected.byteTrace = ["response-wire:108:accepted", "response-decoded:104:accepted"];
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("incomplete-retry-made-valid-complete", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-incomplete-two-hundred-retried");
    const firstResponse = testCase.script.find((action) => action.action === "respondChunks" && action.attempt === 1);
    firstResponse.chunks = [{ asset: "validIdentity" }];
    firstResponse.complete = true;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("attempt:1:response-bytes:6", "attempt:1:response-bytes:104"));
    testCase.expected.byteTrace[0] = "response-wire:104:accepted";
    testCase.expected.byteTrace[1] = "response-decoded:104:accepted";
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("valid-identity-made-malformed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.script.find((action) => action.action === "respond").body.asset = "malformed";
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-bytes:104", "response-bytes:6"));
    testCase.expected.byteTrace = ["response-wire:6:accepted", "response-decoded:6:accepted"];
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("valid-gzip-made-corrupt", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-gzip");
    testCase.script.find((action) => action.action === "respond").body.asset = "corruptGzipTrailer";
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-bytes:108", "response-cancelled:108"));
    testCase.expected.byteTrace = ["response-wire:108:accepted", "response-decoded:0:rejected"];
    testCase.expected.sideEffectTrace.splice(-1, 0, "response.cancel");
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("retryable-protocol-terminal-missing-retry", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-corrupt-gzip-trailer");
    makeCorruptGzipRetryableMalformed(testCase);
  }, /expected\.attemptTrace: retryable response attempt 1 below maxAttempts 4 requires a scheduled next attempt/);

  assertMutation("exhausted-retry-budget-reopened", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-malformed-two-hundred-exhausted");
    testCase.options.maxAttempts = 5;
  }, /expected\.attemptTrace: retryable response attempt 2 below maxAttempts 5 requires a scheduled next attempt/);

  assertMutation("composed-gzip-members-reclassified-retryable", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-concatenated-gzip-rejected");
    const response = testCase.script.find((action) => action.action === "respond");
    response.action = "respondChunks";
    delete response.body;
    response.chunks = [{ asset: "validGzip" }, { asset: "validGzip" }];
    testCase.expected.error.decompressedBytes = 208;
    testCase.expected.error.retryable = true;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:216", "response-bytes:216"));
    testCase.expected.byteTrace = ["response-wire:216:accepted", "response-decoded:208:accepted"];
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
  }, /expected\.fixtureTrace: attempt 1 response progress must be cancelled:216 from the input-derived response classifier/);

  assertMutation("composed-gzip-collapsed-to-valid-single", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-concatenated-gzip-rejected");
    const response = testCase.script.find((action) => action.action === "respond");
    response.action = "respondChunks";
    delete response.body;
    response.chunks = [{ asset: "validGzip" }];
    testCase.expected.error.compressedBytes = 108;
    testCase.expected.error.decompressedBytes = 104;
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace.replace("response-cancelled:216", "response-bytes:108"));
    testCase.expected.byteTrace = ["response-wire:108:accepted", "response-decoded:104:accepted"];
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "response.cancel");
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("fabricated-retry-deadline-stop", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-corrupt-gzip-trailer");
    makeCorruptGzipRetryableMalformed(testCase);
    testCase.expected.scheduleTrace = [
      "retry:1:cap=250@0",
      "retry:1:bound=251@0",
      "retry:1:entropy=125@0",
      "retry:1:deadline=0@0",
    ];
  }, /expected\.scheduleTrace: retry 1 deadline fact must match the input clock, operation timeout, attempt elapsed time, and scripted advanceTime/);

  assertMutation("fabricated-retry-cancelled-stop", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-corrupt-gzip-trailer");
    makeCorruptGzipRetryableMalformed(testCase);
    testCase.expected.scheduleTrace = [
      "retry:1:cap=250@0",
      "retry:1:bound=251@0",
      "retry:1:entropy=125@0",
      "retry:1:cancelled=0@0",
    ];
  }, /expected\.scheduleTrace: retry 1 cancelled fact requires a scripted backoff cancelSend or closeClient action/);

  assertMutation("legitimate-deadline-loses-clock-advance", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-beyond-operation-budget");
    testCase.script = testCase.script.filter((action) => action.action !== "advanceTime");
  }, /expected\.scheduleTrace: retry 1 deadline fact must match the input clock, operation timeout, attempt elapsed time, and scripted advanceTime/);

  assertMutation("legitimate-backoff-cancel-changes-phase", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "cancellation-during-backoff");
    testCase.script.find((action) => action.action === "cancelSend").phase = "responseBody";
  }, /expected\.scheduleTrace: retry 1 cancelled fact requires a scripted backoff cancelSend or closeClient action/);

  assertMutation("retry-after-bogus-with-coordinated-jitter-but-stale-clock", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-delta-seconds");
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = "bogus";
    testCase.input.entropy = [124];
    testCase.expected.scheduleTrace = testCase.expected.scheduleTrace.map((trace) => trace
      .replace("entropy=125", "entropy=124")
      .replace("retry-after=5000", "retry-after=invalid")
      .replace("delay=5000", "delay=124"));
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace.replace("delayMs=5000", "delayMs=124"));
  }, /script: retry 1 must advance monotonic time by exact effective delay 124, got 5000/);

  assertMutation("retry-after-bogus-header-retains-valid-facts", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-delta-seconds");
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = "bogus";
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-http-date-uses-injected-wall-clock", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-http-date");
    testCase.input.clock = { retryAfterWallUnixMs: 1767225601000 };
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-duplicate-identical-becomes-conflicting", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-duplicate-identical");
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = ["5", "6"];
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-past-date-cannot-be-treated-as-zero", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-past-date");
    rewriteRetryAfterOutcome(testCase, "0", 125);
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-present-date-cannot-collapse-into-past-date", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-present-date");
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = "Wed, 31 Dec 2025 23:59:59 GMT";
  }, /retry-after-present-date: must use one IMF-fixdate exactly equal to the injected retryAfterWallUnixMs/);

  assertMutation("retry-after-overflow-cannot-saturate-to-cap", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-overflow");
    rewriteRetryAfterOutcome(testCase, "60000", 60000);
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-over-cap-remains-valid-and-capped", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-over-cap");
    rewriteRetryAfterOutcome(testCase, "invalid", 125);
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("retry-after-date-arithmetic-overflow-cannot-be-treated-as-zero", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-past-date");
    testCase.script.unshift({ actor: "runner", action: "advanceWallTime", byMs: Number.MAX_SAFE_INTEGER });
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = "Mon, 01 Jan 0100 00:00:00 GMT";
    rewriteRetryAfterOutcome(testCase, "0", 125);
  }, /expected\.scheduleTrace: must exactly equal input\/script-derived retry scheduling/);

  assertMutation("operation-deadline-advance-one-millisecond-short", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "deadline-attempt-budget-clamped-exactly");
    testCase.script.find((action) => action.action === "advanceTime").byMs = 12344;
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("connect-timeout-advance-one-millisecond-short", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "defaults-connect-timeout-ten-seconds");
    testCase.script.find((action) => action.action === "advanceTime").byMs = 9999;
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("attempt-timeout-advance-one-millisecond-short", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "defaults-attempt-timeout-thirty-seconds");
    testCase.script.find((action) => action.action === "advanceTime").byMs = 29999;
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("connection-retry-exceeds-max-attempts", (document) => {
    findCase(document, (candidate) => candidate.caseId === "retry-transient-dns-then-success").options.maxAttempts = 1;
  }, /script: starts 2 attempts but maxAttempts permits only 1/);

  assertMutation("terminal-retryable-dns-below-budget-needs-retry", (document) => {
    findCase(document, (candidate) => candidate.caseId === "failure-mapping-dns-before-commitment").options.maxAttempts = 2;
  }, /script: retry 1 requires attempt 2 after its input-derived delay/);

  assertMutation("after-request-failure-retains-commit-after-phase-rewrite", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-io-after-commitment");
    testCase.script.find((action) => action.action === "failConnection").phase = "beforeRequestBytes";
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("before-request-failure-misses-commit-after-phase-rewrite", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-io-before-commitment");
    testCase.script.find((action) => action.action === "failConnection").phase = "afterRequestBytes";
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("immediate-success-invents-monotonic-999", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.attemptTrace = testCase.expected.attemptTrace.map((trace) => trace.replace("@0", "@999"));
    testCase.expected.commitTrace = testCase.expected.commitTrace.map((trace) => trace.replace("@0", "@999"));
  }, /expected\.attemptTrace: must exactly equal the input\/script-derived attempt timeline/);

  assertMutation("parsed-result-id-fabricated", (document) => {
    findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity").expected.result.id = "msg_fabricated";
  }, /expected\.result: must exactly equal the parsed response SendResult/);

  assertMutation("parsed-result-timestamp-fabricated", (document) => {
    findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity").expected.result.timestamp = "2026-01-02T00:00:00.000Z";
  }, /expected\.result: must exactly equal the parsed response SendResult/);

  assertMutation("bounded-error-request-id-fabricated", (document) => {
    findCase(document, (candidate) => candidate.caseId === "error-safety-remote-message-discarded").expected.error.requestId = "req_fabricated";
  }, /expected\.error\.requestId: unknown field/);

  assertMutation("bounded-error-remediation-mismatch", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-dns-before-commitment");
    testCase.expected.error.remediationKey = "network.connect.refused";
    testCase.expected.outcomeEvidence.remediationKey = "network.connect.refused";
  }, /expected\.error\.remediationKey: must exactly match the selected failure reason/);

  assertMutation("dns-terminal-reason-reclassified-connect", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-dns-before-commitment");
    testCase.expected.error.failureReason = "CONNECT_REFUSED";
    testCase.expected.error.remediationKey = "network.connect.refused";
    testCase.expected.outcomeEvidence.failureReason = "CONNECT_REFUSED";
    testCase.expected.outcomeEvidence.remediationKey = "network.connect.refused";
    testCase.expected.observerEmissionTrace = testCase.expected.observerEmissionTrace.map((trace) => trace.replace("failureReason=UNKNOWN", "failureReason=CONNECT_REFUSED"));
    testCase.expected.observerCallbackTrace = testCase.expected.observerCallbackTrace.map((trace) => trace.replace("failureReason=UNKNOWN", "failureReason=CONNECT_REFUSED"));
  }, /expected\.error\.failureReason: must equal input\/script-derived "UNKNOWN"/);

  assertMutation("dns-script-synthetic-cause-coordinated-as-io", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "failure-mapping-dns-before-commitment");
    testCase.script.find((action) => action.action === "failConnection").causeType = "ConformanceIoFailure";
  }, /causeType: must equal ConformanceDnsFailure for failure kind dns/);

  assertMutation("attempt-timeout-cause-reclassified-custom", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "defaults-attempt-timeout-thirty-seconds");
    testCase.expected.error.causeCategory = "CUSTOM_TRANSPORT";
    testCase.expected.outcomeEvidence.causeCategory = "CUSTOM_TRANSPORT";
  }, /expected\.error\.causeCategory: must equal input\/script-derived "HTTP_RUNTIME"/);

  assertMutation("provider-cause-reclassified-io", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-provider-exception-sanitized");
    testCase.input.providerValues[0].$providerError.causeType = "ConformanceIoFailure";
    testCase.expected.error.causeCategory = "HTTP_RUNTIME";
    testCase.expected.outcomeEvidence.causeCategory = "HTTP_RUNTIME";
  }, /input\.providerValues: provider exceptions must use the fixed ConformanceProviderFailure/);

  assertMutation("request-limit-inclusive-case-becomes-plus-one-but-still-sends", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-request-cap-inclusive");
    testCase.input.send.payload.$bytes.serializedRequestBytes += 1;
    testCase.expected.byteTrace[0] = `request:${document.constants.requestLimitBytes + 1}:rejected`;
  }, /script: a request larger than the inclusive request limit must fail before every attempt/);

  assertMutation("request-limit-plus-one-becomes-inclusive-but-still-rejects", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-request-cap-plus-one-rejected");
    testCase.input.send.payload.$bytes.serializedRequestBytes -= 1;
    testCase.expected.byteTrace[0] = `request:${document.constants.requestLimitBytes}:accepted`;
  }, /expected\.error: a request at or below the inclusive request limit cannot be REQUEST_TOO_LARGE/);

  assertMutation("inspection-limit-option-is-effective", (document) => {
    findCase(document, (candidate) => candidate.caseId === "error-safety-remote-message-discarded").options.errorInspectionLimitBytes = 32;
  }, /expected\.fixtureTrace: attempt 1 response progress must be cancelled:33 from the input-derived response classifier/);

  assertMutation("inspection-limit-exact-boundary-cannot-claim-truncation", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-remote-message-discarded");
    testCase.options.errorInspectionLimitBytes = 90;
    testCase.expected.error.truncated = true;
    testCase.expected.legacyBody.truncated = true;
  }, /expected\.error: response counters\/truncation must match input-derived classifier compressed=90 decompressed=90 truncated=false/);

  assertMutation("lifecycle-missing-permit-release", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity");
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.filter((trace) => trace !== "permit.release");
  }, /expected\.sideEffectTrace: must exactly equal the input\/script-derived lifecycle/);

  assertMutation("credential-precedence-trace-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-explicit-api-key-wins-environment");
    testCase.expected.configurationTrace[0] = "credential:REPOST_SEND_API_KEY";
  }, /expected\.configurationTrace: must exactly equal input-derived configuration precedence/);

  assertMutation("base-url-precedence-trace-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-explicit-base-url-wins-environment");
    testCase.expected.configurationTrace[1] = "base-url:REPOST_API_URL";
  }, /expected\.configurationTrace: must exactly equal input-derived configuration precedence/);

  assertMutation("terminal-caller-idempotency-key-fabricated", (document) => {
    findCase(document, (candidate) => candidate.caseId === "failure-mapping-dns-before-commitment").expected.error.idempotencyKey = "idem_wrong";
  }, /expected\.error\.idempotencyKey: must equal the operation key/);

  assertMutation("terminal-generated-idempotency-key-fabricated", (document) => {
    findCase(document, (candidate) => candidate.caseId === "retry-validation-failure-not-attempted").expected.error.idempotencyKey = "idem_wrong";
  }, /expected\.error\.idempotencyKey: must equal the operation key/);

  assertMutation("pre-key-configuration-error-invents-idempotency-key", (document) => {
    findCase(document, (candidate) => candidate.caseId === "configuration-missing-credential").expected.error.idempotencyKey = "idem_wrong";
  }, /expected\.error\.idempotencyKey: must equal the operation key at the terminal stage \(null\)/);

  assertMutation("canonical-manifest-entry-removed", (document) => {
    document.caseManifest.pop();
  }, /caseManifest: must equal the validator-owned canonical 415-vector manifest/);

  assertMutation("canonical-manifest-and-case-renamed-together", (document) => {
    const index = document.caseManifest.indexOf("response-valid-complete-identity");
    document.caseManifest[index] = "response-valid-complete-identity-renamed";
    findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity").caseId = "response-valid-complete-identity-renamed";
  }, /caseManifest: must equal the validator-owned canonical 415-vector manifest/);

  assertMutation("network-attempt-count-rewritten", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-transient-dns-then-success");
    testCase.expected.sideEffectTrace = testCase.expected.sideEffectTrace.map((trace) => trace === "network:2" ? "network:1" : trace);
  }, /expected\.sideEffectTrace: must exactly equal the input\/script-derived lifecycle/);

  assertMutation("hollow-successful-response-headers-delay", (document) => {
    for (const testCase of document.cases.filter((candidate) => candidate.expected.delivery === "ACCEPTED")) {
      const barriers = new Set(testCase.script
        .filter((action) => action.action === "hold" && action.phase === "responseHeaders")
        .map((action) => action.barrier));
      testCase.script = testCase.script.filter((action) => !(action.action === "hold" && action.phase === "responseHeaders")
        && !(action.action === "release" && barriers.has(action.barrier)));
    }
  }, /coverage\.released-response-headers: requires a successful responseHeaders hold causally matched by a later runner release/);

  assertMutation("hollow-successful-response-body-delay", (document) => {
    for (const testCase of document.cases.filter((candidate) => candidate.expected.delivery === "ACCEPTED")) {
      const barriers = new Set(testCase.script
        .filter((action) => action.action === "hold" && action.phase === "responseBody")
        .map((action) => action.barrier));
      testCase.script = testCase.script.filter((action) => !(action.action === "hold" && action.phase === "responseBody")
        && !(action.action === "release" && barriers.has(action.barrier)));
    }
  }, /coverage\.released-response-body: requires a successful byte-threshold responseBody hold causally matched by a later runner release/);

  assertMutation("hollow-private-capture-vector", (document) => {
    const testCase = findCase(document, (candidate) => candidate.script.some((action) => action.action === "captureRequestMetadata"));
    testCase.script = testCase.script.filter((action) => action.action !== "captureRequestMetadata");
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.filter((trace) => !trace.endsWith(":capture-private:clean"));
  }, /coverage\.private-capture: requires captureRequestMetadata with capture-private:clean/);

  assertMutation("coverage-removes-wall-clock-action", (document) => {
    for (const testCase of document.cases) testCase.script = testCase.script.filter((action) => action.action !== "advanceWallTime");
  }, /coverage\.action-advanceWallTime: requires fixture\/runner action coverage for advanceWallTime/);

  assertMutation("coverage-removes-accept-request-action", (document) => {
    for (const testCase of document.cases) testCase.script = testCase.script.filter((action) => action.action !== "acceptRequest");
  }, /coverage\.action-acceptRequest: requires fixture\/runner action coverage for acceptRequest/);

  assertMutation("coverage-removes-observer-failure-action", (document) => {
    for (const testCase of document.cases) testCase.script = testCase.script.filter((action) => action.action !== "observerFail");
  }, /coverage\.action-observerFail: requires fixture\/runner action coverage for observerFail/);

  assertMutation("coverage-removes-all-retry-idempotency-evidence", (document) => {
    for (const testCase of document.cases) {
      if (testCase.expected.attemptTrace.filter((trace) => trace.includes(":start@")).length >= 2) {
        testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.filter((trace) => !/:idempotency:/u.test(trace));
      }
    }
  }, /coverage\.idempotency-reuse: requires one caller\/generated idempotency key captured byte-exact across every retry attempt/);

  assertMutation("option-classification-missing-option", (document) => {
    document.fixtureProtocol.optionClassification.publicClientConfig = document.fixtureProtocol.optionClassification.publicClientConfig.filter((field) => field !== "apiKey");
  }, /optionClassification\.publicClientConfig: must equal the closed publicClientConfig option list/);

  assertMutation("option-classification-duplicate-option", (document) => {
    document.fixtureProtocol.optionClassification.publicSendConfig.push("apiKey");
  }, /optionClassification\.publicSendConfig: must equal the closed publicSendConfig option list/);

  assertMutation("option-classification-misclassifies-harness-control", (document) => {
    document.fixtureProtocol.optionClassification.harnessOnlyOverrides = document.fixtureProtocol.optionClassification.harnessOnlyOverrides.filter((field) => field !== "requestLimitBytes");
    document.fixtureProtocol.optionClassification.publicClientConfig.push("requestLimitBytes");
  }, /optionClassification\.(?:publicClientConfig|harnessOnlyOverrides): must equal the closed/);

  assertMutation("observer-context-input-removed", (document) => {
    delete findCase(document, (candidate) => candidate.caseId === "observer-low-cardinality-order-on-retry").input.observerContext;
  }, /expected\.contextTrace: requires input\.observerContext/);

  assertMutation("observer-context-leaks-into-public-error", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "observer-low-cardinality-order-on-retry");
    testCase.expected.error.message = testCase.input.observerContext;
  }, /public surface contains the private observer context token/);

  assertMutation("retry-after-negative-collapsed-to-generic-invalid", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "retry-after-negative");
    testCase.script.find((action) => action.action === "respond").headers["retry-after"] = "not-a-date";
  }, /coverage\.retry-after-negative-numeric: requires the distinct Retry-After negative-numeric semantics/);

  assertMutation("content-encoding-first-value-escape", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "response-content-encoding-multi-value-conflicting-rejected");
    testCase.script.find((action) => action.action === "respond").headers["Content-Encoding"] = ["gzip"];
  }, /expected\.attemptTrace: attempt 1 terminal outcome must match its fixture\/runner action semantics/);

  assertMutation("content-encoding-loses-all-accepted-mixed-case-fields", (document) => {
    for (const testCase of document.cases) {
      if (testCase.expected.delivery !== "ACCEPTED") continue;
      for (const action of testCase.script) {
        for (const name of Object.keys(action.headers || {})) {
          if (name.toLowerCase() === "content-encoding" && name !== "content-encoding") {
            action.headers["content-encoding"] = action.headers[name];
            delete action.headers[name];
          }
        }
      }
    }
  }, /coverage\.production-http-mixed-case-gzip: requires mixed-case-gzip coverage through the real default production HTTP transport/);

  assertMutation("redirect-refusal-target-was-hit", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-redirect-refused");
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.map((trace) => trace === "fixture:redirect-target-hits:0" ? "fixture:redirect-target-hits:1" : trace);
  }, /configuration-redirect-refused: must execute the default production HTTP transport.*observe zero target hits/);

  assertMutation("redirect-refusal-uses-external-target", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-redirect-refused");
    testCase.script.find((action) => action.action === "respond").headers.location = "https://redirect.invalid/elsewhere";
  }, /configuration-redirect-refused: must execute the default production HTTP transport against the relative same-loopback 202 redirect target/);

  for (const [name, caseId, baseUrl] of [
    ["userinfo", "configuration-uri-userinfo-only-rejected", "https://example.invalid/base"],
    ["query", "configuration-uri-query-only-rejected", "https://example.invalid/base"],
    ["fragment", "configuration-uri-fragment-only-rejected", "https://example.invalid/base"],
  ]) {
    assertMutation(`isolated-uri-${name}-vector-loses-component`, (document) => {
      findCase(document, (candidate) => candidate.caseId === caseId).options.baseUrl = baseUrl;
    }, new RegExp(`${caseId}: must isolate and reject the forbidden base URL component at construction`));
  }

  assertMutation("http-fixture-handshake-field-removed", (document) => {
    document.fixtureProtocol.httpFixture.handshakeFields.pop();
  }, /fixtureProtocol\.httpFixture: must equal the executable loopback fixture command, handshake, data, and control protocol/);

  assertMutation("production-http-mode-allows-dial-rewrite", (document) => {
    document.fixtureProtocol.executionModeSemantics["production-http-fixture"] = "rewrite the default host through a custom dialer";
  }, /fixtureProtocol\.executionModeSemantics: must equal the closed execution mode semantics/);

  assertMutation("commitment-probe-mode-allows-runner-state", (document) => {
    document.fixtureProtocol.executionModeSemantics["commitment-probe-seam"] = "A runner-owned boolean claims the request was not sent.";
  }, /fixtureProtocol\.executionModeSemantics: must equal the closed execution mode semantics/);

  assertMutation("commitment-probe-moved-to-http-fixture", (document) => {
    findCase(document, (candidate) => candidate.caseId === "commitment-failure-before-request-body-demand").executionMode = "production-http-fixture";
  }, /commitment-failure-before-request-body-demand: must exercise the unmodified production commitment tracker through the low-level probe seam/);

  assertMutation("commitment-probe-invents-body-demand", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "commitment-failure-before-request-body-demand");
    testCase.expected.fixtureTrace[1] = "fixture:attempt:1:body-subscriptions:1:body-demands:1";
  }, /commitment-failure-before-request-body-demand: must exercise the unmodified production commitment tracker.*prove zero body-publisher subscription\/demand/);

  assertMutation("fixture-evidence-vocabulary-drops-body-probe", (document) => {
    document.traceVocabulary.fixture = document.traceVocabulary.fixture.replace("body-subscriptions:<count>:body-demands:<count>|", "");
  }, /traceVocabulary\.fixture: must equal the closed fixture evidence vocabulary/);

  assertMutation("fixture-base-url-token-becomes-public-config", (document) => {
    document.fixtureProtocol.testTokenSemantics.$fixtureBaseUrl = "accept as a public base URL default";
  }, /testTokenSemantics\.\$fixtureBaseUrl: must pin the private handshake-only substitution semantics/);

  assertMutation("production-http-case-loses-fixture-base-url", (document) => {
    delete findCase(document, (candidate) => candidate.caseId === "response-delayed-headers-release-success").options.baseUrl;
  }, /options\.baseUrl: production-http-fixture requires explicit \$fixtureBaseUrl/);

  assertMutation("custom-transport-case-loses-explicit-mode", (document) => {
    delete findCase(document, (candidate) => candidate.caseId === "configuration-custom-transport-success").executionMode;
  }, /executionMode: transportMode=custom requires explicit custom-one-attempt mode/);

  assertMutation("production-http-sentinel-coverage-hollowed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "error-safety-production-http-sentinel-echo-redacted");
    delete testCase.executionMode;
    delete testCase.options.baseUrl;
    testCase.expected.configurationTrace[1] = "base-url:default";
  }, /coverage\.production-http-sentinel-echo: requires sentinel-echo coverage through the real default production HTTP transport/);

  assertMutation("authorization-assertion-prefix-rewritten", (document) => {
    document.fixtureProtocol.assertionDefinitions["authorization-octets-preserved"].construction = "UTF-8 bytes of ASCII `Token ` concatenated byte-exact with the snapshotted resolved credential";
  }, /assertionDefinitions\.authorization-octets-preserved: must equal the closed private request-assertion definition/);

  assertMutation("authorization-assertion-allows-trimming", (document) => {
    document.fixtureProtocol.assertionDefinitions["authorization-octets-preserved"].comparison = "trim before comparing";
  }, /assertionDefinitions\.authorization-octets-preserved: must equal the closed private request-assertion definition/);

  assertMutation("authorization-assertion-retains-digest", (document) => {
    document.fixtureProtocol.assertionDefinitions["authorization-octets-preserved"].retention = "retain raw header and digest in fixture state";
  }, /assertionDefinitions\.authorization-octets-preserved: must equal the closed private request-assertion definition/);

  assertMutation("authorization-assertion-evidence-removed", (document) => {
    const testCase = findCase(document, (candidate) => candidate.caseId === "configuration-nonblank-credential-octets-preserved");
    testCase.expected.fixtureTrace = testCase.expected.fixtureTrace.filter((trace) => !trace.includes("assertion:authorization-octets-preserved"));
  }, /request assertion authorization-octets-preserved requires only the private pass evidence/);

  assertMutation("concurrency-protocol-weakened", (document) => {
    document.concurrencyProtocol.runtimeRule = "A runner-owned counter is sufficient.";
  }, /concurrencyProtocol: must equal the validator-owned production concurrency protocol/);

  assertMutation("concurrency-scenario-removed", (document) => {
    document.concurrencyScenarios.pop();
  }, /concurrencyScenarios: scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory/);

  assertMutation("concurrency-capacity-self-consistently-rewritten", (document) => {
    const scenario = findScenario(document, "concurrency-exact-capacity-and-overflow");
    scenario.runtime.maxInFlight = 3;
    scenario.expected.finalRuntime.maxObservedInFlight = 3;
  }, /concurrencyScenarios: scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory/);

  assertMutation("concurrency-overflow-invents-observer", (document) => {
    const scenario = findScenario(document, "concurrency-exact-capacity-and-overflow");
    scenario.expected.operations.find((operation) => operation.operationId === "op-overflow").sideEffects.observerStarts = 1;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-capacity-probe-skips-network", (document) => {
    const scenario = findScenario(document, "concurrency-exact-capacity-and-overflow");
    scenario.expected.operations.find((operation) => operation.operationId === "op-capacity-probe").sideEffects.networkAttempts = 0;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-retry-reacquires-permit", (document) => {
    const scenario = findScenario(document, "concurrency-retry-holds-one-permit");
    scenario.expected.operations.find((operation) => operation.operationId === "op-retry").sideEffects.permitAcquires = 2;
    scenario.expected.finalRuntime.totalPermitAcquires = 2;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-queued-waiter-invented", (document) => {
    findScenario(document, "concurrency-no-queued-waiter").expected.checkpoints[0].queuedWaiters = 1;
  }, /queuedWaiters: must equal zero; admission and byte budgets never queue waiters/);

  assertMutation("concurrency-duplicate-permit-release", (document) => {
    const scenario = findScenario(document, "concurrency-success-failure-release-once");
    const terminalIndex = scenario.expected.orderedTrace.findIndex((entry) => entry.startsWith("operation:op-success:terminal:"));
    scenario.expected.orderedTrace.splice(terminalIndex, 0, "operation:op-success:permit.release:inFlight=0");
  }, /expected\.orderedTrace: must exactly equal the validator-derived execution trace/);

  assertMutation("concurrency-http-rejection-probe-not-successful", (document) => {
    const scenario = findScenario(document, "concurrency-success-failure-release-once");
    scenario.expected.operations.find((operation) => operation.operationId === "op-http-probe").code = "HTTP_REJECTED";
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-success-probe-skips-network", (document) => {
    const scenario = findScenario(document, "concurrency-success-failure-release-once");
    scenario.expected.operations.find((operation) => operation.operationId === "op-success-probe").sideEffects.networkAttempts = 0;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-cancellation-probe-does-not-acquire", (document) => {
    const scenario = findScenario(document, "concurrency-cancel-releases-once");
    scenario.expected.operations.find((operation) => operation.operationId === "op-cancel-probe").sideEffects.permitAcquires = 0;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-server-failure-release-after-terminal", (document) => {
    const scenario = findScenario(document, "concurrency-server-failure-release-and-probe");
    const releaseIndex = scenario.expected.orderedTrace.findIndex((entry) => entry.startsWith("operation:op-server-failure:permit.release:"));
    const [release] = scenario.expected.orderedTrace.splice(releaseIndex, 1);
    const terminalIndex = scenario.expected.orderedTrace.findIndex((entry) => entry.startsWith("operation:op-server-failure:terminal:"));
    scenario.expected.orderedTrace.splice(terminalIndex + 1, 0, release);
  }, /expected\.orderedTrace: must exactly equal the validator-derived execution trace/);

  assertMutation("concurrency-server-failure-probe-not-successful", (document) => {
    const scenario = findScenario(document, "concurrency-server-failure-release-and-probe");
    scenario.expected.operations.find((operation) => operation.operationId === "op-server-probe").delivery = "REJECTED";
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-protocol-exhaustion-reclassified", (document) => {
    const scenario = findScenario(document, "concurrency-protocol-failure-release-and-probe");
    scenario.expected.operations.find((operation) => operation.operationId === "op-protocol-failure").delivery = "REJECTED";
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-protocol-failure-probe-does-not-acquire", (document) => {
    const scenario = findScenario(document, "concurrency-protocol-failure-release-and-probe");
    scenario.expected.operations.find((operation) => operation.operationId === "op-protocol-probe").sideEffects.permitAcquires = 0;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-after-close-invents-configuration", (document) => {
    const scenario = findScenario(document, "concurrency-reject-after-close");
    scenario.expected.operations[0].sideEffects.configurationSnapshots = 1;
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("concurrency-no-wait-terminal-proof-removed", (document) => {
    const scenario = findScenario(document, "concurrency-no-queued-waiter");
    scenario.steps = scenario.steps.filter((step) => !(step.action === "awaitTerminal" && step.operationId === "op-no-wait"));
  }, /concurrencyScenarios: scenario inputs and synchronization steps must equal the validator-owned production concurrency inventory/);

  assertMutation("concurrency-close-reclassified-as-cancel", (document) => {
    const scenario = findScenario(document, "concurrency-close-drains-and-releases-once");
    scenario.expected.operations[0].code = "CANCELLED";
  }, /expected\.operations: must exactly equal validator-derived operation outcomes and side effects/);

  assertMutation("legacy-occupied-permit-added", (document) => {
    findCase(document, (candidate) => candidate.caseId === "response-valid-complete-identity").options.occupiedPermits = 0;
  }, /occupiedPermits is allowed only in the three pinned legacy single-operation vectors/);

  console.log(`transport validator regression suite: pristine plus ${mutationCount} mutations passed`);
} finally {
  fs.rmSync(temporaryDirectory, { recursive: true, force: true });
}
