package org.wavvon.sdk;

/** The challenge could not be answered safely (unknown scheme, wrong length). */
public class AuthSchemeException extends WavvonException {
  /**
   * @param message why the challenge was refused
   */
  public AuthSchemeException(String message) {
    super(message);
  }
}
