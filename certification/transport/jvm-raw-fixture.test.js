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
