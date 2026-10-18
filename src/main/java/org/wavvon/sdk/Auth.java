package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Challenge-response login (wire-format.md, "Auth challenge answer"). */
public final class Auth {
  /** The scheme a current hub names in its challenge. */
  public static final String CHALLENGE_SCHEME = "tagged-v1";

  /** The only length a raw (untagged) challenge is ever signed at. */
  public static final int CHALLENGE_LEN = 32;

  private static final byte[] TAG = Bytes.utf8("wavvon/auth-challenge/v1\0");

  private Auth() {}

  /**
   * The bytes a client signs to answer a challenge. {@code tagged-v1}: the domain prefix plus the
   * challenge. No scheme (an older hub): the raw challenge, and only at exactly 32 bytes, so this
   * can never be used to sign arbitrary bytes. Any other scheme is refused rather than guessed at.
   *
   * @param challenge the decoded challenge
   * @param scheme the challenge's {@code scheme}, or null when the hub sent none
   * @return the bytes to sign
   * @throws AuthSchemeException for an unknown scheme or a raw challenge of the wrong length
   */
  public static byte[] challengeSigningBytes(byte[] challenge, String scheme) {
    if (CHALLENGE_SCHEME.equals(scheme)) return Bytes.concat(TAG, challenge);
    if (scheme == null) {
      if (challenge.length != CHALLENGE_LEN) {
        throw new AuthSchemeException(
            "refusing to sign a raw "
                + challenge.length
                + "-byte challenge; only "
                + CHALLENGE_LEN
                + " bytes are ever signed raw");
      }
      return challenge.clone();
    }
    throw new AuthSchemeException("unknown auth challenge scheme \"" + scheme + "\"");
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
              JsonNode scheme = body.path("scheme");
              byte[] signature =
                  identity.sign(
                      challengeSigningBytes(
                          Bytes.fromHex(challenge), scheme.isTextual() ? scheme.asText() : null));
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
