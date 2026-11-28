# @repost/sdk-jvm

## 1.1.1

### Patch Changes

- [#627](https://github.com/repost-sh/monorepo/pull/627) [`afabc1d`](https://github.com/repost-sh/monorepo/commit/afabc1dcc4c607d8c31eb357c1fa1920312d9aec) Thanks [@schulzfel](https://github.com/schulzfel)! - `repost-client-kotlin` no longer publishes a strict `org.jetbrains:annotations` 23.0.0 constraint. Every release from 1.0.16 to 1.1.0 did, so a Gradle consumer that used a newer `org.jetbrains:annotations`, such as 26.0.2, failed to resolve its runtime classpath. Maven 3 consumers are unaffected: they resolve the Kotlin stdlib's 13.0 from both versions.

- [#623](https://github.com/repost-sh/monorepo/pull/623) [`22438f0`](https://github.com/repost-sh/monorepo/commit/22438f0ae21eec41db7673edf24df9f3748b707d) Thanks [@schulzfel](https://github.com/schulzfel)! - The JVM SDK reports its own version. In 1.1.0, the runtime's user agent (`repost-jvm/1.0.0`) and the OpenTelemetry instrumentation version said 1.0.0. The Maven plugin's help and its compile-graph check already used 1.1.0, but its engine request (`pluginVersion`) and its incremental-state hash used 1.0.0, and so did the Gradle plugin's engine request.

- [#628](https://github.com/repost-sh/monorepo/pull/628) [`54ee832`](https://github.com/repost-sh/monorepo/commit/54ee832e42499cd68a52ecee58f0c53b60f87f18) Thanks [@schulzfel](https://github.com/schulzfel)! - The Spring Boot starter compiles and tests on Spring Boot's managed versions, as a Boot application resolves them: its Boot 4.0 lane compiles against Boot 4.0's dependency management and its Boot 4.1 lane tests on Boot 4.1's. The starter's POM, Gradle module metadata and jars are unchanged; its compile classpath, and with it the released SBOM, moves to Boot 4.0's OpenTelemetry 1.55.0 and commons-logging 1.3.6 and lists `spring-boot-dependencies` 4.0.7.
