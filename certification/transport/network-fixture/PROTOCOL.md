# Enterprise network fixture protocol

The executable emits exactly one JSON line with protocol `repost-enterprise-network-fixture`, contract version `2`, loopback URLs, test-asset paths, capability names, control operations, and numeric trap limits.

Every `*BaseUrl` is passed directly to an SDK. The SDK applies its production rule `stripTrailingSlash(baseUrl) + "/v1/messages"`; no runner-specific destination override is allowed. `controlUrl`, `proxyUrl`, and `authenticatedProxyUrl` are not SDK base URLs.

Control operations are relative to `controlUrl`:

- `GET /state` returns counters and active-resource counts only.
- `POST /reset {}` cancels active fixture work and resets all counters.
- `POST /release {"barrier":"responseHeaders"}` releases held HTTP/1 headers.
- `POST /release {"barrier":"http2Streams"}` releases held HTTP/2 streams.
- `POST /release {"barrier":"preallocationBody"}` terminates an unconsumed declared-length trap for cleanup.
- `POST /release {"barrier":"staleConnection"}` arms the primed pooled HTTP/1 connection to reset after its next first request-body byte.
- `POST /close {}` closes all listeners, sessions, tunnels, sockets, and the executable.

Successful send endpoints parse at most 1 MiB, retain only the request identity during response handling, and return exactly `id`, `type`, `customerId`, and `timestamp`. Trap endpoints deliberately return non-success bodies or transport failures. State never exposes request bodies, headers, credentials, identity values, compressed data, or failure text.

The preallocation trap declares 1 GiB, publishes one byte, and waits for cancellation. The decompression trap publishes a small raw gzip member expanding to exactly 1 MiB + 1 byte. The stale-connection trap counts wire requests, first body publications, bounded observed bytes, reuse, faults, explicit successes, and distinct connections so a transport-owned replay cannot hide behind one core attempt.
