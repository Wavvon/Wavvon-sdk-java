# Wavvon SDK for Java

> **Community / experimental.** This SDK is maintained in-house on a
> best-effort basis until an outside maintainer volunteers. It still runs the
> shared conformance suite on every build, but it may lag the
> [TypeScript SDK](https://github.com/Wavvon/Wavvon-sdk-js), which is the
> reference. Not yet published to Maven Central.

Write programs that talk to a [Wavvon](https://github.com/Wavvon) hub: bots,
apps with slash commands and buttons, event consumers, mini-app hosts, and
receivers for outgoing webhooks.

A program on Wavvon is a client like any other: an Ed25519 keypair, the same
challenge-response login, the same permissions. There is no bot account. What
makes a program an *app* is a role carrying `apps.register`. The design this
library implements is
[sdk.md](https://github.com/Wavvon/Wavvon-docs/blob/main/docs/sdk.md) (tier 1).

- Java 17 or later. Ed25519, HMAC and HTTP/WebSocket come from the JDK.
- Depends on Jackson and `jakarta.annotation-api`. `httpmime` is optional:
  only the two multipart operations (file and soundboard upload) need it.
- Blocking API, thread-safe: on Java 21+ call it from virtual threads.
- Apache-2.0, so a closed-source program can use it.

## Install

Until it is published, build it locally:

```sh
./mvnw -B install
```

```xml
<dependency>
  <groupId>org.wavvon</groupId>
  <artifactId>wavvon-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Quick start

```java
WavvonClient client = WavvonClient.builder("https://hub.example")
    .seed(System.getenv("WAVVON_SEED"))          // 32-byte Ed25519 seed, hex. Never sent anywhere.
    .inviteCode(System.getenv("WAVVON_INVITE"))  // only needed the first time, on an invite-only hub
    .build();

DispatchHandler handler = DispatchHandler.builder(client.info().publicKey())
    .onSlashCommand(inv -> AppResponse.reply("you said: " + inv.argsRaw()))
    .build();
HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.createContext("/hook", handler.asHttpHandler());
server.start();

client.setAppProfile(AppProfile.named("Echo").withWebhookUrl("https://bot.example/hook"));
client.setAppCommands(List.of(AppCommand.of("echo", "Repeat after me")));

EventStream events = client.connect();
events.on(WsEvent.MessageCreated.class, e ->
    System.out.println(e.message().getSender() + ": " + e.message().getContent()));
events.on(WsEvent.Unknown.class, e -> System.out.println("new frame type: " + e.rawType()));
```

A fuller one is [examples/.../EchoBot.java](examples/org/wavvon/sdk/examples/EchoBot.java).

## What is in it

| Piece | Entry point |
|---|---|
| Login (challenge, sign, verify) that renews itself | `WavvonClient`, `Auth`, `Identity` |
| Every REST route, generated from `openapi.yaml` | `new ChannelsApi(client.api())` and the other `org.wavvon.sdk.generated.api` classes |
| A raw request for anything the generated client gets wrong | `client.send(method, path, body)` |
| Retry with backoff on 429, 5xx, timeouts | built into both of the above; `Retry` |
| Capability checks | `client.hasCapability`, `client.requireCapability` |
| Keyset paging to the end of a list | `Pager` (an `Iterable`, and `stream()`) |
| WebSocket events with resume after reconnect | `client.connect()` -> `EventStream` |
| HTTP polling instead of a socket | `client.pollEvents`, `client.ackEvents` |
| App registration | `setAppProfile`, `setAppCommands`, `setAppSubscriptions` |
| Slash command and button webhooks | `DispatchHandler`, `Webhooks.verifyDispatch` |
| Outgoing webhook receiver | `Webhooks.verifyOutgoingWebhook` |
| Mini-app host | `EventStream.announceApp`, `dismissApp`, `sendMiniAppMessage` |

`DispatchHandler.handle(method, headers, body)` is framework-agnostic; wire it
into Spring, Javalin or anything else in a few lines. `asHttpHandler()` adapts
it to the JDK's `com.sun.net.httpserver`.

## Capabilities each feature needs

Hubs say what they support in `GET /info` `capabilities`. The SDK checks those
strings and never compares version numbers. A feature the hub does not
advertise throws `UnsupportedByHubException`.

| Feature | Capability |
|---|---|
| `setAppProfile`, `setAppCommands`, `setAppSubscriptions`, embeds, game cards | `apps.register` |
| `verifyDispatch` / `verifyOutgoingWebhook` (they require `X-Wavvon-Signature-V1`) | `webhooks.signedtimestamp` |
| Paging `GET /users`, `/conversations/{id}/messages`, `/admin/reports` | `list.cursor` |
| Paging bans, mutes, invites, pending, conversations, pins, polls | `list.cursor.lists` |
| `ping` / `pong` over the socket | `ws.ping` |

Login, REST, the socket and polling need no capability.

## Behaviour you can rely on

- **A transient failure is never an answer.** 429, 5xx, timeouts and dropped
  connections are retried with capped exponential backoff and jitter. If the
  budget runs out you get `HubUnavailableException`, not an empty list or a
  404. `RetryOptions.DEFAULTS.withMaxAttempts(Integer.MAX_VALUE)` waits forever.
- **Sessions renew themselves.** A 401 triggers one fresh login and a retry.
  `client.onStateChange` and `events.onStateChange` show when it is happening.
- **Unknown is delivered.** A socket frame or dispatch type this version does
  not model reaches you as `WsEvent.Unknown` / `Dispatch.UnknownDispatch` with
  the raw payload.
- **Missed events are replayed.** The event stream remembers the last
  `hub_event` seq and asks the hub to resume from it after every reconnect.
  If the gap is older than the hub keeps (72 hours) you get
  `WsEvent.ReplayUnavailable`. Register the app profile before `connect()`:
  the hub only pushes and replays events on a socket opened by a registered
  app.
- **The seed stays with you.** No call sends a seed or private key anywhere.

`GET /me/events` polling is implemented, but the hub does not fill that queue
yet ([Wavvon-server#170](https://github.com/Wavvon/Wavvon-server/issues/170)).

## Conformance

Every signed byte sequence is checked against the shared wire vectors in
Wavvon-server (`crates/identity/wire-vectors.json`): the auth challenge, the
Ed25519 dispatch signature, and the outgoing-webhook HKDF + HMAC. CI reads them
from Wavvon-server `develop`. A smoke test (`./mvnw -Psmoke verify`) drives a
real hub binary.

The REST client was generated from Wavvon-docs at the commit in
[src/main/generated/SPEC_COMMIT](src/main/generated/SPEC_COMMIT).

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
