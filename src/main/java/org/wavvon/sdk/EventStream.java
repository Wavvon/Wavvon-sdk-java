package org.wavvon.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One hub WebSocket that reconnects on its own. Tracks the last hub_event seq and sends {@code
 * resume} after every reconnect, so an app misses nothing inside the hub's 72-hour window; {@link
 * WsEvent.ReplayUnavailable} is delivered like any other event when it falls outside. The hub only
 * honours {@code resume} (and only pushes hub_event) for an identity whose app profile existed when
 * the socket connected: register the profile first.
 *
 * <p>Handlers run on the socket's thread, one frame at a time, in order.
 */
public final class EventStream implements AutoCloseable {
  /** Where the socket is. */
  public enum State {
    /** First connection under way. */
    CONNECTING,
    /** Greeted by the hub. */
    OPEN,
    /** Lost; waiting to connect again. */
    RECONNECTING,
    /** Closed by the program. */
    CLOSED
  }

  interface Socket {
    void send(String text);

    void close();
  }

  interface Listener {
    void onText(Socket from, String text);

    void onBinary();

    void onClose();
  }

  interface SocketFactory {
    CompletableFuture<Socket> open(String url, Listener listener);
  }

  interface Source<T> {
    T get() throws Exception;
  }

  interface Action {
    void run() throws Exception;
  }

  private final Source<String> url;
  private final Action reauthenticate;
  private final SocketFactory sockets;
  private final RetryOptions retry;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "wavvon-event-stream");
            t.setDaemon(true);
            return t;
          });
  private final Map<Class<?>, List<Consumer<WsEvent>>> handlers =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final List<Consumer<WsEvent>> anyHandlers = new CopyOnWriteArrayList<>();
  private final List<Consumer<State>> stateHandlers = new CopyOnWriteArrayList<>();
  private volatile Socket socket;
  private volatile Listener current;
  private volatile State state = State.CONNECTING;
  private volatile boolean stopped;
  private volatile Long lastSeq;
  private int attempt;

  EventStream(
      Source<String> url,
      Action reauthenticate,
      SocketFactory sockets,
      RetryOptions retry,
      Long sinceSeq) {
    this.url = url;
    this.reauthenticate = reauthenticate;
    this.sockets = sockets;
    this.retry = retry;
    this.lastSeq = sinceSeq;
    timer.execute(this::open);
  }

  /**
   * @return where the socket is
   */
  public State state() {
    return state;
  }

  /**
   * @return the highest hub_event seq seen (or the hub's live seq at first connect); persist it to
   *     resume across restarts with {@link WavvonClient#connect(Long)}
   */
  public Long lastSeq() {
    return lastSeq;
  }

  /**
   * @param fn called on every state change
   * @return a handle that removes the listener
   */
  public Runnable onStateChange(Consumer<State> fn) {
    stateHandlers.add(fn);
    return () -> stateHandlers.remove(fn);
  }

  /**
   * Subscribes to one event type, e.g. {@code WsEvent.MessageCreated.class} or {@code
   * WsEvent.Unknown.class}.
   *
   * @param type the event record
   * @param fn the handler
   * @param <T> the event type
   * @return a handle that removes the handler
   */
  public <T extends WsEvent> Runnable on(Class<T> type, Consumer<? super T> fn) {
    Consumer<WsEvent> h = e -> fn.accept(type.cast(e));
    handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(h);
    return () -> handlers.get(type).remove(h);
  }

  /**
   * @param fn called for every event, known or not
   * @return a handle that removes the handler
   */
  public Runnable onAny(Consumer<WsEvent> fn) {
    anyHandlers.add(fn);
    return () -> anyHandlers.remove(fn);
  }

  /**
   * Sends a client frame: a map or record, serialised as JSON (snake_case).
   *
   * @param frame the frame, with its {@code type}
   * @throws IllegalStateException the socket is not open
   */
  public void send(Object frame) {
    Socket s = socket;
    if (s == null || state != State.OPEN) {
      throw new IllegalStateException("socket is " + state + ", not open");
    }
    s.send(Json.string(frame));
  }

  /**
   * @param channelId start receiving this channel's events
   */
  public void subscribe(String channelId) {
    send(frame("subscribe", "channel_id", channelId));
  }

  /**
   * @param channelId stop receiving this channel's events
   */
  public void unsubscribe(String channelId) {
    send(frame("unsubscribe", "channel_id", channelId));
  }

  /**
   * @param channelId the channel
   * @param typing started or stopped
   */
  public void typing(String channelId, boolean typing) {
    send(frame("typing", "channel_id", channelId, "typing", typing));
  }

  /**
   * Needs the {@code ws.ping} capability; the answer is a {@link WsEvent.Pong}.
   *
   * @param nonce echoed back
   */
  public void ping(long nonce) {
    send(frame("ping", "nonce", nonce));
  }

  /**
   * Mini-app host: show a launch card in a channel.
   *
   * @param channelId the channel
   * @param title the card's title
   * @param description the card's text
   */
  public void announceApp(String channelId, String title, String description) {
    send(
        frame("app_announce", "channel_id", channelId, "title", title, "description", description));
  }

  /**
   * Mini-app host: close the session; clients dismiss their webviews.
   *
   * @param channelId the channel
   */
  public void dismissApp(String channelId) {
    send(frame("app_dismiss", "channel_id", channelId));
  }

  /**
   * Mini-app host: relay an opaque payload to one joined player's mini-app. Players' messages
   * arrive as {@link WsEvent.MiniAppMessage} with {@code fromPubkey}. The hub answers {@code
   * app_join} itself.
   *
   * @param appId the app
   * @param channelId the channel
   * @param toPubkey the player
   * @param payload opaque
   */
  public void sendMiniAppMessage(String appId, String channelId, String toPubkey, String payload) {
    send(
        frame(
            "mini_app_message",
            "app_id",
            appId,
            "channel_id",
            channelId,
            "payload",
            payload,
            "to_pubkey",
            toPubkey));
  }

  /** Closes the socket for good. */
  @Override
  public void close() {
    stopped = true;
    Socket s = socket;
    if (s != null) s.close();
    setState(State.CLOSED);
    timer.shutdownNow();
  }

  private static Map<String, Object> frame(String type, Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private void setState(State s) {
    if (s == state) return;
    state = s;
    for (Consumer<State> fn : stateHandlers) fn.accept(s);
  }

  private void emit(WsEvent event) {
    List<Consumer<WsEvent>> typed = handlers.get(event.getClass());
    if (typed != null) for (Consumer<WsEvent> fn : typed) fn.accept(event);
    for (Consumer<WsEvent> fn : anyHandlers) fn.accept(event);
  }

  private void open() {
    if (stopped) return;
    String target;
    try {
      target = url.get();
    } catch (Exception e) {
      scheduleReconnect(false);
      return;
    }
    AtomicBoolean greeted = new AtomicBoolean();
    Listener listener =
        new Listener() {
          @Override
          public synchronized void onText(Socket from, String text) {
            if (current != this) return;
            socket = from;
            WsEvent event = WsEvent.parse(text);
            if (event instanceof WsEvent.Hello hello) {
              greeted.set(true);
              synchronized (EventStream.this) {
                attempt = 0;
              }
              setState(State.OPEN);
              if (lastSeq == null) {
                lastSeq = hello.liveSeq();
              } else {
                from.send(Json.string(frame("resume", "since_seq", lastSeq)));
              }
            } else if (event instanceof WsEvent.HubEvent hub) {
              // Live events that arrived during a replay are flushed after it and can repeat what
              // the replay already carried.
              if (lastSeq != null && hub.seq() <= lastSeq) return;
              lastSeq = hub.seq();
            }
            emit(event);
          }

          @Override
          public void onBinary() {
            emit(new WsEvent.Unknown("binary", null));
          }

          @Override
          public void onClose() {
            if (current == this) scheduleReconnect(!greeted.get());
          }
        };
    current = listener;
    sockets
        .open(target, listener)
        .whenComplete(
            (s, err) -> {
              if (err != null) {
                if (current == listener) scheduleReconnect(true);
                return;
              }
              if (current == listener) socket = s;
              if (stopped) s.close();
            });
  }

  private void scheduleReconnect(boolean diedBeforeHello) {
    if (stopped) return;
    setState(State.RECONNECTING);
    int n;
    synchronized (this) {
      n = ++attempt;
    }
    long delay = Retry.backoffDelay(n, retry, () -> ThreadLocalRandom.current().nextDouble());
    try {
      timer.schedule(
          () -> {
            if (stopped) return;
            if (diedBeforeHello) {
              // A refused upgrade looks like a dropped one; a fresh session costs one login and
              // rules out a dead token.
              try {
                reauthenticate.run();
              } catch (Exception ignored) {
                // the next open retries
              }
            }
            open();
          },
          delay,
          TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      // close() raced the reconnect
    }
  }

  /** Sockets from the JDK's own WebSocket client. */
  static SocketFactory jdkSockets(HttpClient http) {
    return (url, listener) -> {
      AtomicBoolean closed = new AtomicBoolean();
      Runnable closeOnce =
          () -> {
            if (closed.compareAndSet(false, true)) listener.onClose();
          };
      var jdk =
          new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();
            private Socket sock;

            @Override
            public void onOpen(WebSocket ws) {
              sock = wrap(ws);
              ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
              text.append(data);
              if (last) {
                String frame = text.toString();
                text.setLength(0);
                listener.onText(sock, frame);
              }
              ws.request(1);
              return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
              if (last) listener.onBinary();
              ws.request(1);
              return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
              closeOnce.run();
              return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
              closeOnce.run();
            }
          };
      return http.newWebSocketBuilder().buildAsync(URI.create(url), jdk).thenApply(ws -> jdk.sock);
    };
  }

  private static Socket wrap(WebSocket ws) {
    return new Socket() {
      @Override
      public synchronized void send(String frame) {
        ws.sendText(frame, true).join();
      }

      @Override
      public void close() {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((w, e) -> ws.abort());
      }
    };
  }
}
