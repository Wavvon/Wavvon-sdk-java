package org.wavvon.sdk;

/**
 * A poll cursor is older than the hub's 72-hour window: events after it were missed and cannot be
 * replayed. Resynchronise from REST, then poll without a cursor.
 */
public class ReplayUnavailableException extends WavvonException {
  private final long earliestSeq;
  private final long earliestAt;

  /**
   * @param earliestSeq the oldest seq the hub still holds
   * @param earliestAt when that event happened, in Unix seconds
   */
  public ReplayUnavailableException(long earliestSeq, long earliestAt) {
    super("events before seq " + earliestSeq + " are outside the hub's replay window");
    this.earliestSeq = earliestSeq;
    this.earliestAt = earliestAt;
  }

  /**
   * @return the oldest seq the hub still holds
   */
  public long earliestSeq() {
    return earliestSeq;
  }

  /**
   * @return when that event happened, in Unix seconds
   */
  public long earliestAt() {
    return earliestAt;
  }
}
