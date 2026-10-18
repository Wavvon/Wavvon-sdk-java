package org.wavvon.sdk;

import java.util.List;

/**
 * The parts of {@code GET /info} a program needs. The full shape is the generated {@code HubApi}.
 *
 * @param name the hub's name
 * @param publicKey the hub's Ed25519 key, hex: pin it to verify dispatches
 * @param version informational only; never compare it, check {@link #capabilities()}
 * @param capabilities the features this hub supports
 */
public record HubInfo(String name, String publicKey, String version, List<String> capabilities) {
  /** Never null. */
  public HubInfo {
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
  }
}
