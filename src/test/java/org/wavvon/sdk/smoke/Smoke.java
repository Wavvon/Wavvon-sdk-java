package org.wavvon.sdk.smoke;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.wavvon.sdk.AppCommand;
import org.wavvon.sdk.AppProfile;
import org.wavvon.sdk.AppResponse;
import org.wavvon.sdk.AppSubscription;
import org.wavvon.sdk.Dispatch;
import org.wavvon.sdk.DispatchHandler;
import org.wavvon.sdk.EventStream;
import org.wavvon.sdk.RetryOptions;
import org.wavvon.sdk.Session;
import org.wavvon.sdk.WavvonClient;
import org.wavvon.sdk.WsEvent;
import org.wavvon.sdk.generated.api.ChannelsApi;
import org.wavvon.sdk.generated.api.InvitesApi;
import org.wavvon.sdk.generated.api.MessagesApi;
import org.wavvon.sdk.generated.api.RolesApi;
import org.wavvon.sdk.generated.model.Channel;
import org.wavvon.sdk.generated.model.CreateChannelMessageRequest;
import org.wavvon.sdk.generated.model.CreateChannelRequest;
import org.wavvon.sdk.generated.model.CreateInviteRequest;
import org.wavvon.sdk.generated.model.CreateRoleRequest;
import org.wavvon.sdk.generated.model.Message;

/**
 * Smoke test against a real hub, through the public API only. Not a unit test: run it with {@code
 * ./mvnw -Psmoke verify}.
 *
 * <p>With {@code WAVVON_SMOKE_HUB} (+ {@code WAVVON_SMOKE_INVITE} for an invite whose role grants
 * apps.register, optional {@code WAVVON_SMOKE_CHANNEL}) it uses that hub. Without it, it copies the
 * hub binary ({@code WAVVON_HUB_BIN}, default {@code ../../server/target/debug/wavvon-hub}) to a
 * temp dir, boots it on a fresh database in a PostgreSQL docker container ({@code
 * WAVVON_PG_CONTAINER}, default {@code wavvon-pg}), sets up a role, invite and channel as owner,
 * runs the smoke, then kills only that hub and drops the database.
 *
 * <p>Authenticates as a fresh identity, posts a message, registers a slash command, invokes it, and
 * checks that the verified invocation's reply lands in the channel, then polls the message back
 * from {@code GET /me/events}.
 */
public final class Smoke {
  private static final String DB = "wavvon_sdk_java_smoke";

  private Smoke() {}

  static void step(String s) {
    System.out.println("smoke: " + s);
  }

  public static void main(String[] args) throws Exception {
    String hub = System.getenv("WAVVON_SMOKE_HUB");
    int code;
    if (hub != null) {
      code = run(hub, System.getenv("WAVVON_SMOKE_INVITE"), System.getenv("WAVVON_SMOKE_CHANNEL"));
    } else {
      code = local();
    }
    System.exit(code);
  }

  static byte[] randomSeed() {
    byte[] seed = new byte[32];
    new SecureRandom().nextBytes(seed);
    return seed;
  }

  static int local() throws Exception {
    String exe =
        System.getProperty("os.name").startsWith("Windows") ? "wavvon-hub.exe" : "wavvon-hub";
    Path bin =
        Path.of(System.getenv().getOrDefault("WAVVON_HUB_BIN", "../../server/target/debug/" + exe))
            .toAbsolutePath()
            .normalize();
    if (!Files.exists(bin)) {
      System.err.println("no hub binary at " + bin + "; build Wavvon-server or set WAVVON_HUB_BIN");
      return 2;
    }
    String pg =
        System.getenv().getOrDefault("WAVVON_PG", "postgres://postgres:postgres@localhost:5432");
    String container = System.getenv().getOrDefault("WAVVON_PG_CONTAINER", "wavvon-pg");
    int port = 3900 + ThreadLocalRandom.current().nextInt(500);
    String hubUrl = "http://localhost:" + port;
    Path dir = Files.createTempDirectory("wavvon-sdk-java-smoke-");
    // A copy, so the running hub never locks the build output.
    Path copy = Files.copy(bin, dir.resolve(exe), StandardCopyOption.REPLACE_EXISTING);
    WavvonClient owner = WavvonClient.builder(hubUrl).seed(randomSeed()).build();

    psql(container, "DROP DATABASE IF EXISTS " + DB);
    psql(container, "CREATE DATABASE " + DB);
    Path log = dir.resolve("hub.log");
    ProcessBuilder pb = new ProcessBuilder(copy.toString()).directory(dir.toFile());
    pb.redirectErrorStream(true).redirectOutput(log.toFile());
    Map<String, String> env = pb.environment();
    env.put("WAVVON_DATABASE_URL", pg + "/" + DB);
    env.put("WAVVON_ALLOW_PRIVATE_WEBHOOKS", "true");
    env.put("WAVVON_HTTP_PORT", String.valueOf(port));
    env.put("WAVVON_VOICE_UDP_PORT", String.valueOf(port + 1));
    env.put("WAVVON_PUBLIC_URL", hubUrl);
    env.put("WAVVON_OWNER_PUBKEY", owner.identity().publicKey());
    env.put("WAVVON_WEB_CLIENT_DIR", "");
    Process proc = pb.start();
    int code = 1;
    try {
      waitForHub(proc, hubUrl, log);
      System.out.println("smoke-local: hub up at " + hubUrl);
      String roleId =
          new RolesApi(owner.api())
              .createRole(
                  new CreateRoleRequest()
                      .name("Apps")
                      .priority(10L)
                      .permissions(List.of("apps.register")))
              .getId();
      String invite =
          new InvitesApi(owner.api())
              .createInvite(new CreateInviteRequest().grantRoleId(roleId).maxUses(1L))
              .getCode();
      String channel =
          new ChannelsApi(owner.api())
              .createChannel(new CreateChannelRequest().name("sdk-smoke"))
              .getId();
      System.out.println("smoke-local: role, invite and channel ready");
      code = run(hubUrl, invite, channel);
      if (code != 0) System.out.println(Files.readString(log));
    } catch (Exception e) {
      e.printStackTrace();
      System.out.println(Files.readString(log));
    } finally {
      proc.descendants().forEach(ProcessHandle::destroyForcibly);
      proc.destroyForcibly();
      proc.waitFor(10, TimeUnit.SECONDS);
      try {
        psql(container, "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
      } catch (Exception e) {
        System.err.println("smoke-local: could not drop " + DB + ": " + e.getMessage());
      }
      deleteTree(dir);
    }
    return code;
  }

  static void waitForHub(Process proc, String hubUrl, Path log) throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    long deadline = System.currentTimeMillis() + 90_000;
    while (true) {
      if (!proc.isAlive()) {
        throw new IllegalStateException(
            "hub exited (" + proc.exitValue() + ")\n" + Files.readString(log));
      }
      try {
        HttpResponse<Void> r =
            http.send(
                HttpRequest.newBuilder(URI.create(hubUrl + "/info")).build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 200) return;
      } catch (IOException notUp) {
        // not up yet
      }
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("hub never answered\n" + Files.readString(log));
      }
      Thread.sleep(400);
    }
  }

  static void psql(String container, String sql) throws Exception {
    Process p =
        new ProcessBuilder("docker", "exec", container, "psql", "-U", "postgres", "-c", sql)
            .redirectErrorStream(true)
            .start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) throw new IllegalStateException("psql failed: " + out);
  }

  static void deleteTree(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // a temp dir; Windows may hold a file a moment longer
    }
  }

  static int run(String hubUrl, String invite, String channelId) {
    if (invite == null) {
      System.err.println("set WAVVON_SMOKE_INVITE");
      return 2;
    }
    WavvonClient client =
        WavvonClient.builder(hubUrl)
            .seed(randomSeed())
            .inviteCode(invite)
            .retry(
                RetryOptions.DEFAULTS.withOnRetry(
                    i ->
                        step(
                            "retrying after "
                                + (i.status() == null ? "network error" : i.status())
                                + " (attempt "
                                + i.attempt()
                                + ")")))
            .build();
    HttpServer server = null;
    EventStream stream = null;
    try {
      Session session = client.login();
      step("authenticated as " + session.canonicalPubkey() + " (" + session.scope() + ")");
      client.requireCapability("apps.register");
      client.requireCapability("webhooks.signedtimestamp");

      AtomicReference<Dispatch.SlashInvocation> invocation = new AtomicReference<>();
      DispatchHandler handler =
          DispatchHandler.builder(client.info().publicKey())
              .onSlashCommand(
                  inv -> {
                    invocation.set(inv);
                    return AppResponse.reply("pong: " + inv.argsRaw());
                  })
              .build();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/hook", handler.asHttpHandler());
      server.start();
      String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

      client.setAppProfile(
          AppProfile.named("SDK smoke")
              .withDescription("wavvon-sdk (Java) smoke test")
              .withWebhookUrl(webhookUrl));
      client.setAppCommands(
          List.of(AppCommand.of("sdksmoke", "Answers pong").withCooldownSeconds(0)));
      step("registered /sdksmoke -> " + webhookUrl);

      // After the profile, so the hub treats this socket as an app's.
      stream = client.connect();
      List<Message> seen = new CopyOnWriteArrayList<>();
      List<String> unknown = new CopyOnWriteArrayList<>();
      CompletableFuture<Void> open = new CompletableFuture<>();
      stream.onStateChange(
          s -> {
            if (s == EventStream.State.OPEN) open.complete(null);
          });
      stream.on(WsEvent.MessageCreated.class, e -> seen.add(e.message()));
      stream.on(WsEvent.Unknown.class, e -> unknown.add(e.rawType()));
      if (stream.state() != EventStream.State.OPEN) open.get(10, TimeUnit.SECONDS);
      step("socket open");

      if (channelId == null) {
        channelId =
            new ChannelsApi(client.api())
                .listChannels().stream()
                    .filter(c -> !Boolean.TRUE.equals(c.getIsCategory()))
                    .filter(c -> c.getChannelType() == null || c.getChannelType().equals("text"))
                    .map(Channel::getId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no text channel to post in"));
      }

      client.setAppSubscriptions(
          List.of(new AppSubscription("message.created", List.of(channelId))));

      MessagesApi messages = new MessagesApi(client.api());
      String text = "hello from wavvon-sdk (Java) " + System.currentTimeMillis();
      messages.createChannelMessage(channelId, new CreateChannelMessageRequest().content(text));
      waitFor(seen, m -> text.equals(m.getContent()), "our own message over the socket");
      step("posted a message and saw it come back");

      messages.createChannelMessage(
          channelId, new CreateChannelMessageRequest().content("/sdksmoke ping"));
      Message reply = waitFor(seen, m -> "pong: ping".equals(m.getContent()), "the slash reply");
      Dispatch.SlashInvocation inv = invocation.get();
      if (inv == null || !inv.command().equals("sdksmoke") || !inv.argsRaw().equals("ping")) {
        throw new IllegalStateException("odd invocation " + inv);
      }
      if (!session.canonicalPubkey().equals(reply.getSender())) {
        throw new IllegalStateException("reply sent by " + reply.getSender());
      }
      step("verified the invocation and the reply landed");

      List<WsEvent.HubEvent> polled = client.pollEvents(null, null);
      WsEvent.HubEvent ours =
          polled.stream()
              .filter(e -> e.event().equals("message.created"))
              .filter(e -> text.equals(e.payload().path("content").asText()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("polling did not return our message"));
      long last = polled.get(polled.size() - 1).seq();
      if (client.pollEvents(last, null).stream().anyMatch(e -> e.seq() <= last)) {
        throw new IllegalStateException("the cursor did not page forward");
      }
      step("polled " + polled.size() + " events, ours at seq " + ours.seq());

      if (!unknown.isEmpty()) {
        step("frames this SDK does not model (delivered as unknown): " + Set.copyOf(unknown));
      }
      step("PASSED");
      return 0;
    } catch (Exception e) {
      System.err.println("smoke: FAILED");
      e.printStackTrace();
      return 1;
    } finally {
      if (stream != null) stream.close();
      if (server != null) server.stop(0);
    }
  }

  static <T> T waitFor(List<T> seen, Predicate<T> pred, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      for (T t : seen) if (pred.test(t)) return t;
      Thread.sleep(50);
    }
    throw new IllegalStateException("timed out waiting for " + what);
  }
}
