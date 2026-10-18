package org.wavvon.sdk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class Bytes {
  private static final HexFormat HEX = HexFormat.of();

  private Bytes() {}

  static byte[] fromHex(String hex) {
    return HEX.parseHex(hex);
  }

  static String toHex(byte[] bytes) {
    return HEX.formatHex(bytes);
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.writeBytes(p);
    return out.toByteArray();
  }

  static byte[] u64le(long v) {
    byte[] out = new byte[8];
    for (int i = 0; i < 8; i++) out[i] = (byte) (v >>> (8 * i));
    return out;
  }
}
