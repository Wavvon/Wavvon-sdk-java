package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class EventStreamTest {
  static final RetryOptions FAST =
      RetryOptions.DEFAULTS.withDelays(Duration.ofMillis(1), Duration.ofMillis(1));

  @Test
  void typesKnownFrames() {
    WsEvent e =
        WsEvent.parse("{\"type\":\"replay_unavailable\",\"earliest_seq\":5,\"earliest_at\":1}");
    assertEquals(new WsEvent.ReplayUnavailable(5, 1), e);
    WsEvent m =
        WsEvent.parse(
            "{\"type\":\"message\",\"channel_id\":\"c\",\"message\":{\"id\":\"m\",\"content\":\"hi\","
                + "\"created_at\":1760000000000,\"sender\":\"p\"}}");
    WsEvent.MessageCreated mc = assertInstanceOf(WsEvent.MessageCreated.class, m);
    assertEquals("hi", mc.message().getContent());
    assertEquals(1760000000000L, mc.message().getCreatedAt());
  }

  @Test
  void deliversUnknownTypesWithTheRawPayload() {
    WsEvent.Unknown u =
        assertInstanceOf(
            WsEvent.Unknown.class,
            WsEvent.parse("{\"type\":\"voice_zone_created\",\"zone_id\":\"z\"}"));
    assertEquals("voice_zone_created", u.rawType());
    assertEquals("z", ((com.fasterxml.jackson.databind.JsonNode) u.raw()).get("zone_id").asText());
  }

  @Test
  void deliversGarbageAndMisshapenKnownFramesAsUnknownToo() {
    assertEquals("", ((WsEvent.Unknown) WsEvent.parse("{nope")).rawType());
    assertEquals(
        "hello",
        ((WsEvent.Unknown) WsEvent.parse("{\"type\":\"hello\",\"live_seq\":\"x\"}")).rawType());
  }

  /** Scripted sockets: each new connection is recorded for the test to drive. */
  static final class FakeSockets implements EventStream.SocketFactory {
    final List<Conn> all = new CopyOnWriteArrayList<>();

    final class Conn implements EventStream.Socket {
      final List<String> sent = new CopyOnWriteArrayList<>();
      final EventStream.Listener listener;

      Conn(EventStream.Listener listener) {
        this.listener = listener;
      }

      @Override
      public void send(String text) {
        sent.add(text);
      }

      @Override
      public void close() {
        listener.onClose();
      }

      void frame(String json) {
        listener.onText(this, json);
      }
    }

    @Override
    public CompletableFuture<EventStream.Socket> open(String url, EventStream.Listener listener) {
      Conn c = new Conn(listener);
      all.add(c);
      return CompletableFuture.completedFuture(c);
    }

    Conn await(int i) {
      waitFor(() -> all.size() > i);
      return all.get(i);
    }
  }

  static void waitFor(BooleanSupplier cond) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("timed out");
      Thread.onSpinWait();
    }
  }

  static String hubEvent(long seq) {
    return "{\"type\":\"hub_event\",\"seq\":"
        + seq
        + ",\"event\":\"member.joined\",\"hub_url\":\"h\",\"at\":1,\"payload\":{}}";
  }

  @Test
  void resumesFromTheLastSeqAfterAReconnectAndDropsReplayDuplicates() {
    FakeSockets sockets = new FakeSockets();
    AtomicInteger reauths = new AtomicInteger();
    EventStream stream =
        new EventStream(() -> "ws://hub/ws?token=t", reauths::incrementAndGet, sockets, FAST, null);
    List<EventStream.State> states = new CopyOnWriteArrayList<>();
    stream.onStateChange(states::add);
    List<Long> seqs = new CopyOnWriteArrayList<>();
    List<WsEvent> all = new CopyOnWriteArrayList<>();
    stream.on(WsEvent.HubEvent.class, e -> seqs.add(e.seq()));
    stream.onAny(all::add);

    FakeSockets.Conn first = sockets.await(0);
    first.frame("{\"type\":\"hello\",\"live_seq\":10}");
    assertEquals(List.of(), first.sent);
    first.frame(hubEvent(11));
    first.frame(hubEvent(12));
    first.close();

    FakeSockets.Conn second = sockets.await(1);
    second.frame("{\"type\":\"hello\",\"live_seq\":20}");
    assertEquals(List.of("{\"type\":\"resume\",\"since_seq\":12}"), second.sent);
    second.frame(hubEvent(12));
    second.frame(hubEvent(13));
    second.frame("{\"type\":\"replay_unavailable\",\"earliest_seq\":1,\"earliest_at\":2}");

    assertEquals(List.of(11L, 12L, 13L), seqs);
    assertTrue(all.stream().anyMatch(e -> e instanceof WsEvent.ReplayUnavailable));
    assertEquals(
        List.of(EventStream.State.OPEN, EventStream.State.RECONNECTING, EventStream.State.OPEN),
        states);
    assertEquals(0, reauths.get());
    assertEquals(13L, stream.lastSeq());
    stream.close();
  }

  @Test
  void reauthenticatesWhenAConnectionDiesBeforeHello() {
    FakeSockets sockets = new FakeSockets();
    AtomicInteger reauths = new AtomicInteger();
    EventStream stream =
        new EventStream(() -> "ws://hub/ws", reauths::incrementAndGet, sockets, FAST, 4L);
    sockets.await(0).close();
    FakeSockets.Conn s = sockets.await(1);
    assertEquals(1, reauths.get());
    s.frame("{\"type\":\"hello\",\"live_seq\":9}");
    assertEquals(List.of("{\"type\":\"resume\",\"since_seq\":4}"), s.sent);
    stream.close();
    assertEquals(EventStream.State.CLOSED, stream.state());
  }

  @Test
  void sendsMiniAppHostFrames() {
    FakeSockets sockets = new FakeSockets();
    EventStream stream = new EventStream(() -> "ws://hub/ws", () -> {}, sockets, FAST, null);
    FakeSockets.Conn c = sockets.await(0);
    c.frame("{\"type\":\"hello\",\"live_seq\":1}");
    stream.announceApp("ch", "Quiz", "Join in");
    stream.sendMiniAppMessage("app", "ch", "pk", "{}");
    assertEquals(
        List.of(
            "{\"type\":\"app_announce\",\"channel_id\":\"ch\",\"title\":\"Quiz\",\"description\":\"Join in\"}",
            "{\"type\":\"mini_app_message\",\"app_id\":\"app\",\"channel_id\":\"ch\",\"payload\":\"{}\",\"to_pubkey\":\"pk\"}"),
        c.sent);
    stream.close();
  }
}
