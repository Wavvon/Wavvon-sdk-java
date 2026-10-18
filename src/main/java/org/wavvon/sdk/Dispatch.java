package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A verified webhook dispatch from the hub (Wavvon-server {@code
 * crates/hub/src/routes/app_models.rs}). A type this SDK does not know arrives as {@link
 * UnknownDispatch}, never dropped.
 */
public sealed interface Dispatch
    permits Dispatch.SlashInvocation, Dispatch.ComponentInteraction, Dispatch.UnknownDispatch {

  /**
   * Who invoked.
   *
   * @param pubkey the invoker's key
   * @param displayName may be null
   */
  record Author(String pubkey, String displayName) {}

  /**
   * {@code slash_command}: answer with an {@link AppResponse}.
   *
   * @param hubUrl the hub
   * @param channelId where it was typed
   * @param messageIdHint the id the invocation message would have had
   * @param author who typed it
   * @param command the command, without the slash
   * @param argsRaw everything after the command
   * @param argsTokens the arguments split on whitespace
   */
  record SlashInvocation(
      String hubUrl,
      String channelId,
      String messageIdHint,
      Author author,
      String command,
      String argsRaw,
      List<String> argsTokens)
      implements Dispatch {}

  /**
   * {@code component_interaction}: a button or select; answer with a {@link ComponentResponse}.
   *
   * @param hubUrl the hub
   * @param channelId the channel
   * @param messageId the message carrying the component
   * @param customId the component's id
   * @param values a select's chosen values
   * @param user who clicked
   */
  record ComponentInteraction(
      String hubUrl,
      String channelId,
      String messageId,
      String customId,
      List<String> values,
      Author user)
      implements Dispatch {}

  /**
   * A verified dispatch of a type this SDK does not know.
   *
   * @param rawType its {@code type}
   * @param raw the whole body
   */
  record UnknownDispatch(String rawType, JsonNode raw) implements Dispatch {}
}
