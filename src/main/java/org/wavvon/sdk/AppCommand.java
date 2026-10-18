package org.wavvon.sdk;

/**
 * One slash command in {@code PUT /me/app/commands}. Null fields are left out.
 *
 * @param name the command, without the slash
 * @param description shown in the command picker
 * @param args a usage hint such as {@code <text>}
 * @param scope "channel" or "hub"
 * @param privileged optional
 * @param cooldownSeconds per-user, per-command cooldown (hub default 3, 0 = none)
 */
public record AppCommand(
    String name,
    String description,
    String args,
    String scope,
    Boolean privileged,
    Long cooldownSeconds) {

  /**
   * @param name the command
   * @param description what it does
   * @return a command with only the required fields
   */
  public static AppCommand of(String name, String description) {
    return new AppCommand(name, description, null, null, null, null);
  }

  /**
   * @param usage a usage hint
   * @return a copy with {@code args} replaced
   */
  public AppCommand withArgs(String usage) {
    return new AppCommand(name, description, usage, scope, privileged, cooldownSeconds);
  }

  /**
   * @param seconds the cooldown
   * @return a copy with {@code cooldownSeconds} replaced
   */
  public AppCommand withCooldownSeconds(long seconds) {
    return new AppCommand(name, description, args, scope, privileged, seconds);
  }
}
