package org.wavvon.sdk.examples;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import org.wavvon.sdk.AppCommand;
import org.wavvon.sdk.AppProfile;
import org.wavvon.sdk.AppResponse;
import org.wavvon.sdk.DispatchHandler;
import org.wavvon.sdk.EventStream;
import org.wavvon.sdk.WavvonClient;
import org.wavvon.sdk.WsEvent;
import org.wavvon.sdk.generated.ApiException;
import org.wavvon.sdk.generated.api.MessagesApi;
import org.wavvon.sdk.generated.model.CreateChannelMessageRequest;
import org.wavvon.sdk.generated.model.Message;

/**
 * Echoes every message in the channels it can read, and answers {@code /echo}.
 *
 * <pre>
 * WAVVON_HUB=https://hub.example  WAVVON_SEED=&lt;64 hex chars&gt;  \
 * WAVVON_INVITE=&lt;code, first run only&gt;  WAVVON_WEBHOOK_URL=https://bot.example/hook  \
 * PORT=8080 ./mvnw -q exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=org.wavvon.sdk.examples.EchoBot
 * </pre>
 *
 * <p>The identity needs a role carrying apps.register to register /echo. The hub POSTs invocations
 * to WAVVON_WEBHOOK_URL, which must reach PORT here.
 */
public final class EchoBot {
  private EchoBot() {}

  public static void main(String[] args) throws Exception {
    WavvonClient client =
        WavvonClient.builder(System.getenv("WAVVON_HUB"))
            .seed(System.getenv("WAVVON_SEED"))
            .inviteCode(System.getenv("WAVVON_INVITE"))
            .build();
    String me = client.login().canonicalPubkey();

    DispatchHandler handler =
        DispatchHandler.builder(client.info().publicKey())
            .onSlashCommand(
                inv ->
                    AppResponse.ephemeralReply(
                        inv.argsRaw().isEmpty() ? "(nothing to echo)" : inv.argsRaw()))
            .build();
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/hook", handler.asHttpHandler());
    server.start();

    client.setAppProfile(
        AppProfile.named("Echo").withWebhookUrl(System.getenv("WAVVON_WEBHOOK_URL")));
    client.setAppCommands(
        List.of(AppCommand.of("echo", "Repeat what you say, to you only").withArgs("<text>")));

    MessagesApi messages = new MessagesApi(client.api());
    EventStream events = client.connect();
    events.onStateChange(s -> System.out.println("socket: " + s));
    events.on(WsEvent.Unknown.class, e -> System.out.println("unmodelled frame: " + e.rawType()));
    events.on(
        WsEvent.MessageCreated.class,
        e -> {
          Message m = e.message();
          String content = m.getContent();
          if (me.equals(m.getSender())
              || m.getVisibleToPubkey() != null
              || content == null
              || content.startsWith("/")) {
            return;
          }
          try {
            messages.createChannelMessage(
                e.channelId(), new CreateChannelMessageRequest().content("echo: " + content));
          } catch (ApiException ex) {
            System.err.println("could not echo: " + ex.getMessage());
          }
        });
    Thread.currentThread().join();
  }
}
