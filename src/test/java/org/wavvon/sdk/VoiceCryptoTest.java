package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VoiceCryptoTest {
  @Test
  void opusDurationReadsTheTocByte() {
    assertEquals(20_000, VoiceCrypto.opusDurationUs(new byte[] {(byte) 0xf8}));
    assertEquals(40_000, VoiceCrypto.opusDurationUs(new byte[] {(byte) 0xf9}));
    assertEquals(20_000, VoiceCrypto.opusDurationUs(new byte[] {0x08}));
    assertEquals(60_000, VoiceCrypto.opusDurationUs(new byte[] {0x18}));
    assertEquals(60_000, VoiceCrypto.opusDurationUs(new byte[] {0x0b, 0x03}));
    assertThrows(IllegalArgumentException.class, () -> VoiceCrypto.opusDurationUs(new byte[0]));
    assertThrows(
        IllegalArgumentException.class, () -> VoiceCrypto.opusDurationUs(new byte[] {0x0b, 0}));
  }
}
