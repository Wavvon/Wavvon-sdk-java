package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Something that happened in a voice room ({@link Voice#next()}). Switch on the record type. */
public sealed interface VoiceEvent
    permits VoiceEvent.Audio,
        VoiceEvent.Roster,
        VoiceEvent.Speaking,
        VoiceEvent.Zones,
        VoiceEvent.Position,
        VoiceEvent.PositionRefused,
        VoiceEvent.TalkGranted,
        VoiceEvent.MoveRequested,
        VoiceEvent.Rejoining,
        VoiceEvent.KeySkipped,
        VoiceEvent.Unknown,
        VoiceEvent.Error {

  /**
   * One participant of a voice room.
   *
   * @param publicKey their identity
   * @param senderId their relay id in this room (u16)
   * @param displayName their display name, or null
   */
  record Participant(String publicKey, int senderId, String displayName) {}

  /**
   * A proximity zone of the room.
   *
   * @param zoneId the zone's id
   * @param name its name
   * @param coordinateSystem {@code 2d} or {@code 3d}
   * @param attenuation the hub's attenuation object, as sent
   * @param positions the last known position of each participant in it, by public key
   */
  record Zone(
      String zoneId,
      String name,
      String coordinateSystem,
      JsonNode attenuation,
      Map<String, List<Double>> positions) {}

  /**
   * One decrypted 20 ms packet from one speaker. Jitter buffering is the program's: order by {@code
   * ts} and {@code ctr}.
   *
   * @param from the speaker's public key
   * @param senderId the speaker's relay id
   * @param keyId the sender key generation it was sealed under (u32)
   * @param ts 48 kHz sample clock, +960 per frame (u32)
   * @param ctr the packet counter under {@code keyId}
   * @param whisper whether it was whispered to this program
   * @param opus the Opus packet
   */
  record Audio(
      String from, int senderId, long keyId, long ts, long ctr, boolean whisper, byte[] opus)
      implements VoiceEvent {}

  /**
   * Who is in the room, after any change.
   *
   * @param participants everyone, by sender id
   */
  record Roster(List<Participant> participants) implements VoiceEvent {}

  /**
   * A participant started or stopped speaking.
   *
   * @param publicKey who
   * @param speaking started or stopped
   */
  record Speaking(String publicKey, boolean speaking) implements VoiceEvent {}

  /**
   * The room's zones, after any change.
   *
   * @param zones every zone
   */
  record Zones(List<Zone> zones) implements VoiceEvent {}

  /**
   * A participant moved in a zone.
   *
   * @param zoneId the zone
   * @param publicKey who
   * @param position where, 2 or 3 coordinates
   */
  record Position(String zoneId, String publicKey, List<Double> position) implements VoiceEvent {}

  /**
   * The hub refused a position (unknown zone, or someone else's creator-only zone).
   *
   * @param reason the hub's explanation
   */
  record PositionRefused(String reason) implements VoiceEvent {}

  /** A moderator gave this program the floor; {@link Voice#maySpeak()} is now true. */
  record TalkGranted() implements VoiceEvent {}

  /**
   * A moderator, or the AFK sweep ({@code auto}), asks the program to move. The hub moves no one.
   *
   * @param targetChannelId where to
   * @param targetChannelName its name
   * @param sourceChannelId where from, or null
   * @param eventId the event that caused it, or null
   * @param auto whether the AFK sweep asked
   */
  record MoveRequested(
      String targetChannelId,
      String targetChannelName,
      String sourceChannelId,
      String eventId,
      boolean auto)
      implements VoiceEvent {}

  /** The socket dropped, which ended the room on the hub; the SDK joins again with a fresh key. */
  record Rejoining() implements VoiceEvent {}

  /**
   * A participant could not be given this program's key (no DH key, or a changed one), so it cannot
   * hear it.
   *
   * @param publicKey the participant
   * @param reason why
   */
  record KeySkipped(String publicKey, String reason) implements VoiceEvent {}

  /**
   * A {@code voice_*} message this SDK does not model.
   *
   * @param rawType its {@code type}
   * @param raw the frame as received
   */
  record Unknown(String rawType, JsonNode raw) implements VoiceEvent {}

  /**
   * Something failed in the background; the room carries on.
   *
   * @param error what failed
   */
  record Error(Exception error) implements VoiceEvent {}
}
