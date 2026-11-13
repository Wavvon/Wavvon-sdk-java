package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhooksTest {
  static final Identity HUB = Identity.fromSeed(AuthTest.filled(32, 5));
  static final long NOW = 1_700_000_000L;
  static final Webhooks.VerifyOptions AT_NOW = Webhooks.VerifyOptions.at(NOW);
  static final String SLASH =
      "{\"type\":\"slash_command\",\"hub_url\":\"https://h\",\"channel_id\":\"c\","
          + "\"message_id_hint\":\"m\",\"author\":{\"pubkey\":\"p\"},\"command\":\"echo\","
          + "\"args_raw\":\"hi there\",\"args_tokens\":[\"hi\",\"there\"]}";

  static Map<String, List<String>> signed(String body, long ts) {
    byte[] sig = HUB.sign(Webhooks.deliverySigningBytes(ts, Bytes.utf8(body)));
    Map<String, List<String>> h = new HashMap<>();
    h.put("X-Wavvon-Hub-Pubkey", List.of(HUB.publicKey()));
    h.put("X-Wavvon-Signature-V1", List.of(Bytes.toHex(sig)));
    h.put("X-Wavvon-Timestamp", List.of(String.valueOf(ts)));
    return h;
  }

  @Test
  void hkdfMatchesRfc5869() {
    byte[] ikm = AuthTest.filled(22, 0x0b);
    assertEquals(
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        Bytes.toHex(
            Webhooks.hkdfSha256(
                ikm,
                Bytes.fromHex("000102030405060708090a0b0c"),
                Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9"),
                42)));
    assertEquals(
        "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
        Bytes.toHex(Webhooks.hkdfSha256(ikm, new byte[0], new byte[0], 42)));
  }

  @Test
  void acceptsACorrectlySignedInvocationAndDecodesIt() {
    Dispatch d =
        Webhooks.verifyDispatch(signed(SLASH, NOW), Bytes.utf8(SLASH), HUB.publicKey(), AT_NOW);
    Dispatch.SlashInvocation inv = assertInstanceOf(Dispatch.SlashInvocation.class, d);
    assertEquals("echo", inv.command());
    assertEquals(List.of("hi", "there"), inv.argsTokens());
    assertEquals("p", inv.author().pubkey());
  }

  @Test
  void refusesATamperedBodyAStaleTimestampAMissingV1HeaderAndAForeignHub() {
    Map<String, List<String>> h = signed(SLASH, NOW);
    String key = HUB.publicKey();
    assertThrows(
        WebhookVerificationException.class,
        () -> Webhooks.verifyDispatch(h, Bytes.utf8(SLASH.replace("echo", "ban")), key, AT_NOW));
    assertThrows(
        WebhookVerificationException.class,
        () ->
            Webhooks.verifyDispatch(
                h, Bytes.utf8(SLASH), key, Webhooks.VerifyOptions.at(NOW + 301)));
    Webhooks.verifyDispatch(h, Bytes.utf8(SLASH), key, Webhooks.VerifyOptions.at(NOW + 300));
    Map<String, List<String>> legacyOnly = new HashMap<>(h);
    legacyOnly.remove("X-Wavvon-Signature-V1");
    assertThrows(
        WebhookVerificationException.class,
        () -> Webhooks.verifyDispatch(legacyOnly, Bytes.utf8(SLASH), key, AT_NOW));
    String other = Identity.fromSeed(AuthTest.filled(32, 6)).publicKey();
    assertThrows(
        WebhookVerificationException.class,
        () -> Webhooks.verifyDispatch(h, Bytes.utf8(SLASH), other, AT_NOW));
  }

  @Test
  void deliversAnUnknownDispatchTypeInsteadOfDroppingIt() {
    String body = "{\"type\":\"modal_submit\",\"x\":1}";
    Dispatch.UnknownDispatch u =
        assertInstanceOf(
            Dispatch.UnknownDispatch.class,
            Webhooks.verifyDispatch(signed(body, NOW), Bytes.utf8(body), HUB.publicKey(), AT_NOW));
    assertEquals("modal_submit", u.rawType());
  }

  @Test
  void handlerAnswersAVerifiedInvocationWithTheReplyAndAnUnverifiedOneWith401() {
    DispatchHandler handler =
        DispatchHandler.builder(HUB.publicKey())
            .verifyOptions(AT_NOW)
            .onSlashCommand(inv -> AppResponse.ephemeralReply(inv.argsRaw()))
            .build();
    DispatchHandler.Response ok = handler.handle("POST", signed(SLASH, NOW), Bytes.utf8(SLASH));
    assertEquals(200, ok.status());
    assertEquals(
        "{\"reply\":{\"body\":\"hi there\"},\"ephemeral\":true}",
        new String(ok.body(), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(401, handler.handle("POST", Map.of(), Bytes.utf8(SLASH)).status());
    assertEquals(405, handler.handle("GET", Map.of(), new byte[0]).status());
  }

  static final String SECRET = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc";
  static final String EVENT =
      "{\"type\":\"hub_event\",\"seq\":3,\"event\":\"member.joined\",\"hub_url\":\"h\",\"at\":"
          + NOW
          + ",\"payload\":{}}";

  static Map<String, List<String>> outgoingHeaders() {
    byte[] sig =
        Webhooks.hmacSha256(
            Webhooks.outgoingWebhookSigningKey(SECRET),
            Webhooks.deliverySigningBytes(NOW, Bytes.utf8(EVENT)));
    return Map.of(
        "x-wavvon-signature-v1", List.of(Bytes.toHex(sig)),
        "x-wavvon-timestamp", List.of(String.valueOf(NOW)));
  }

  @Test
  void acceptsTheOutgoingSecretAsShownAndAsDecodedBytes() {
    assertEquals(
        3,
        Webhooks.verifyOutgoingWebhook(outgoingHeaders(), Bytes.utf8(EVENT), SECRET, AT_NOW).seq());
    byte[] raw = new byte[32];
    Arrays.fill(raw, (byte) 7);
    assertEquals(
        "member.joined",
        Webhooks.verifyOutgoingWebhook(outgoingHeaders(), Bytes.utf8(EVENT), raw, AT_NOW).event());
  }

  @Test
  void refusesTheWrongSecretOrAReplayOutsideTheWindow() {
    assertThrows(
        WebhookVerificationException.class,
        () -> Webhooks.verifyOutgoingWebhook(outgoingHeaders(), Bytes.utf8(EVENT), "AAAA", AT_NOW));
    assertThrows(
        WebhookVerificationException.class,
        () ->
            Webhooks.verifyOutgoingWebhook(
                outgoingHeaders(),
                Bytes.utf8(EVENT),
                SECRET,
                Webhooks.VerifyOptions.at(NOW - 400)));
  }
}
