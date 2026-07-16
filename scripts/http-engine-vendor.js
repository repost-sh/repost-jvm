#!/usr/bin/env node

"use strict";

const childProcess = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const repositoryRoot = path.resolve(__dirname, "../../..");
const vendorDirectory = path.join(
  repositoryRoot,
  "sdk/jvm/vendor/apache-httpcomponents",
);
const lockPath = path.join(vendorDirectory, "vendor-lock.toml");
const engineContract = require(path.join(
  repositoryRoot,
  "sdk/jvm/certification/transport/jvm-engine-contract.js",
));

function fail(message) {
  throw new Error(message);
}

function parseLock(contents) {
  const result = { sources: [], patches: [] };
  let section = result;
  for (const rawLine of contents.split("\n")) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#")) continue;
    if (line === "[[source]]") {
      section = {};
      result.sources.push(section);
      continue;
    }
    if (line === "[[patch]]") {
      section = {};
      result.patches.push(section);
      continue;
    }
    const match = line.match(/^([a-z0-9_]+) = "([^"]*)"$/);
    if (!match) fail(`unsupported vendor-lock line: ${line}`);
    if (Object.hasOwn(section, match[1])) fail(`duplicate vendor-lock key: ${match[1]}`);
    section[match[1]] = match[2];
  }
  return result;
}

function assertExactKeys(location, value, expected) {
  const actual = Object.keys(value);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${location} fields must be exactly ${expected.join(",")}; received ${actual.join(",")}`);
  }
}

function loadAndValidateLock() {
  const lock = parseLock(fs.readFileSync(lockPath, "utf8"));
  assertExactKeys("vendor lock", lock, [
    "sources",
    "patches",
    "format",
    "engine_id",
    "relocation_prefix",
    "production_state",
  ]);
  if (lock.format !== "repost-http-engine-vendor-lock-v1") fail("vendor-lock format mismatch");
  if (lock.engine_id !== engineContract.engineId) fail("vendor-lock engine ID mismatch");
  if (lock.relocation_prefix !== engineContract.implementation.relocationPrefix) {
    fail("vendor-lock relocation prefix mismatch");
  }
  if (!new Set(["planned", "materialized"]).has(lock.production_state)) {
    fail("vendor-lock production state is invalid");
  }

  const expectedSources = engineContract.implementation.upstreamSources;
  if (lock.sources.length !== expectedSources.length) fail("vendor-lock source count mismatch");
  lock.sources.forEach((source, index) => {
    assertExactKeys(`source[${index}]`, source, ["module", "version", "archive", "sha256"]);
    const expected = expectedSources[index];
    for (const key of ["module", "version", "archive", "sha256"]) {
      if (source[key] !== expected[key]) fail(`source[${index}].${key} mismatch`);
    }
    if (!/^[a-z0-9-]+$/.test(source.module)) fail(`source[${index}].module is unsafe`);
    if (!/^[0-9a-f]{64}$/.test(source.sha256)) fail(`source[${index}].sha256 is invalid`);
  });

  const expectedPatches = engineContract.implementation.patchSeries;
  if (lock.patches.length !== expectedPatches.length) fail("vendor-lock patch count mismatch");
  lock.patches.forEach((patch, index) => {
    if (patch.file !== expectedPatches[index]) fail(`patch[${index}].file mismatch`);
    const patchPath = path.join(vendorDirectory, "patches", patch.file);
    if (patch.state === "planned") {
      assertExactKeys(`patch[${index}]`, patch, ["file", "state"]);
      if (fs.existsSync(patchPath)) fail(`patch[${index}] exists but remains planned`);
    } else if (patch.state === "materialized") {
      assertExactKeys(`patch[${index}]`, patch, ["file", "state", "sha256"]);
      if (!/^[0-9a-f]{64}$/.test(patch.sha256)) fail(`patch[${index}].sha256 is invalid`);
      let stat;
      try {
        stat = fs.lstatSync(patchPath);
      } catch {
        fail(`patch[${index}] is materialized but missing`);
      }
      if (!stat.isFile() || stat.isSymbolicLink()) fail(`patch[${index}] is not a regular file`);
      if (sha256File(patchPath) !== patch.sha256) fail(`patch[${index}] SHA-256 mismatch`);
    } else {
      fail(`patch[${index}].state is invalid`);
    }
  });
  const plannedPatchCount = lock.patches.filter((patch) => patch.state === "planned").length;
  if (lock.production_state === "materialized" && plannedPatchCount !== 0) {
    fail(`vendor-lock is materialized while ${plannedPatchCount} patches remain planned`);
  }
  return lock;
}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function sourceArchivePath(archiveDirectory, source) {
  const archiveName = path.basename(new URL(source.archive).pathname);
  const archivePath = path.join(archiveDirectory, archiveName);
  let stat;
  try {
    stat = fs.lstatSync(archivePath);
  } catch {
    fail(`missing source archive: ${archivePath}`);
  }
  if (!stat.isFile() || stat.isSymbolicLink()) fail(`source archive is not a regular file: ${archivePath}`);
  const digest = sha256File(archivePath);
  if (digest !== source.sha256) {
    fail(`SHA-256 mismatch for ${archiveName}: expected ${source.sha256}, received ${digest}`);
  }
  return archivePath;
}

function runJar(argumentsList, cwd) {
  const result = childProcess.spawnSync("jar", argumentsList, {
    cwd,
    encoding: "utf8",
    timeout: 30_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    fail(`jar ${argumentsList[0]} failed (${result.status}): ${(result.stderr || result.stdout).trim()}`);
  }
  return result.stdout;
}

function validateArchiveEntries(archivePath) {
  const entries = runJar(["tf", archivePath], repositoryRoot)
    .split("\n")
    .filter((entry) => entry !== "");
  if (entries.length === 0 || entries.length > 50_000) fail(`${archivePath}: unsafe entry count`);
  const seen = new Set();
  for (const entry of entries) {
    if (entry.includes("\\") || entry.startsWith("/") || /^[A-Za-z]:/.test(entry)) {
      fail(`${archivePath}: unsafe archive path: ${entry}`);
    }
    const components = entry.split("/");
    if (components.some((component, index) => (
      component === ".." || component === "." || (component === "" && index !== components.length - 1)
    ))) {
      fail(`${archivePath}: unsafe archive path: ${entry}`);
    }
    if (seen.has(entry)) fail(`${archivePath}: duplicate archive entry: ${entry}`);
    seen.add(entry);
  }
}

function listRegularFiles(root) {
  const files = [];
  const visit = (directory) => {
    const entries = fs.readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => Buffer.from(left.name).compare(Buffer.from(right.name)));
    for (const entry of entries) {
      const absolute = path.join(directory, entry.name);
      const relative = path.relative(root, absolute).split(path.sep).join("/");
      const stat = fs.lstatSync(absolute);
      if (stat.isSymbolicLink()) fail(`materialized source contains a symbolic link: ${relative}`);
      if (stat.isDirectory()) visit(absolute);
      else if (stat.isFile()) files.push(relative);
      else fail(`materialized source contains a non-file entry: ${relative}`);
    }
  };
  visit(root);
  return files;
}

function replaceAtomically(stagedOutput, outputDirectory, temporaryRoot) {
  const parent = path.dirname(outputDirectory);
  const backup = path.join(
    parent,
    `.${path.basename(outputDirectory)}.backup-${process.pid}-${Date.now()}`,
  );
  const existed = fs.existsSync(outputDirectory);
  if (existed) fs.renameSync(outputDirectory, backup);
  try {
    fs.renameSync(stagedOutput, outputDirectory);
  } catch (error) {
    if (existed && fs.existsSync(backup)) fs.renameSync(backup, outputDirectory);
    throw error;
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
  if (existed) fs.rmSync(backup, { recursive: true, force: true });
}

function readUtf8Strict(file) {
  const bytes = fs.readFileSync(file);
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    fail(`source is not valid UTF-8: ${file}`);
  }
}

function relocatedSourcePath(relative, relocationPrefix) {
  if (relative.startsWith("org/apache/hc/") || relative.startsWith("org/slf4j/")) {
    return `${relocationPrefix.replaceAll(".", "/")}/${relative}`;
  }
  fail(`source is outside the relocatable namespaces: ${relative}`);
}

function relocatePatchedSources(upstreamDirectory, outputDirectory, specification = engineContract) {
  const upstream = path.resolve(upstreamDirectory);
  const resolvedOutput = path.resolve(outputDirectory);
  if (resolvedOutput === path.parse(resolvedOutput).root) fail("refusing to replace a filesystem root");
  const implementation = specification.implementation;
  const relocationPrefix = implementation.relocationPrefix;
  const pruning = implementation.optionalFeaturePruning;
  const optionalPaths = new Set(pruning.archiveDerivedExcludedPaths);
  const optionalMatches = new Map([...optionalPaths].map((entry) => [entry, 0]));
  const excludedSources = [];
  const candidates = new Map();

  const modules = fs.readdirSync(upstream, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort((left, right) => Buffer.from(left).compare(Buffer.from(right)));
  if (modules.length === 0) fail(`patched upstream source stage is empty: ${upstream}`);

  for (const module of modules) {
    const moduleRoot = path.join(upstream, module);
    for (const relative of listRegularFiles(moduleRoot)) {
      const source = `${module}/${relative}`;
      if (!relative.endsWith(".java")) {
        excludedSources.push({ source, reason: "non-java-source" });
        continue;
      }
      if (optionalPaths.has(relative)) {
        optionalMatches.set(relative, optionalMatches.get(relative) + 1);
        excludedSources.push({ source, reason: "optional-feature-pruning" });
        continue;
      }
      const entries = candidates.get(relative) || [];
      entries.push({ module, source, absolute: path.join(moduleRoot, relative) });
      candidates.set(relative, entries);
    }
  }

  for (const [relative, count] of optionalMatches) {
    if (count !== 1) fail(`optional exclusion must match exactly one source: ${relative}; received ${count}`);
  }

  const selected = [];
  for (const [relative, entries] of [...candidates].sort(([left], [right]) => (
    Buffer.from(left).compare(Buffer.from(right))
  ))) {
    if (entries.length === 1) {
      selected.push({ relative, ...entries[0] });
      continue;
    }
    const modulesForPath = entries.map((entry) => entry.module).sort();
    const nopBinding = relative.startsWith("org/slf4j/impl/Static")
      && relative.endsWith("Binder.java")
      && JSON.stringify(modulesForPath) === JSON.stringify(["slf4j-api", "slf4j-nop"]);
    if (!nopBinding) {
      fail(`duplicate patched source path ${relative}: ${entries.map((entry) => entry.source).join(",")}`);
    }
    const apiEntry = entries.find((entry) => entry.module === "slf4j-api");
    const nopEntry = entries.find((entry) => entry.module === "slf4j-nop");
    excludedSources.push({ source: apiEntry.source, reason: "relocated-nop-binding-wins" });
    selected.push({ relative, ...nopEntry });
  }

  const parent = path.dirname(resolvedOutput);
  fs.mkdirSync(parent, { recursive: true });
  const temporaryRoot = fs.mkdtempSync(path.join(parent, ".repost-http-engine-relocate-"));
  const stagedOutput = path.join(temporaryRoot, "relocated");
  fs.mkdirSync(stagedOutput);

  try {
    const includedSources = [];
    for (const entry of selected) {
      let contents = readUtf8Strict(entry.absolute).replace(/\r\n?/g, "\n");
      if (contents.includes(relocationPrefix)) {
        fail(`source already contains relocation prefix: ${entry.source}`);
      }
      for (const prohibited of pruning.prohibitedNamespacesAndStrings) {
        if (contents.includes(prohibited)) {
          fail(`prohibited source string ${prohibited}: ${entry.source}`);
        }
      }
      contents = contents
        .replaceAll("org.apache.hc", `${relocationPrefix}.org.apache.hc`)
        .replaceAll("org.slf4j", `${relocationPrefix}.org.slf4j`)
        .replaceAll("org/apache/hc", `${relocationPrefix.replaceAll(".", "/")}/org/apache/hc`)
        .replaceAll("org/slf4j", `${relocationPrefix.replaceAll(".", "/")}/org/slf4j`);
      const relocated = relocatedSourcePath(entry.relative, relocationPrefix);
      const destination = path.join(stagedOutput, relocated);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.writeFileSync(destination, contents, { encoding: "utf8", flag: "wx" });
      includedSources.push({
        source: entry.source,
        path: relocated,
        sha256: sha256File(destination),
      });
    }

    excludedSources.sort((left, right) => Buffer.from(left.source).compare(Buffer.from(right.source)));
    const treeProjection = includedSources
      .map((entry) => `${entry.sha256}  ${entry.path}`)
      .join("\n") + "\n";
    const inventory = {
      formatVersion: 1,
      engineId: specification.engineId,
      relocationPrefix,
      treeSha256: crypto.createHash("sha256").update(treeProjection).digest("hex"),
      includedSources,
      excludedSources,
    };
    fs.writeFileSync(
      path.join(stagedOutput, "source-inventory.json"),
      `${JSON.stringify(inventory, null, 2)}\n`,
      { encoding: "utf8", flag: "wx" },
    );
    replaceAtomically(stagedOutput, resolvedOutput, temporaryRoot);
    return inventory;
  } catch (error) {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
    throw error;
  }
}

function runGitApply(upstreamDirectory, patchPath, checkOnly) {
  const argumentsList = ["apply"];
  if (checkOnly) argumentsList.push("--check");
  argumentsList.push("--whitespace=error-all", patchPath);
  const result = childProcess.spawnSync("git", argumentsList, {
    cwd: upstreamDirectory,
    encoding: "utf8",
    env: {
      ...process.env,
      // The production staging directory can live below this worktree. Prevent
      // Git from discovering that repository and silently filtering every
      // patch path as outside the current subdirectory.
      GIT_CEILING_DIRECTORIES: path.dirname(upstreamDirectory),
    },
    timeout: 30_000,
    maxBuffer: 4 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const phase = checkOnly ? "does not apply cleanly" : "failed to apply";
    fail(`${path.basename(patchPath)} ${phase}: ${(result.stderr || result.stdout).trim()}`);
  }
}

function applyMaterializedPatches(upstreamDirectory, patchDirectory, patches) {
  const upstream = path.resolve(upstreamDirectory);
  const directory = path.resolve(patchDirectory);
  const verified = patches.map((patch, index) => {
    if (patch.state !== "materialized") fail(`${patch.file}: patch remains ${patch.state}`);
    if (!/^\d{4}-[a-z0-9-]+\.patch$/.test(patch.file) || path.basename(patch.file) !== patch.file) {
      fail(`patch[${index}] has an unsafe file name`);
    }
    const patchPath = path.join(directory, patch.file);
    let stat;
    try {
      stat = fs.lstatSync(patchPath);
    } catch {
      fail(`missing materialized patch: ${patch.file}`);
    }
    if (!stat.isFile() || stat.isSymbolicLink()) fail(`patch is not a regular file: ${patch.file}`);
    const digest = sha256File(patchPath);
    if (digest !== patch.sha256) {
      fail(`patch SHA-256 mismatch for ${patch.file}: expected ${patch.sha256}, received ${digest}`);
    }
    return patchPath;
  });

  for (const patchPath of verified) {
    runGitApply(upstream, patchPath, true);
    runGitApply(upstream, patchPath, false);
  }
}

function materializeUpstream(archiveDirectory, outputDirectory) {
  const lock = loadAndValidateLock();
  const archives = lock.sources.map((source) => ({
    source,
    path: sourceArchivePath(archiveDirectory, source),
  }));
  for (const archive of archives) validateArchiveEntries(archive.path);

  const resolvedOutput = path.resolve(outputDirectory);
  const parent = path.dirname(resolvedOutput);
  if (resolvedOutput === path.parse(resolvedOutput).root) fail("refusing to replace a filesystem root");
  fs.mkdirSync(parent, { recursive: true });
  const temporaryRoot = fs.mkdtempSync(path.join(parent, ".repost-http-engine-stage-"));
  const stagedOutput = path.join(temporaryRoot, "materialized");
  fs.mkdirSync(stagedOutput);

  try {
    for (const archive of archives) {
      const moduleDirectory = path.join(stagedOutput, archive.source.module);
      fs.mkdirSync(moduleDirectory);
      runJar(["xf", archive.path], moduleDirectory);
    }
    const metadata = {
      format: "repost-http-engine-upstream-stage-v1",
      engineId: lock.engine_id,
      relocationPrefix: lock.relocation_prefix,
      sources: lock.sources,
    };
    fs.writeFileSync(
      path.join(stagedOutput, "source-metadata.json"),
      `${JSON.stringify(metadata, null, 2)}\n`,
      { encoding: "utf8", flag: "wx" },
    );
    const files = listRegularFiles(stagedOutput);
    const manifest = files.map((relative) => (
      `${sha256File(path.join(stagedOutput, relative))}  ${relative}`
    )).join("\n") + "\n";
    fs.writeFileSync(
      path.join(stagedOutput, "upstream-files.sha256"),
      manifest,
      { encoding: "utf8", flag: "wx" },
    );
    replaceAtomically(stagedOutput, resolvedOutput, temporaryRoot);
  } catch (error) {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
    throw error;
  }
  process.stdout.write(`materialized ${archives.length} verified upstream source archives at ${resolvedOutput}\n`);
}

function argumentValue(args, name) {
  const index = args.indexOf(name);
  if (index === -1 || index + 1 >= args.length) fail(`missing ${name}`);
  if (args.indexOf(name, index + 1) !== -1) fail(`duplicate ${name}`);
  return args[index + 1];
}

function checkProductionReady(relocatedSourceDirectory) {
  const lock = loadAndValidateLock();
  const plannedPatchCount = lock.patches.filter((patch) => patch.state === "planned").length;
  if (lock.production_state !== "materialized") {
    fail(`${plannedPatchCount} vendor patches remain planned`);
  }
  const relocatedSource = path.resolve(relocatedSourceDirectory);
  let stat;
  try {
    stat = fs.statSync(relocatedSource);
  } catch {
    fail(`verified relocated engine sources are missing: ${relocatedSource}`);
  }
  if (!stat.isDirectory()) fail(`verified relocated engine sources are not a directory: ${relocatedSource}`);
}

function materializeRelocated(archiveDirectory, outputDirectory) {
  const lock = loadAndValidateLock();
  const plannedPatchCount = lock.patches.filter((patch) => patch.state === "planned").length;
  if (lock.production_state !== "materialized" || plannedPatchCount !== 0) {
    fail(`${plannedPatchCount} vendor patches remain planned`);
  }
  const resolvedOutput = path.resolve(outputDirectory);
  const parent = path.dirname(resolvedOutput);
  fs.mkdirSync(parent, { recursive: true });
  const temporaryRoot = fs.mkdtempSync(path.join(parent, ".repost-http-engine-production-"));
  const upstream = path.join(temporaryRoot, "upstream");
  try {
    materializeUpstream(path.resolve(archiveDirectory), upstream);
    applyMaterializedPatches(
      upstream,
      path.join(vendorDirectory, "patches"),
      lock.patches,
    );
    const inventory = relocatePatchedSources(upstream, resolvedOutput);
    process.stdout.write(
      `relocated ${inventory.includedSources.length} verified engine sources at ${resolvedOutput}\n`,
    );
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

function main(args) {
  if (args[0] === "check-production-ready") {
    if (args.length !== 3) {
      fail("usage: http-engine-vendor.js check-production-ready --relocated-source-dir <dir>");
    }
    checkProductionReady(argumentValue(args, "--relocated-source-dir"));
    return;
  }
  if (args[0] === "materialize-relocated") {
    if (args.length !== 5) {
      fail("usage: http-engine-vendor.js materialize-relocated --archive-dir <dir> --output <dir>");
    }
    materializeRelocated(
      argumentValue(args, "--archive-dir"),
      argumentValue(args, "--output"),
    );
    return;
  }
  if (args[0] !== "materialize-upstream" || args.length !== 5) {
    fail("usage: http-engine-vendor.js materialize-upstream --archive-dir <dir> --output <dir>");
  }
  const archiveDirectory = path.resolve(argumentValue(args, "--archive-dir"));
  const outputDirectory = path.resolve(argumentValue(args, "--output"));
  if (!fs.statSync(archiveDirectory).isDirectory()) fail(`archive directory does not exist: ${archiveDirectory}`);
  materializeUpstream(archiveDirectory, outputDirectory);
}

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`http-engine-vendor: ${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = { applyMaterializedPatches, relocatePatchedSources };
