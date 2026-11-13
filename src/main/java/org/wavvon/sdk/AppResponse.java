package org.wavvon.sdk;

import java.util.List;

/**
 * What a slash command handler answers. Null fields are left out.
 *
 * @param reply the message to post
 * @param ephemeral only the invoker sees the reply
 * @param reactions reactions to add
 * @param defer the app will post later on its own
 * @param game a "Play" card attached to the reply; ignored without a reply
 */
public record AppResponse(
    Reply reply, Boolean ephemeral, List<Reaction> reactions, Boolean defer, GameLaunchCard game) {

  /**
   * @param body the text
   * @return a public reply
   */
  public static AppResponse reply(String body) {
    return new AppResponse(new Reply(body, null, null, null), null, null, null, null);
  }

  /**
   * @param body the text
   * @return a reply only the invoker sees
   */
  public static AppResponse ephemeralReply(String body) {
    return new AppResponse(new Reply(body, null, null, null), true, null, null, null);
  }

  /**
   * @return "I will post later"
   */
  public static AppResponse deferred() {
    return new AppResponse(null, null, null, true, null);
  }

  /**
   * The reply message.
   *
   * @param body the text
   * @param embeds optional
   * @param components optional
   * @param replyTo a message id to reply to
   */
  public record Reply(
      String body, List<Embed> embeds, List<ComponentRow> components, String replyTo) {}

  /**
   * A reaction to add.
   *
   * @param messageId the message
   * @param emoji the emoji
   */
  public record Reaction(String messageId, String emoji) {}
}
