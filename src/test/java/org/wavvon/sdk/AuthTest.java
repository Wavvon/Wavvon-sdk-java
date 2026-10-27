package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuthTest {
  static final String SEED = "5a".repeat(32);
  static final byte[] C32 = filled(32, 9);
  static final RetryOptions FAST =
      RetryOptions.DEFAULTS.withDelays(Duration.ofMillis(1), Duration.ofMillis(1));

  static byte[] filled(int n, int v) {
    byte[] b = new byte[n];
    Arrays.fill(b, (byte) v);
    return b;
  }

  @Test
  void prefixesTheTagUnderTaggedV1() {
    byte[] b = Auth.challengeSigningBytes(C32, "tagged-v1");
    assertArrayEquals(Bytes.utf8("wavvon/auth-challenge/v1\0"), Arrays.copyOf(b, 25));
    assertArrayEquals(C32, Arrays.copyOfRange(b, 25, b.length));
  }

  @Test
  void refusesAMissingOrUnknownSchemeInsteadOfSigningRaw() {
    assertThrows(AuthSchemeException.class, () -> Auth.challengeSigningBytes(C32, null));
    assertThrows(AuthSchemeException.class, () -> Auth.challengeSigningBytes(C32, "tagged-v2"));
    assertThrows(AuthSchemeException.class, () -> Auth.challengeSigningBytes(C32, ""));
  }

  @Test
  void acceptsASeedAsHexOrBytesAndRejectsOtherLengths() {
    assertEquals(
        Identity.fromSeed(SEED).publicKey(), Identity.fromSeed(Bytes.fromHex(SEED)).publicKey());
    assertThrows(IllegalArgumentException.class, () -> Identity.fromSeed("abcd"));
  }

  /** A hub that answers the challenge flow; {@code failVerify} transient verifies first. */
  static final class FakeHub {
    final List<String> challenges = new CopyOnWriteArrayList<>();
    final List<JsonNode> verifies = new CopyOnWriteArrayList<>();
    final TestHub hub;

    FakeHub(String scheme, int failVerify, int verifyStatus) throws Exception {
      AtomicInteger fails = new AtomicInteger(failVerify);
      hub =
          new TestHub(
              req -> {
                JsonNode body = Json.MAPPER.readTree(req.body());
                if (req.path().equals("/auth/challenge")) {
                  String ch = Bytes.toHex(filled(32, challenges.size() + 1));
                  challenges.add(ch);
                  return TestHub.Res.json(
                      scheme == null
                          ? "{\"challenge\":\"" + ch + "\"}"
                          : "{\"challenge\":\"" + ch + "\",\"scheme\":\"" + scheme + "\"}");
                }
                verifies.add(body);
                if (fails.getAndDecrement() > 0) return new TestHub.Res(503, "busy");
                if (verifyStatus != 0) {
                  return new TestHub.Res(verifyStatus, "This hub requires an invite code");
                }
                byte[] msg =
                    Auth.challengeSigningBytes(
                        Bytes.fromHex(body.get("challenge").asText()), scheme);
                boolean ok =
                    Identity.verify(
                        body.get("public_key").asText(),
                        msg,
                        Bytes.fromHex(body.get("signature").asText()));
                if (!ok) return new TestHub.Res(401, "Invalid signature");
                return TestHub.Res.json(
                    "{\"token\":\"tok\",\"scope\":\"member\",\"canonical_pubkey\":\""
                        + body.get("public_key").asText()
                        + "\"}");
              });
    }

    Session login(String invite) throws InterruptedException {
      return Auth.authenticate(
          HttpClient.newHttpClient(), hub.url() + "/", Identity.fromSeed(SEED), invite, FAST);
    }
  }

  @Test
  void logsInWithTaggedV1AndPassesTheInvite() throws Exception {
    FakeHub f = new FakeHub("tagged-v1", 0, 0);
    try (f.hub) {
      assertEquals("tok", f.login("inv").token());
      assertEquals("inv", f.verifies.get(0).get("invite_code").asText());
    }
  }

  @Test
  void restartsFromAFreshChallengeAfterATransientVerifyFailure() throws Exception {
    FakeHub f = new FakeHub("tagged-v1", 2, 0);
    try (f.hub) {
      f.login(null);
      assertEquals(3, f.challenges.size());
      HashSet<String> used = new HashSet<>();
      f.verifies.forEach(v -> used.add(v.get("challenge").asText()));
      assertEquals(3, used.size());
    }
  }

  @Test
  void refusesAHubThatNamesNoSchemeWithoutSendingAVerify() throws Exception {
    FakeHub f = new FakeHub(null, 0, 0);
    try (f.hub) {
      assertThrows(AuthSchemeException.class, () -> f.login(null));
      assertEquals(0, f.verifies.size());
    }
  }

  @Test
  void surfacesADefinitiveRefusalAsHubException() throws Exception {
    FakeHub f = new FakeHub("tagged-v1", 0, 403);
    try (f.hub) {
      HubException e = assertThrows(HubException.class, () -> f.login(null));
      assertEquals(403, e.status());
    }
  }

  @Test
  void neverSendsTheSeed() throws Exception {
    FakeHub f = new FakeHub("tagged-v1", 0, 0);
    try (f.hub) {
      f.login("x");
      f.hub.log.forEach(r -> assertFalse(r.body().contains(SEED)));
    }
  }
}
