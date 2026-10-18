package org.wavvon.sdk;

/** Base class: every exception this SDK throws on purpose is one of these. */
public class WavvonException extends RuntimeException {
  /**
   * @param message what went wrong
   */
  public WavvonException(String message) {
    super(message);
  }

  /**
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public WavvonException(String message, Throwable cause) {
    super(message, cause);
  }
}
