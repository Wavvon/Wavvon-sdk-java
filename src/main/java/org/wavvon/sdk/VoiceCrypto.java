package org.wavvon.sdk;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Voice crypto (wire-format.md, "Voice key wrap" and "Voice packet seal"). */
final class VoiceCrypto {
  static final int HEADER = 16;
  static final int TAG = 16;
  private static final byte[] VOICE_KEY_INFO = Bytes.utf8("wavvon/voice-key/v1");

  private VoiceCrypto() {}

  /** An unwrapped sender key. */
  record Unwrapped(byte[] senderKey, byte[] nonceSalt, long keyId) {}

  /** An opened packet. */
  record Opened(long keyId, long ctr, long ts, byte[] opus) {}

  private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] input, byte[] aad)
      throws GeneralSecurityException {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
    if (aad != null) c.updateAAD(aad);
    return c.doFinal(input);
  }

  private static byte[] wrapKey(Identity own, byte[] peerDh, String channelId)
      throws GeneralSecurityException {
    return own.dhDerive(peerDh, Bytes.utf8(channelId), VOICE_KEY_INFO, 32);
  }

  static byte[] keyWrap(
      Identity sender,
      byte[] recipientDh,
      String channelId,
      byte[] senderKey,
      byte[] nonceSalt,
      long keyId,
      byte[] nonce)
      throws GeneralSecurityException {
    byte[] plain =
        ByteBuffer.allocate(40).put(senderKey).put(nonceSalt).putInt((int) keyId).array();
    return gcm(Cipher.ENCRYPT_MODE, wrapKey(sender, recipientDh, channelId), nonce, plain, null);
  }

  static Unwrapped keyUnwrap(
      Identity recipient, byte[] senderDh, String channelId, byte[] ciphertext, byte[] nonce)
      throws GeneralSecurityException {
    byte[] p =
        gcm(Cipher.DECRYPT_MODE, wrapKey(recipient, senderDh, channelId), nonce, ciphertext, null);
    if (p.length != 40) {
      throw new GeneralSecurityException("wrapped voice key is " + p.length + " bytes, not 40");
    }
    return new Unwrapped(
        Arrays.copyOf(p, 32),
        Arrays.copyOfRange(p, 32, 36),
        Integer.toUnsignedLong(ByteBuffer.wrap(p, 36, 4).getInt()));
  }

  private static byte[] packetNonce(byte[] salt, byte[] header) {
    return ByteBuffer.allocate(12).put(salt).put(header, 4, 8).array();
  }

  /** {@code key_id[4] || ctr[8] || ts[4]} in clear (and as AAD), then AES-256-GCM(opus). */
  static byte[] packetSeal(
      byte[] senderKey, byte[] nonceSalt, long keyId, long ctr, long ts, byte[] opus)
      throws GeneralSecurityException {
    byte[] header =
        ByteBuffer.allocate(HEADER).putInt((int) keyId).putLong(ctr).putInt((int) ts).array();
    return Bytes.concat(
        header, gcm(Cipher.ENCRYPT_MODE, senderKey, packetNonce(nonceSalt, header), opus, header));
  }

  static Opened packetOpen(byte[] senderKey, byte[] nonceSalt, byte[] packet, int offset)
      throws GeneralSecurityException {
    if (packet.length - offset < HEADER + TAG) {
      throw new GeneralSecurityException(
          "voice packet too short: " + (packet.length - offset) + " bytes");
    }
    byte[] header = Arrays.copyOfRange(packet, offset, offset + HEADER);
    byte[] opus =
        gcm(
            Cipher.DECRYPT_MODE,
            senderKey,
            packetNonce(nonceSalt, header),
            Arrays.copyOfRange(packet, offset + HEADER, packet.length),
            header);
    ByteBuffer h = ByteBuffer.wrap(header);
    return new Opened(
        Integer.toUnsignedLong(h.getInt()), h.getLong(), Integer.toUnsignedLong(h.getInt()), opus);
  }

  /**
   * Duration of an Opus packet in microseconds, from its TOC byte (RFC 6716 3.1). The SDK sends
   * only 20 ms packets: the desktop decoder's output buffer holds 20 ms, so a 40 or 60 ms packet
   * fails to decode on every desktop listener.
   *
   * @throws IllegalArgumentException the packet is empty or declares no frames
   */
  static int opusDurationUs(byte[] packet) {
    if (packet.length == 0) throw new IllegalArgumentException("empty packet");
    int toc = packet[0] & 0xff;
    int config = toc >> 3;
    int[] table =
        config <= 11
            ? new int[] {10_000, 20_000, 40_000, 60_000}
            : config <= 15 ? new int[] {10_000, 20_000} : new int[] {2_500, 5_000, 10_000, 20_000};
    int frameUs = table[config % table.length];
    int frames;
    if ((toc & 3) == 0) {
      frames = 1;
    } else if ((toc & 3) != 3) {
      frames = 2;
    } else {
      if (packet.length < 2) {
        throw new IllegalArgumentException("code-3 packet without a frame count");
      }
      frames = packet[1] & 0x3f;
    }
    if (frames == 0) throw new IllegalArgumentException("packet declares zero frames");
    return frameUs * frames;
  }
}
