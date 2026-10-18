package org.wavvon.sdk;

/**
 * A hub session.
 *
 * @param token the bearer token
 * @param scope "member", or "lobby" on a hub that holds newcomers back
 * @param canonicalPubkey the identity the hub attributes this session's actions to
 */
public record Session(String token, String scope, String canonicalPubkey) {}
