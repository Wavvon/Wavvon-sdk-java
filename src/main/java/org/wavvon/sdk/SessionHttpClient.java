package org.wavvon.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An HttpClient that adds the bearer, retries transient failures and logs in again once on a 401.
 * The generated REST client is handed this, so every generated call gets the same behaviour.
 */
final class SessionHttpClient extends HttpClient {
  private final HttpClient delegate;
  private final WavvonClient owner;
  private final RetryOptions retry;

  SessionHttpClient(HttpClient delegate, WavvonClient owner, RetryOptions retry) {
    this.delegate = delegate;
    this.owner = owner;
    this.retry = retry;
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    String token = owner.loginInterruptibly().token();
    HttpResponse<T> res = sendWith(request, handler, token);
    if (res.statusCode() != 401) return res;
    if (res.body() instanceof InputStream in) in.close();
    return sendWith(request, handler, owner.relogin(token).token());
  }

  private <T> HttpResponse<T> sendWith(
      HttpRequest request, HttpResponse.BodyHandler<T> handler, String token)
      throws InterruptedException {
    return Retry.send(
        timeout -> {
          HttpRequest.Builder b =
              HttpRequest.newBuilder(
                      request, (name, value) -> !name.equalsIgnoreCase("authorization"))
                  .header("Authorization", "Bearer " + token);
          if (request.timeout().isEmpty()) b.timeout(timeout);
          return delegate.send(b.build(), handler);
        },
        retry);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    Supplier<HttpResponse<T>> call =
        () -> {
          try {
            return send(request, handler);
          } catch (IOException | InterruptedException e) {
            throw new CompletionException(e);
          }
        };
    return delegate
        .executor()
        .map(ex -> CompletableFuture.supplyAsync(call, ex))
        .orElseGet(() -> CompletableFuture.supplyAsync(call));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, handler);
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  /** A builder that always yields this client: the generated ApiClient asks for one. */
  Builder asBuilder() {
    HttpClient self = this;
    return new Builder() {
      @Override
      public Builder cookieHandler(CookieHandler h) {
        return this;
      }

      @Override
      public Builder connectTimeout(Duration d) {
        return this;
      }

      @Override
      public Builder sslContext(SSLContext c) {
        return this;
      }

      @Override
      public Builder sslParameters(SSLParameters p) {
        return this;
      }

      @Override
      public Builder executor(Executor e) {
        return this;
      }

      @Override
      public Builder followRedirects(Redirect r) {
        return this;
      }

      @Override
      public Builder version(Version v) {
        return this;
      }

      @Override
      public Builder priority(int p) {
        return this;
      }

      @Override
      public Builder proxy(ProxySelector p) {
        return this;
      }

      @Override
      public Builder authenticator(Authenticator a) {
        return this;
      }

      @Override
      public HttpClient build() {
        return self;
      }
    };
  }
}
