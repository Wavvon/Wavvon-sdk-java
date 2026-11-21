package org.wavvon.sdk;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPrivateKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/**
 * A keypair the program holds: Ed25519 from the JDK, plus the X25519 key every Wavvon client
 * derives from the same seed (voice keys are wrapped to it). The seed never leaves this object;
 * only signatures and public keys do.
 */
public final class Identity {
  // DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519; the raw key follows.
  private static final byte[] X509_PREFIX = Bytes.fromHex("302a300506032b6570032100");
  private static final byte[] X25519_PREFIX = Bytes.fromHex("302a300506032b656e032100");
  private static final byte[] X25519_BASE = new byte[32];

  static {
    X25519_BASE[0] = 9;
  }

  private final PrivateKey privateKey;
  private final String publicKey;
  private final PrivateKey dhPrivateKey;
  private final String dhPublicKey;

  private Identity(byte[] seed) {
    if (seed.length != 32) throw new IllegalArgumentException("an Ed25519 seed is 32 bytes");
    try {
      privateKey =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed.clone()));
      // The JDK derives a public key only while generating a pair; hand it the seed as its
      // randomness so it derives ours.
      KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
      gen.initialize(NamedParameterSpec.ED25519, new FixedRandom(seed));
      byte[] der = gen.generateKeyPair().getPublic().getEncoded();
      publicKey = Bytes.toHex(Arrays.copyOfRange(der, der.length - 32, der.length));
      // SHA-512(seed)[0..32], clamped: the scalar every Wavvon client derives.
      byte[] h = MessageDigest.getInstance("SHA-512").digest(seed);
      byte[] scalar = Arrays.copyOf(h, 32);
      scalar[0] &= (byte) 248;
      scalar[31] &= 127;
      scalar[31] |= 64;
      dhPrivateKey =
          KeyFactory.getInstance("X25519")
              .generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar));
      Arrays.fill(h, (byte) 0);
      Arrays.fill(scalar, (byte) 0);
      dhPublicKey = Bytes.toHex(x25519(X25519_BASE));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("this JDK has no Ed25519", e);
    }
  }

  /**
   * Builds an identity from a 32-byte Ed25519 seed the operator keeps in a file or environment
   * variable.
   *
   * @param seedHex the seed as 64 hex characters
   * @return the identity
   */
  public static Identity fromSeed(String seedHex) {
    return new Identity(Bytes.fromHex(seedHex.trim()));
  }

  /**
   * Builds an identity from a 32-byte Ed25519 seed.
   *
   * @param seed the seed bytes; copied
   * @return the identity
   */
  public static Identity fromSeed(byte[] seed) {
    return new Identity(seed);
  }

  /**
   * @return the Ed25519 public key, lowercase hex
   */
  public String publicKey() {
    return publicKey;
  }

  /**
   * @return the X25519 public key derived from the seed, lowercase hex: what peers wrap voice keys
   *     to. Published with {@link WavvonClient#publishDhKey()}.
   */
  public String dhPublicKey() {
    return dhPublicKey;
  }

  /**
   * HKDF-SHA256 over X25519(own DH secret, peerDh). The shared secret itself never leaves.
   *
   * @throws GeneralSecurityException peerDh is not a usable X25519 key (e.g. low order)
   */
  byte[] dhDerive(byte[] peerDh, byte[] salt, byte[] info, int length)
      throws GeneralSecurityException {
    byte[] shared = x25519(peerDh);
    try {
      return Webhooks.hkdfSha256(shared, salt, info, length);
    } finally {
      Arrays.fill(shared, (byte) 0);
    }
  }

  private byte[] x25519(byte[] peer) throws GeneralSecurityException {
    if (peer.length != 32) throw new GeneralSecurityException("not an X25519 public key");
    KeyAgreement ka = KeyAgreement.getInstance("X25519");
    ka.init(dhPrivateKey);
    ka.doPhase(
        KeyFactory.getInstance("X25519")
            .generatePublic(new X509EncodedKeySpec(Bytes.concat(X25519_PREFIX, peer))),
        true);
    return ka.generateSecret();
  }

  /**
   * Signs a message.
   *
   * @param message the bytes to sign
   * @return the 64-byte Ed25519 signature
   */
  public byte[] sign(byte[] message) {
    try {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(privateKey);
      s.update(message);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Verifies an Ed25519 signature. A malformed key or signature verifies as false.
   *
   * @param publicKeyHex the signer's public key, hex
   * @param message the signed bytes
   * @param signature the signature
   * @return whether the signature is valid
   */
  public static boolean verify(String publicKeyHex, byte[] message, byte[] signature) {
    try {
      byte[] raw = Bytes.fromHex(publicKeyHex);
      if (raw.length != 32 || signature.length != 64) return false;
      Signature s = Signature.getInstance("Ed25519");
      s.initVerify(
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Bytes.concat(X509_PREFIX, raw))));
      s.update(message);
      return s.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static final class FixedRandom extends SecureRandom {
    private final byte[] bytes;

    FixedRandom(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    @Override
    public void nextBytes(byte[] out) {
      if (out.length != bytes.length) throw new IllegalStateException("unexpected key size");
      System.arraycopy(bytes, 0, out, 0, out.length);
    }
  }
}
