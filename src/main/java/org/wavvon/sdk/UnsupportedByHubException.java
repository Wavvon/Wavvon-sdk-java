package org.wavvon.sdk;

/** The hub does not advertise a capability the call needs. Never a version check. */
public class UnsupportedByHubException extends WavvonException {
  private final String capability;

  /**
   * @param capability the capability string the hub lacks
   */
  public UnsupportedByHubException(String capability) {
    super("this hub does not advertise the \"" + capability + "\" capability");
    this.capability = capability;
  }

  /**
   * @return the capability string the hub lacks
   */
  public String capability() {
    return capability;
  }
}
