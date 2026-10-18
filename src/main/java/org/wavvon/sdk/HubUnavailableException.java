package org.wavvon.sdk;

/**
 * The hub could not be reached or kept saying "not now" (429, 5xx, timeout, network) until the
 * retry budget ran out. This is not an answer: the request may succeed later, and nothing about the
 * resource can be concluded from it.
 */
public class HubUnavailableException extends WavvonException {
  private final Integer lastStatus;

  /**
   * @param message what happened
   * @param lastStatus the last HTTP status seen, or null for a network error or timeout
   * @param cause the last transport failure, if any
   */
  public HubUnavailableException(String message, Integer lastStatus, Throwable cause) {
    super(message, cause);
    this.lastStatus = lastStatus;
  }

  /**
   * @return the last HTTP status seen, or null when the last attempt failed in transport
   */
  public Integer lastStatus() {
    return lastStatus;
  }
}
