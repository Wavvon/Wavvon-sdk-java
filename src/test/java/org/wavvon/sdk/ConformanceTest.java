package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts the tier-1 vectors from Wavvon-server's {@code crates/identity/wire-vectors.json}, the
 * one authority for every signed byte sequence (sdk.md §4). The file is read, never copied in:
 * point {@code WAVVON_WIRE_VECTORS} at a pinned copy, or keep the default sibling checkout.
 */
class ConformanceTest {
  static final Path PATH =
      Path.of(
              System.getenv()
                  .getOrDefault(
                      "WAVVON_WIRE_VECTORS", "../../server/crates/identity/wire-vectors.json"))
          .toAbsolutePath()
          .normalize();
  static JsonNode file;

  @BeforeAll
  static void load() throws Exception {
    if (!Files.exists(PATH)) {
      String bar = "!".repeat(72);
      System.err.printf(
          "%n%s%n!! CONFORMANCE VECTORS NOT FOUND at %s%n"
              + "!! The tier-1 wire vectors were NOT checked. Set WAVVON_WIRE_VECTORS.%n%s%n%n",
          bar, PATH, bar);
      if (System.getenv("CI") != null) fail("wire vectors missing at " + PATH);
      Assumptions.abort("wire vectors missing at " + PATH);
    }
    file = Json.MAPPER.readTree(Files.readAllBytes(PATH));
  }

  /** "@name" anywhere in the file means fixtures[name]. */
  static JsonNode resolve(JsonNode v) {
    if (v.isTextual() && v.asText().startsWith("@")) {
      JsonNode f = file.path("fixtures").get(v.asText().substring(1));
      if (f == null) throw new IllegalStateException("unknown fixture " + v.asText());
      return TextNode.valueOf(f.asText());
    }
    if (v.isObject()) {
      ObjectNode out = Json.MAPPER.createObjectNode();
      v.fields().forEachRemaining(e -> out.set(e.getKey(), resolve(e.getValue())));
      return out;
    }
    return v;
  }

  static JsonNode vector(String name) {
    JsonNode v = file.path("vectors").get(name);
    if (v == null) throw new IllegalStateException("vector " + name + " missing from " + PATH);
    return resolve(v);
  }

  static String hex(JsonNode v, String field) {
    return v.get(field).asText();
  }

  @Test
  void speaksFormat1() {
    assertEquals(1, file.get("format").asInt());
  }

  @Test
  void authChallenge() {
    JsonNode v = vector("auth_challenge");
    byte[] bytes =
        Auth.challengeSigningBytes(
            Bytes.fromHex(v.at("/inputs/challenge").asText()), "tagged-v1", null);
    assertEquals(hex(v, "signing_bytes"), Bytes.toHex(bytes));
    assertEquals(
        hex(v, "signature"),
        Bytes.toHex(Identity.fromSeed(v.at("/inputs/signer_seed").asText()).sign(bytes)));
  }

  @Test
  void boundAuthChallenge() {
    JsonNode v = vector("bound_auth_challenge");
    byte[] bytes =
        Auth.challengeSigningBytes(
            Bytes.fromHex(v.at("/inputs/challenge").asText()),
            "tagged-v2",
            v.at("/inputs/server_pubkey").asText());
    assertEquals(hex(v, "signing_bytes"), Bytes.toHex(bytes));
    assertEquals(
        hex(v, "signature"),
        Bytes.toHex(Identity.fromSeed(v.at("/inputs/signer_seed").asText()).sign(bytes)));
  }

  @Test
  void webhookDeliveryHub() {
    JsonNode v = vector("webhook_delivery_hub");
    byte[] body = Bytes.utf8(v.at("/inputs/body").asText());
    long ts = v.at("/inputs/timestamp").asLong();
    Identity hub = Identity.fromSeed(v.at("/inputs/hub_seed").asText());
    byte[] bytes = Webhooks.deliverySigningBytes(ts, body);
    assertEquals(hex(v, "signing_bytes"), Bytes.toHex(bytes));
    assertEquals(hex(v, "hub_pubkey"), hub.publicKey());
    assertEquals(hex(v, "signature_v1"), Bytes.toHex(hub.sign(bytes)));

    JsonNode h = v.get("headers");
    Map<String, List<String>> headers =
        Map.of(
            h.get("hub_pubkey").asText(), List.of(hex(v, "hub_pubkey")),
            h.get("signature_v1").asText(), List.of(hex(v, "signature_v1")),
            h.get("timestamp").asText(), List.of(String.valueOf(ts)));
    assertInstanceOf(
        Dispatch.SlashInvocation.class,
        Webhooks.verifyDispatch(
            headers, body, hex(v, "hub_pubkey"), Webhooks.VerifyOptions.at(ts)));
  }

  @Test
  void webhookDeliveryOutgoing() {
    JsonNode v = vector("webhook_delivery_outgoing");
    assertEquals("wavvon-webhook-signing", v.at("/inputs/hkdf_salt").asText());
    assertEquals("", v.at("/inputs/hkdf_info").asText());
    byte[] key = Webhooks.outgoingWebhookSigningKey(v.at("/inputs/secret_as_shown").asText());
    assertEquals(hex(v, "signing_key"), Bytes.toHex(key));
    assertEquals(
        hex(v, "signing_key"),
        Bytes.toHex(
            Webhooks.outgoingWebhookSigningKey(
                Bytes.fromHex(v.at("/inputs/raw_secret").asText()))));

    byte[] body = Bytes.utf8(v.at("/inputs/body").asText());
    long ts = v.at("/inputs/timestamp").asLong();
    byte[] bytes = Webhooks.deliverySigningBytes(ts, body);
    assertEquals(hex(v, "signing_bytes"), Bytes.toHex(bytes));
    assertEquals(hex(v, "signature_v1"), Bytes.toHex(Webhooks.hmacSha256(key, bytes)));

    JsonNode h = v.get("headers");
    Map<String, List<String>> headers =
        Map.of(
            h.get("signature_v1").asText(), List.of(hex(v, "signature_v1")),
            h.get("timestamp").asText(), List.of(String.valueOf(ts)),
            h.get("webhook_id").asText(), List.of("wh_test"));
    OutgoingWebhookEvent event =
        Webhooks.verifyOutgoingWebhook(
            headers, body, v.at("/inputs/secret_as_shown").asText(), Webhooks.VerifyOptions.at(ts));
    assertEquals("hub_event", event.type());
  }

  @Test
  void masterDhPubkey() {
    JsonNode v = vector("master_dh_pubkey");
    assertEquals(
        hex(v, "dh_pubkey"), Identity.fromSeed(v.at("/inputs/seed").asText()).dhPublicKey());
  }

  @Test
  void dhKeyRecord() {
    JsonNode v = vector("dh_key_record");
    String pub = v.at("/inputs/pubkey").asText();
    String dh = v.at("/inputs/dh_pubkey").asText();
    byte[] bytes = DhKeys.signingBytes(pub, dh);
    assertEquals(hex(v, "signing_bytes"), Bytes.toHex(bytes));
    assertEquals(
        hex(v, "signature"),
        Bytes.toHex(Identity.fromSeed(v.at("/inputs/signer_seed").asText()).sign(bytes)));
    assertTrue(DhKeys.verifyRecord(pub, dh, hex(v, "signature")));
    assertFalse(DhKeys.verifyRecord(pub, pub, hex(v, "signature")));
  }

  @Test
  void voiceKeyWrap() throws Exception {
    JsonNode v = vector("voice_key_wrap");
    Identity sender = Identity.fromSeed(v.at("/inputs/sender_seed").asText());
    Identity recipient = Identity.fromSeed(v.at("/inputs/recipient_seed").asText());
    String channel = v.at("/inputs/channel_id").asText();
    long keyId = v.at("/inputs/key_id").asLong();
    byte[] nonce = Bytes.fromHex(v.at("/inputs/wrap_nonce").asText());
    byte[] ct =
        VoiceCrypto.keyWrap(
            sender,
            Bytes.fromHex(recipient.dhPublicKey()),
            channel,
            Bytes.fromHex(v.at("/inputs/sender_key").asText()),
            Bytes.fromHex(v.at("/inputs/nonce_salt").asText()),
            keyId,
            nonce);
    assertEquals(hex(v, "ciphertext"), Bytes.toHex(ct));
    VoiceCrypto.Unwrapped k =
        VoiceCrypto.keyUnwrap(recipient, Bytes.fromHex(sender.dhPublicKey()), channel, ct, nonce);
    assertEquals(v.at("/inputs/sender_key").asText(), Bytes.toHex(k.senderKey()));
    assertEquals(v.at("/inputs/nonce_salt").asText(), Bytes.toHex(k.nonceSalt()));
    assertEquals(keyId, k.keyId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"voice_packet_ctr0", "voice_packet_ctr7"})
  void voicePacket(String name) throws Exception {
    JsonNode v = vector(name);
    byte[] key = Bytes.fromHex(v.at("/inputs/sender_key").asText());
    byte[] salt = Bytes.fromHex(v.at("/inputs/nonce_salt").asText());
    long keyId = v.at("/inputs/key_id").asLong();
    long ctr = v.at("/inputs/counter").asLong();
    long ts = v.at("/inputs/timestamp").asLong();
    byte[] opus = Bytes.fromHex(v.at("/inputs/opus").asText());
    byte[] packet = VoiceCrypto.packetSeal(key, salt, keyId, ctr, ts, opus);
    assertEquals(hex(v, "packet"), Bytes.toHex(packet));
    VoiceCrypto.Opened o = VoiceCrypto.packetOpen(key, salt, packet, 0);
    assertEquals(List.of(keyId, ctr, ts), List.of(o.keyId(), o.ctr(), o.ts()));
    assertArrayEquals(opus, o.opus());
    packet[packet.length - 1] ^= 1;
    assertThrows(
        GeneralSecurityException.class, () -> VoiceCrypto.packetOpen(key, salt, packet, 0));
  }
}
