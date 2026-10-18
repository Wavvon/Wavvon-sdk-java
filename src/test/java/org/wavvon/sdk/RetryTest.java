package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class RetryTest {
  record Res(int statusCode, String body) implements HttpResponse<String> {
    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder(URI.create("http://hub")).build();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (a, b) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request().uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  static <T> HttpResponse<T> run(Retry.Attempt<T> attempt, RetryOptions opts)
      throws InterruptedException {
    return Retry.send(attempt, opts, ms -> {}, () -> 1.0);
  }

  @Test
  void treats429And5xxAsTransientOtherStatusesAsAnswers() {
    IntStream.of(429, 500, 502, 503).forEach(s -> assertTrue(Retry.isTransientStatus(s)));
    IntStream.of(200, 400, 401, 403, 404).forEach(s -> assertFalse(Retry.isTransientStatus(s)));
  }

  @Test
  void backsOffExponentiallyUpToTheCap() {
    RetryOptions o =
        RetryOptions.DEFAULTS.withDelays(Duration.ofMillis(100), Duration.ofSeconds(1));
    List<Long> d = new ArrayList<>();
    for (int n : new int[] {1, 2, 3, 4, 5, 9, 64}) d.add(Retry.backoffDelay(n, o, () -> 1.0));
    assertEquals(List.of(100L, 200L, 400L, 800L, 1000L, 1000L, 1000L), d);
    assertEquals(0, Retry.backoffDelay(3, RetryOptions.DEFAULTS, () -> 0.0));
  }

  @Test
  void retries429UntilAnAnswerArrives() throws Exception {
    ArrayDeque<Integer> statuses = new ArrayDeque<>(List.of(429, 503, 200));
    List<Integer> seen = new ArrayList<>();
    HttpResponse<String> res =
        run(
            t -> new Res(statuses.pop(), ""),
            RetryOptions.DEFAULTS.withOnRetry(i -> seen.add(i.status())));
    assertEquals(200, res.statusCode());
    assertEquals(List.of(429, 503), seen);
  }

  @Test
  void retriesNetworkErrors() throws Exception {
    AtomicInteger n = new AtomicInteger();
    HttpResponse<String> res =
        run(
            t -> {
              if (n.getAndIncrement() < 2) throw new IOException("connection refused");
              return new Res(200, "ok");
            },
            RetryOptions.DEFAULTS);
    assertEquals("ok", res.body());
  }

  @Test
  void neverReturnsATransientFailureAsTheAnswer() {
    HubUnavailableException e =
        assertThrows(
            HubUnavailableException.class,
            () -> run(t -> new Res(429, ""), RetryOptions.DEFAULTS.withMaxAttempts(3)));
    assertEquals(429, e.lastStatus());
  }

  @Test
  void returnsADefinitiveRefusalWithoutRetrying() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpResponse<String> res =
        run(
            t -> {
              calls.incrementAndGet();
              return new Res(403, "");
            },
            RetryOptions.DEFAULTS);
    assertEquals(403, res.statusCode());
    assertEquals(1, calls.get());
  }

  @Test
  void letsNonTransportExceptionsThroughAtOnce() {
    AtomicInteger calls = new AtomicInteger();
    assertThrows(
        AuthSchemeException.class,
        () ->
            run(
                t -> {
                  calls.incrementAndGet();
                  throw new AuthSchemeException("no");
                },
                RetryOptions.DEFAULTS));
    assertEquals(1, calls.get());
  }
}
