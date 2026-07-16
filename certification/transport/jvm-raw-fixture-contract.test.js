"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const contract = require("./jvm-raw-fixture-contract");
const engineContract = require("./jvm-engine-contract");

const nonNetworkGroups = new Set([
  "custom-transport-ordered-duplicate-header-abi-map-adversaries",
  "jackson-nonrecycling-factory-borrowed-executor-redeploy-and-heap-sentinel",
  "single-monotonic-close-budget-wall-jump-self-close-and-interrupt",
  "relocated-agent-span-isolation-and-authoritative-otel-bridge",
]);

function projection(groupId) {
  const group = contract.groups[groupId];
  return {
    barriers: group.requiredBarriers,
    counters: group.requiredObservedCounters,
    endpoints: group.requiredEndpointIds,
    assets: group.requiredAssetIds,
    capabilities: group.requiredCapabilities,
  };
}

test("all 33 raw groups expose an explicit independently falsifiable mechanism contract", () => {
  assert.deepEqual(Object.keys(contract.groups), engineContract.requiredRawFixtureGroups);
  assert.equal(Object.keys(contract.groups).length, 33);
  assert.equal(Object.keys(contract.mechanisms).length, 33);
  assert.equal(Object.values(contract.groups).reduce((total, group) => total + group.requiredBarriers.length, 0), 200);
  assert.equal(Object.values(contract.groups).reduce((total, group) => total + Object.keys(group.requiredObservedCounters).length, 0), 364);
  assert.equal(Object.values(contract.groups).reduce((total, group) => total + group.requiredCapabilities.length, 0), 127);

  for (const [groupId, group] of Object.entries(contract.groups)) {
    assert.ok(group.requiredBarriers.length >= 1, `${groupId}: barrier`);
    assert.ok(Object.keys(group.requiredObservedCounters).length >= 2, `${groupId}: counters`);
    assert.ok(group.requiredCapabilities.length >= 1, `${groupId}: capabilities`);
    assert.equal(new Set(group.requiredBarriers).size, group.requiredBarriers.length, `${groupId}: barriers unique`);
    assert.equal(new Set(Object.keys(group.requiredObservedCounters)).size, Object.keys(group.requiredObservedCounters).length, `${groupId}: counters unique`);
    assert.equal(new Set(group.requiredCapabilities).size, group.requiredCapabilities.length, `${groupId}: capabilities unique`);
    assert.equal(new Set(group.requiredEndpointIds).size, group.requiredEndpointIds.length, `${groupId}: endpoints unique`);
    assert.equal(new Set(group.requiredAssetIds).size, group.requiredAssetIds.length, `${groupId}: assets unique`);

    for (const barrier of group.requiredBarriers) {
      assert.match(barrier, /^[a-z0-9]+(?:-[a-z0-9]+)*$/u, `${groupId}: barrier ${barrier}`);
      assert.ok(group.terminalAssertions.includes(`barrier:${barrier}:released`), `${groupId}: barrier assertion ${barrier}`);
    }
    for (const [counter, value] of Object.entries(group.requiredObservedCounters)) {
      assert.match(counter, /^[a-z][A-Za-z0-9]*$/u, `${groupId}: counter ${counter}`);
      assert.ok(Number.isSafeInteger(value) && value >= 0, `${groupId}: counter value ${counter}`);
      assert.ok(group.terminalAssertions.includes(`counter:${counter}:${value}`), `${groupId}: counter assertion ${counter}`);
    }
    for (const capability of group.requiredCapabilities) {
      assert.match(capability, /^[a-z0-9]+(?:-[a-z0-9]+)*$/u, `${groupId}: capability ${capability}`);
      assert.ok(group.terminalAssertions.includes(`capability:${capability}`), `${groupId}: capability assertion ${capability}`);
    }
    for (const id of [...group.requiredEndpointIds, ...group.requiredAssetIds]) {
      assert.match(id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/u, `${groupId}: descriptor ${id}`);
    }
    if (nonNetworkGroups.has(groupId)) assert.deepEqual(group.requiredEndpointIds, [], `${groupId}: non-network endpoints`);
    else assert.ok(group.requiredEndpointIds.length > 0, `${groupId}: advertised endpoint`);
  }

  assert.doesNotMatch(
    JSON.stringify(contract.mechanisms),
    /[<>*]|per-case|scenario-origin|scenario-proxy|h2-rst-frame|response-budget-plus-one/u,
  );
});

test("the exported validator oracle is deeply immutable", () => {
  const groupId = engineContract.requiredRawFixtureGroups[0];
  const originalBarrier = contract.groups[groupId].requiredBarriers[0];
  const originalConsumer = contract.productionConsumers[groupId][0];
  assert.throws(() => contract.groups[groupId].requiredBarriers.push("forged"), TypeError);
  assert.throws(() => { contract.groups[groupId].requiredObservedCounters.forged = 1; }, TypeError);
  assert.throws(() => contract.productionConsumers[groupId].push("forged"), TypeError);
  assert.throws(() => contract.protocol.scenarioSchema.endpointRoles.push("FORGED"), TypeError);
  assert.equal(contract.groups[groupId].requiredBarriers[0], originalBarrier);
  assert.equal(contract.productionConsumers[groupId][0], originalConsumer);
  const fixture = contract.fixtureCases()[0];
  fixture.expected.requiredBarriers.push("caller-owned-copy");
  assert.equal(contract.groups[groupId].requiredBarriers.includes("caller-owned-copy"), false);
});

test("the first H1/H2 family is frozen exactly from the executable matrix", () => {
  assert.deepEqual(projection("h1-h2-eight-vs-nine-informational-responses"), {
    barriers: ["eighth-informational", "ninth-informational", "h2-reuse-probe"],
    counters: { h1InformationalResponsesObserved: 17, h2InformationalResponsesObserved: 17, h1FinalResponsesAccepted: 1, h2FinalResponsesAccepted: 1, h1NineResponseConnectionAbortsObserved: 1, h2NineResponseStreamResetsObserved: 1, h2SameSessionReusesObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"],
    assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["http1-informational", "tls-alpn-h2", "informational-budget", "h2-stream-reuse"],
  });
  assert.deepEqual(projection("h1-combined-header-trailer-bounds-framing-smuggling-and-no-reuse"), {
    barriers: ["exact-metadata", "overflow-metadata", "poison-close", "clean-followup"],
    counters: { exactMetadataBytesObserved: 65536, overflowMetadataBytesObserved: 65537, exactMetadataFieldSetsMatchedObserved: 1, teClFaultRejectionsObserved: 1, obsFoldFaultRejectionsObserved: 1, contentLengthFaultRejectionsObserved: 1, chunkFaultRejectionsObserved: 1, extensionFaultRejectionsObserved: 1, trailerFaultRejectionsObserved: 1, poisonedH1ReusesObserved: 0, freshCleanConnectionsObserved: 1, h2FaultStreamResetsObserved: 6, survivingH2SessionsObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"],
    assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["http1-strict-framing", "tls-alpn-h2", "combined-metadata-budget", "smuggling-rejection"],
  });
  assert.deepEqual(projection("outbound-h1-h2-content-length-field-order-cardinality-and-no-transfer-encoding"), {
    barriers: ["request-headers-captured", "request-body-complete"],
    counters: { h1ZeroBodyBytesObserved: 0, h1OneByteBodyBytesObserved: 1, h1MaximumBodyBytesObserved: 1048576, h2ZeroBodyBytesObserved: 0, h2OneByteBodyBytesObserved: 1, h2MaximumBodyBytesObserved: 1048576, canonicalContentLengthFieldsObserved: 6, transferEncodingFieldsObserved: 0, duplicateHostFieldsObserved: 0, duplicateContentLengthFieldsObserved: 0, h1FieldOrderMatchesObserved: 3, h2FieldOrderMatchesObserved: 3, singlePublicationsObserved: 6 },
    endpoints: ["origin-h1", "origin-h2"],
    assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["http1-request-capture", "tls-alpn-h2", "canonical-content-length", "ordered-header-fields"],
  });
  const utf8 = projection("h1-h2-utf8-report-boundaries-and-malformed-input");
  assert.equal(utf8.barriers.length, 13);
  assert.deepEqual(utf8.barriers.slice(0, 4), ["split-multibyte-utf8-bytes-written", "split-multibyte-client-abort-observed", "overlong-utf8-bytes-written", "overlong-client-abort-observed"]);
  assert.equal(utf8.barriers.at(-1), "h2-reuse-probe");

  const response = projection("budgeted-response-consumer-exact-plus-one-chunk-cancel-and-peer-survival");
  assert.equal(response.barriers.length, 67);
  assert.deepEqual(response.barriers.slice(0, 3), ["chunk-ready-1", "chunk-ready-2", "chunk-ready-3"]);
  assert.deepEqual(response.barriers.slice(-4), ["chunk-ready-64", "plus-one-ready", "cancel-observed", "reuse-probe"]);
  assert.deepEqual(response.counters, { peakChunkBytesObserved: 16384, chunkBoundaryEventsObserved: 128, h1ExactRawBytesObserved: 1048576, h2ExactRawBytesObserved: 1048576, h1PlusOneRejectionsObserved: 1, h2PlusOneRejectionsObserved: 1, nearMaximumGzipCasesObserved: 2, nearMaximumGzipDecodedBytesObserved: 1048576, gzipLimitMatchesObserved: 2, duplicatedResponseBytesObserved: 0, upstreamCancelCallsObserved: 1, upstreamCloseCallsObserved: 1, h2StreamResetsObserved: 1, h2SameSessionReusesObserved: 1 });
});

test("representative later mechanism families retain exact descriptors and observations", () => {
  assert.deepEqual(projection("h2-queued-cancel-zero-publication-and-stream-local-peer-reuse").endpoints, ["origin-h2"]);
  assert.deepEqual(projection("h2-cold-burst-session-count-peer-cap-scale-out-ttl-goaway-drain").barriers, ["cold-burst", "peer-cap-applied", "ttl-expired", "drain-complete"]);
  assert.deepEqual(projection("happy-eyeballs-dual-stack-success-failure-cancel-proxy-and-no-leak").endpoints, ["origin-v6", "alternate-v4", "proxy-v6", "proxy-v4"]);
  assert.deepEqual(projection("dns-direct-origin-vs-proxy-only-split-horizon-sni-and-verification").counters, {
    directOriginResolverCallsObserved: 1,
    directProxyResolverCallsObserved: 0,
    proxiedProxyResolverCallsObserved: 1,
    proxiedOriginResolverCallsObserved: 0,
    connectAuthorityMatchesObserved: 1,
    originSniMatchesObserved: 1,
    hostnameDecisionsObserved: 1,
    resolverTrapHitsObserved: 0,
  });
  assert.deepEqual(projection("tls-full-async-wrap-unwrap-context-session-and-global-default-isolation").assets, ["ca-a", "ca-b", "truststore-a", "truststore-b", "keystore-a", "keystore-b"]);
  assert.deepEqual(projection("proxy-connect-2xx-bogus-content-length-transfer-encoding-and-coalesced-tls").barriers, ["connect-header-written", "coalesced-tail-written", "tls-tail-consumed"]);
  assert.deepEqual(projection("custom-transport-ordered-duplicate-header-abi-map-adversaries").endpoints, []);
  assert.deepEqual(projection("h1-h2-plaintext-zeroization-idle-pool-rotation-close-and-benchmark").barriers, ["sentinel-published", "connection-idle", "rotation-complete", "runtime-closed", "heap-probe-complete"]);
  assert.deepEqual(projection("relocated-agent-span-isolation-and-authoritative-otel-bridge").endpoints, []);
  assert.deepEqual(projection("otel-bridge-traceparent-only-per-attempt-and-ambient-propagator-isolation").barriers, ["attempt-header-captured-1", "attempt-header-captured-2", "retry-issued", "operation-ended"]);
});

test("RST, GOAWAY, SETTINGS, and HPACK projections cannot hide a missing variant", () => {
  assert.deepEqual(projection("h2-rst-refused-cancel-protocol-error-pre-postcommit-retry-exhaustion"), {
    barriers: ["rst-refused-stream-precommit", "rst-refused-stream-postcommit", "rst-cancel-precommit", "rst-cancel-postcommit", "rst-protocol-error-precommit", "rst-protocol-error-postcommit", "retry-observed", "reuse-probe"],
    counters: {
      refusedStreamPrecommitResetsObserved: 1, refusedStreamPrecommitAttemptsObserved: 2, refusedStreamPrecommitHeadersObserved: 2, refusedStreamPrecommitDataObserved: 0, refusedStreamPrecommitUnresolvedObserved: 0,
      refusedStreamPostcommitResetsObserved: 1, refusedStreamPostcommitAttemptsObserved: 2, refusedStreamPostcommitHeadersObserved: 2, refusedStreamPostcommitDataObserved: 1, refusedStreamPostcommitUnresolvedObserved: 0,
      cancelPrecommitResetsObserved: 1, cancelPrecommitAttemptsObserved: 2, cancelPrecommitHeadersObserved: 2, cancelPrecommitDataObserved: 0, cancelPrecommitUnresolvedObserved: 0,
      cancelPostcommitResetsObserved: 1, cancelPostcommitAttemptsObserved: 1, cancelPostcommitHeadersObserved: 1, cancelPostcommitDataObserved: 1, cancelPostcommitUnresolvedObserved: 0,
      protocolErrorPrecommitResetsObserved: 1, protocolErrorPrecommitAttemptsObserved: 2, protocolErrorPrecommitHeadersObserved: 2, protocolErrorPrecommitDataObserved: 0, protocolErrorPrecommitUnresolvedObserved: 0,
      protocolErrorPostcommitResetsObserved: 1, protocolErrorPostcommitAttemptsObserved: 1, protocolErrorPostcommitHeadersObserved: 1, protocolErrorPostcommitDataObserved: 1, protocolErrorPostcommitUnresolvedObserved: 0,
      successfulRetryVariantsObserved: 3, exhaustedVariantsObserved: 3, stableIdempotencyKeysObserved: 1, survivingPeerSessionsObserved: 1,
    },
    endpoints: ["origin-h2"], assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["tls-alpn-h2", "rst-stream-matrix", "retry-exhaustion", "session-survival"],
  });
  assert.deepEqual(projection("h2-goaway-last-stream-reserved-bit-queued-active-and-replacement"), {
    barriers: ["streams-established", "goaway-sent", "replacement-ready"],
    counters: { goawayFramesObserved: 2, lowerStreamCompletionsObserved: 1, higherStreamReplaysObserved: 1, replacementSessionsObserved: 1, queuedCommandCancellationsObserved: 1, duplicateReleasesObserved: 0, operationsSettledOnceObserved: 3 },
    endpoints: ["origin-h2"], assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["tls-alpn-h2", "goaway-last-stream", "replacement-session"],
  });
  assert.deepEqual(projection("h2-dynamic-settings-zero-positive-lower-active-huge-and-invalid"), {
    barriers: ["settings-zero", "publication-paused", "settings-positive", "settings-invalid"],
    counters: { zeroSettingsFramesObserved: 1, zeroSettingsAcksObserved: 1, positiveSettingsFramesObserved: 1, positiveSettingsAcksObserved: 1, lowerActiveSettingsFramesObserved: 1, lowerActiveSettingsAcksObserved: 1, hugeUnsignedSettingsFramesObserved: 1, hugeUnsignedSettingsAcksObserved: 1, invalidSettingsFramesObserved: 1, invalidSettingsAcksObserved: 0, publicationsWhileZeroObserved: 0, fifoResumesObserved: 1, activeStreamAbortsAfterDecreaseObserved: 0, invalidSettingsConnectionClosesObserved: 1 },
    endpoints: ["origin-h2"], assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["tls-alpn-h2", "dynamic-settings", "unsigned-peer-limits"],
  });
  assert.deepEqual(projection("h2-secret-never-index-and-huge-peer-table-retention-cap"), {
    barriers: ["header-block-captured", "secret-rotation-complete"],
    counters: { neverIndexedSecretFieldsObserved: 2, withoutIndexingVolatileFieldsObserved: 2, indexedSecretFieldsObserved: 0, secretTableHitsObserved: 0, peakEncoderTableBytesObserved: 16384, retainedSecretsObserved: 0 },
    endpoints: ["origin-h2"], assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["tls-alpn-h2", "hpack-inspection", "secret-never-index"],
  });
});

test("FIFO, credentials, close, and hostname projections are exact", () => {
  assert.deepEqual(projection("fifo-establishment-sponsor-failure-cancel-transfer-and-no-fanout"), {
    barriers: ["sponsor-selected", "sponsor-cancelled", "successor-selected", "zero-waiter-cancel"],
    counters: { sponsoredEstablishmentsObserved: 1, cancelledSponsorEstablishmentsObserved: 1, successorEstablishmentsObserved: 1, sponsorTransfersObserved: 1, zeroWaiterCancellationsObserved: 1, waiterFailureFanoutsObserved: 0, preassignmentPublicationsObserved: 0, abandonedEstablishmentClosesObserved: 1, independentWaiterSettlementSetsMatchedObserved: 1, unsettledWaitersObserved: 0 },
    endpoints: ["origin"], assets: [],
    capabilities: ["fifo-establishment", "sponsor-transfer", "failure-isolation"],
  });
  assert.deepEqual(projection("proxy-credential-validation-encoding-boundary-rotation-and-raw-observation"), {
    barriers: ["credential-returned", "connect-auth-captured", "establishment-closed"],
    counters: { minimumUsernameAcceptedObserved: 1, maximumUsernameAcceptedObserved: 1, colonUsernameRejectedObserved: 1, nonAsciiUsernameRejectedObserved: 1, emptyUsernameRejectedObserved: 1, minimumPasswordAcceptedObserved: 1, maximumPasswordAcceptedObserved: 1, colonPasswordAcceptedObserved: 1, allSpacePasswordRejectedObserved: 1, nonPrintablePasswordRejectedObserved: 1, decodedMinimumUsernameBytesObserved: 1, decodedMaximumUsernameBytesObserved: 256, decodedMinimumPasswordBytesObserved: 1, decodedMaximumPasswordBytesObserved: 4096, paddedBase64FormsMatchedObserved: 4, malformedBase64FormsAcceptedObserved: 0, invalidCredentialConnectsObserved: 0, credentialRotationsObserved: 2, staleCredentialReusesObserved: 0, callerCredentialMutationsObserved: 0, retainedSdkCredentialCopiesObserved: 0 },
    endpoints: ["origin-tls", "raw-connect-proxy"], assets: ["fixture-ca", "fixture-keystore"],
    capabilities: ["raw-connect-proxy", "basic-auth-capture", "credential-boundaries", "credential-rotation"],
  });
  assert.deepEqual(projection("single-monotonic-close-budget-wall-jump-self-close-and-interrupt"), {
    barriers: ["callback-entered", "close-linearized", "wall-jumped", "callback-released"],
    counters: { monotonicCloseMillisecondsObserved: 5000, wallClockDecisionsObserved: 0, currentCallbackSelfWaitsObserved: 0, interruptCorruptionsObserved: 0, closeCompletionsObserved: 1, finalRetainedBytesObserved: 0, finalInFlightOperationsObserved: 0 },
    endpoints: [], assets: [],
    capabilities: ["fake-monotonic-clock", "callback-identity", "self-close", "interrupt-preservation"],
  });
  assert.deepEqual(projection("builtin-hostname-verification-non-overridable-jvm-and-native"), {
    barriers: ["tls-handshake", "hostname-decision", "http-request-captured"],
    counters: { validOriginSuccessesObserved: 1, hostnameMismatchFailuresObserved: 1, mismatchHttpPublicationsObserved: 0, customVerifierCallsObserved: 0, jvmExecutionsObserved: 1, nativeExecutionsObserved: 1, authorityHostnameDecisionsObserved: 2 },
    endpoints: ["origin-tls-valid", "origin-tls-mismatch"], assets: ["fixture-ca", "valid-server-certificate", "mismatch-server-certificate", "valid-keystore", "mismatch-keystore"],
    capabilities: ["builtin-hostname-verification", "authority-connect-separation", "jvm-execution", "native-execution"],
  });
});
