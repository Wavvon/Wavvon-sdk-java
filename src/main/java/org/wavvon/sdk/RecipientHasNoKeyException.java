package org.wavvon.sdk;

/** The peer has published no DH key on this hub: nothing can be wrapped to it. */
public class RecipientHasNoKeyException extends WavvonException {
  private final String publicKey;

  /**
   * @param publicKey the peer
   */
  public RecipientHasNoKeyException(String publicKey) {
    super(publicKey + " has published no DH key on this hub");
    this.publicKey = publicKey;
  }

  /**
   * @return the peer
   */
  public String publicKey() {
    return publicKey;
  }
}
