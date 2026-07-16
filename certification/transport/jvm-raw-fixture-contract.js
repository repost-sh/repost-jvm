"use strict";

const engineContract = require("./jvm-engine-contract");

const actions = Object.freeze([
  "load",
  "start",
  "awaitBarrier",
  "releaseBarrier",
  "advanceMonotonicTime",
  "snapshot",
  "reset",
  "close",
]);

const evidenceFields = Object.freeze([
  "schema",
  "protocolVersion",
  "groupId",
  "fixtureCaseId",
  "outcome",
  "skipped",
  "controls",
  "barriers",
  "observedCounters",
  "state",
  "endpointIds",
  "assetIds",
  "capabilities",
  "terminalAssertions",
]);

function caseId(groupId) {
  return `jvm-raw-${groupId}`;
}

const productionConsumers = Object.freeze({
  "h2-queued-cancel-zero-publication-and-stream-local-peer-reuse": ["jvm-engine-h2-stream-local-terminalization"],
  "h2-rst-refused-cancel-protocol-error-pre-postcommit-retry-exhaustion": ["jvm-engine-h2-rst-delivery-retry-and-exhaustion"],
  "h2-goaway-last-stream-reserved-bit-queued-active-and-replacement": ["jvm-engine-h2-goaway-classification-and-replay"],
  "h2-dynamic-settings-zero-positive-lower-active-huge-and-invalid": ["jvm-engine-h2-unsigned-dynamic-settings"],
  "h1-h2-eight-vs-nine-informational-responses": ["jvm-engine-informational-header-and-trailer-budget"],
  "h1-combined-header-trailer-bounds-framing-smuggling-and-no-reuse": ["jvm-engine-informational-header-and-trailer-budget", "jvm-engine-h1-framing-smuggling-and-h2-connection-header-rejection"],
  "h2-secret-never-index-and-huge-peer-table-retention-cap": ["jvm-engine-hpack-secret-and-retention-bounds"],
  "outbound-base-uri-request-target-h1-line-h2-header-list-continuation-and-peer-setting": ["jvm-engine-bounded-outbound-request-target-and-header-blocks"],
  "outbound-h1-h2-content-length-field-order-cardinality-and-no-transfer-encoding": ["jvm-engine-exact-outbound-h1-h2-framing"],
  "h1-h2-utf8-report-boundaries-and-malformed-input": ["jvm-engine-strict-utf8-report"],
  "h2-cold-burst-session-count-peer-cap-scale-out-ttl-goaway-drain": ["jvm-engine-capacity-aware-pool-cold-burst-settings-and-drain"],
  "fifo-establishment-sponsor-failure-cancel-transfer-and-no-fanout": ["jvm-engine-fifo-establishment-sponsor-and-cancel-transfer"],
  "happy-eyeballs-dual-stack-success-failure-cancel-proxy-and-no-leak": ["jvm-engine-bounded-happy-eyeballs"],
  "happy-eyeballs-first-tcp-tls-stall-or-bad-cert-alternate-ready-direct-and-proxy": ["jvm-engine-bounded-happy-eyeballs"],
  "dns-isolation-saturation-cache-deadline-and-close": ["jvm-engine-bounded-dns-isolation-cache-and-deadline"],
  "dns-direct-origin-vs-proxy-only-split-horizon-sni-and-verification": ["jvm-engine-direct-vs-proxy-dns-routing"],
  "tls-delegated-task-isolation-saturation-deadline-and-close": ["jvm-engine-bounded-tls-delegated-task-isolation"],
  "tls-full-async-wrap-unwrap-context-session-and-global-default-isolation": ["jvm-engine-tls-context-session-ownership-isolation"],
  "tls-lazy-provider-init-and-suite-probe-blocking-startup-deadline": ["jvm-engine-bounded-tls-delegated-task-isolation", "jvm-engine-tls-context-session-ownership-isolation"],
  "proxy-credential-isolation-saturation-deadline-wipe-and-close": ["jvm-engine-bounded-proxy-credential-isolation"],
  "proxy-credential-validation-encoding-boundary-rotation-and-raw-observation": ["jvm-engine-proxy-credential-validation-encoding-and-rotation"],
  "proxy-connect-informational-framing-body-deadline-cancel-retention-and-no-reuse": ["jvm-engine-hardened-proxy-connect-response-path"],
  "proxy-connect-2xx-bogus-content-length-transfer-encoding-and-coalesced-tls": ["jvm-engine-hardened-proxy-connect-response-path"],
  "proxy-connect-vs-tunneled-origin-field-and-sentinel-separation": ["jvm-engine-hardened-proxy-connect-response-path"],
  "custom-transport-ordered-duplicate-header-abi-map-adversaries": ["jvm-engine-custom-transport-ordered-header-field-abi"],
  "budgeted-response-consumer-exact-plus-one-chunk-cancel-and-peer-survival": ["jvm-engine-budgeted-response-consumer"],
  "idle-extra-async-reclaim-current-reject-and-later-probe-success": ["jvm-engine-shared-budget-reservations", "jvm-engine-capacity-aware-pool-cold-burst-settings-and-drain"],
  "h1-h2-plaintext-zeroization-idle-pool-rotation-close-and-benchmark": ["jvm-engine-plaintext-zeroization"],
  "jackson-nonrecycling-factory-borrowed-executor-redeploy-and-heap-sentinel": ["jvm-engine-jackson-nonrecycling-lifecycle"],
  "single-monotonic-close-budget-wall-jump-self-close-and-interrupt": ["jvm-engine-single-monotonic-close-budget", "jvm-engine-provider-worker-reentrant-close"],
  "builtin-hostname-verification-non-overridable-jvm-and-native": ["jvm-engine-public-transport-options-and-builtin-hostname-verification"],
  "relocated-agent-span-isolation-and-authoritative-otel-bridge": ["jvm-engine-authoritative-otel-and-agent-isolation"],
  "otel-bridge-traceparent-only-per-attempt-and-ambient-propagator-isolation": ["jvm-engine-authoritative-otel-and-agent-isolation"],
});

const tlsAssets = Object.freeze(["fixture-ca", "fixture-keystore"]);
const sequence = (prefix, count) => Array.from({ length: count }, (_, index) => `${prefix}-${index + 1}`);
const mechanism = ({ barriers, counters, endpoints = [], assets = [], capabilities }) => Object.freeze({
  requiredBarriers: Object.freeze([...barriers]),
  requiredObservedCounters: Object.freeze({ ...counters }),
  requiredEndpointIds: Object.freeze([...endpoints]),
  requiredAssetIds: Object.freeze([...assets]),
  requiredCapabilities: Object.freeze([...capabilities]),
});

const mechanisms = Object.freeze({
  "h2-queued-cancel-zero-publication-and-stream-local-peer-reuse": mechanism({
    barriers: ["active-stream", "cancel-linearized", "reuse-probe"],
    counters: { tlsSessionsObserved: 1, cancelledHeadersObserved: 0, cancelledDataFramesObserved: 0, peerSuccessesObserved: 2, sameSessionReusesObserved: 1, cancelledStreamIdsAssigned: 0 },
    endpoints: ["origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "queued-cancel", "stream-local-reuse"],
  }),
  "h2-rst-refused-cancel-protocol-error-pre-postcommit-retry-exhaustion": mechanism({
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
    endpoints: ["origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "rst-stream-matrix", "retry-exhaustion", "session-survival"],
  }),
  "h2-goaway-last-stream-reserved-bit-queued-active-and-replacement": mechanism({
    barriers: ["streams-established", "goaway-sent", "replacement-ready"],
    counters: { goawayFramesObserved: 2, lowerStreamCompletionsObserved: 1, higherStreamReplaysObserved: 1, replacementSessionsObserved: 1, queuedCommandCancellationsObserved: 1, duplicateReleasesObserved: 0, operationsSettledOnceObserved: 3 },
    endpoints: ["origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "goaway-last-stream", "replacement-session"],
  }),
  "h2-dynamic-settings-zero-positive-lower-active-huge-and-invalid": mechanism({
    barriers: ["settings-zero", "publication-paused", "settings-positive", "settings-invalid"],
    counters: { zeroSettingsFramesObserved: 1, zeroSettingsAcksObserved: 1, positiveSettingsFramesObserved: 1, positiveSettingsAcksObserved: 1, lowerActiveSettingsFramesObserved: 1, lowerActiveSettingsAcksObserved: 1, hugeUnsignedSettingsFramesObserved: 1, hugeUnsignedSettingsAcksObserved: 1, invalidSettingsFramesObserved: 1, invalidSettingsAcksObserved: 0, publicationsWhileZeroObserved: 0, fifoResumesObserved: 1, activeStreamAbortsAfterDecreaseObserved: 0, invalidSettingsConnectionClosesObserved: 1 },
    endpoints: ["origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "dynamic-settings", "unsigned-peer-limits"],
  }),
  "h1-h2-eight-vs-nine-informational-responses": mechanism({
    barriers: ["eighth-informational", "ninth-informational", "h2-reuse-probe"],
    counters: { h1InformationalResponsesObserved: 17, h2InformationalResponsesObserved: 17, h1FinalResponsesAccepted: 1, h2FinalResponsesAccepted: 1, h1NineResponseConnectionAbortsObserved: 1, h2NineResponseStreamResetsObserved: 1, h2SameSessionReusesObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["http1-informational", "tls-alpn-h2", "informational-budget", "h2-stream-reuse"],
  }),
  "h1-combined-header-trailer-bounds-framing-smuggling-and-no-reuse": mechanism({
    barriers: ["exact-metadata", "overflow-metadata", "poison-close", "clean-followup"],
    counters: { exactMetadataBytesObserved: 65536, overflowMetadataBytesObserved: 65537, exactMetadataFieldSetsMatchedObserved: 1, teClFaultRejectionsObserved: 1, obsFoldFaultRejectionsObserved: 1, contentLengthFaultRejectionsObserved: 1, chunkFaultRejectionsObserved: 1, extensionFaultRejectionsObserved: 1, trailerFaultRejectionsObserved: 1, poisonedH1ReusesObserved: 0, freshCleanConnectionsObserved: 1, h2FaultStreamResetsObserved: 6, survivingH2SessionsObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["http1-strict-framing", "tls-alpn-h2", "combined-metadata-budget", "smuggling-rejection"],
  }),
  "h2-secret-never-index-and-huge-peer-table-retention-cap": mechanism({
    barriers: ["header-block-captured", "secret-rotation-complete"],
    counters: { neverIndexedSecretFieldsObserved: 2, withoutIndexingVolatileFieldsObserved: 2, indexedSecretFieldsObserved: 0, secretTableHitsObserved: 0, peakEncoderTableBytesObserved: 16384, retainedSecretsObserved: 0 },
    endpoints: ["origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "hpack-inspection", "secret-never-index"],
  }),
  "outbound-base-uri-request-target-h1-line-h2-header-list-continuation-and-peer-setting": mechanism({
    barriers: ["peer-header-cap-zero", "exact-request-captured", "peer-header-cap-increased"],
    counters: { exactUriBoundaryMatchesObserved: 1, exactTargetBoundaryMatchesObserved: 1, exactRequestLineBoundaryMatchesObserved: 1, exactHeaderListBoundaryMatchesObserved: 1, exactContinuationBoundaryMatchesObserved: 1, exactPeerHeaderListBoundaryMatchesObserved: 1, uriPlusOneWirePublicationsObserved: 0, targetPlusOneWirePublicationsObserved: 0, requestLinePlusOneWirePublicationsObserved: 0, headerListPlusOneWirePublicationsObserved: 0, peakContinuationFramesObserved: 2, thirdContinuationFramesObserved: 0, streamsWhilePeerCapZeroObserved: 0, fifoResumesObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["http1-request-capture", "tls-alpn-h2", "request-target-bounds", "continuation-bounds"],
  }),
  "outbound-h1-h2-content-length-field-order-cardinality-and-no-transfer-encoding": mechanism({
    barriers: ["request-headers-captured", "request-body-complete"],
    counters: { h1ZeroBodyBytesObserved: 0, h1OneByteBodyBytesObserved: 1, h1MaximumBodyBytesObserved: 1048576, h2ZeroBodyBytesObserved: 0, h2OneByteBodyBytesObserved: 1, h2MaximumBodyBytesObserved: 1048576, canonicalContentLengthFieldsObserved: 6, transferEncodingFieldsObserved: 0, duplicateHostFieldsObserved: 0, duplicateContentLengthFieldsObserved: 0, h1FieldOrderMatchesObserved: 3, h2FieldOrderMatchesObserved: 3, singlePublicationsObserved: 6 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["http1-request-capture", "tls-alpn-h2", "canonical-content-length", "ordered-header-fields"],
  }),
  "h1-h2-utf8-report-boundaries-and-malformed-input": mechanism({
    barriers: [...["split-multibyte", "overlong", "truncated", "control", "del", "non-scalar"].flatMap((name) => [`${name}-utf8-bytes-written`, `${name}-client-abort-observed`]), "h2-reuse-probe"],
    counters: { splitMultibyteRawLogicalMatchesObserved: 2, overlongRawLogicalMatchesObserved: 2, truncatedRawLogicalMatchesObserved: 2, controlRawLogicalMatchesObserved: 2, delRawLogicalMatchesObserved: 2, nonScalarRawLogicalMatchesObserved: 2, overlongAcceptedObserved: 0, truncatedAcceptedObserved: 0, controlAcceptedObserved: 0, delAcceptedObserved: 0, nonScalarAcceptedObserved: 0, h1MalformedConnectionClosesObserved: 5, h2MalformedStreamResetsObserved: 5, h2SameSessionReusesObserved: 1, replacementDecodesObserved: 0 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["http1-raw-utf8", "tls-alpn-h2", "scalar-valid-utf8", "stream-local-abort"],
  }),
  "h2-cold-burst-session-count-peer-cap-scale-out-ttl-goaway-drain": mechanism({
    barriers: ["cold-burst", "peer-cap-applied", "ttl-expired", "drain-complete"],
    counters: { peerCapZeroAppliedObserved: 1, peerCapOneAppliedObserved: 1, peerCapTwoAppliedObserved: 1, peerCapHundredAppliedObserved: 1, peerCapHugeAppliedObserved: 1, capZeroPeakFormulaMatchesObserved: 1, capOnePeakFormulaMatchesObserved: 1, capTwoPeakFormulaMatchesObserved: 1, capHundredPeakFormulaMatchesObserved: 1, capHugePeakFormulaMatchesObserved: 1, fifoAssignmentViolationsObserved: 0, goawayReplacementsObserved: 1, ttlExpirySweepsObserved: 1, physicalConnectionClosesObserved: 1, residualConnectionsObserved: 0 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["tls-alpn-h2", "pool-scale-out", "fake-monotonic-ttl", "goaway-drain"],
  }),
  "fifo-establishment-sponsor-failure-cancel-transfer-and-no-fanout": mechanism({
    barriers: ["sponsor-selected", "sponsor-cancelled", "successor-selected", "zero-waiter-cancel"],
    counters: { sponsoredEstablishmentsObserved: 1, cancelledSponsorEstablishmentsObserved: 1, successorEstablishmentsObserved: 1, sponsorTransfersObserved: 1, zeroWaiterCancellationsObserved: 1, waiterFailureFanoutsObserved: 0, preassignmentPublicationsObserved: 0, abandonedEstablishmentClosesObserved: 1, independentWaiterSettlementSetsMatchedObserved: 1, unsettledWaitersObserved: 0 },
    endpoints: ["origin"],
    capabilities: ["fifo-establishment", "sponsor-transfer", "failure-isolation"],
  }),
  "happy-eyeballs-dual-stack-success-failure-cancel-proxy-and-no-leak": mechanism({
    barriers: ["candidate-1-started", "cadence-due", "candidate-2-started", "winner-ready"],
    counters: { blackholeThenSuccessScenariosObserved: 1, reverseFamilySuccessScenariosObserved: 1, bothFailScenariosObserved: 1, cancelScenariosObserved: 1, closeScenariosObserved: 1, peakCandidatesObserved: 2, ipv4WinnersObserved: 1, ipv6WinnersObserved: 1, retainedLoserSocketsObserved: 0, retainedLoserSlicesObserved: 0, sharedDeadlinesObserved: 5, peakCandidateToConnectionRatioObserved: 2 },
    endpoints: ["origin-v6", "alternate-v4", "proxy-v6", "proxy-v4"], assets: tlsAssets,
    capabilities: ["dual-stack-loopback", "full-readiness-race", "proxy-candidates", "bounded-cadence"],
  }),
  "happy-eyeballs-first-tcp-tls-stall-or-bad-cert-alternate-ready-direct-and-proxy": mechanism({
    barriers: ["first-tcp-connected", "first-tls-stalled", "alternate-ready", "loser-destroyed"],
    counters: { directTlsStallAlternateWinsObserved: 1, directBadCertificateAlternateWinsObserved: 1, proxyTlsStallAlternateWinsObserved: 1, proxyBadCertificateAlternateWinsObserved: 1, peakTcpAcceptsPerScenarioObserved: 2, firstCandidateWinsObserved: 0, alternateWinsObserved: 4, credentialProviderCallsObserved: 2, leakedCandidateResourcesObserved: 0 },
    endpoints: ["first-origin-tls", "alternate-origin-tls", "direct-proxy", "alternate-proxy"], assets: tlsAssets,
    capabilities: ["tls-readiness-race", "bad-certificate-failover", "proxy-candidates", "loser-destruction"],
  }),
  "dns-isolation-saturation-cache-deadline-and-close": mechanism({
    barriers: ["resolver-entered", "dns-lane-saturated", "deadline-settled", "resolver-released"],
    counters: { positiveResolverCallsObserved: 1, positiveCacheHitsObserved: 1, negativeResolverCallsObserved: 1, negativeCacheHitsObserved: 1, arbitraryErrorResolverCallsObserved: 1, arbitraryErrorCacheHitsObserved: 0, deadlineResolverCallsObserved: 1, closeResolverCallsObserved: 1, peakActiveResolversObserved: 2, resolverQueueBoundMatchesObserved: 1, callerThreadResolverCallbacksObserved: 0, reactorThreadResolverCallbacksObserved: 0, lateResolverResultsAcceptedObserved: 0, reactorHeartbeatsObserved: 1, residualResolverBoundMatchesObserved: 1, unboundedResolverAdmissionsObserved: 0 },
    endpoints: ["origin"],
    capabilities: ["scripted-dns-resolver", "bounded-dns-lane", "positive-negative-cache", "late-result-discard"],
  }),
  "dns-direct-origin-vs-proxy-only-split-horizon-sni-and-verification": mechanism({
    barriers: ["direct-resolved", "proxy-resolved", "connect-captured", "sni-captured"],
    counters: { directOriginResolverCallsObserved: 1, directProxyResolverCallsObserved: 0, proxiedProxyResolverCallsObserved: 1, proxiedOriginResolverCallsObserved: 0, connectAuthorityMatchesObserved: 1, originSniMatchesObserved: 1, hostnameDecisionsObserved: 1, resolverTrapHitsObserved: 0 },
    endpoints: ["origin-tls", "connect-proxy", "resolver-trap"], assets: tlsAssets,
    capabilities: ["split-horizon-dns", "connect-proxy", "sni-capture", "hostname-verification"],
  }),
  "tls-delegated-task-isolation-saturation-deadline-and-close": mechanism({
    barriers: ["tls-provider-engine", "tls-provider-config", "tls-provider-session", "tls-provider-wrap", "tls-provider-unwrap", "tls-provider-verification", "unrelated-session-complete", "deadline-settled"],
    counters: { engineProviderCallsObserved: 1, configProviderCallsObserved: 1, sessionProviderCallsObserved: 1, wrapProviderCallsObserved: 1, unwrapProviderCallsObserved: 1, verificationProviderCallsObserved: 1, tlsWorkerBoundMatchesObserved: 1, tlsQueueBoundMatchesObserved: 1, reactorProviderCallsObserved: 0, unrelatedSessionCompletionsObserved: 1, deadlineSettlementsObserved: 1, lateProviderResultsAcceptedObserved: 0, blockedControlSessionsObserved: 0, residualTlsBoundMatchesObserved: 1 },
    endpoints: ["blocked-origin-tls", "control-origin-tls"], assets: tlsAssets,
    capabilities: ["tls-provider-gates", "bounded-tls-lane", "deadline-isolation", "session-progress"],
  }),
  "tls-full-async-wrap-unwrap-context-session-and-global-default-isolation": mechanism({
    barriers: ["lazy-context-init", "wrap-unwrap", "session-invalidation", "contexts-closed"],
    counters: { constructionProviderCallsObserved: 0, lazyRuntimeInitializationsObserved: 2, wrapUnwrapCyclesObserved: 1, globalDefaultReadsObserved: 0, globalDefaultMutationsObserved: 0, crossRuntimeSessionReusesObserved: 0, ownedSessionInvalidationsObserved: 1, borrowedContextMutationsObserved: 0, contextsClosedObserved: 2 },
    endpoints: ["origin-tls-a", "origin-tls-b"], assets: ["ca-a", "ca-b", "truststore-a", "truststore-b", "keystore-a", "keystore-b"],
    capabilities: ["async-ssle-engine", "context-isolation", "session-ownership", "global-default-isolation"],
  }),
  "tls-lazy-provider-init-and-suite-probe-blocking-startup-deadline": mechanism({
    barriers: ["deadline-registered", "provider-entered", "send-timed-out", "late-provider-released"],
    counters: { constructionProviderCallsObserved: 0, preSendSocketCallsObserved: 0, firstSendProviderCallsObserved: 1, timeoutSettlementsObserved: 1, lateProviderResultsAcceptedObserved: 0 },
    endpoints: ["origin-tls"], assets: tlsAssets,
    capabilities: ["lazy-tls-provider", "suite-probe-gate", "deadline-first", "late-result-discard"],
  }),
  "proxy-credential-isolation-saturation-deadline-wipe-and-close": mechanism({
    barriers: ["credential-provider-entered", "credential-lane-saturated", "connect-captured", "late-return-released"],
    counters: { providerCallsPerLogicalEstablishmentObserved: 1, credentialWorkerBoundMatchesObserved: 1, credentialQueueBoundMatchesObserved: 1, invalidCredentialConnectsObserved: 0, lateCredentialConnectsObserved: 0, lateCredentialResultsAcceptedObserved: 0, retainedSdkCredentialCopiesObserved: 0 },
    endpoints: ["origin-tls", "connect-proxy"], assets: tlsAssets,
    capabilities: ["connect-proxy", "bounded-credential-lane", "candidate-shared-credentials", "credential-wipe"],
  }),
  "proxy-credential-validation-encoding-boundary-rotation-and-raw-observation": mechanism({
    barriers: ["credential-returned", "connect-auth-captured", "establishment-closed"],
    counters: { minimumUsernameAcceptedObserved: 1, maximumUsernameAcceptedObserved: 1, colonUsernameRejectedObserved: 1, nonAsciiUsernameRejectedObserved: 1, emptyUsernameRejectedObserved: 1, minimumPasswordAcceptedObserved: 1, maximumPasswordAcceptedObserved: 1, colonPasswordAcceptedObserved: 1, allSpacePasswordRejectedObserved: 1, nonPrintablePasswordRejectedObserved: 1, decodedMinimumUsernameBytesObserved: 1, decodedMaximumUsernameBytesObserved: 256, decodedMinimumPasswordBytesObserved: 1, decodedMaximumPasswordBytesObserved: 4096, paddedBase64FormsMatchedObserved: 4, malformedBase64FormsAcceptedObserved: 0, invalidCredentialConnectsObserved: 0, credentialRotationsObserved: 2, staleCredentialReusesObserved: 0, callerCredentialMutationsObserved: 0, retainedSdkCredentialCopiesObserved: 0 },
    endpoints: ["origin-tls", "raw-connect-proxy"], assets: tlsAssets,
    capabilities: ["raw-connect-proxy", "basic-auth-capture", "credential-boundaries", "credential-rotation"],
  }),
  "proxy-connect-informational-framing-body-deadline-cancel-retention-and-no-reuse": mechanism({
    barriers: ["proxy-final-header", "non2xx-body-window", "slow-body-blocked", "poison-close"],
    counters: { originTlsHandshakesObserved: 0, eightInformationalCasesObserved: 1, nineInformationalRejectionsObserved: 1, exactNon2xxBodyBytesObserved: 1048576, plusOneNon2xxBodyRejectionsObserved: 1, slow407DeadlineSettlementsObserved: 1, slow407CancellationsObserved: 1, teClFramingRejectionsObserved: 1, obsFoldFramingRejectionsObserved: 1, peakProxyBodyWindowBytesObserved: 65536, retainedProxyResponseBytesObserved: 0, poisonedSocketReusesObserved: 0, freshFollowupConnectionsObserved: 1 },
    endpoints: ["raw-proxy", "origin-trap"],
    capabilities: ["raw-connect-proxy", "strict-non2xx-framing", "bounded-body-discard", "poison-no-reuse"],
  }),
  "proxy-connect-2xx-bogus-content-length-transfer-encoding-and-coalesced-tls": mechanism({
    barriers: ["connect-header-written", "coalesced-tail-written", "tls-tail-consumed"],
    counters: { coalescedTailWritesObserved: 1, coalescedTlsTailBytesObserved: 5, discardedCoalescedBytesObserved: 0, contentLengthFramingWaitsObserved: 0, transferEncodingFramingWaitsObserved: 0, originReadinessCompletionsObserved: 1, trailingOctetsDroppedObserved: 0 },
    endpoints: ["raw-proxy", "origin-tls"], assets: tlsAssets,
    capabilities: ["raw-connect-proxy", "header-boundary-tunnel", "coalesced-tls-tail", "bogus-framing-ignore"],
  }),
  "proxy-connect-vs-tunneled-origin-field-and-sentinel-separation": mechanism({
    barriers: ["connect-captured", "origin-request-captured", "tunnel-complete"],
    counters: { proxyConnectMethodsMatchedObserved: 1, proxyConnectHostFieldsMatchedObserved: 1, proxyAuthorizationFieldsObserved: 1, originFieldsOnConnectObserved: 0, originBodiesOnConnectObserved: 0, originRequestsObserved: 1, originSdkFieldSetsMatchedObserved: 1, originBodiesMatchedObserved: 1, proxyAuthorizationFieldsAtOriginObserved: 0, sentinelCrossoversObserved: 0 },
    endpoints: ["raw-proxy", "origin-tls"], assets: tlsAssets,
    capabilities: ["raw-connect-proxy", "tunneled-origin", "credential-separation", "sentinel-capture"],
  }),
  "custom-transport-ordered-duplicate-header-abi-map-adversaries": mechanism({
    barriers: ["iterator-acquired", "hundredth-field", "hundred-first-field", "body-closed"],
    counters: { iteratorNextCallsObserved: 101, retainedPrefixFieldsObserved: 100, postFailureNextCallsObserved: 0, duplicateOrderMatchesObserved: 1, closeOnceStructuralFailuresObserved: 1, callerMutationEffectsObserved: 0 },
    capabilities: ["adversarial-header-iterable", "ordered-duplicates", "bounded-iteration", "close-counting-body"],
  }),
  "budgeted-response-consumer-exact-plus-one-chunk-cancel-and-peer-survival": mechanism({
    barriers: [...sequence("chunk-ready", 64), "plus-one-ready", "cancel-observed", "reuse-probe"],
    counters: { peakChunkBytesObserved: 16384, chunkBoundaryEventsObserved: 128, h1ExactRawBytesObserved: 1048576, h2ExactRawBytesObserved: 1048576, h1PlusOneRejectionsObserved: 1, h2PlusOneRejectionsObserved: 1, nearMaximumGzipCasesObserved: 2, nearMaximumGzipDecodedBytesObserved: 1048576, gzipLimitMatchesObserved: 2, duplicatedResponseBytesObserved: 0, upstreamCancelCallsObserved: 1, upstreamCloseCallsObserved: 1, h2StreamResetsObserved: 1, h2SameSessionReusesObserved: 1 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["streaming-response", "tls-alpn-h2", "raw-decoded-budget", "peer-survival"],
  }),
  "idle-extra-async-reclaim-current-reject-and-later-probe-success": mechanism({
    barriers: ["extras-idle", "current-rejected", "physical-destruction-complete", "probe-complete"],
    counters: { reclaimMarkSetsMatchedObserved: 1, rejectedOperationPublicationsObserved: 0, physicalDestructionCompletionsObserved: 1, permitsReleasedBeforePhysicalCloseObserved: 0, permitsReleasedAfterPhysicalCloseObserved: 1, laterProbeSuccessesObserved: 1, bufferedBytesBalanceObserved: 0 },
    endpoints: ["origin"],
    capabilities: ["idle-buffer-reclaim", "async-physical-close", "permit-accounting", "capacity-probe"],
  }),
  "h1-h2-plaintext-zeroization-idle-pool-rotation-close-and-benchmark": mechanism({
    barriers: ["sentinel-published", "connection-idle", "rotation-complete", "runtime-closed", "heap-probe-complete"],
    counters: { requestHeapStorageRegionsObserved: 1, requestDirectStorageRegionsObserved: 1, responseHeapStorageRegionsObserved: 1, responseDirectStorageRegionsObserved: 1, tlsPlaintextStorageRegionsObserved: 1, hpackStorageRegionsObserved: 1, idlePoolStorageRegionsObserved: 1, resizedStorageRegionsObserved: 1, unobservedExpectedStorageRegionsObserved: 0, nonzeroResidualRegionsObserved: 0, retainedSentinelsObserved: 0, callerStorageMutationsObserved: 0, h1PinnedBenchmarkMatchesObserved: 1, h2PinnedBenchmarkMatchesObserved: 1, tlsPinnedBenchmarkMatchesObserved: 1, benchmarkRegressionViolationsObserved: 0 },
    endpoints: ["origin-h1", "origin-h2", "origin-tls"], assets: tlsAssets,
    capabilities: ["http1-plaintext", "tls-alpn-h2", "heap-sentinel-probe", "buffer-zeroization"],
  }),
  "jackson-nonrecycling-factory-borrowed-executor-redeploy-and-heap-sentinel": mechanism({
    barriers: ["parse-complete", "loader-dropped", "gc-cycle-complete"],
    counters: { redeploysObserved: 100, clearedLoaderReferencesObserved: 100, retainedKeySentinelsObserved: 0, retainedPayloadSentinelsObserved: 0, borrowedExecutorClosesObserved: 0, laterOperationLeaksObserved: 0 },
    capabilities: ["nonrecycling-json-factory", "classloader-probe", "borrowed-executor", "heap-sentinel-probe"],
  }),
  "single-monotonic-close-budget-wall-jump-self-close-and-interrupt": mechanism({
    barriers: ["callback-entered", "close-linearized", "wall-jumped", "callback-released"],
    counters: { monotonicCloseMillisecondsObserved: 5000, wallClockDecisionsObserved: 0, currentCallbackSelfWaitsObserved: 0, interruptCorruptionsObserved: 0, closeCompletionsObserved: 1, finalRetainedBytesObserved: 0, finalInFlightOperationsObserved: 0 },
    capabilities: ["fake-monotonic-clock", "callback-identity", "self-close", "interrupt-preservation"],
  }),
  "builtin-hostname-verification-non-overridable-jvm-and-native": mechanism({
    barriers: ["tls-handshake", "hostname-decision", "http-request-captured"],
    counters: { validOriginSuccessesObserved: 1, hostnameMismatchFailuresObserved: 1, mismatchHttpPublicationsObserved: 0, customVerifierCallsObserved: 0, jvmExecutionsObserved: 1, nativeExecutionsObserved: 1, authorityHostnameDecisionsObserved: 2 },
    endpoints: ["origin-tls-valid", "origin-tls-mismatch"], assets: ["fixture-ca", "valid-server-certificate", "mismatch-server-certificate", "valid-keystore", "mismatch-keystore"],
    capabilities: ["builtin-hostname-verification", "authority-connect-separation", "jvm-execution", "native-execution"],
  }),
  "relocated-agent-span-isolation-and-authoritative-otel-bridge": mechanism({
    barriers: ["operation-span-started", "attempt-span-started", "operation-ended", "agent-scan-complete"],
    counters: { operationSpansObserved: 1, attemptSpansObserved: 2, parentageMatchesObserved: 2, spanHandlesEndedOnceObserved: 3, relocatedApacheAgentSpansObserved: 0, secretSpanAttributesObserved: 0, nonfatalTelemetryFailuresObserved: 1, operationsBrokenByTelemetryFailuresObserved: 0, fatalTelemetryFailuresObserved: 0 },
    capabilities: ["recording-telemetry", "authoritative-otel", "agent-scan", "context-continuation"],
  }),
  "otel-bridge-traceparent-only-per-attempt-and-ambient-propagator-isolation": mechanism({
    barriers: ["attempt-header-captured-1", "attempt-header-captured-2", "retry-issued", "operation-ended"],
    counters: { firstAttemptTraceparentBytesObserved: 55, secondAttemptTraceparentBytesObserved: 55, lowercaseTraceparentFormsMatchedObserved: 2, freshTraceIdentifiersObserved: 2, tracestateHeadersObserved: 0, baggageHeadersObserved: 0, ambientPropagatorHitsObserved: 0 },
    endpoints: ["origin-h1", "origin-h2"], assets: tlsAssets,
    capabilities: ["traceparent-capture", "per-attempt-context", "ambient-propagator-isolation", "tls-alpn-h2"],
  }),
});

const groups = Object.fromEntries(engineContract.requiredRawFixtureGroups.map((groupId) => {
  const fixture = mechanisms[groupId];
  return [groupId, {
  fixtureCaseId: caseId(groupId),
  productionConsumerCaseIds: [...productionConsumers[groupId]],
  requiredEndpointIds: [...fixture.requiredEndpointIds],
  requiredAssetIds: [...fixture.requiredAssetIds],
  requiredCapabilities: [...fixture.requiredCapabilities],
  requiredControls: [...actions],
  requiredBarriers: [...fixture.requiredBarriers],
  requiredObservedCounters: { ...fixture.requiredObservedCounters },
  requiredEvidenceFields: [...evidenceFields],
  terminalAssertions: [
    "outcome:SUCCESS",
    "skipped:false",
    `groupId:${groupId}`,
    `fixtureCaseId:${caseId(groupId)}`,
    "controls:all-required",
    ...fixture.requiredBarriers.map((barrier) => `barrier:${barrier}:released`),
    ...Object.entries(fixture.requiredObservedCounters).map(([counter, value]) => `counter:${counter}:${value}`),
    ...fixture.requiredCapabilities.map((capability) => `capability:${capability}`),
    "state:CLOSED",
  ],
  }];
}));

const protocol = Object.freeze({
  protocolVersion: 1,
  command: ["node", "sdk/jvm/certification/transport/jvm-raw-fixture-runner.js", "--group", "{groupId}"],
  displayCommand: "node sdk/jvm/certification/transport/jvm-raw-fixture-runner.js --group <groupId>",
  allEvidenceCommand: "node sdk/jvm/certification/transport/jvm-raw-fixture-runner.js --all --evidence <path>",
  selfDriverControls: ["reset", "load", "start", "awaitBarrier", "advanceMonotonicTime", "snapshot", "releaseBarrier", "awaitBarrier", "close"],
  javaMainClass: "sh.repost.conformance.transport.RawFixtureMain",
  javaRunnerClass: "sh.repost.conformance.transport.JvmRawFixtureRunner",
  transport: "HTTP/1.1 JSON controls over the exact loopback controlEndpoint from the one-line handshake; no authentication token, query, fragment, redirect, ambient proxy, or non-loopback address is permitted",
  handshakeSchema: {
    name: "jvm-raw-fixture-handshake-v1",
    fields: ["protocolVersion", "groupId", "controlEndpoint", "pid"],
    rules: "one scalar-valid UTF-8 JSON line with exactly the four fields; protocolVersion=1; groupId equals the requested group; controlEndpoint matches http://127.0.0.1:<1..65535>/control; pid is a positive safe integer; no secret or filesystem path is present",
  },
  controlProtocol: {
    actions: [...actions],
    requestFieldsByAction: {
      load: ["action"],
      start: ["action"],
      awaitBarrier: ["action", "barrier"],
      releaseBarrier: ["action", "barrier"],
      advanceMonotonicTime: ["action", "deltaMs"],
      snapshot: ["action"],
      reset: ["action"],
      close: ["action"],
    },
    rules: "POST one scalar-valid UTF-8 JSON object to the exact controlEndpoint; reject unknown or duplicate fields, unknown actions, invalid order, barriers not declared by the selected group, and nonpositive or unsafe deltaMs; releaseBarrier releases only the named fixture stimulus, while the independently observed raw event path updates counters; every accepted action returns exactly one closed-schema response",
  },
  stateSchema: {
    name: "jvm-raw-fixture-state-v1",
    fields: ["schema", "protocolVersion", "groupId", "lifecycle", "monotonicTimeMs", "controls", "observedCounters", "scenario", "loaded", "started", "waitingBarriers", "releasedBarriers", "closed"],
  },
  scenarioSchema: {
    fields: ["endpoints", "assets"],
    endpointFields: ["id", "role", "scheme", "connectHost", "port", "authorityHost", "basePath", "alpnProtocols"],
    endpointRoles: ["ORIGIN", "PROXY", "TRAP", "ALTERNATE"],
    endpointSchemes: ["http", "https", "tcp"],
    alpnProtocols: ["h2", "http/1.1"],
    assetFields: ["id", "kind", "path"],
    assetKinds: ["CA_CERTIFICATE", "SERVER_CERTIFICATE", "CLIENT_CERTIFICATE", "CLIENT_PRIVATE_KEY", "CLIENT_PRIVATE_KEY_PASSWORD", "KEYSTORE", "TRUSTSTORE"],
    rules: "connectHost is exact 127.0.0.1 or ::1 and both families are mandatory for each Happy-Eyeballs group; port is 1..65535; authorityHost and basePath are bounded printable ASCII without credentials, query, fragment, control, or traversal; alpnProtocols is an ordered subset of h2,http/1.1; asset paths resolve only inside the fixture source tree or .superpowers/prototypes/2026-07-13-jvm-sdk-task-2-evidence and never appear in retained evidence",
  },
  barrierSchema: {
    name: "jvm-raw-fixture-barrier-v1",
    fields: ["schema", "protocolVersion", "groupId", "barrier", "status", "monotonicTimeMs"],
  },
  evidenceSchema: {
    name: "jvm-raw-fixture-evidence-v1",
    fields: ["schema", "protocolVersion", "summary", "groups"],
    summaryFields: ["total", "success", "skipped", "undeclared"],
    groupFields: [...evidenceFields],
  },
  groups,
});

function fixtureCases() {
  return engineContract.requiredRawFixtureGroups.map((groupId) => ({
    caseId: caseId(groupId),
    groupId,
    expected: {
      outcome: "SUCCESS",
      skipped: false,
      requiredControls: [...groups[groupId].requiredControls],
      requiredBarriers: [...groups[groupId].requiredBarriers],
      requiredObservedCounters: { ...groups[groupId].requiredObservedCounters },
      requiredEndpointIds: [...groups[groupId].requiredEndpointIds],
      requiredAssetIds: [...groups[groupId].requiredAssetIds],
      requiredCapabilities: [...groups[groupId].requiredCapabilities],
      terminalAssertions: [...groups[groupId].terminalAssertions],
    },
  }));
}

function attachProductionGroups(cases) {
  const groupsByCaseId = new Map();
  for (const [groupId, group] of Object.entries(groups)) {
    for (const consumerCaseId of group.productionConsumerCaseIds) {
      if (!groupsByCaseId.has(consumerCaseId)) groupsByCaseId.set(consumerCaseId, []);
      groupsByCaseId.get(consumerCaseId).push(groupId);
    }
  }
  return cases.map((testCase) => {
    const rawFixtureGroups = groupsByCaseId.get(testCase.caseId);
    const copy = structuredClone(testCase);
    if (Object.hasOwn(copy.input, "rawFixtureGroups")) {
      delete copy.input.rawFixtureGroups;
      if (copy.expected.terminalKind === "EXECUTABLE_PROOF") copy.expected.terminalKind = "CONSTRUCTION_PROOF";
    }
    if (!rawFixtureGroups) return copy;
    copy.input.rawFixtureGroups = rawFixtureGroups;
    if (copy.expected.terminalKind === "CONSTRUCTION_PROOF") copy.expected.terminalKind = "EXECUTABLE_PROOF";
    return copy;
  });
}

function deepFreeze(value) {
  if (value !== null && (typeof value === "object" || typeof value === "function")) {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}

module.exports = deepFreeze({ actions, attachProductionGroups, caseId, evidenceFields, fixtureCases, groups, mechanisms, productionConsumers, protocol });
