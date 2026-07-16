"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const directory = __dirname;
const requestSchema = read("generation-request-v1.schema.json");
const responseSchema = read("generation-response-v1.schema.json");

function read(name) {
  return JSON.parse(fs.readFileSync(path.join(directory, name), "utf8"));
}

function resolveRef(root, reference) {
  assert.match(reference, /^#\/\$defs\/[A-Za-z0-9]+$/);
  return root.$defs[reference.slice("#/$defs/".length)];
}

function validate(root, schema, value, location = "$") {
  if (schema.$ref) return validate(root, resolveRef(root, schema.$ref), value, location);
  if (schema.oneOf) {
    const matches = schema.oneOf.filter((candidate) => {
      try { validate(root, candidate, value, location); return true; } catch { return false; }
    });
    assert.equal(matches.length, 1, `${location}: expected exactly one schema branch`);
    return;
  }
  if (Object.hasOwn(schema, "const")) assert.deepEqual(value, schema.const, `${location}: const`);
  if (schema.enum) assert.ok(schema.enum.includes(value), `${location}: enum`);
  if (schema.type === "object") {
    assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${location}: object`);
    for (const key of schema.required || []) assert.ok(Object.hasOwn(value, key), `${location}: missing ${key}`);
    if (schema.additionalProperties === false) {
      for (const key of Object.keys(value)) assert.ok(Object.hasOwn(schema.properties, key), `${location}: unknown ${key}`);
    }
    if (schema.propertyNames?.minLength) {
      for (const key of Object.keys(value)) assert.ok(key.length >= schema.propertyNames.minLength, `${location}: empty key`);
    }
    for (const [key, item] of Object.entries(value)) {
      const child = schema.properties?.[key] || (typeof schema.additionalProperties === "object" ? schema.additionalProperties : null);
      if (child) validate(root, child, item, `${location}.${key}`);
    }
  } else if (schema.type === "array") {
    assert.ok(Array.isArray(value), `${location}: array`);
    if (schema.minItems !== undefined) assert.ok(value.length >= schema.minItems, `${location}: minItems`);
    value.forEach((item, index) => validate(root, schema.items, item, `${location}[${index}]`));
  } else if (schema.type === "string") {
    assert.equal(typeof value, "string", `${location}: string`);
    if (schema.minLength !== undefined) assert.ok(value.length >= schema.minLength, `${location}: minLength`);
    if (schema.pattern) assert.match(value, new RegExp(schema.pattern), `${location}: pattern`);
  } else if (schema.type === "integer") {
    assert.ok(Number.isSafeInteger(value), `${location}: integer`);
    if (schema.minimum !== undefined) assert.ok(value >= schema.minimum, `${location}: minimum`);
  }
}

const request = {
  protocolVersion: 1,
  pluginVersion: "1.0.0",
  engineVersion: "0.9.0",
  runtimeVersion: "1.0.0",
  descriptorVersion: 2,
  schemaPath: "/workspace/repost/schema.repost",
  generators: [{
    generatorName: "javaSdk",
    sourceOutputDirectory: "/workspace/target/generated-sources/repost/javaSdk",
    resourceOutputDirectory: "/workspace/target/generated-resources/repost/javaSdk",
    sourceControlDirectory: "/workspace/target/repost-state/javaSdk/source",
    resourceControlDirectory: "/workspace/target/repost-state/javaSdk/resource",
  }],
  environmentInputs: { PACKAGE_SUFFIX: "internal" },
  buildIdentity: { kind: "MAVEN", groupId: "com.acme", artifactId: "orders" },
  checkMode: "GENERATE",
};

const response = {
  protocolVersion: 1,
  engineVersion: "0.9.0",
  runtimeVersion: "1.0.0",
  descriptorVersion: 2,
  generators: [{
    generatorName: "javaSdk",
    generatorId: "0123456789abcdef",
    sourceRoot: "/workspace/target/generated-sources/repost/javaSdk",
    resourceRoot: "/workspace/target/generated-resources/repost/javaSdk",
    sourceTreeHash: `sha256:${"a".repeat(64)}`,
    resourceTreeHash: `sha256:${"b".repeat(64)}`,
  }],
  diagnostics: [{
    severity: "INFO",
    code: "GENERATED",
    message: "generation complete",
    source: { path: "/workspace/repost/schema.repost", startByte: 0, endByte: 9 },
  }],
};

test("accepts the closed request and response protocol", () => {
  validate(requestSchema, requestSchema, request);
  validate(responseSchema, responseSchema, response);
});

test("rejects unknown and missing request fields", () => {
  assert.throws(() => validate(requestSchema, requestSchema, { ...request, inheritedEnvironment: true }), /unknown/);
  const missing = structuredClone(request);
  delete missing.generators[0].resourceControlDirectory;
  assert.throws(() => validate(requestSchema, requestSchema, missing), /missing resourceControlDirectory/);
});

test("rejects ambiguous build identity and malformed response data", () => {
  const ambiguous = structuredClone(request);
  ambiguous.buildIdentity = {
    kind: "MAVEN",
    groupId: "com.acme",
    artifactId: "orders",
    rootProjectName: "orders",
    projectPath: ":",
  };
  assert.throws(() => validate(requestSchema, requestSchema, ambiguous), /exactly one schema branch/);
  const malformed = structuredClone(response);
  malformed.generators[0].sourceTreeHash = "sha256:not-a-hash";
  assert.throws(() => validate(responseSchema, responseSchema, malformed), /pattern/);
});

test("pins the canonical top-level field order", () => {
  assert.deepEqual(Object.keys(request), requestSchema.required);
  assert.deepEqual(Object.keys(response), responseSchema.required);
});
