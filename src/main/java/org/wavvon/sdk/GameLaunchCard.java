package org.wavvon.sdk;

/**
 * A "Play" card: on an app profile, or attached to a slash command reply.
 *
 * @param entryUrl where the game loads from
 * @param name the game's name
 * @param description optional
 * @param thumbnailUrl optional
 */
public record GameLaunchCard(
    String entryUrl, String name, String description, String thumbnailUrl) {}
