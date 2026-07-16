#!/usr/bin/env node

"use strict";

const fs = require("node:fs");
const path = require("node:path");

const contract = require("./jvm-raw-fixture-contract");
const overlayPath = path.join(__dirname, "jvm-v2.json");

function renderOverlay(document) {
  const generated = structuredClone(document);
  generated.jvmRawFixtureProtocol = {
    ...contract.protocol,
    fixtureCases: contract.fixtureCases(),
  };
  generated.cases = contract.attachProductionGroups(generated.cases);
  return `${JSON.stringify(generated, null, 2)}\n`;
}

function expectedBytes(file = overlayPath) {
  return renderOverlay(JSON.parse(fs.readFileSync(file, "utf8")));
}

function check(file = overlayPath) {
  const actual = fs.readFileSync(file, "utf8");
  const expected = expectedBytes(file);
  if (actual !== expected) throw new Error("jvm-v2.json raw fixture protocol is stale; run generate-jvm-raw-fixture-overlay.js --write");
}

function write(file = overlayPath) {
  const expected = expectedBytes(file);
  const temporary = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(temporary, expected, { encoding: "utf8", flag: "wx", mode: 0o644 });
  fs.renameSync(temporary, file);
}

function main(argv) {
  if (argv.length !== 1 || !["--check", "--write"].includes(argv[0])) {
    throw new Error("usage: node generate-jvm-raw-fixture-overlay.js --check|--write");
  }
  if (argv[0] === "--write") write();
  else check();
  process.stdout.write(`jvm raw fixture overlay ${argv[0] === "--write" ? "generated" : "current"}\n`);
}

if (require.main === module) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exit(1); }
}

module.exports = Object.freeze({ check, expectedBytes, renderOverlay, write });
