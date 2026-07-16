"use strict";

const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { applyMaterializedPatches } = require("../../scripts/http-engine-vendor.js");
const specification = require("../../certification/transport/jvm-engine-contract.js");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const materializeScript = path.join(repositoryRoot, "sdk/jvm/scripts/materialize-http-engine.sh");
const patchDirectory = path.join(__dirname, "patches");
const vendorLockPath = path.join(__dirname, "vendor-lock.toml");
const patchNames = specification.implementation.patchSeries;

function run(command, argumentsList, cwd) {
  const result = childProcess.spawnSync(command, argumentsList, {
    cwd,
    encoding: "utf8",
    timeout: 120_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function lockedPatch(name) {
  const patchPath = path.join(patchDirectory, name);
  const digest = crypto.createHash("sha256").update(fs.readFileSync(patchPath)).digest("hex");
  assert.ok(
    fs.readFileSync(vendorLockPath, "utf8").includes(
      `file = "${name}"\nstate = "materialized"\nsha256 = "${digest}"`,
    ),
    `${name} bytes must match the executable vendor lock`,
  );
  return { file: name, state: "materialized", sha256: digest };
}

test("patch 0004 is limited to outbound H2 cancellation and local GOAWAY accounting", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is required for the stock-baseline regression");
    return;
  }

  assert.deepEqual(patchNames.slice(-3), [
    "0001-shared-byte-reservations-and-budgeted-response-consumer.patch",
    "0003-prune-conscrypt-optional-provider-references.patch",
    "0004-h2-stream-cancel-and-goaway-accounting.patch",
  ]);
  const patchText = fs.readFileSync(path.join(patchDirectory, patchNames[2]), "utf8");
  assert.deepEqual(
    [...patchText.matchAll(/^diff --git a\/(\S+) b\/\1$/gm)].map((match) => match[1]),
    [
      "httpcore5-h2/org/apache/hc/core5/http2/impl/nio/AbstractH2StreamMultiplexer.java",
      "httpcore5-h2/org/apache/hc/core5/http2/impl/nio/H2Stream.java",
    ],
  );
  assert.deepEqual(
    patchText.split("\n").filter((line) => /^[-+](?![-+])/.test(line)),
    [
      "-                            if (!streams.isSameSide(activeStreamId) && activeStreamId > processedLocalStreamId) {",
      "+                            if (streams.isSameSide(activeStreamId) && activeStreamId > processedLocalStreamId) {",
      "-import java.nio.channels.CancelledKeyException;",
      "-                channel.requestOutput();",
      "+                localResetCancelled();",
      "-            } catch (final CancelledKeyException ignore) {",
      "+            } catch (final IOException ignore) {",
    ],
  );

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-patch-0004-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const upstream = path.join(temporaryRoot, "upstream");
  run(
    "bash",
    [materializeScript, "--archive-dir", archiveDirectory, "--output", upstream],
    repositoryRoot,
  );
  applyMaterializedPatches(
    upstream,
    patchDirectory,
    patchNames.slice(0, 2).map(lockedPatch),
  );

  const h2Root = path.join(upstream, "httpcore5-h2/org/apache/hc/core5/http2/impl/nio");
  const stockStream = fs.readFileSync(path.join(h2Root, "H2Stream.java"), "utf8");
  const stockMultiplexer = fs.readFileSync(
    path.join(h2Root, "AbstractH2StreamMultiplexer.java"),
    "utf8",
  );
  assert.match(
    stockStream,
    /boolean abort\(\) \{\n        if \(cancelled\.compareAndSet\(false, true\)\) \{\n            try \{\n                channel\.requestOutput\(\);\n                return true;\n            \} catch \(final CancelledKeyException ignore\) \{/,
  );
  assert.match(
    stockMultiplexer,
    /if \(!streams\.isSameSide\(activeStreamId\) && activeStreamId > processedLocalStreamId\) \{/,
  );

  applyMaterializedPatches(upstream, patchDirectory, [lockedPatch(patchNames[2])]);
  const patchedStream = fs.readFileSync(path.join(h2Root, "H2Stream.java"), "utf8");
  const patchedMultiplexer = fs.readFileSync(
    path.join(h2Root, "AbstractH2StreamMultiplexer.java"),
    "utf8",
  );
  assert.match(
    patchedStream,
    /boolean abort\(\) \{\n        if \(cancelled\.compareAndSet\(false, true\)\) \{\n            try \{\n                localResetCancelled\(\);\n                return true;\n            \} catch \(final IOException ignore\) \{/,
  );
  assert.doesNotMatch(patchedStream, /import java\.nio\.channels\.CancelledKeyException;/);
  assert.match(
    patchedMultiplexer,
    /if \(streams\.isSameSide\(activeStreamId\) && activeStreamId > processedLocalStreamId\) \{/,
  );
  assert.doesNotMatch(
    patchedMultiplexer,
    /if \(!streams\.isSameSide\(activeStreamId\) && activeStreamId > processedLocalStreamId\) \{/,
  );
});
