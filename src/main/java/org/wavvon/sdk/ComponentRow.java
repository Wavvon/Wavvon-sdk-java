package org.wavvon.sdk;

import java.util.List;

/**
 * A row of buttons or selects on an app's message.
 *
 * @param type always "row"
 * @param components the row's components
 */
public record ComponentRow(String type, List<Component> components) {
  /**
   * @param components the row's components
   * @return a row
   */
  public static ComponentRow of(Component... components) {
    return new ComponentRow("row", List.of(components));
  }

  /**
   * A button or a select. Null fields are left out.
   *
   * @param type "button" or "select"
   * @param customId comes back in the {@link Dispatch.ComponentInteraction}
   * @param label a button's label
   * @param style a button's style
   * @param disabled optional
   * @param placeholder a select's placeholder
   * @param minValues a select's minimum choices
   * @param maxValues a select's maximum choices
   * @param options a select's options
   */
  public record Component(
      String type,
      String customId,
      String label,
      String style,
      Boolean disabled,
      String placeholder,
      Integer minValues,
      Integer maxValues,
      List<Option> options) {

    /**
     * @param customId the button's id
     * @param label the button's label
     * @return a button
     */
    public static Component button(String customId, String label) {
      return new Component("button", customId, label, null, null, null, null, null, null);
    }

    /**
     * @param customId the select's id
     * @param options its options
     * @return a select
     */
    public static Component select(String customId, List<Option> options) {
      return new Component("select", customId, null, null, null, null, null, null, options);
    }
  }

  /**
   * One option of a select.
   *
   * @param label shown
   * @param value sent back
   * @param description optional
   */
  public record Option(String label, String value, String description) {}
}
