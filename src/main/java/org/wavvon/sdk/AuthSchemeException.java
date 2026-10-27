package org.wavvon.sdk;

/** The challenge could not be answered safely (missing or unknown scheme). */
public class AuthSchemeException extends WavvonException {
  /**
   * @param message why the challenge was refused
   */
  public AuthSchemeException(String message) {
    super(message);
  }
}
