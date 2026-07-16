"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  applyMaterializedPatches,
  relocatePatchedSources,
} = require("../../scripts/http-engine-vendor.js");

const specification = {
  engineId: "fixture-engine",
  implementation: {
    relocationPrefix: "sh.repost.internal.apache",
    optionalFeaturePruning: {
      archiveDerivedExcludedPaths: ["org/apache/hc/Optional.java"],
      prohibitedNamespacesAndStrings: ["org.conscrypt"],
    },
  },
};

function write(root, module, relative, contents) {
  const file = path.join(root, module, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, contents);
}

test("relocation is deterministic, source-only, NOP-bound, and atomic", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-relocation-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const upstream = path.join(temporaryRoot, "upstream");
  const output = path.join(temporaryRoot, "relocated");

  write(upstream, "httpclient5", "org/apache/hc/Example.java", [
    "package org.apache.hc;\r\n",
    "final class Example { String name = \"org.apache.hc.Example\";\r\n",
    "String resource = \"org/slf4j/impl/StaticLoggerBinder.class\"; }\r\n",
  ].join(""));
  write(upstream, "httpclient5", "org/apache/hc/Optional.java", "package org.apache.hc;\n");
  write(upstream, "httpclient5", "META-INF/MANIFEST.MF", "untrusted-resource\n");
  write(upstream, "slf4j-api", "org/slf4j/Logger.java", "package org.slf4j;\n");
  write(upstream, "slf4j-api", "org/slf4j/impl/StaticLoggerBinder.java", "package org.slf4j.impl; final class ApiStub {}\n");
  write(upstream, "slf4j-nop", "org/slf4j/impl/StaticLoggerBinder.java", "package org.slf4j.impl; final class NopBinding {}\n");

  const first = relocatePatchedSources(upstream, output, specification);
  const examplePath = path.join(
    output,
    "sh/repost/internal/apache/org/apache/hc/Example.java",
  );
  assert.equal(
    fs.readFileSync(examplePath, "utf8"),
    "package sh.repost.internal.apache.org.apache.hc;\n" +
      "final class Example { String name = \"sh.repost.internal.apache.org.apache.hc.Example\";\n" +
      "String resource = \"sh/repost/internal/apache/org/slf4j/impl/StaticLoggerBinder.class\"; }\n",
  );
  const binder = fs.readFileSync(
    path.join(output, "sh/repost/internal/apache/org/slf4j/impl/StaticLoggerBinder.java"),
    "utf8",
  );
  assert.match(binder, /NopBinding/);
  assert.doesNotMatch(binder, /ApiStub/);
  assert.equal(first.includedSources.length, 3);
  assert.deepEqual(
    first.excludedSources.map(({ source, reason }) => ({ source, reason })),
    [
      { source: "httpclient5/META-INF/MANIFEST.MF", reason: "non-java-source" },
      { source: "httpclient5/org/apache/hc/Optional.java", reason: "optional-feature-pruning" },
      { source: "slf4j-api/org/slf4j/impl/StaticLoggerBinder.java", reason: "relocated-nop-binding-wins" },
    ],
  );
  const inventory = fs.readFileSync(path.join(output, "source-inventory.json"), "utf8");
  assert.equal(inventory, `${JSON.stringify(first, null, 2)}\n`);

  const second = relocatePatchedSources(upstream, output, specification);
  assert.deepEqual(second, first);
  assert.equal(fs.readFileSync(path.join(output, "source-inventory.json"), "utf8"), inventory);

  write(upstream, "httpclient5", "org/apache/hc/Forbidden.java", "package org.apache.hc; // org.conscrypt\n");
  assert.throws(
    () => relocatePatchedSources(upstream, output, specification),
    /prohibited source string org\.conscrypt/,
  );
  assert.equal(
    fs.readFileSync(path.join(output, "source-inventory.json"), "utf8"),
    inventory,
    "a failed relocation must preserve the prior complete tree",
  );
});

test("materialized patches are hash-verified and applied in lock order", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-patches-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const upstream = path.join(temporaryRoot, "upstream");
  const patchDirectory = path.join(temporaryRoot, "patches");
  const source = "httpclient5/org/apache/hc/Example.java";
  write(upstream, "httpclient5", "org/apache/hc/Example.java", "package org.apache.hc;\nfinal class Before {}\n");
  fs.mkdirSync(patchDirectory);
  const patchName = "0001-example.patch";
  const patchContents = [
    `diff --git a/${source} b/${source}`,
    `--- a/${source}`,
    `+++ b/${source}`,
    "@@ -1,2 +1,2 @@",
    " package org.apache.hc;",
    "-final class Before {}",
    "+final class After {}",
    "",
  ].join("\n");
  const patchPath = path.join(patchDirectory, patchName);
  fs.writeFileSync(patchPath, patchContents);
  const patch = {
    file: patchName,
    state: "materialized",
    sha256: crypto.createHash("sha256").update(patchContents).digest("hex"),
  };

  applyMaterializedPatches(upstream, patchDirectory, [patch]);
  assert.match(fs.readFileSync(path.join(upstream, source), "utf8"), /After/);

  write(upstream, "httpclient5", "org/apache/hc/Second.java", "package org.apache.hc;\nfinal class Before {}\n");
  assert.throws(
    () => applyMaterializedPatches(upstream, patchDirectory, [{ ...patch, sha256: "0".repeat(64) }]),
    /patch SHA-256 mismatch/,
  );
  assert.match(fs.readFileSync(path.join(upstream, "httpclient5/org/apache/hc/Second.java"), "utf8"), /Before/);
});

test("materialized patches apply when the staging tree is inside this worktree", (context) => {
  const buildRoot = path.resolve(__dirname, "../../repost-http-engine/build/vendor");
  fs.mkdirSync(buildRoot, { recursive: true });
  const temporaryRoot = fs.mkdtempSync(path.join(buildRoot, "patch-application-test-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const upstream = path.join(temporaryRoot, "upstream");
  const patchDirectory = path.join(temporaryRoot, "patches");
  const source = "httpclient5/org/apache/hc/Example.java";
  write(upstream, "httpclient5", "org/apache/hc/Example.java", "package org.apache.hc;\nfinal class Before {}\n");
  fs.mkdirSync(patchDirectory);
  const patchName = "0001-example.patch";
  const patchContents = [
    `diff --git a/${source} b/${source}`,
    `--- a/${source}`,
    `+++ b/${source}`,
    "@@ -1,2 +1,2 @@",
    " package org.apache.hc;",
    "-final class Before {}",
    "+final class After {}",
    "",
  ].join("\n");
  fs.writeFileSync(path.join(patchDirectory, patchName), patchContents);

  applyMaterializedPatches(upstream, patchDirectory, [{
    file: patchName,
    state: "materialized",
    sha256: crypto.createHash("sha256").update(patchContents).digest("hex"),
  }]);

  assert.match(fs.readFileSync(path.join(upstream, source), "utf8"), /After/);
});
