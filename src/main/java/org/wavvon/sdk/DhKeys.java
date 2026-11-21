package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The DH key record (wire-format.md, DhKeyRecord): publish, fetch, verify, pin. */
final class DhKeys {
  private static final byte[] DOMAIN = Bytes.utf8("wavvon/dh-key/v1\0");

  private DhKeys() {}

  private static byte[] lenPrefixed(String s) {
    byte[] b = Bytes.utf8(s);
    return ByteBuffer.allocate(4 + b.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(b.length)
        .put(b)
        .array();
  }

  /** What {@code pubkey} signs to publish its DH key. */
  static byte[] signingBytes(String pubkey, String dhPubkeyHex) {
    return Bytes.concat(DOMAIN, lenPrefixed(pubkey), lenPrefixed(dhPubkeyHex));
  }

  static boolean verifyRecord(String pubkey, String dhPubkeyHex, String signatureHex) {
    try {
      return Identity.verify(
          pubkey, signingBytes(pubkey, dhPubkeyHex), Bytes.fromHex(signatureHex));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The DH key {@code pubkey} published, verified; null on a 404 or a record without a key. A
   * record the peer did not sign throws: the hub is not trusted with it.
   */
  static String fetch(WavvonClient client, String pubkey) {
    JsonNode res;
    try {
      res =
          client.send(
              "GET",
              "/identity/" + URLEncoder.encode(pubkey, StandardCharsets.UTF_8) + "/dh-key",
              null);
    } catch (HubException e) {
      if (e.status() == 404) return null;
      throw e;
    }
    String dh = res == null ? "" : res.path("dh_pubkey_hex").asText("");
    if (dh.isEmpty()) return null;
    JsonNode sig = res.get("signature_hex");
    if (sig == null || !sig.isTextual() || !verifyRecord(pubkey, dh, sig.asText())) {
      throw new DhKeyUnsignedException(pubkey);
    }
    return dh;
  }

  static boolean publish(WavvonClient client) {
    Identity id = client.identity();
    String own = id.publicKey();
    String dh = id.dhPublicKey();
    try {
      if (dh.equals(fetch(client, own))) return false;
    } catch (DhKeyUnsignedException e) {
      // replaced below with a record this identity signs
    }
    String signature = Bytes.toHex(id.sign(signingBytes(own, dh)));
    client.send(
        "PUT",
        "/identity/" + own + "/dh-key",
        Map.of("dh_pubkey_hex", dh, "signature_hex", signature));
    return true;
  }

  static byte[] peer(WavvonClient client, DhPinStore pins, String pubkey) {
    String dh = fetch(client, pubkey);
    if (dh == null) throw new RecipientHasNoKeyException(pubkey);
    byte[] raw;
    try {
      raw = Bytes.fromHex(dh);
    } catch (IllegalArgumentException e) {
      throw new DhKeyUnsignedException(pubkey);
    }
    if (raw.length != 32) throw new DhKeyUnsignedException(pubkey);
    String key = "dh-pin/" + pubkey;
    String pinned = pins.get(key);
    if (pinned == null) {
      pins.put(key, dh);
    } else if (!pinned.equals(dh)) {
      throw new DhKeyChangedException(pubkey, pinned, dh);
    }
    return raw;
  }

  static void accept(WavvonClient client, DhPinStore pins, String pubkey) {
    String dh = fetch(client, pubkey);
    if (dh == null) throw new RecipientHasNoKeyException(pubkey);
    pins.put("dh-pin/" + pubkey, dh);
  }
}
