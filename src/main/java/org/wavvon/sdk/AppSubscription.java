package org.wavvon.sdk;

import java.util.List;

/**
 * One hub-event subscription in {@code PUT /me/app/subscriptions}.
 *
 * @param event e.g. {@code member.joined}, {@code message.created}
 * @param channels required for {@code message.*}; null for hub-wide events
 */
public record AppSubscription(String event, List<String> channels) {
  /**
   * @param event the event name
   * @param channels the channels it is limited to
   * @return the subscription
   */
  public static AppSubscription of(String event, String... channels) {
    return new AppSubscription(event, channels.length == 0 ? null : List.of(channels));
  }
}
