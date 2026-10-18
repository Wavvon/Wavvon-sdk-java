package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An outgoing-webhook delivery: the hub_event envelope plus delivery fields.
 *
 * @param type "hub_event"
 * @param seq the event's sequence number
 * @param event e.g. {@code member.joined}
 * @param hubUrl the hub
 * @param at Unix seconds
 * @param payload the event's payload
 * @param actorPubkey when known
 * @param targetPubkey when known
 * @param channelId when known
 * @param webhookId which webhook delivered it
 * @param truncated the payload was cut to fit
 */
public record OutgoingWebhookEvent(
    String type,
    long seq,
    String event,
    String hubUrl,
    long at,
    JsonNode payload,
    String actorPubkey,
    String targetPubkey,
    String channelId,
    String webhookId,
    Boolean truncated) {}
