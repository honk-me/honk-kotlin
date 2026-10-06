# Changelog

All notable changes to `app.honk-me:sdk` (Maven) are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `Message.actions`: up to 3 `Action(title, url)` buttons (`https://`, `mailto:`, `tel:` or
  `sms:`), sent as `actions` and omitted when empty; `Message.Builder.action(title, url)` and
  `actions(list)`. Validated locally like the server, with errors on `actions`,
  `actions[i].title` and `actions[i].url`; `Limits.ACTIONS` and `Limits.ACTION_TITLE`.

## [0.1.0] - 2026-10-04

### Added
- `Honk` client for `POST /v1/messages` on `java.net.http.HttpClient` (JDK 17+): a `suspend`
  `send`, plus `sendAsync` (`CompletableFuture`) and `sendBlocking` for Java; `Honk.builder()`,
  `Honk.fromEnvironment()`, `AutoCloseable`.
- `Message` data class with every field of the v1 ingestion API (including `imageUrl`) and a
  fluent `Message.Builder` (`Message.builder().title(…).loud().build()`), plus static helper
  builders `Message.loud(title, message)`, `Message.problem(groupKey, …)`, ….
- The Honk scale: `Severity.LIGHT/BEEP/LOUD/LONG/BLAST` aliases of `INFO … CRITICAL`,
  `Severity.parse()`, and suspend helpers `light`, `beep`, `loud`, `long`, `blast` (plus the
  `info` … `critical` synonyms), `problem`, `recovery`.
- Automatic UUIDv7 `Idempotency-Key` (or your own), reused on every retry; retries for network
  errors, timeouts, 429 and 5xx with exponential backoff, full jitter, `Retry-After` and a total
  deadline; redirects are reported, never followed.
- Unchecked exceptions `HonkValidationException`, `HonkAuthException`, `HonkQuotaException`,
  `HonkConflictException`, `HonkNetworkException`, `HonkTimeoutException`,
  `HonkServerException` (base `HonkException`); local validation of every field.
- Runtime dependencies: `kotlin-stdlib` and `kotlinx-coroutines-core` only (hand-written JSON).
