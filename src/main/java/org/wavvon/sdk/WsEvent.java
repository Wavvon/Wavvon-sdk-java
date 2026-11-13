package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.wavvon.sdk.generated.model.Message;

/**
 * A server-to-client WebSocket frame (ws-protocol.md, checked against Wavvon-server {@code
 * crates/hub/src/routes/chat_models.rs}). A frame of any other type, a binary frame, or a known
 * type whose shape does not parse arrives as {@link Unknown} with the raw payload, never dropped.
 */
public sealed interface WsEvent
    permits WsEvent.Hello,
        WsEvent.CommandError,
        WsEvent.Pong,
        WsEvent.MessageCreated,
        WsEvent.MessageEdited,
        WsEvent.MessageDeleted,
        WsEvent.MessagePinned,
        WsEvent.MessageUnpinned,
        WsEvent.ReactionsUpdated,
        WsEvent.Typing,
        WsEvent.Dm,
        WsEvent.HubEvent,
        WsEvent.ReplayComplete,
        WsEvent.ReplayUnavailable,
        WsEvent.AppLaunch,
        WsEvent.AppOpen,
        WsEvent.AppClose,
        WsEvent.MiniAppMessage,
        WsEvent.Unknown {

  /**
   * {@code hello}: the first frame on every connection.
   *
   * @param liveSeq the hub's current hub_event seq
   */
  record Hello(long liveSeq) implements WsEvent {}

  /**
   * {@code error}: a recoverable command failure.
   *
   * @param context what failed
   * @param message why
   */
  record CommandError(String context, String message) implements WsEvent {}

  /**
   * {@code pong}: the answer to a ping (capability {@code ws.ping}).
   *
   * @param nonce the ping's nonce
   * @param outboundLossPct optional
   */
  record Pong(long nonce, Double outboundLossPct) implements WsEvent {}

  /**
   * {@code message}: a new channel message.
   *
   * @param channelId the channel
   * @param message the message (generated model)
   */
  record MessageCreated(String channelId, Message message) implements WsEvent {}

  /**
   * {@code message_edited}.
   *
   * @param channelId the channel
   * @param message the edited message
   */
  record MessageEdited(String channelId, Message message) implements WsEvent {}

  /**
   * {@code message_deleted}.
   *
   * @param channelId the channel
   * @param messageId the message
   */
  record MessageDeleted(String channelId, String messageId) implements WsEvent {}

  /**
   * {@code message_pinned}.
   *
   * @param channelId the channel
   * @param messageId the message
   */
  record MessagePinned(String channelId, String messageId) implements WsEvent {}

  /**
   * {@code message_unpinned}.
   *
   * @param channelId the channel
   * @param messageId the message
   */
  record MessageUnpinned(String channelId, String messageId) implements WsEvent {}

  /**
   * {@code reactions_updated}.
   *
   * @param channelId the channel
   * @param messageId the message
   * @param reactions the message's reactions now
   */
  record ReactionsUpdated(String channelId, String messageId, List<ReactionCount> reactions)
      implements WsEvent {}

  /**
   * One emoji's count on a message.
   *
   * @param emoji the emoji
   * @param count how many reacted
   * @param me whether this identity did
   */
  record ReactionCount(String emoji, long count, boolean me) {}

  /**
   * {@code typing}.
   *
   * @param channelId the channel
   * @param publicKey who
   * @param displayName may be null
   * @param typing started or stopped
   */
  record Typing(String channelId, String publicKey, String displayName, boolean typing)
      implements WsEvent {}

  /**
   * {@code dm}: a direct message notification (tier 1 does not decrypt DMs).
   *
   * @param conversationId the conversation
   * @param sender who
   * @param senderName may be null
   * @param content the content as the hub sent it
   * @param timestamp when
   */
  record Dm(String conversationId, String sender, String senderName, String content, long timestamp)
      implements WsEvent {}

  /**
   * {@code hub_event}: one envelope for live push, replay and outgoing webhooks (apps.md §5).
   *
   * @param seq the event's sequence number
   * @param event e.g. {@code member.joined}, {@code message.created}
   * @param hubUrl the hub
   * @param at Unix seconds
   * @param payload the event's payload
   * @param actorPubkey replay only, when the audit row has it
   * @param targetPubkey replay only, when the audit row has it
   * @param channelId replay only, when the audit row has it
   * @param replayed true on a replayed event
   */
  record HubEvent(
      long seq,
      String event,
      String hubUrl,
      long at,
      JsonNode payload,
      String actorPubkey,
      String targetPubkey,
      String channelId,
      Boolean replayed)
      implements WsEvent {}

  /**
   * {@code replay_complete}.
   *
   * @param replayed how many events were replayed
   * @param liveFromSeq live events continue from here
   */
  record ReplayComplete(long replayed, long liveFromSeq) implements WsEvent {}

  /**
   * {@code replay_unavailable}: the gap is older than the hub keeps (72 hours).
   *
   * @param earliestSeq the oldest seq the hub still has
   * @param earliestAt its time, Unix seconds
   */
  record ReplayUnavailable(long earliestSeq, long earliestAt) implements WsEvent {}

  /**
   * {@code app_launch}: a mini-app launch card appeared.
   *
   * @param appId the app
   * @param title the card's title
   * @param description the card's text
   * @param channelId the channel
   */
  record AppLaunch(String appId, String title, String description, String channelId)
      implements WsEvent {}

  /**
   * {@code app_open}.
   *
   * @param appId the app
   * @param channelId the channel
   * @param miniAppUrl where the mini-app loads from
   * @param sessionToken the mini-app session
   * @param requiresCamera whether it needs the camera
   */
  record AppOpen(
      String appId,
      String channelId,
      String miniAppUrl,
      String sessionToken,
      boolean requiresCamera)
      implements WsEvent {}

  /**
   * {@code app_close}.
   *
   * @param appId the app
   * @param channelId the channel
   */
  record AppClose(String appId, String channelId) implements WsEvent {}

  /**
   * {@code mini_app_message}: an opaque payload; on the host side it carries the player's {@code
   * fromPubkey}.
   *
   * @param appId the app
   * @param channelId the channel
   * @param payload opaque
   * @param fromPubkey the player who sent it
   */
  record MiniAppMessage(String appId, String channelId, String payload, String fromPubkey)
      implements WsEvent {}

  /**
   * A frame this SDK does not model.
   *
   * @param rawType the frame's own {@code type}, {@code "binary"} for a binary frame, {@code ""} if
   *     unparseable
   * @param raw the parsed JSON, or the text when it was not JSON
   */
  record Unknown(String rawType, Object raw) implements WsEvent {}

  /** Wire type to record. */
  Map<String, Class<? extends WsEvent>> TYPES =
      Map.ofEntries(
          Map.entry("hello", Hello.class),
          Map.entry("error", CommandError.class),
          Map.entry("pong", Pong.class),
          Map.entry("message", MessageCreated.class),
          Map.entry("message_edited", MessageEdited.class),
          Map.entry("message_deleted", MessageDeleted.class),
          Map.entry("message_pinned", MessagePinned.class),
          Map.entry("message_unpinned", MessageUnpinned.class),
          Map.entry("reactions_updated", ReactionsUpdated.class),
          Map.entry("typing", Typing.class),
          Map.entry("dm", Dm.class),
          Map.entry("hub_event", HubEvent.class),
          Map.entry("replay_complete", ReplayComplete.class),
          Map.entry("replay_unavailable", ReplayUnavailable.class),
          Map.entry("app_launch", AppLaunch.class),
          Map.entry("app_open", AppOpen.class),
          Map.entry("app_close", AppClose.class),
          Map.entry("mini_app_message", MiniAppMessage.class));

  /**
   * Turns one text frame into a typed event.
   *
   * @param text the frame
   * @return the event; {@link Unknown} for anything not modelled
   */
  static WsEvent parse(String text) {
    JsonNode frame;
    try {
      frame = Json.MAPPER.readTree(text);
    } catch (Exception e) {
      return new Unknown("", text);
    }
    JsonNode t = frame == null ? null : frame.get("type");
    String type = t != null && t.isTextual() ? t.asText() : "";
    Class<? extends WsEvent> cls = TYPES.get(type);
    if (cls == null) return new Unknown(type, frame);
    try {
      return Json.MAPPER.treeToValue(frame, cls);
    } catch (Exception e) {
      return new Unknown(type, frame);
    }
  }
}
