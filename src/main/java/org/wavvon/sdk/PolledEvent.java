package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One row of {@code GET /me/events}.
 *
 * @param id acknowledge it with {@link WavvonClient#ackEvents}
 * @param eventType e.g. {@code member.joined}
 * @param payload the payload, parsed; a text node if it was not JSON
 * @param createdAt Unix seconds
 */
public record PolledEvent(String id, String eventType, JsonNode payload, long createdAt) {}
