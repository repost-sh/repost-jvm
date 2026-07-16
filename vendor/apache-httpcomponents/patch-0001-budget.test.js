"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { applyMaterializedPatches } = require("../../scripts/http-engine-vendor.js");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const patchName = "0001-shared-byte-reservations-and-budgeted-response-consumer.patch";
const patchDirectory = path.join(__dirname, "patches");
const patchPath = path.join(patchDirectory, patchName);
const probePath = path.join(__dirname, "probes/RepostBudgetProbe.java");
const stockProbePath = path.join(__dirname, "probes/RepostStockResponseProbe.java");
const materializeScript = path.join(repositoryRoot, "sdk/jvm/scripts/materialize-http-engine.sh");
const vendorLockPath = path.join(__dirname, "vendor-lock.toml");

function runResult(command, argumentsList, cwd) {
  return childProcess.spawnSync(command, argumentsList, {
    cwd,
    encoding: "utf8",
    timeout: 30_000,
    maxBuffer: 4 * 1024 * 1024,
  });
}

function run(command, argumentsList, cwd) {
  const result = runResult(command, argumentsList, cwd);
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr || result.stdout);
  return result.stdout;
}

function jdkDependentLintArguments() {
  const supported = runResult("javac", ["-Xlint:-this-escape", "-version"], repositoryRoot);
  return supported.status === 0 ? ["-Xlint:-this-escape"] : [];
}

function patchDigest() {
  const digest = crypto.createHash("sha256").update(fs.readFileSync(patchPath)).digest("hex");
  assert.ok(
    fs.readFileSync(vendorLockPath, "utf8").includes(
      `file = "${patchName}"\nstate = "materialized"\nsha256 = "${digest}"`,
    ),
    "Patch 0001 bytes must match the executable vendor lock",
  );
  return digest;
}

function applyPatch0001(upstream) {
  applyMaterializedPatches(upstream, patchDirectory, [{
    file: patchName,
    state: "materialized",
    sha256: patchDigest(),
  }]);
}

function compileAndRunPatchedProbe(upstream, classes, cwd) {
  const sourceRoot = path.join(upstream, "httpcore5/org/apache/hc/core5/repost");
  const budgetSource = path.join(sourceRoot, "RepostByteBudget.java");
  const consumerSource = path.join(sourceRoot, "RepostBudgetedBodyConsumer.java");
  run(
    "javac",
    ["--release", "11", "-Xlint:all", "-Werror", "-d", classes, budgetSource, consumerSource, probePath],
    cwd,
  );
  assert.equal(
    run("java", ["-ea", "-cp", classes, "RepostBudgetProbe"], cwd),
    "repost-budget-probe:ok\n",
  );
}

test("patch 0001 bounds shared reservations and response chunks", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-patch-0001-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const upstream = path.join(temporaryRoot, "upstream");
  const classes = path.join(temporaryRoot, "classes");
  fs.mkdirSync(path.join(upstream, "httpcore5"), { recursive: true });
  fs.mkdirSync(classes);

  applyPatch0001(upstream);

  const sourceRoot = path.join(upstream, "httpcore5/org/apache/hc/core5/repost");
  const consumerSource = path.join(sourceRoot, "RepostBudgetedBodyConsumer.java");
  const consumer = fs.readFileSync(consumerSource, "utf8");
  assert.doesNotMatch(
    consumer,
    /ByteArrayOutputStream|PipedInputStream|PipedOutputStream|toByteArray|Arrays\.copyOf|addSuppressed/,
  );
  assert.match(consumer, /extends InputStream/);
  assert.match(consumer, /MAX_BODY_BYTES = 1_048_576/);
  assert.match(consumer, /MAX_CHUNK_BYTES = 16_384/);
  assert.match(consumer, /MAX_CHUNKS = 64/);
  assert.match(consumer, /interface UpstreamControl/);
  assert.match(consumer, /chunks = null/);

  compileAndRunPatchedProbe(upstream, classes, temporaryRoot);
});

test("patch 0001 is required by the frozen verified-stock response regression", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is required for the stock-baseline regression");
    return;
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-patch-0001-stock-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const stock = path.join(temporaryRoot, "stock");
  const patched = path.join(temporaryRoot, "patched");
  const stockClasses = path.join(temporaryRoot, "stock-classes");
  const patchedClasses = path.join(temporaryRoot, "patched-classes");
  fs.mkdirSync(stockClasses);
  fs.mkdirSync(patchedClasses);

  run(
    "bash",
    [materializeScript, "--archive-dir", archiveDirectory, "--output", stock],
    repositoryRoot,
  );
  fs.cpSync(stock, patched, { recursive: true });
  applyPatch0001(patched);

  run(
    "javac",
    [
      "--release", "11", "-Xlint:all,-serial", ...jdkDependentLintArguments(), "-Werror",
      "-sourcepath", path.join(stock, "httpcore5"),
      "-d", stockClasses,
      stockProbePath,
    ],
    temporaryRoot,
  );
  const stockResult = runResult(
    "java",
    ["-ea", "-cp", stockClasses, "RepostStockResponseProbe"],
    temporaryRoot,
  );
  if (stockResult.error) throw stockResult.error;
  assert.equal(
    stockResult.status,
    17,
    stockResult.status === 0
      ? "verified stock now satisfies the frozen regression; remove Patch 0001"
      : stockResult.stderr || stockResult.stdout,
  );
  assert.equal(
    stockResult.stdout,
    "repost-stock-response-probe:FAIL:plus-one-accepted-with-full-duplicate\n",
  );
  assert.equal(stockResult.stderr, "");

  compileAndRunPatchedProbe(patched, patchedClasses, temporaryRoot);
});
