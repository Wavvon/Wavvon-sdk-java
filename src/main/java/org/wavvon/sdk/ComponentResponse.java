package org.wavvon.sdk;

import java.util.List;

/**
 * What a component interaction handler answers. Null fields are left out.
 *
 * @param update edits the message carrying the component
 * @param ephemeralReply a reply only the clicker sees
 * @param defer the app will act later on its own
 */
public record ComponentResponse(Update update, EphemeralReply ephemeralReply, Boolean defer) {

  /**
   * An edit to the message.
   *
   * @param body new text, or null to keep it
   * @param components new components, or null to keep them
   */
  public record Update(String body, List<ComponentRow> components) {}

  /**
   * A private reply.
   *
   * @param body the text
   */
  public record EphemeralReply(String body) {}
}
