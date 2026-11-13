package org.wavvon.sdk;

import com.sun.net.httpserver.HttpHandler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The app's {@code webhook_url} endpoint, independent of any HTTP framework: method, headers and
 * raw body in, status and JSON out. Refuses anything that does not verify with 401; answers a
 * verified dispatch with the handler's JSON. The hub waits 5 s for the answer. {@link
 * #asHttpHandler()} plugs it into the JDK's own {@code com.sun.net.httpserver}.
 */
public final class DispatchHandler {
  /**
   * What to send back.
   *
   * @param status the HTTP status
   * @param contentType the content type
   * @param body the body
   */
  public record Response(int status, String contentType, byte[] body) {}

  private final String hubPublicKey;
  private final Webhooks.VerifyOptions verify;
  private final Function<Dispatch.SlashInvocation, AppResponse> onSlash;
  private final Function<Dispatch.ComponentInteraction, ComponentResponse> onComponent;
  private final Function<Dispatch.UnknownDispatch, Object> onUnknown;

  private DispatchHandler(Builder b) {
    this.hubPublicKey = b.hubPublicKey;
    this.verify = b.verify;
    this.onSlash = b.onSlash;
    this.onComponent = b.onComponent;
    this.onUnknown = b.onUnknown;
  }

  /**
   * @param hubPublicKey the hub's key as pinned from {@code GET /info}
   * @return a builder
   */
  public static Builder builder(String hubPublicKey) {
    return new Builder(hubPublicKey);
  }

  /** Configures a {@link DispatchHandler}. */
  public static final class Builder {
    private final String hubPublicKey;
    private Webhooks.VerifyOptions verify = Webhooks.VerifyOptions.DEFAULTS;
    private Function<Dispatch.SlashInvocation, AppResponse> onSlash;
    private Function<Dispatch.ComponentInteraction, ComponentResponse> onComponent;
    private Function<Dispatch.UnknownDispatch, Object> onUnknown;

    private Builder(String hubPublicKey) {
      this.hubPublicKey = Objects.requireNonNull(hubPublicKey);
    }

    /**
     * @param fn answers a slash command
     * @return this
     */
    public Builder onSlashCommand(Function<Dispatch.SlashInvocation, AppResponse> fn) {
      this.onSlash = fn;
      return this;
    }

    /**
     * @param fn answers a button or select
     * @return this
     */
    public Builder onComponent(Function<Dispatch.ComponentInteraction, ComponentResponse> fn) {
      this.onComponent = fn;
      return this;
    }

    /**
     * @param fn answers a verified dispatch of a type this SDK does not know; default {@code {}}
     * @return this
     */
    public Builder onUnknown(Function<Dispatch.UnknownDispatch, Object> fn) {
      this.onUnknown = fn;
      return this;
    }

    /**
     * @param opts the clock and window
     * @return this
     */
    public Builder verifyOptions(Webhooks.VerifyOptions opts) {
      this.verify = Objects.requireNonNull(opts);
      return this;
    }

    /**
     * @return the handler
     */
    public DispatchHandler build() {
      return new DispatchHandler(this);
    }
  }

  /**
   * Handles one request.
   *
   * @param method the HTTP method
   * @param headers the request headers
   * @param body the raw request body
   * @return what to send back
   */
  public Response handle(String method, Map<String, List<String>> headers, byte[] body) {
    if (!"POST".equalsIgnoreCase(method)) {
      return new Response(405, "text/plain", Bytes.utf8("method not allowed"));
    }
    Dispatch dispatch;
    try {
      dispatch = Webhooks.verifyDispatch(headers, body, hubPublicKey, verify);
    } catch (WebhookVerificationException e) {
      return new Response(401, "text/plain", Bytes.utf8(e.getMessage()));
    }
    Object answer = null;
    if (dispatch instanceof Dispatch.SlashInvocation s && onSlash != null) {
      answer = onSlash.apply(s);
    } else if (dispatch instanceof Dispatch.ComponentInteraction c && onComponent != null) {
      answer = onComponent.apply(c);
    } else if (dispatch instanceof Dispatch.UnknownDispatch u && onUnknown != null) {
      answer = onUnknown.apply(u);
    }
    return new Response(
        200, "application/json", answer == null ? Bytes.utf8("{}") : Json.bytes(answer));
  }

  /**
   * @return this handler for {@code com.sun.net.httpserver.HttpServer}
   */
  public HttpHandler asHttpHandler() {
    return exchange -> {
      try (exchange) {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Response r;
        try {
          r = handle(exchange.getRequestMethod(), exchange.getRequestHeaders(), body);
        } catch (RuntimeException e) {
          System.getLogger("org.wavvon.sdk").log(System.Logger.Level.ERROR, "dispatch handler", e);
          r = new Response(500, "text/plain", new byte[0]);
        }
        exchange.getResponseHeaders().set("Content-Type", r.contentType());
        exchange.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
        exchange.getResponseBody().write(r.body());
      }
    };
  }
}
