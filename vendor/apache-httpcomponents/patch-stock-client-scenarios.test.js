"use strict";

const assert = require("node:assert/strict");
const childProcess = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "../../../..");
const materializeScript = path.join(repositoryRoot, "sdk/jvm/scripts/materialize-http-engine.sh");
const redirectProbe = path.join(__dirname, "probes/RepostStockRedirectProbe.java");

function run(command, argumentsList, cwd) {
  const result = childProcess.spawnSync(command, argumentsList, {
    cwd,
    encoding: "utf8",
    timeout: 30_000,
    maxBuffer: 4 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, result.stderr || result.stdout);
  return result.stdout;
}

function jdkDependentLintArguments() {
  const supported = childProcess.spawnSync("javac", ["-Xlint:-this-escape", "-version"]);
  return supported.status === 0 ? ["-Xlint:-this-escape"] : [];
}

test("stock HttpCore satisfies configuration-redirect-refused without a vendor patch", (context) => {
  const archiveDirectory = process.env.REPOST_HTTP_ENGINE_ARCHIVE_DIR;
  if (!archiveDirectory) {
    context.skip("REPOST_HTTP_ENGINE_ARCHIVE_DIR is required for stock-client certification");
    return;
  }

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "repost-stock-client-redirect-"));
  context.after(() => fs.rmSync(temporaryRoot, { recursive: true, force: true }));
  const stock = path.join(temporaryRoot, "stock");
  const classes = path.join(temporaryRoot, "classes");
  fs.mkdirSync(classes);
  run(
    "bash",
    [materializeScript, "--archive-dir", archiveDirectory, "--output", stock],
    repositoryRoot,
  );

  run(
    "javac",
    [
      "--release", "11",
      "-Xlint:all,-overloads,-serial,-fallthrough", ...jdkDependentLintArguments(), "-Werror",
      "-sourcepath",
      ["httpcore5", "slf4j-api", "slf4j-nop"]
        .map((moduleName) => path.join(stock, moduleName))
        .join(path.delimiter),
      "-d", classes,
      redirectProbe,
    ],
    temporaryRoot,
  );
  assert.equal(
    run("java", ["-ea", "-cp", classes, "RepostStockRedirectProbe"], temporaryRoot),
    "repost-stock-redirect-probe:ok:configuration-redirect-refused:primary=1:target=0\n",
  );
});
