package org.wavvon.sdk;

/** A voice call failed; {@link #kind()} says how. */
public class VoiceException extends WavvonException {
  /** What went wrong in a voice room. */
  public enum Kind {
    /** The socket or the WebRTC data channel failed, or the hub did not answer in time. */
    TRANSPORT,
    /** The hub refused a voice request. */
    REFUSED,
    /** The packet is not one 20 ms Opus packet. */
    BAD_FRAME,
    /** The room was left, or is being joined again. */
    NOT_IN_ROOM,
    /** The room gives this program no talk power; the relay would drop what it sends. */
    MAY_NOT_SPEAK,
    /** The sealed packet would not fit one SCTP chunk. */
    PACKET_TOO_LARGE,
    /** The room has no zone with that id. */
    UNKNOWN_ZONE,
    /** The position has the wrong number of coordinates for its zone. */
    BAD_POSITION
  }

  private final Kind kind;

  /**
   * @param kind what went wrong
   * @param message details
   */
  public VoiceException(Kind kind, String message) {
    this(kind, message, null);
  }

  /**
   * @param kind what went wrong
   * @param message details
   * @param cause the underlying failure, or null
   */
  public VoiceException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  /**
   * @return what went wrong
   */
  public Kind kind() {
    return kind;
  }
}
