"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const contract = require("./jvm-raw-fixture-contract");
const engineContract = require("./jvm-engine-contract");
const overlay = require("./jvm-v2.json");
const runnerPath = path.join(__dirname, "jvm-raw-fixture-runner.js");
const javaSourceDirectory = path.join(__dirname, "jvm-raw-fixture/src/sh/repost/conformance/transport");
const javaSource = fs.readdirSync(javaSourceDirectory)
  .filter((file) => file.endsWith(".java"))
  .sort()
  .map((file) => fs.readFileSync(path.join(javaSourceDirectory, file), "utf8"))
  .join("\n");
const nodeRunnerSource = fs.readFileSync(runnerPath, "utf8");
const runner = require("./jvm-raw-fixture-runner");

function validatorEvidence() {
  const groups = Object.fromEntries(Object.entries(contract.groups).map(([groupId, group]) => [groupId, {
    schema: contract.protocol.evidenceSchema.name,
    protocolVersion: 1,
    groupId,
    fixtureCaseId: group.fixtureCaseId,
    outcome: "SUCCESS",
    skipped: false,
    controls: [...contract.protocol.selfDriverControls],
    barriers: group.requiredBarriers.flatMap((barrier) => ["WAITING", "RELEASED", "RELEASED"].map((status, index) => ({
      schema: contract.protocol.barrierSchema.name,
      protocolVersion: 1,
      groupId,
      barrier,
      status,
      monotonicTimeMs: index === 0 ? 0 : 1,
    }))),
    observedCounters: { ...group.requiredObservedCounters },
    state: "CLOSED",
    endpointIds: [...group.requiredEndpointIds],
    assetIds: [...group.requiredAssetIds],
    capabilities: [...group.requiredCapabilities],
    terminalAssertions: [...group.terminalAssertions],
  }]));
  return { schema: contract.protocol.evidenceSchema.name, protocolVersion: 1, summary: { total: 33, success: 33, skipped: 0, undeclared: 0 }, groups };
}

function verifyEvidence(file) {
  return spawnSync(process.execPath, [path.join(__dirname, "validate.js"), "--verify-jvm-raw-evidence", file], { encoding: "utf8", timeout: 30_000 });
}

test("the raw protocol is an exact 33-group fixture/production-consumer bijection", () => {
  const groupIds = Object.keys(contract.groups);
  assert.deepEqual(groupIds, engineContract.requiredRawFixtureGroups);
  assert.equal(groupIds.length, 33);
  assert.equal(new Set(Object.values(contract.groups).map((group) => group.fixtureCaseId)).size, 33);
  const qualifiedBarriers = [];
  const qualifiedCounters = [];
  for (const [groupId, group] of Object.entries(contract.groups)) {
    assert.equal(group.fixtureCaseId, `jvm-raw-${groupId}`);
    assert.ok(group.productionConsumerCaseIds.length > 0);
    assert.ok(group.requiredBarriers.length >= 1);
    assert.equal(new Set(group.requiredBarriers).size, group.requiredBarriers.length);
    assert.ok(Object.keys(group.requiredObservedCounters).length >= 2);
    qualifiedBarriers.push(...group.requiredBarriers.map((barrier) => `${groupId}:${barrier}`));
    qualifiedCounters.push(...Object.keys(group.requiredObservedCounters).map((counter) => `${groupId}:${counter}`));
    for (const caseId of group.productionConsumerCaseIds) {
      const consumer = overlay.cases.find((candidate) => candidate.caseId === caseId);
      assert.ok(consumer, `${groupId} production consumer ${caseId}`);
      assert.equal(consumer.expected.terminalKind, "EXECUTABLE_PROOF");
      assert.ok(consumer.input.rawFixtureGroups.includes(groupId));
    }
  }
  assert.equal(new Set(qualifiedBarriers).size, qualifiedBarriers.length);
  assert.equal(new Set(qualifiedCounters).size, qualifiedCounters.length);
});

test("the raw evidence validator rejects tampering, omission, skip, and unsafe retention", (t) => {
  const root = path.resolve(__dirname, "../../../../.superpowers/prototypes/2026-07-13-jvm-sdk-task-2-evidence");
  fs.mkdirSync(root, { recursive: true });
  const directory = fs.mkdtempSync(path.join(root, "raw-evidence-validator-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const pristine = validatorEvidence();
  const pristineFile = path.join(directory, "pristine.json");
  fs.writeFileSync(pristineFile, `${JSON.stringify(pristine)}\n`);
  const valid = verifyEvidence(pristineFile);
  assert.equal(valid.status, 0, `${valid.stdout}${valid.stderr}`);
  const firstGroupId = engineContract.requiredRawFixtureGroups[0];
  const barrierGroupId = "h2-rst-refused-cancel-protocol-error-pre-postcommit-retry-exhaustion";
  const descriptorGroupId = "happy-eyeballs-dual-stack-success-failure-cancel-proxy-and-no-leak";
  const mutations = {
    "barrier-omitted-non-first"(document) { document.groups[barrierGroupId].barriers.splice(3, 1); },
    "barrier-reordered-non-first"(document) { [document.groups[barrierGroupId].barriers[3], document.groups[barrierGroupId].barriers[4]] = [document.groups[barrierGroupId].barriers[4], document.groups[barrierGroupId].barriers[3]]; },
    "barrier-name-non-first"(document) { document.groups[barrierGroupId].barriers[3].barrier = "forged-barrier"; },
    "barrier-group-non-first"(document) { document.groups[barrierGroupId].barriers[3].groupId = firstGroupId; },
    "barrier-status-non-first"(document) { document.groups[barrierGroupId].barriers[4].status = "WAITING"; },
    "barrier-time-non-first"(document) { document.groups[barrierGroupId].barriers[4].monotonicTimeMs = 2; },
    "counter-omitted-non-first"(document) { delete document.groups[barrierGroupId].observedCounters[Object.keys(document.groups[barrierGroupId].observedCounters)[1]]; },
    "counter-added"(document) { document.groups[barrierGroupId].observedCounters.forgedCounterObserved = 1; },
    "counter-reordered-non-first"(document) {
      const entries = Object.entries(document.groups[barrierGroupId].observedCounters);
      document.groups[barrierGroupId].observedCounters = Object.fromEntries([entries[1], entries[0], ...entries.slice(2)]);
    },
    "counter-value-non-first"(document) { document.groups[barrierGroupId].observedCounters[Object.keys(document.groups[barrierGroupId].observedCounters)[1]] += 1; },
    "capability-omitted"(document) { document.groups[barrierGroupId].capabilities.splice(1, 1); },
    "capability-added"(document) { document.groups[barrierGroupId].capabilities.push("forged-capability"); },
    "capability-reordered"(document) { document.groups[barrierGroupId].capabilities.reverse(); },
    "endpoint-omitted"(document) { document.groups[descriptorGroupId].endpointIds.splice(1, 1); },
    "endpoint-added"(document) { document.groups[descriptorGroupId].endpointIds.push("forged-endpoint"); },
    "endpoint-reordered"(document) { document.groups[descriptorGroupId].endpointIds.reverse(); },
    "endpoint-value"(document) { document.groups[descriptorGroupId].endpointIds[1] = "forged-endpoint"; },
    "asset-omitted"(document) { document.groups[descriptorGroupId].assetIds.splice(1, 1); },
    "asset-added"(document) { document.groups[descriptorGroupId].assetIds.push("forged-asset"); },
    "asset-reordered"(document) { document.groups[descriptorGroupId].assetIds.reverse(); },
    "asset-value"(document) { document.groups[descriptorGroupId].assetIds[1] = "forged-asset"; },
    skipped(document) { document.groups[firstGroupId].skipped = true; },
    omitted(document) { delete document.groups[firstGroupId]; },
    unknown(document) { document.groups[firstGroupId].unexpected = true; },
    unsafe(document) { document.groups[firstGroupId].endpointIds = ["http://127.0.0.1:4321/secret"]; },
  };
  for (const [name, mutate] of Object.entries(mutations)) {
    const document = structuredClone(pristine);
    mutate(document);
    const file = path.join(directory, `${name}.json`);
    fs.writeFileSync(file, `${JSON.stringify(document)}\n`);
    const result = verifyEvidence(file);
    assert.notEqual(result.status, 0, `${name} mutation unexpectedly passed`);
  }
  const boundedInputs = {
    bytes: JSON.stringify({ padding: "x".repeat(1_048_576) }),
    depth: `${"[".repeat(17)}0${"]".repeat(17)}`,
    tokens: `[${Array.from({ length: 50_000 }, () => "0").join(",")}]`,
  };
  const diagnostics = {
    bytes: /maximum byte size 1048576/u,
    depth: /maximum depth 16/u,
    tokens: /maximum token count 50000/u,
  };
  for (const [name, contents] of Object.entries(boundedInputs)) {
    const file = path.join(directory, `bounded-${name}.json`);
    fs.writeFileSync(file, contents);
    const result = verifyEvidence(file);
    assert.notEqual(result.status, 0, `${name} bound unexpectedly passed`);
    assert.match(result.stderr, diagnostics[name]);
  }
  const exactBytePrefix = '{"padding":"';
  const exactByteSuffix = '"}';
  const exactBoundInputs = {
    bytes: `${exactBytePrefix}${"x".repeat(1_048_576 - exactBytePrefix.length - exactByteSuffix.length)}${exactByteSuffix}`,
    depth: `${"[".repeat(16)}0${"]".repeat(16)}`,
    tokens: `[${Array.from({ length: 49_999 }, () => "0").join(",")}]`,
  };
  assert.equal(Buffer.byteLength(exactBoundInputs.bytes), 1_048_576);
  for (const [name, contents] of Object.entries(exactBoundInputs)) {
    const file = path.join(directory, `exact-bound-${name}.json`);
    fs.writeFileSync(file, contents);
    const result = verifyEvidence(file);
    assert.notEqual(result.status, 0, `${name} exact-bound schema unexpectedly passed`);
    assert.doesNotMatch(result.stderr, diagnostics[name]);
  }
});

test("the Java raw fixture advertises the frozen reusable scenario descriptors", () => {
  for (const token of [
    "scenario", "endpoints", "assets", "connectHost", "authorityHost", "alpnProtocols",
    "ORIGIN", "PROXY", "ALTERNATE", "CA_CERTIFICATE", "KEYSTORE",
  ]) assert.match(javaSource, new RegExp(`\\b${token}\\b`), `missing live scenario token ${token}`);
});

test("the internal H1 canary consumes its descriptor and proves the real two-phase peer path", () => {
  const result = spawnSync(process.execPath, [runnerPath, "--internal-canary"], {
    encoding: "utf8",
    timeout: 30_000,
  });
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
  const evidence = JSON.parse(result.stdout);
  assert.deepEqual(evidence, {
    schema: "jvm-raw-fixture-foundation-canary-v1",
    groupId: "__internal-h1-canary",
    endpointIds: ["canary-origin"],
    assetIds: [],
    capabilities: ["http/1.1", "two-phase-barrier"],
    observedCounters: { h1CanaryRequestsObserved: 1 },
    checks: {
      advertisedEndpointConsumed: true,
      releaseBeforeArrivalRejected: true,
      earlyReleaseWithoutEventRejected: true,
      peerEventRequired: true,
      loadBeforeResetRejected: true,
      strictControlParser: true,
      closeBeforeAcceptTerminated: true,
      acceptCloseRaceTerminated: true,
      closeTerminatedResources: true,
    },
  });
  assert.doesNotMatch(result.stdout, /127\.0\.0\.1|\/canary|"port"/u);
  assert.doesNotMatch(result.stdout, /"outcome"\s*:\s*"SUCCESS"/u);
});

test("the Node protocol reader rejects duplicate keys, invalid scalars, and malformed UTF-8", () => {
  assert.throws(() => runner.parseJson('{"a":1,"a":2}', "duplicate"), /duplicate object key/u);
  assert.throws(() => runner.parseJson('{"a":"\\ud800"}', "scalar"), /invalid Unicode scalar/u);
  assert.throws(() => runner.parseJsonBytes(Buffer.from([0xc3, 0x28]), "utf8"), /invalid UTF-8/u);
});

test("suppressing the peer-produced request event makes the canary fail", () => {
  const result = spawnSync(process.execPath, [runnerPath, "--internal-canary"], {
    encoding: "utf8",
    timeout: 30_000,
    env: { ...process.env, REPOST_RAW_FIXTURE_TEST_SUPPRESS_EVENT: "h1CanaryRequestsObserved" },
  });
  assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
  assert.equal(result.stdout, "");
  assert.match(result.stderr, /observations did not originate from the H1 peer/u);
});

test("every production raw group remains explicitly unsupported and exits quickly", () => {
  const groupId = engineContract.requiredRawFixtureGroups[0];
  const startedAt = Date.now();
  const result = spawnSync(process.execPath, [runnerPath, "--group", groupId], {
    encoding: "utf8",
    timeout: 5_000,
  });
  const elapsedMs = Date.now() - startedAt;
  assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
  assert.equal(result.stdout, "");
  assert.equal(
    result.stderr.trim(),
    `UNSUPPORTED JVM raw fixture production group: ${groupId}; foundation implements only __internal-h1-canary`,
  );
  assert.ok(elapsedMs < 2_000, `unsupported group took ${elapsedMs}ms`);
});

test("the Java raw fixture contains no declaration-derived toy mechanism shortcuts", () => {
  const forbidden = [
    ["single-frame H2 echo", /roundTrip\(h2Frame/],
    ["connection-count Happy Eyeballs", /countTcpConnections\(/],
    ["queue-only FIFO", /fifoTransfers\(/],
    ["localhost-only DNS counter", /dnsCalls\("localhost"/],
    ["single Supplier credential counter", /providerCalls\(/],
    ["array round-trip response budget", /roundTrip\(new byte\[1_048_577\]\)/],
    ["ByteBuffer.clear reclaim", /reclaimedBuffers\(/],
    ["empty URLClassLoader redeploy", /closedIsolatedClassLoaders\(/],
    ["deadline arithmetic close proof", /monotonicCloseBudget\(/],
    ["local POJO span proof", /authoritativeSpanGraphs\(/],
    ["locally fabricated traceparent", /validTraceparentHeaders\(/],
  ];
  const detected = forbidden.filter(([, pattern]) => pattern.test(javaSource)).map(([name]) => name);
  assert.deepEqual(detected, [], `replace non-adversarial shortcuts with advertised raw peers/providers: ${detected.join(", ")}`);
  assert.doesNotMatch(javaSource, /groupId\.matches|Pattern\.compile|\.contains\("h2"\)|\.contains\("proxy"\)/u);
  assert.doesNotMatch(nodeRunnerSource, /peerPort\s*=\s*controlPort|controlPort\s*[+-]\s*1/u);
});
