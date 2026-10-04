# Honk for Kotlin and Java

[![CI](https://github.com/honk-me/honk-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/honk-me/honk-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/app.honk-me/sdk)](https://central.sonatype.com/artifact/app.honk-me/sdk)

Official Kotlin and Java client for [Honk](https://honk-me.app), the inbox that turns events
from your apps, scripts, cron jobs and CI into calm, grouped push notifications on your phone.

- JDK 17+, `java.net.http.HttpClient` (keep-alive, HTTP/2 over https).
- Kotlin: `suspend` API with cancellation. Java: `sendBlocking`, `sendAsync`
  (`CompletableFuture`), builders and static helpers.
- Retries with backoff, `Retry-After`, a total deadline and an idempotency key on every send,
  so a retry never creates a duplicate.
- Runtime dependencies: `kotlin-stdlib` and `kotlinx-coroutines-core`, nothing else.

The ingestion key (`honk_…`) is a secret: use this library on servers, in jobs and CLIs. Never
ship it inside an Android or desktop app; have the app call your backend instead.

Create a project and an ingestion key at [honk-me.app](https://honk-me.app). Its
*Integrations* page generates ready-to-paste code for this library.

## Install

```kotlin
// build.gradle.kts
dependencies { implementation("app.honk-me:sdk:0.1.0") }
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>app.honk-me</groupId>
  <artifactId>sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Everything is in the package `app.honkme` (`import app.honkme.Honk`).

## Quick start

Kotlin:

```kotlin
import app.honkme.Honk

val honk = Honk.fromEnvironment()                 // HONK_URL, HONK_KEY (+ HONK_SOURCE, HONK_ENVIRONMENT, HONK_CHANNEL)
honk.beep("Backup finished", "nightly pg_dump took 42 s")   // suspend
```

Java:

```java
import app.honkme.Honk;
import app.honkme.Message;

Honk honk = Honk.fromEnvironment();
honk.sendBlocking(Message.beep("Backup finished", "nightly pg_dump took 42 s").build());
```

Explicitly: `Honk(url, key)` in Kotlin (named arguments for the options), or
`Honk.builder().url(url).key(key).timeoutMs(3000).build()` in Java. A missing or malformed URL
or key throws `IllegalArgumentException`. Create one `Honk` per application and `close()` it on
shutdown.

## The Honk scale

Every severity has a horn name. Use either; the SDK always sends the canonical value.

| Horn | Severity | Kotlin helper | Java builder | Constant |
|---|---|---|---|---|
| light honk | `light` (info) | `honk.light(title, message)` | `Message.light(title, message)` / `.light()` | `Severity.LIGHT` |
| beep-beep | `beep` (success) | `honk.beep(…)` | `Message.beep(…)` / `.beep()` | `Severity.BEEP` |
| loud honk | `loud` (warning) | `honk.loud(…)` | `Message.loud(…)` / `.loud()` | `Severity.LOUD` |
| long honk | `long` (error) | `honk.long(…)` | `Message.longHonk(…)` / `.longHonk()` (`long` is a Java keyword) | `Severity.LONG` |
| blast | `blast` (critical) | `honk.blast(…)` | `Message.blast(…)` / `.blast()` | `Severity.BLAST` |

`Severity.LOUD === Severity.WARNING`; `Severity.parse("LOUD")` and the builder's
`severity("loud")` accept horn and canonical names in any case. `LONG` and `BLAST` push at least
as high priority. `info` … `critical` remain as synonyms.

## Recipe: notify me when a customer asks for something (Spring Boot)

Code only: the library has no Spring dependency.

```java
@Configuration
class HonkConfig {
    @Bean(destroyMethod = "close")
    Honk honk(@Value("${honk.url}") String url, @Value("${honk.key}") String key) {
        return Honk.builder().url(url).key(key).build();          // one client, keep-alive
    }
}

@Service
class CustomerRequestNotifier {
    private static final Logger log = LoggerFactory.getLogger(CustomerRequestNotifier.class);
    private final Honk honk;

    CustomerRequestNotifier(Honk honk) { this.honk = honk; }

    // After the request is saved: never block the customer's request on the network.
    @TransactionalEventListener
    void on(CustomerRequestCreated event) {
        CustomerRequest r = event.request();
        Message message = Message.light("New request: " + r.subject(), r.name() + " (" + r.company() + ") asked: " + r.body())
            .priority("high")                                     // push right away
            .category(Category.CUSTOMERS)
            .channel("requests")
            .groupKey("requests/" + r.id())                       // one group per request
            .url("https://shop.example.com/admin/requests/" + r.id())   // https only
            .meta("request_id", String.valueOf(r.id()))
            .build();
        honk.sendAsync(message, "request-" + r.id())              // same request, same key
            .exceptionally(e -> { log.warn("honk: {}", e.getMessage()); return null; });
    }
}
```

Kotlin (Ktor, a coroutine-based service, …):

```kotlin
scope.launch {
    honk.light("New request: ${r.subject}", "${r.name} (${r.company}) asked: ${r.body.take(2000)}") {
        priority(Priority.HIGH)
        category(Category.CUSTOMERS)
        groupKey("requests/${r.id}")
        url("https://shop.example.com/admin/requests/${r.id}")
        idempotencyKey("request-${r.id}")
    }
}
```

## Grouping in three lines

Messages with the same `groupKey` (per project, environment, source and channel) form one group:
the first one pushes, repeats update it calmly instead of buzzing again. Use one key per customer
request (`requests/<id>`), and a shared key only for repeats of the same problem
(`queue/failed-jobs`). `problem`/`recovery` pairs need a `groupKey`.

## Sending

```kotlin
suspend fun send(message: Message, idempotencyKey: String? = null): Accepted       // id, duplicate, receivedAt
fun sendAsync(message: Message, idempotencyKey: String? = null): CompletableFuture<Accepted>
fun sendBlocking(message: Message, idempotencyKey: String? = null): Accepted
```

| Field | Notes |
|---|---|
| `message` | **required**, 1–8192 bytes UTF-8, line breaks allowed (`line()` appends one) |
| `title` | ≤ 160 characters, one line; default: first line of `message` |
| `severity` | `LIGHT` `BEEP` `LOUD` `LONG` `BLAST` (or `INFO` … `CRITICAL`) |
| `priority` | `LOW` `NORMAL` `HIGH` `URGENT` (`URGENT` needs a key with *allow urgent*) |
| `category` | `INFRASTRUCTURE` `SECURITY` `BACKUPS` `DEPLOYMENTS` `PAYMENTS` `CUSTOMERS` `SALES` `AUTOMATION` `PERSONAL` `OTHER` |
| `source` / `environment` / `channel` | ≤ 64 / 32 / 64 characters; default `api` / `default` / `general` or `Defaults` |
| `groupKey` | ≤ 128 characters |
| `eventType` | `EVENT` `PROBLEM` `RECOVERY` (`RECOVERY` needs `groupKey`) |
| `occurredAt` | `Instant`, sent as UTC RFC 3339 with milliseconds |
| `url` / `imageUrl` | `https://` only, no credentials (`imageUrl`: no `#fragment`; fetched by the server afterwards) |
| `metadata` (`meta(k, v)`) | ≤ 16 keys `[A-Za-z0-9_.-]{1,64}`; String (≤ 512 characters), Number or Boolean values |
| `ttlSeconds` | push lifetime 60–86400 (default 3600) |
| `sourceSequence` | 0 … 2^53-1, needs `groupKey` |
| `idempotencyKey` | 1–128 printable ASCII characters (also a `send` argument) |

null and empty optional fields are omitted. A returned `Accepted` means Honk **durably stored**
the message (202), not that a push was delivered or read.

```kotlin
honk.loud("Disk 91%", "/var on app-01") { groupKey("disk/app-01/var") }
honk.problem("db/backup", "Backup failed", "pg_dump exited with 1")      // a long honk by default
honk.recovery("db/backup", "Backup OK", "pg_dump finished in 41 s")      // a beep by default
honk.send(Message("Imported 1 204 rows", severity = Severity.BEEP, channel = "imports"))
```

```java
honk.sendBlocking(Message.problem("db/backup", "Backup failed", "pg_dump exited with 1").meta("exit", 1).build());
honk.sendAsync(Message.builder().title("Disk 91%").message("/var on app-01").loud().build());
```

Options (Kotlin named arguments or `Honk.builder()`): `timeoutMs` 5000 per attempt, `retries` 4,
`deadlineMs` 30000, `defaults`, `validate` (false leaves all checks to the server), `backoff`,
`httpClient` (configure it never to follow redirects), `userAgent`.

## Retries and idempotency, guaranteed

- Every send carries an `Idempotency-Key`: yours, or a fresh UUIDv7 (`UuidV7.generate()`).
  **The same key is reused on every retry.** Within 24 h Honk answers a replay with the original
  id and `duplicate == true`, so a lost response never creates a second message.
- Only network errors, timeouts, `429` and `5xx` are retried, with exponential backoff and full
  jitter (`random(0, min(8 s, 0.5 s·2ⁿ))`), never sooner than the server's `Retry-After`.
- Everything stops at `deadlineMs`: if the next wait would cross it (for example a daily quota
  that resets at midnight), the exception is thrown at once with `retryAfter`.
- `4xx` other than `429` are never retried. Coroutine cancellation (and cancelling the
  `CompletableFuture` of `sendAsync`) stops the send.

## Errors

All exceptions are unchecked and extend `HonkException` (`status`, `code`, `requestId`,
`idempotencyKey`, `attempts`, `retryAfter`, `isRetryable`).

| Exception | When | What to do |
|---|---|---|
| `HonkValidationException` | rejected locally (`isLocal`) or `400`/`413`/`415`/`422`; `fields` lists every problem | fix the message |
| `HonkAuthException` | `401 invalid_key`, `403 priority_not_allowed`, `project_suspended`, `workspace_suspended` | fix the key or the priority |
| `HonkQuotaException` | `429 quota_exceeded` (daily) or `rate_limited`, after retries | retry after `retryAfter` |
| `HonkConflictException` | `409 idempotency_conflict`: same key, different payload | new key or original payload |
| `HonkNetworkException` / `HonkTimeoutException` | unreachable / no answer before the deadline | retry later, same key |
| `HonkServerException` | `5xx` on every attempt | retry later, same key |
| `HonkException` | anything else (wrong URL → 404, a redirect) | fix the URL |

```java
try {
    honk.sendBlocking(Message.longHonk("Payment failed", "Stripe declined order 1042").groupKey("payments/stripe").build());
} catch (HonkValidationException e) {
    log.error("bug: {}", e.getFields());
} catch (HonkException e) {
    if (e.isRetryable()) queue.retryLater(e.getIdempotencyKey(), e.getRetryAfter());
    else throw e;
}
```

## Why a hand-written JSON codec

The request is one flat object (plus a flat `metadata` map) and the answers are two small fixed
shapes. A ~150-line encoder/parser (`Json.kt`, fully tested) keeps the dependency tree at
`kotlin-stdlib` + `kotlinx-coroutines-core`: no kotlinx-serialization compiler plugin to configure
in your build, no Jackson/Gson version conflicts for Java users, and an 89 KB jar.
`kotlinx-coroutines-core` is the one deliberate dependency: it makes `withTimeout`,
cancellation and `CompletableFuture` interop correct.

## Development

```sh
./gradlew test                                        # JUnit 5, JDK HttpServer mock, Kotlin + Java tests
HONK_URL=… HONK_KEY=… ./gradlew integrationTest       # against a real server (use a test project's key)
./gradlew publishToMavenLocal                          # jar, sources, Dokka javadoc and POM (signed only when a key is configured)
```

Any JDK 17+ runs the build; Gradle downloads a JDK 17 toolchain if the machine has none. The
version lives in `gradle.properties` (`VERSION_NAME`); `Honk.VERSION` is generated from it.
Releases: push a tag `vX.Y.Z` matching `VERSION_NAME` and the release workflow publishes to
Maven Central (see `CHANGELOG.md`).

## Links

- [honk-me.app](https://honk-me.app): the Honk inbox (web, iPhone).
- Other SDKs: [Node.js](https://github.com/honk-me/honk-node),
  [PHP / Laravel](https://github.com/honk-me/honk-php), [Go + CLI](https://github.com/honk-me/honk-go),
  [Swift](https://github.com/honk-me/honk-swift).

MIT License.
