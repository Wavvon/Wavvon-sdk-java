package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        Auth.challengeSigningBytes(Bytes.fromHex(v.at("/inputs/challenge").asText()), "tagged-v1");
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
    assertEquals(hex(v, "signature_legacy"), Bytes.toHex(hub.sign(body)));

    JsonNode h = v.get("headers");
    Map<String, List<String>> headers =
        Map.of(
            h.get("hub_pubkey").asText(), List.of(hex(v, "hub_pubkey")),
            h.get("signature_v1").asText(), List.of(hex(v, "signature_v1")),
            h.get("signature_legacy").asText(), List.of(hex(v, "signature_legacy")),
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
    assertEquals(hex(v, "signature_legacy"), Bytes.toHex(Webhooks.hmacSha256(key, body)));

    JsonNode h = v.get("headers");
    Map<String, List<String>> headers =
        Map.of(
            h.get("signature_v1").asText(), List.of(hex(v, "signature_v1")),
            h.get("signature_legacy").asText(), List.of(hex(v, "signature_legacy")),
            h.get("timestamp").asText(), List.of(String.valueOf(ts)),
            h.get("webhook_id").asText(), List.of("wh_test"));
    OutgoingWebhookEvent event =
        Webhooks.verifyOutgoingWebhook(
            headers, body, v.at("/inputs/secret_as_shown").asText(), Webhooks.VerifyOptions.at(ts));
    assertEquals("hub_event", event.type());
  }
}
