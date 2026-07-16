"use strict";

const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  applyMaterializedPatches,
  relocatePatchedSources,
} = require("../../scripts/http-engine-vendor.js");
const specification = require("../../certification/transport/jvm-engine-contract.js");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const materializeScript = path.join(repositoryRoot, "sdk/jvm/scripts/materialize-http-engine.sh");
const patchDirectory = path.join(__dirname, "patches");
const vendorLockPath = path.join(__dirname, "vendor-lock.toml");
const probePath = path.join(__dirname, "probes/RepostPatchedClientBuilderProbe.java");
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
  return result.stdout;
}

function jdkDependentLintArguments() {
  const supported = childProcess.spawnSync("javac", ["-Xlint:-this-escape", "-version"]);
  return supported.status === 0 ? ["-Xlint:-this-escape"] : [];
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

test("patch 0003 is the bounded correction for the JDK-only source closure", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is required for the stock-baseline regression");
    return;
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-patch-0003-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const stock = path.join(temporaryRoot, "stock");
  const patched = path.join(temporaryRoot, "patched");
  const relocated = path.join(temporaryRoot, "relocated");
  const classes = path.join(temporaryRoot, "classes");
  fs.mkdirSync(classes);
  run(
    "bash",
    [materializeScript, "--archive-dir", archiveDirectory, "--output", stock],
    repositoryRoot,
  );
  applyMaterializedPatches(stock, patchDirectory, [lockedPatch(patchNames[0])]);
  fs.cpSync(stock, patched, { recursive: true });

  assert.throws(
    () => relocatePatchedSources(stock, path.join(temporaryRoot, "stock-relocated"), specification),
    /prohibited source string org\.conscrypt: httpclient5\/org\/apache\/hc\/client5\/http\/impl\/TunnelRefusedException\.java/,
  );

  applyMaterializedPatches(patched, patchDirectory, [lockedPatch(patchNames[1])]);
  const inventory = relocatePatchedSources(patched, relocated, specification);
  const excluded = new Set(inventory.excludedSources.map((entry) => entry.source));
  for (const excludedPath of specification.implementation.optionalFeaturePruning
    .archiveDerivedExcludedPaths) {
    const matches = [...excluded].filter((entry) => entry.endsWith(`/${excludedPath}`));
    assert.equal(matches.length, 1, `one excluded source expected for ${excludedPath}`);
  }

  run(
    "javac",
    [
      "--release", "11",
      "-Xlint:all,-overloads,-serial,-fallthrough,-try,-dep-ann,-deprecation,-removal,-unchecked,-cast",
      ...jdkDependentLintArguments(),
      "-Werror",
      "-sourcepath", relocated,
      "-d", classes,
      probePath,
    ],
    temporaryRoot,
  );
  assert.equal(
    run("java", ["-ea", "-cp", classes, "RepostPatchedClientBuilderProbe"], temporaryRoot),
    "repost-patched-client-builder-probe:ok\n",
  );
});
