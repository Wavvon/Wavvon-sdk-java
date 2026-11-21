package org.wavvon.sdk;

/**
 * The hub holds a DH key for a peer that differs from the one pinned on first use. It is not used;
 * {@link WavvonClient#acceptDhKey(String)} re-pins once the program decides to.
 */
public class DhKeyChangedException extends WavvonException {
  private final String publicKey;
  private final String pinned;
  private final String fetched;

  /**
   * @param publicKey the peer
   * @param pinned the DH key pinned earlier, hex
   * @param fetched the DH key the hub serves now, hex
   */
  public DhKeyChangedException(String publicKey, String pinned, String fetched) {
    super("the DH key of " + publicKey + " changed since it was pinned");
    this.publicKey = publicKey;
    this.pinned = pinned;
    this.fetched = fetched;
  }

  /**
   * @return the peer
   */
  public String publicKey() {
    return publicKey;
  }

  /**
   * @return the DH key pinned earlier, hex
   */
  public String pinned() {
    return pinned;
  }

  /**
   * @return the DH key the hub serves now, hex
   */
  public String fetched() {
    return fetched;
  }
}
