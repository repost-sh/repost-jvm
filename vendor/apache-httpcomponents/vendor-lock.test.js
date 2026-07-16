"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const lockPath = path.join(__dirname, "vendor-lock.toml");
const ledgerPath = path.join(__dirname, "patch-ledger.md");
const engineContract = require(path.join(
  repositoryRoot,
  "sdk/jvm/certification/transport/jvm-engine-contract.js",
));
const approvedPatchSeries = [
  "0001-shared-byte-reservations-and-budgeted-response-consumer.patch",
  "0003-prune-conscrypt-optional-provider-references.patch",
  "0004-h2-stream-cancel-and-goaway-accounting.patch",
];

function parseQuotedValue(line, key) {
  const match = line.match(new RegExp(`^${key} = "([^"]*)"$`));
  return match && match[1];
}

function parseLock(contents) {
  const result = { sources: [], patches: [] };
  let section = result;
  let record = result;

  for (const rawLine of contents.split("\n")) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#")) continue;
    if (line === "[[source]]") {
      record = {};
      result.sources.push(record);
      section = record;
      continue;
    }
    if (line === "[[patch]]") {
      record = {};
      result.patches.push(record);
      section = record;
      continue;
    }

    const equals = line.indexOf(" = ");
    assert.notEqual(equals, -1, `unsupported vendor-lock line: ${line}`);
    const key = line.slice(0, equals);
    const value = parseQuotedValue(line, key);
    assert.notEqual(value, null, `vendor-lock values must be quoted: ${line}`);
    section[key] = value;
  }
  return result;
}

test("vendor lock is the exact executable projection of the engine contract", () => {
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  const implementation = engineContract.implementation;

  assert.equal(lock.format, "repost-http-engine-vendor-lock-v1");
  assert.equal(lock.engine_id, engineContract.engineId);
  assert.equal(lock.relocation_prefix, implementation.relocationPrefix);
  assert.equal(lock.production_state, "materialized");
  assert.deepEqual(implementation.patchSeries, approvedPatchSeries);
  assert.deepEqual(
    lock.sources,
    implementation.upstreamSources.map((source) => ({
      module: source.module,
      version: source.version,
      archive: source.archive,
      sha256: source.sha256,
    })),
  );
  assert.deepEqual(
    lock.patches.map((patch) => patch.file),
    approvedPatchSeries,
  );
  for (const patch of lock.patches) {
    const patchPath = path.join(__dirname, "patches", patch.file);
    assert.equal(fs.existsSync(patchPath), true, `${patch.file} must exist`);
    assert.equal(patch.state, "materialized", patch.file);
    assert.match(patch.sha256, /^[0-9a-f]{64}$/);
    const digest = crypto
      .createHash("sha256")
      .update(fs.readFileSync(patchPath))
      .digest("hex");
    assert.equal(digest, patch.sha256, patch.file);
  }
});

test("patch ledger owns a non-placeholder rationale and adversarial proof for every patch", () => {
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  const ledger = fs.readFileSync(ledgerPath, "utf8");
  for (const patch of lock.patches) {
    const heading = `## ${patch.file}`;
    const start = ledger.indexOf(heading);
    assert.notEqual(start, -1, `missing patch ledger heading: ${heading}`);
    const end = ledger.indexOf("\n## ", start + heading.length);
    const entry = ledger.slice(start, end === -1 ? undefined : end);
    assert.match(entry, /\nRationale: \S.+/);
    assert.match(entry, /\nAdversarial proof: \S.+/);
    assert.doesNotMatch(entry, /\b(?:TBD|TODO)\b/i);
  }
});

test("locally supplied archives match the lock before materialization", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is not set");
    return;
  }
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  for (const source of lock.sources) {
    const archivePath = path.join(archiveDirectory, path.basename(source.archive));
    const digest = crypto
      .createHash("sha256")
      .update(fs.readFileSync(archivePath))
      .digest("hex");
    assert.equal(digest, source.sha256, source.module);
  }
});

test("upstream source materialization is deterministic, verified, and atomic", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is not set");
    return;
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-http-engine-vendor-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const outputDirectory = path.join(temporaryRoot, "materialized");
  const script = path.join(repositoryRoot, "sdk/jvm/scripts/materialize-http-engine.sh");
  const run = (archiveDir) => childProcess.spawnSync(
    "bash",
    [script, "--archive-dir", archiveDir, "--output", outputDirectory],
    { cwd: repositoryRoot, encoding: "utf8", timeout: 60_000 },
  );

  const first = run(archiveDirectory);
  assert.equal(first.status, 0, first.stderr || first.stdout);
  const firstManifest = fs.readFileSync(
    path.join(outputDirectory, "upstream-files.sha256"),
    "utf8",
  );
  assert.match(firstManifest, /httpclient5\/org\/apache\/hc\/client5\/.+\.java/m);
  assert.match(firstManifest, /httpcore5-h2\/org\/apache\/hc\/core5\/http2\/.+\.java/m);
  assert.doesNotMatch(firstManifest, /upstream-files\.sha256/);

  for (const excludedPath of engineContract.implementation.optionalFeaturePruning
    .archiveDerivedExcludedPaths) {
    const matches = lockSourcePaths(outputDirectory, excludedPath);
    assert.equal(matches.length, 1, `expected one archive-derived exclusion anchor: ${excludedPath}`);
  }
  for (const reference of engineContract.implementation.optionalFeaturePruning
    .archiveDerivedPatchedReferences) {
    const [sourcePath, action] = reference.split(":");
    const matches = lockSourcePaths(outputDirectory, sourcePath);
    assert.equal(matches.length, 1, `expected one archive-derived patch anchor: ${sourcePath}`);
    const contents = fs.readFileSync(matches[0], "utf8");
    if (action.includes("org.conscrypt.Internal")) {
      assert.match(contents, /org\.conscrypt\.Internal/);
    } else if (action.includes("ConscryptClientTlsStrategy")) {
      assert.match(contents, /ConscryptClientTlsStrategy/);
    } else if (action.includes("ContentCompressionAsyncExec")) {
      assert.match(contents, /ContentCompressionAsyncExec/);
    } else if (action.includes("org.conscrypt-source-strings")) {
      assert.match(contents, /org\.conscrypt/);
    } else {
      assert.fail(`unrecognized patch-anchor action: ${action}`);
    }
  }

  const second = run(archiveDirectory);
  assert.equal(second.status, 0, second.stderr || second.stdout);
  assert.equal(
    fs.readFileSync(path.join(outputDirectory, "upstream-files.sha256"), "utf8"),
    firstManifest,
  );

  const tamperedArchives = path.join(temporaryRoot, "tampered");
  fs.mkdirSync(tamperedArchives);
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  for (const source of lock.sources) {
    fs.copyFileSync(
      path.join(archiveDirectory, path.basename(source.archive)),
      path.join(tamperedArchives, path.basename(source.archive)),
    );
  }
  fs.appendFileSync(
    path.join(tamperedArchives, path.basename(lock.sources[0].archive)),
    "tampered",
  );

  const rejected = run(tamperedArchives);
  assert.notEqual(rejected.status, 0, "tampered archive must fail");
  assert.match(rejected.stderr, /SHA-256 mismatch/);
  assert.equal(
    fs.readFileSync(path.join(outputDirectory, "upstream-files.sha256"), "utf8"),
    firstManifest,
    "failed replacement must preserve the prior materialized tree",
  );
});

function lockSourcePaths(materializedRoot, sourcePath) {
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  return lock.sources
    .map((source) => path.join(materializedRoot, source.module, sourcePath))
    .filter((candidate) => fs.existsSync(candidate));
}
