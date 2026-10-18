package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.wavvon.sdk.generated.api.ChannelsApi;

class ClientTest {
  static final String SEED = "02".repeat(32);
  static final RetryOptions FAST =
      RetryOptions.DEFAULTS.withDelays(Duration.ofMillis(1), Duration.ofMillis(1));

  /** A hub with a token that can be revoked mid-run and a flaky /channels. */
  static final class FakeHub {
    final AtomicInteger tokens = new AtomicInteger();
    final AtomicReference<String> valid = new AtomicReference<>("");
    final AtomicInteger flaky = new AtomicInteger();
    final TestHub hub;

    FakeHub(String capabilities) throws Exception {
      hub =
          new TestHub(
              req -> {
                switch (req.path()) {
                  case "/info":
                    return TestHub.Res.json(
                        "{\"public_key\":\"aa\",\"capabilities\":[" + capabilities + "]}");
                  case "/auth/challenge":
                    return TestHub.Res.json(
                        "{\"challenge\":\"" + "00".repeat(32) + "\",\"scheme\":\"tagged-v1\"}");
                  case "/auth/verify":
                    valid.set("t" + tokens.incrementAndGet());
                    return TestHub.Res.json(
                        "{\"token\":\"" + valid.get() + "\",\"canonical_pubkey\":\"me\"}");
                  default:
                    break;
                }
                if (!("Bearer " + valid.get()).equals(req.headers().getFirst("Authorization"))) {
                  return new TestHub.Res(401, "expired");
                }
                if (req.path().equals("/channels") && flaky.getAndDecrement() > 0) {
                  return new TestHub.Res(429, null);
                }
                if (req.path().startsWith("/me/app/")) return new TestHub.Res(200, null);
                return TestHub.Res.json("[{\"id\":\"c1\",\"name\":\"general\"}]");
              });
    }

    WavvonClient client(RetryOptions retry) {
      return WavvonClient.builder(hub.url()).seed(SEED).retry(retry).build();
    }
  }

  @Test
  void addsTheBearerReauthenticatesOnceOn401AndRetriesTheCall() throws Exception {
    FakeHub f = new FakeHub("");
    try (f.hub) {
      WavvonClient client = f.client(FAST);
      List<WavvonClient.SessionState> states = new CopyOnWriteArrayList<>();
      client.onStateChange(states::add);
      ChannelsApi channels = new ChannelsApi(client.api());
      assertEquals("general", channels.listChannels().get(0).getName());
      f.valid.set("revoked");
      assertEquals(1, channels.listChannels().size());
      assertEquals(2, f.hub.count("POST", "/auth/verify"));
      assertEquals(
          List.of(
              WavvonClient.SessionState.AUTHENTICATING,
              WavvonClient.SessionState.READY,
              WavvonClient.SessionState.AUTHENTICATING,
              WavvonClient.SessionState.READY),
          states);
      assertEquals("me", client.canonicalPubkey());
    }
  }

  @Test
  void retries429AndFailsLoudlyOnceTheBudgetIsSpent() throws Exception {
    FakeHub f = new FakeHub("");
    try (f.hub) {
      WavvonClient client = f.client(FAST.withMaxAttempts(3));
      f.flaky.set(2);
      assertEquals("c1", client.send("GET", "/channels", null).get(0).get("id").asText());
      f.flaky.set(10);
      assertThrows(HubUnavailableException.class, () -> client.send("GET", "/channels", null));
      assertThrows(
          HubUnavailableException.class, () -> new ChannelsApi(client.api()).listChannels());
    }
  }

  @Test
  void gatesAppRegistrationOnTheAppsRegisterCapability() throws Exception {
    FakeHub without = new FakeHub("");
    try (without.hub) {
      assertThrows(
          UnsupportedByHubException.class, () -> without.client(FAST).setAppCommands(List.of()));
      assertEquals(0, without.hub.count("PUT", "/me/app/commands"));
    }
    FakeHub with = new FakeHub("\"apps.register\"");
    try (with.hub) {
      with.client(FAST).setAppCommands(List.of(AppCommand.of("echo", "Echo")));
      assertEquals(1, with.hub.count("PUT", "/me/app/commands"));
      String body =
          with.hub.log.stream()
              .filter(r -> r.path().equals("/me/app/commands"))
              .findFirst()
              .orElseThrow()
              .body();
      assertEquals("{\"commands\":[{\"name\":\"echo\",\"description\":\"Echo\"}]}", body);
    }
  }

  @Test
  void pollingParsesThePayloadString() throws Exception {
    try (TestHub hub =
        new TestHub(
            req ->
                switch (req.path()) {
                  case "/auth/challenge" ->
                      TestHub.Res.json(
                          "{\"challenge\":\"" + "00".repeat(32) + "\",\"scheme\":\"tagged-v1\"}");
                  case "/auth/verify" -> TestHub.Res.json("{\"token\":\"t\"}");
                  default ->
                      TestHub.Res.json(
                          "{\"events\":[{\"id\":\"e1\",\"event_type\":\"member.joined\","
                              + "\"payload\":\"{\\\"pubkey\\\":\\\"p\\\"}\",\"created_at\":5}]}");
                })) {
      WavvonClient client = WavvonClient.builder(hub.url()).seed(SEED).build();
      List<PolledEvent> events = client.pollEvents(3L);
      assertEquals("p", events.get(0).payload().get("pubkey").asText());
      assertTrue(hub.log.stream().anyMatch(r -> "since=3".equals(r.query())));
    }
  }
}
