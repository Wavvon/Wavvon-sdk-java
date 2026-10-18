package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies what the hub POSTs to a program: slash command and component dispatches (Ed25519 by the
 * hub key) and outgoing-webhook deliveries (HMAC-SHA256). Both check {@code X-Wavvon-Signature-V1}
 * over {@code tag || u64le(timestamp) || body} within a time window; the older body-only signature
 * is deliberately not accepted, because on its own a captured request replays forever.
 */
public final class Webhooks {
  private static final byte[] DELIVERY_TAG = Bytes.utf8("wavvon/webhook-delivery/v1\0");
  private static final byte[] HKDF_SALT = Bytes.utf8("wavvon-webhook-signing");

  private Webhooks() {}

  /**
   * Verification settings.
   *
   * @param now the current time; null means the clock
   * @param tolerance maximum |now - X-Wavvon-Timestamp|; default 300 s
   */
  public record VerifyOptions(Instant now, Duration tolerance) {
    /** The clock and 300 seconds. */
    public static final VerifyOptions DEFAULTS = new VerifyOptions(null, Duration.ofSeconds(300));

    /**
     * @param epochSeconds a fixed time, for tests
     * @return options at that time with the default window
     */
    public static VerifyOptions at(long epochSeconds) {
      return new VerifyOptions(Instant.ofEpochSecond(epochSeconds), Duration.ofSeconds(300));
    }
  }

  /**
   * {@code "wavvon/webhook-delivery/v1\0" || u64le(timestamp) || body} (wire-format.md, "Webhook
   * delivery").
   *
   * @param timestamp Unix seconds
   * @param body the raw request body
   * @return the signed bytes
   */
  public static byte[] deliverySigningBytes(long timestamp, byte[] body) {
    return Bytes.concat(DELIVERY_TAG, Bytes.u64le(timestamp), body);
  }

  /**
   * The HMAC key for an outgoing webhook: HKDF-SHA256 over the secret's decoded bytes, salt {@code
   * wavvon-webhook-signing}, empty info, 32 bytes.
   *
   * @param secretAsShown the secret as the admin was shown it (base64url, no padding)
   * @return the 32-byte key
   */
  public static byte[] outgoingWebhookSigningKey(String secretAsShown) {
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(secretAsShown.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("secret is not base64url", e);
    }
    return outgoingWebhookSigningKey(raw);
  }

  /**
   * @param rawSecret the decoded secret bytes
   * @return the 32-byte key
   */
  public static byte[] outgoingWebhookSigningKey(byte[] rawSecret) {
    return hkdfSha256(rawSecret, HKDF_SALT, new byte[0], 32);
  }

  /** RFC 5869 extract-then-expand; JDK 17 has HMAC but no HKDF. */
  static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
    byte[] prk = hmacSha256(salt.length == 0 ? new byte[32] : salt, ikm);
    byte[] out = new byte[length];
    byte[] t = new byte[0];
    for (int i = 1, at = 0; at < length; i++) {
      t = hmacSha256(prk, Bytes.concat(t, info, new byte[] {(byte) i}));
      int n = Math.min(t.length, length - at);
      System.arraycopy(t, 0, out, at, n);
      at += n;
    }
    return out;
  }

  static byte[] hmacSha256(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Verifies a slash command or component dispatch and decodes it. {@code hubPublicKey} is the
   * hub's key as the program pinned it ({@code GET /info} {@code public_key}); the {@code
   * X-Wavvon-Hub-Pubkey} header is only compared against it, never trusted. Pass the raw request
   * bytes: re-serialised JSON will not verify.
   *
   * @param headers the request headers; names are matched case-insensitively
   * @param body the raw request body
   * @param hubPublicKey the pinned hub key, hex
   * @param opts the clock and window
   * @return the decoded dispatch
   * @throws WebhookVerificationException answer 401 and do nothing
   */
  public static Dispatch verifyDispatch(
      Map<String, List<String>> headers, byte[] body, String hubPublicKey, VerifyOptions opts) {
    String sent = header(headers, "X-Wavvon-Hub-Pubkey");
    if (sent != null && !sent.equalsIgnoreCase(hubPublicKey)) {
      throw new WebhookVerificationException("signed by a different hub");
    }
    Signed s = signedTimestamp(headers, opts);
    if (!Identity.verify(hubPublicKey, deliverySigningBytes(s.ts, body), s.sig)) {
      throw new WebhookVerificationException("bad signature");
    }
    JsonNode raw = parseObject(body);
    String type = raw.path("type").asText("");
    try {
      if (type.equals("slash_command")) {
        return Json.MAPPER.treeToValue(raw, Dispatch.SlashInvocation.class);
      }
      if (type.equals("component_interaction")) {
        return Json.MAPPER.treeToValue(raw, Dispatch.ComponentInteraction.class);
      }
    } catch (Exception e) {
      // a known type whose shape does not parse is still delivered, as unknown
    }
    return new Dispatch.UnknownDispatch(type, raw);
  }

  /**
   * {@link #verifyDispatch(Map, byte[], String, VerifyOptions)} with the clock and 300 s window.
   *
   * @param headers the request headers
   * @param body the raw request body
   * @param hubPublicKey the pinned hub key, hex
   * @return the decoded dispatch
   */
  public static Dispatch verifyDispatch(
      Map<String, List<String>> headers, byte[] body, String hubPublicKey) {
    return verifyDispatch(headers, body, hubPublicKey, VerifyOptions.DEFAULTS);
  }

  /**
   * Verifies an outgoing-webhook delivery (HMAC-SHA256) and decodes its hub_event envelope.
   *
   * @param headers the request headers; names are matched case-insensitively
   * @param body the raw request body
   * @param secretAsShown what the admin copied when the webhook was created or rotated
   * @param opts the clock and window
   * @return the decoded event
   * @throws WebhookVerificationException answer 401 and do nothing
   */
  public static OutgoingWebhookEvent verifyOutgoingWebhook(
      Map<String, List<String>> headers, byte[] body, String secretAsShown, VerifyOptions opts) {
    return verifyOutgoing(headers, body, outgoingWebhookSigningKey(secretAsShown), opts);
  }

  /**
   * As {@link #verifyOutgoingWebhook(Map, byte[], String, VerifyOptions)}, with the decoded secret.
   *
   * @param headers the request headers
   * @param body the raw request body
   * @param rawSecret the decoded secret bytes
   * @param opts the clock and window
   * @return the decoded event
   */
  public static OutgoingWebhookEvent verifyOutgoingWebhook(
      Map<String, List<String>> headers, byte[] body, byte[] rawSecret, VerifyOptions opts) {
    return verifyOutgoing(headers, body, outgoingWebhookSigningKey(rawSecret), opts);
  }

  private static OutgoingWebhookEvent verifyOutgoing(
      Map<String, List<String>> headers, byte[] body, byte[] key, VerifyOptions opts) {
    Signed s = signedTimestamp(headers, opts);
    byte[] expected = hmacSha256(key, deliverySigningBytes(s.ts, body));
    if (!MessageDigest.isEqual(s.sig, expected)) {
      throw new WebhookVerificationException("bad signature");
    }
    try {
      return Json.MAPPER.treeToValue(parseObject(body), OutgoingWebhookEvent.class);
    } catch (Exception e) {
      throw new WebhookVerificationException("body is not a hub_event envelope");
    }
  }

  private record Signed(long ts, byte[] sig) {}

  private static Signed signedTimestamp(Map<String, List<String>> headers, VerifyOptions opts) {
    String sigHex = header(headers, "X-Wavvon-Signature-V1");
    String tsText = header(headers, "X-Wavvon-Timestamp");
    if (sigHex == null || tsText == null) {
      throw new WebhookVerificationException("missing X-Wavvon-Signature-V1 or X-Wavvon-Timestamp");
    }
    if (!tsText.matches("\\d{1,19}")) {
      throw new WebhookVerificationException("malformed X-Wavvon-Timestamp");
    }
    long ts;
    try {
      ts = Long.parseLong(tsText);
    } catch (NumberFormatException e) {
      throw new WebhookVerificationException("malformed X-Wavvon-Timestamp");
    }
    long now = (opts.now() != null ? opts.now() : Instant.now()).getEpochSecond();
    if (Math.abs(now - ts) > opts.tolerance().getSeconds()) {
      throw new WebhookVerificationException("timestamp outside the allowed window");
    }
    try {
      return new Signed(ts, Bytes.fromHex(sigHex));
    } catch (IllegalArgumentException e) {
      throw new WebhookVerificationException("malformed signature");
    }
  }

  private static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
        List<String> v = e.getValue();
        return v == null || v.isEmpty() ? null : v.get(0);
      }
    }
    return null;
  }

  private static JsonNode parseObject(byte[] body) {
    try {
      JsonNode v = Json.MAPPER.readTree(body);
      if (v != null && v.isObject()) return v;
    } catch (Exception e) {
      // reported below
    }
    throw new WebhookVerificationException("body is not a JSON object");
  }
}
