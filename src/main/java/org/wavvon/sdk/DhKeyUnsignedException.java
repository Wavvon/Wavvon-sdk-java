package org.wavvon.sdk;

/** The hub served a DH key record the peer did not sign. Never read as "no key". */
public class DhKeyUnsignedException extends WavvonException {
  private final String publicKey;

  /**
   * @param publicKey the peer
   */
  public DhKeyUnsignedException(String publicKey) {
    super("the DH key record of " + publicKey + " is not signed by it");
    this.publicKey = publicKey;
  }

  /**
   * @return the peer
   */
  public String publicKey() {
    return publicKey;
  }
}
