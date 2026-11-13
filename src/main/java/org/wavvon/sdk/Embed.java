package org.wavvon.sdk;

import java.util.List;

/**
 * A rich embed on an app's message. Null fields are left out.
 *
 * @param title optional
 * @param url optional
 * @param description optional
 * @param color optional, e.g. {@code #ffaa00}
 * @param fields optional
 * @param thumbnailUrl optional
 * @param imageUrl optional
 * @param footer optional
 */
public record Embed(
    String title,
    String url,
    String description,
    String color,
    List<Field> fields,
    String thumbnailUrl,
    String imageUrl,
    Footer footer) {

  /**
   * One name/value row.
   *
   * @param name the label
   * @param value the value
   * @param inline lay out beside its neighbours
   */
  public record Field(String name, String value, Boolean inline) {}

  /**
   * The footer.
   *
   * @param text the footer's text
   */
  public record Footer(String text) {}
}
