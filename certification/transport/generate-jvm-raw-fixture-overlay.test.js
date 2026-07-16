"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const contract = require("./jvm-raw-fixture-contract");
const generator = require("./generate-jvm-raw-fixture-overlay");
const overlayPath = path.join(__dirname, "jvm-v2.json");
const scriptPath = path.join(__dirname, "generate-jvm-raw-fixture-overlay.js");

test("the checked-in JVM raw fixture overlay is the deterministic generated projection", () => {
  const actual = fs.readFileSync(overlayPath, "utf8");
  assert.equal(generator.expectedBytes(), actual);
  const result = spawnSync(process.execPath, [scriptPath, "--check"], { encoding: "utf8", timeout: 30_000 });
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
  assert.equal(result.stdout, "jvm raw fixture overlay current\n");
  assert.equal(result.stderr, "");
});

test("rendering replaces every raw-contract-derived overlay field", () => {
  const input = JSON.parse(fs.readFileSync(overlayPath, "utf8"));
  const expectedCases = structuredClone(input.cases);
  input.jvmRawFixtureProtocol.groups[Object.keys(input.jvmRawFixtureProtocol.groups)[0]].requiredBarriers = ["stale"];
  const mappedIndex = input.cases.findIndex((testCase) => testCase.input.rawFixtureGroups);
  input.cases[mappedIndex].input.rawFixtureGroups = ["stale-group"];
  input.cases[mappedIndex].expected.terminalKind = "CONSTRUCTION_PROOF";
  const rendered = JSON.parse(generator.renderOverlay(input));
  assert.deepEqual(rendered.jvmRawFixtureProtocol, { ...contract.protocol, fixtureCases: contract.fixtureCases() });
  assert.deepEqual(rendered.cases, expectedCases);
});
