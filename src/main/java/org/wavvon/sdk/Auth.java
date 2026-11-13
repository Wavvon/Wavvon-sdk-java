package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Challenge-response login (wire-format.md, "Auth challenge answer"). */
public final class Auth {
  /** The scheme an older hub names in its challenge: the answer names no server. */
  public static final String CHALLENGE_SCHEME = "tagged-v1";

  /** The scheme a current hub or farm names in its challenge: the answer is bound to its key. */
  public static final String BOUND_CHALLENGE_SCHEME = "tagged-v2";

  private static final int CHALLENGE_LENGTH = 32;
  private static final byte[] TAG = Bytes.utf8("wavvon/auth-challenge/v1\0");
  private static final byte[] BOUND_TAG = Bytes.utf8("wavvon/auth-challenge/v2\0");

  private Auth() {}

  /**
   * The bytes a client signs to answer a challenge. Under {@code tagged-v2}: the v2 tag, the server
   * public key (u32 little-endian length, lowercase hex), then the challenge. Under {@code
   * tagged-v1}: the v1 tag plus the challenge. Anything else, a missing scheme included, is
   * refused, and so is a challenge that is not 32 bytes: a raw answer would sign bytes the hub
   * chose.
   *
   * @param challenge the decoded challenge
   * @param scheme the challenge's {@code scheme}, or null when the hub sent none
   * @param serverPubkey under {@code tagged-v2}, the key the server's own {@code GET /info}
   *     presented, never one from the challenge response, or a hostile hub could relay another
   *     hub's challenge and sign in there; unused under {@code tagged-v1}
   * @return the bytes to sign
   * @throws AuthSchemeException for a missing or unknown scheme or a challenge of the wrong length
   */
  public static byte[] challengeSigningBytes(byte[] challenge, String scheme, String serverPubkey) {
    if (scheme == null) throw new AuthSchemeException("hub named no auth challenge scheme");
    if (!CHALLENGE_SCHEME.equals(scheme) && !BOUND_CHALLENGE_SCHEME.equals(scheme)) {
      throw new AuthSchemeException("unknown auth challenge scheme \"" + scheme + "\"");
    }
    if (challenge.length != CHALLENGE_LENGTH) {
      throw new AuthSchemeException(
          "auth challenge is " + challenge.length + " bytes, expected " + CHALLENGE_LENGTH);
    }
    if (CHALLENGE_SCHEME.equals(scheme)) return Bytes.concat(TAG, challenge);
    if (serverPubkey == null || serverPubkey.isEmpty()) {
      throw new AuthSchemeException("tagged-v2 needs the server public key");
    }
    byte[] key = Bytes.utf8(serverPubkey);
    byte[] length =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(key.length).array();
    return Bytes.concat(BOUND_TAG, length, key, challenge);
  }

  /**
   * Challenge, sign, verify. A transient failure at either step restarts from a fresh challenge,
   * because the hub drops a pending challenge as soon as verify reads it.
   *
   * @param http the HTTP client to use
   * @param hubUrl the hub's base URL
   * @param identity who logs in
   * @param inviteCode admits the identity once on an invite_only hub; may be null
   * @param retry the retry budget
   * @return the session
   * @throws HubException the hub refused the login
   * @throws HubUnavailableException the hub stayed unreachable or busy
   * @throws InterruptedException the thread was interrupted
   */
  public static Session authenticate(
      HttpClient http, String hubUrl, Identity identity, String inviteCode, RetryOptions retry)
      throws InterruptedException {
    String base = hubUrl.replaceAll("/+$", "");
    String[] serverPubkey = {null};
    HttpResponse<String> res =
        Retry.send(
            timeout -> {
              HttpResponse<String> ch =
                  post(
                      http,
                      base + "/auth/challenge",
                      Map.of("public_key", identity.publicKey()),
                      timeout);
              if (ch.statusCode() / 100 != 2) return ch;
              JsonNode body = Json.MAPPER.readTree(ch.body());
              String challenge = body.path("challenge").asText();
              JsonNode schemeNode = body.path("scheme");
              String scheme = schemeNode.isTextual() ? schemeNode.asText() : null;
              if (BOUND_CHALLENGE_SCHEME.equals(scheme) && serverPubkey[0] == null) {
                HttpResponse<String> info =
                    http.send(
                        HttpRequest.newBuilder(URI.create(base + "/info")).timeout(timeout).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (info.statusCode() / 100 != 2) return info;
                serverPubkey[0] = Json.MAPPER.readTree(info.body()).path("public_key").asText("");
              }
              byte[] signature =
                  identity.sign(
                      challengeSigningBytes(Bytes.fromHex(challenge), scheme, serverPubkey[0]));
              Map<String, Object> verify = new LinkedHashMap<>();
              verify.put("public_key", identity.publicKey());
              verify.put("challenge", challenge);
              verify.put("signature", Bytes.toHex(signature));
              if (inviteCode != null) verify.put("invite_code", inviteCode);
              return post(http, base + "/auth/verify", verify, timeout);
            },
            retry);
    if (res.statusCode() / 100 != 2) throw new HubException(res.statusCode(), res.body());
    try {
      JsonNode body = Json.MAPPER.readTree(res.body());
      String canonical = body.path("canonical_pubkey").asText("");
      return new Session(
          body.path("token").asText(),
          body.path("scope").asText("member"),
          canonical.isEmpty() ? identity.publicKey() : canonical);
    } catch (IOException e) {
      throw new HubException(res.statusCode(), "unreadable /auth/verify answer: " + res.body());
    }
  }

  private static HttpResponse<String> post(
      HttpClient http, String url, Object body, Duration timeout)
      throws IOException, InterruptedException {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }
}
