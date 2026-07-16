"use strict";

const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const jvmRoot = path.join(repositoryRoot, "sdk/jvm");
const buildFile = path.join(jvmRoot, "repost-http-engine/build.gradle.kts");
const rootBuildFile = path.join(jvmRoot, "build.gradle.kts");
const lockFile = path.join(__dirname, "vendor-lock.toml");
const attributesFile = path.join(repositoryRoot, ".gitattributes");

test("engine build materializes the verified sources only through repost-client", () => {
  const lock = fs.readFileSync(lockFile, "utf8");
  assert.match(lock, /^production_state = "materialized"$/m);
  assert.doesNotMatch(lock, /^state = "planned"$/m);

  const build = fs.readFileSync(buildFile, "utf8");
  assert.match(build, /checkHttpEngineProvenance/);
  assert.match(build, /checkHttpEngineProductionReady/);
  assert.match(build, /materializeHttpEngineSources/);
  assert.match(build, /dependsOn\(checkHttpEngineProvenance, materializeHttpEngineSources\)/);
  assert.match(build, /removeUnreadyHttpEngineOutputs/);
  assert.match(build, /production_state = \\"materialized\\"/);
  assert.match(build, /tasks\.named<Jar>\("jar"\)/);
  assert.match(build, /enabled = productionReady\.get\(\)/);
  assert.match(build, /buildDirectory\.dir\("vendor\/relocated"\)/);
  assert.match(build, /java\.setSrcDirs\(emptyList<String>\(\)\)/);

  const rootBuild = fs.readFileSync(rootBuildFile, "utf8");
  assert.match(rootBuild, /project\(":repost-client"\)/);
  assert.match(rootBuild, /dependsOn\(":repost-http-engine:materializeHttpEngineSources"\)/);
  assert.match(rootBuild, /options\.sourcepath = files\(relocatedEngineSources\)/);
});

test("hash-pinned patches retain LF bytes on every checkout", () => {
  assert.match(
    fs.readFileSync(attributesFile, "utf8"),
    /^sdk\/jvm\/vendor\/apache-httpcomponents\/patches\/\*\.patch text eol=lf$/m,
  );
});

test("Gradle proves the materialized production source closure", (context) => {
  if (process.env.REPOST_RUN_GRADLE_INTEGRATION !== "1") {
    context.skip("REPOST_RUN_GRADLE_INTEGRATION is not set");
    return;
  }
  const environment = {
    ...process.env,
    REPOST_HTTP_ENGINE_ARCHIVE_DIR: process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR,
  };
  const run = (...tasks) => childProcess.spawnSync(
    path.join(jvmRoot, "gradlew"),
    [...tasks, "--no-daemon"],
    { cwd: jvmRoot, encoding: "utf8", timeout: 120_000, env: environment },
  );

  const provenance = run(":repost-http-engine:checkHttpEngineProvenance");
  assert.equal(provenance.status, 0, provenance.stderr || provenance.stdout);

  const materialization = run(":repost-http-engine:materializeHttpEngineSources");
  assert.equal(materialization.status, 0, materialization.stderr || materialization.stdout);

  const readiness = run(":repost-http-engine:checkHttpEngineProductionReady");
  assert.equal(readiness.status, 0, readiness.stderr || readiness.stdout);

  const compilation = run(":repost-client:compileJava");
  assert.equal(compilation.status, 0, compilation.stderr || compilation.stdout);
});

test("production materialization fails atomically when a pinned archive is unavailable", (context) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-engine-missing-archive-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const output = path.join(temporaryRoot, "should-not-exist");
  const result = childProcess.spawnSync(
    "node",
    [
      path.join(jvmRoot, "scripts/http-engine-vendor.js"),
      "materialize-relocated",
      "--archive-dir",
      path.join(jvmRoot, "definitely-missing-archives"),
      "--output",
      output,
    ],
    { cwd: jvmRoot, encoding: "utf8", timeout: 30_000 },
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /http-engine-vendor: missing source archive:/);
  assert.equal(fs.existsSync(output), false);
});
