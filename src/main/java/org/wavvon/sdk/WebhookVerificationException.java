package org.wavvon.sdk;

/** A webhook request failed verification; answer it with 401 and do nothing. */
public class WebhookVerificationException extends WavvonException {
  /**
   * @param message why verification failed
   */
  public WebhookVerificationException(String message) {
    super(message);
  }
}
