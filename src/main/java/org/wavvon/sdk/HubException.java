package org.wavvon.sdk;

/** A definitive refusal from the hub (a 4xx other than 429), carrying the hub's own words. */
public class HubException extends WavvonException {
  private final int status;
  private final String body;

  /**
   * @param status the HTTP status
   * @param body the response body as text
   */
  public HubException(int status, String body) {
    super("hub answered " + status + ": " + body);
    this.status = status;
    this.body = body;
  }

  /**
   * @return the HTTP status
   */
  public int status() {
    return status;
  }

  /**
   * @return the response body as text
   */
  public String body() {
    return body;
  }
}
