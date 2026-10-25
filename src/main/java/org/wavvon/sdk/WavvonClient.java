package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.wavvon.sdk.generated.ApiClient;

/**
 * A program's connection to one hub: a session that renews itself, the generated REST client
 * ({@link #api()}), a raw request escape hatch ({@link #send}), capability checks, app registration
 * and the event stream. Thread-safe; every call blocks the calling thread.
 */
public final class WavvonClient {
  /** Where the session is. "Authenticating" after a dead session is REST's reconnecting state. */
  public enum SessionState {
    /** No session. */
    SIGNED_OUT,
    /** Logging in. */
    AUTHENTICATING,
    /** A session is held. */
    READY
  }

  private final String hubUrl;
  private final Identity identity;
  private final String inviteCode;
  private final RetryOptions retry;
  private final HttpClient raw;
  private final SessionHttpClient http;
  private final ApiClient api;
  private final Object loginLock = new Object();
  private final List<Consumer<SessionState>> stateHandlers = new CopyOnWriteArrayList<>();
  private volatile Session session;
  private volatile HubInfo infoCache;
  private volatile SessionState state = SessionState.SIGNED_OUT;

  private WavvonClient(Builder b) {
    this.hubUrl = b.hubUrl.replaceAll("/+$", "");
    this.identity = Objects.requireNonNull(b.identity, "WavvonClient needs a seed or an identity");
    this.inviteCode = b.inviteCode;
    this.retry = b.retry;
    this.raw = b.httpClient != null ? b.httpClient : HttpClient.newHttpClient();
    this.http = new SessionHttpClient(raw, this, retry);
    this.api = new ApiClient(http.asBuilder(), ApiClient.createDefaultObjectMapper(), hubUrl);
  }

  /**
   * @param hubUrl the hub's base URL, e.g. {@code https://hub.example}
   * @return a builder
   */
  public static Builder builder(String hubUrl) {
    return new Builder(hubUrl);
  }

  /** Configures a {@link WavvonClient}. */
  public static final class Builder {
    private final String hubUrl;
    private Identity identity;
    private String inviteCode;
    private RetryOptions retry = RetryOptions.DEFAULTS;
    private HttpClient httpClient;

    private Builder(String hubUrl) {
      this.hubUrl = Objects.requireNonNull(hubUrl, "hubUrl");
    }

    /**
     * @param seedHex a 32-byte Ed25519 seed as hex. Never sent anywhere.
     * @return this
     */
    public Builder seed(String seedHex) {
      this.identity = Identity.fromSeed(seedHex);
      return this;
    }

    /**
     * @param seed a 32-byte Ed25519 seed. Never sent anywhere.
     * @return this
     */
    public Builder seed(byte[] seed) {
      this.identity = Identity.fromSeed(seed);
      return this;
    }

    /**
     * @param identity an identity built elsewhere
     * @return this
     */
    public Builder identity(Identity identity) {
      this.identity = identity;
      return this;
    }

    /**
     * @param code admits the program once on an invite_only hub; ignored for members
     * @return this
     */
    public Builder inviteCode(String code) {
      this.inviteCode = code;
      return this;
    }

    /**
     * @param retry the retry budget for every request
     * @return this
     */
    public Builder retry(RetryOptions retry) {
      this.retry = Objects.requireNonNull(retry);
      return this;
    }

    /**
     * @param client the underlying HTTP client (proxy, TLS, executor)
     * @return this
     */
    public Builder httpClient(HttpClient client) {
      this.httpClient = client;
      return this;
    }

    /**
     * @return the client; nothing is sent until the first call
     */
    public WavvonClient build() {
      return new WavvonClient(this);
    }
  }

  /**
   * @return the hub's base URL, without a trailing slash
   */
  public String hubUrl() {
    return hubUrl;
  }

  /**
   * @return the program's identity
   */
  public Identity identity() {
    return identity;
  }

  /**
   * @return where the session is
   */
  public SessionState state() {
    return state;
  }

  /**
   * @param fn called on every session state change
   * @return a handle that removes the listener
   */
  public Runnable onStateChange(Consumer<SessionState> fn) {
    stateHandlers.add(fn);
    return () -> stateHandlers.remove(fn);
  }

  /**
   * @return the current session, logging in if there is none
   * @throws HubException the hub refused the login
   * @throws HubUnavailableException the hub stayed unreachable or busy
   */
  public Session login() {
    return uninterrupted(this::loginInterruptibly);
  }

  /**
   * @return the identity the hub attributes this program's actions to, or null before login
   */
  public String canonicalPubkey() {
    Session s = session;
    return s == null ? null : s.canonicalPubkey();
  }

  Session loginInterruptibly() throws InterruptedException {
    Session s = session;
    return s != null ? s : relogin(null);
  }

  /** Logs in again unless another caller already replaced the token this one saw fail. */
  Session relogin(String staleToken) throws InterruptedException {
    synchronized (loginLock) {
      Session s = session;
      if (s != null && !s.token().equals(staleToken)) return s;
      setState(SessionState.AUTHENTICATING);
      try {
        session = Auth.authenticate(raw, hubUrl, identity, inviteCode, retry);
        setState(SessionState.READY);
        return session;
      } catch (RuntimeException | InterruptedException e) {
        session = null;
        setState(SessionState.SIGNED_OUT);
        throw e;
      }
    }
  }

  private void setState(SessionState s) {
    if (s == state) return;
    state = s;
    for (Consumer<SessionState> fn : stateHandlers) fn.accept(s);
  }

  /**
   * The HTTP client every SDK request goes through: bearer, retry and re-authentication built in.
   *
   * @return the session-aware HTTP client
   */
  public HttpClient http() {
    return http;
  }

  /**
   * The generated REST client (openapi-generator, from Wavvon-docs {@code openapi.yaml}); pass it
   * to any generated API, e.g. {@code new ChannelsApi(client.api())}. Bearer, retry and
   * re-authentication are built in.
   *
   * @return the generated API client
   */
  public ApiClient api() {
    return api;
  }

  /**
   * Raw request escape hatch for a route the generated client models badly or not at all.
   *
   * @param method the HTTP method
   * @param path the path below the hub URL, e.g. {@code /channels}
   * @param body serialised as JSON (snake_case); null for none
   * @return the parsed JSON answer, or null for an empty body
   * @throws HubException the hub refused the request
   * @throws HubUnavailableException the hub stayed unreachable or busy
   */
  public JsonNode send(String method, String path, Object body) {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(hubUrl + path)).header("Accept", "application/json");
    if (body == null) {
      req.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      req.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
    }
    HttpResponse<String> res =
        uninterrupted(() -> http.send(req.build(), HttpResponse.BodyHandlers.ofString()));
    if (res.statusCode() / 100 != 2) throw new HubException(res.statusCode(), res.body());
    if (res.body() == null || res.body().isBlank()) return null;
    try {
      return Json.MAPPER.readTree(res.body());
    } catch (IOException e) {
      throw new HubException(res.statusCode(), "not JSON: " + res.body());
    }
  }

  /**
   * @return {@code GET /info}, cached after the first call
   */
  public HubInfo info() {
    return info(false);
  }

  /**
   * @param refresh re-read it instead of using the cache
   * @return {@code GET /info}
   */
  public HubInfo info(boolean refresh) {
    HubInfo cached = infoCache;
    if (cached != null && !refresh) return cached;
    HttpResponse<String> res =
        uninterrupted(
            () ->
                Retry.send(
                    timeout ->
                        raw.send(
                            HttpRequest.newBuilder(URI.create(hubUrl + "/info"))
                                .timeout(timeout)
                                .build(),
                            HttpResponse.BodyHandlers.ofString()),
                    retry));
    if (res.statusCode() / 100 != 2) throw new HubException(res.statusCode(), res.body());
    try {
      infoCache = Json.MAPPER.readValue(res.body(), HubInfo.class);
    } catch (IOException e) {
      throw new HubException(res.statusCode(), "unreadable /info: " + res.body());
    }
    return infoCache;
  }

  /**
   * @param name a capability string
   * @return whether the hub advertises it. Capabilities, never versions.
   */
  public boolean hasCapability(String name) {
    return info().capabilities().contains(name);
  }

  /**
   * @param name a capability string
   * @throws UnsupportedByHubException unless the hub advertises it
   */
  public void requireCapability(String name) {
    if (!hasCapability(name)) throw new UnsupportedByHubException(name);
  }

  /**
   * Registers or replaces this identity's app profile. Do it before {@link #connect()}: the hub
   * only pushes and replays hub events on a socket opened by a registered app.
   *
   * @param profile the profile
   * @throws UnsupportedByHubException the hub lacks {@code apps.register}
   */
  public void setAppProfile(AppProfile profile) {
    requireCapability("apps.register");
    send("PUT", "/me/app/profile", profile);
  }

  /**
   * @param commands replaces the whole slash command list
   * @throws UnsupportedByHubException the hub lacks {@code apps.register}
   */
  public void setAppCommands(List<AppCommand> commands) {
    requireCapability("apps.register");
    send("PUT", "/me/app/commands", Map.of("commands", commands));
  }

  /**
   * @param subscriptions replaces every hub-event subscription; a {@code message.*} one must name
   *     its channels
   * @throws UnsupportedByHubException the hub lacks {@code apps.register}
   */
  public void setAppSubscriptions(List<AppSubscription> subscriptions) {
    requireCapability("apps.register");
    send("PUT", "/me/app/subscriptions", Map.of("subscriptions", subscriptions));
  }

  /**
   * Opens the hub WebSocket, starting from now. Reconnects, re-authenticates and resumes on its
   * own.
   *
   * @return the event stream
   */
  public EventStream connect() {
    return connect(null);
  }

  /**
   * Opens the hub WebSocket.
   *
   * @param sinceSeq replay hub events after this seq on the first connect (a position the program
   *     persisted); null starts from now
   * @return the event stream
   */
  public EventStream connect(Long sinceSeq) {
    String wsBase = hubUrl.replaceFirst("^http", "ws");
    return new EventStream(
        () ->
            wsBase
                + "/ws?token="
                + URLEncoder.encode(loginInterruptibly().token(), StandardCharsets.UTF_8),
        () -> {
          Session s = session;
          relogin(s == null ? null : s.token());
        },
        EventStream.jdkSockets(raw),
        retry,
        sinceSeq);
  }

  /**
   * {@code GET /me/events}: the polling transport, for a program that holds no socket. Returns the
   * same {@code hub_event} envelopes the socket replays, oldest first. Nothing is acknowledged:
   * pass the last {@link WsEvent.HubEvent#seq} handled as {@code cursor}.
   *
   * @param cursor only events after this seq; null starts at the beginning of the hub's 72-hour
   *     window
   * @param limit page size; null for the hub default
   * @return the events, oldest first
   * @throws ReplayUnavailableException the cursor is older than the window
   */
  public List<WsEvent.HubEvent> pollEvents(Long cursor, Integer limit) {
    List<String> query = new ArrayList<>();
    if (cursor != null) query.add("cursor=" + cursor);
    if (limit != null) query.add("limit=" + limit);
    JsonNode res;
    try {
      res =
          send("GET", "/me/events" + (query.isEmpty() ? "" : "?" + String.join("&", query)), null);
    } catch (HubException e) {
      if (e.status() != 410) throw e;
      JsonNode gone;
      try {
        gone = Json.MAPPER.readTree(e.body());
      } catch (IOException notJson) {
        throw e;
      }
      throw new ReplayUnavailableException(
          gone.path("earliest_seq").asLong(), gone.path("earliest_at").asLong());
    }
    if (res == null || !res.isArray()) {
      throw new HubException(200, "GET /me/events answered without an array");
    }
    List<WsEvent.HubEvent> out = new ArrayList<>();
    for (JsonNode r : res) {
      try {
        out.add(Json.MAPPER.treeToValue(r, WsEvent.HubEvent.class));
      } catch (IOException e) {
        throw new HubException(200, "GET /me/events answered an event this SDK cannot read: " + r);
      }
    }
    return out;
  }

  interface Call<T> {
    T run() throws IOException, InterruptedException;
  }

  static <T> T uninterrupted(Call<T> call) {
    try {
      return call.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WavvonException("interrupted", e);
    } catch (IOException e) {
      throw new HubUnavailableException("hub unreachable: " + e.getMessage(), null, e);
    }
  }
}
