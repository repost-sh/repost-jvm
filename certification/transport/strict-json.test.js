"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

const directory = path.join(__dirname, "strict-json");
const corpus = path.join(directory, "corpus-manifest.tsv");
const expected = fs.readFileSync(corpus, "utf8").trimEnd().split("\n").map((line) => {
  const [decision, id] = line.split("\t");
  return `${decision} ${id}`;
});
expected.push("SUMMARY accept=5 reject=19 total=24");
const expectedOutput = `${expected.join("\n")}\n`;

function run(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8" });
  assert.equal(result.error, undefined, `${command} must be installed: ${result.error?.message}`);
  assert.equal(result.status, 0, `${command} ${args.join(" ")} failed:\n${result.stderr}`);
  assert.equal(result.signal, null);
  assert.equal(result.stderr, "");
  return result.stdout;
}

function runFailure(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8" });
  assert.equal(result.error, undefined, `${command} must be installed: ${result.error?.message}`);
  assert.notEqual(result.status, 0, `${command} ${args.join(" ")} must reject the mutated manifest`);
}

test("the strict validator accepts and rejects the checked-in JSON byte corpus", () => {
  const output = run(process.execPath, [path.join(__dirname, "validate.js"), "--strict-json-corpus", corpus]);
  assert.equal(output, expectedOutput);
});

test("the strict validator rejects malformed or unsafe corpus manifests", (t) => {
  const temporary = fs.mkdtempSync(path.join(process.env.TMPDIR || "/tmp", "repost-strict-json-"));
  t.after(() => fs.rmSync(temporary, { recursive: true, force: true }));
  const canonical = fs.readFileSync(corpus);
  const text = canonical.toString("utf8");
  const lines = text.trimEnd().split("\n");
  const mutations = new Map([
    ["absolute", text.replace("corpus/duplicate-escaped-equivalent.json", "/tmp/duplicate.json")],
    ["parent", text.replace("corpus/duplicate-escaped-equivalent.json", "corpus/../duplicate.json")],
    ["empty", ""],
    ["duplicate", `${lines[0]}\n${text}`],
    ["unsorted", `${lines[1]}\n${lines[0]}\n${lines.slice(2).join("\n")}\n`],
    ["crlf", text.replaceAll("\n", "\r\n")],
    ["non-lf", text.slice(0, -1)],
    ["invalid-utf8", Buffer.concat([canonical.subarray(0, 1), Buffer.from([0xc0, 0xaf]), canonical.subarray(1)])],
  ]);
  for (const [name, value] of mutations) {
    const mutationRoot = path.join(temporary, name);
    fs.mkdirSync(mutationRoot);
    fs.cpSync(path.join(directory, "corpus"), path.join(mutationRoot, "corpus"), { recursive: true });
    const manifest = path.join(mutationRoot, "corpus-manifest.tsv");
    fs.writeFileSync(manifest, value);
    runFailure(process.execPath, [path.join(__dirname, "validate.js"), "--strict-json-corpus", manifest]);
  }
});
