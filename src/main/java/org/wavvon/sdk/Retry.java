package org.wavvon.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Capped exponential backoff with full jitter, and the rule for what counts as transient. */
public final class Retry {
  private Retry() {}

  /** One attempt; it may make several requests (login does) and return the deciding response. */
  @FunctionalInterface
  public interface Attempt<T> {
    /**
     * @param timeout this attempt's timeout
     * @return the response that decides this attempt
     * @throws IOException a transport failure, which is retried
     * @throws InterruptedException the thread was interrupted
     */
    HttpResponse<T> run(Duration timeout) throws IOException, InterruptedException;
  }

  interface Sleeper {
    void sleep(long ms) throws InterruptedException;
  }

  /**
   * @param status an HTTP status
   * @return true for 429 ("not now") and 5xx (the hub or a proxy in front of it failing)
   */
  public static boolean isTransientStatus(int status) {
    return status == 429 || status >= 500;
  }

  /**
   * Full jitter: uniform in [0, min(max, base * 2^(attempt-1))].
   *
   * @param attempt the attempt that failed, from 1
   * @param opts the backoff settings
   * @param random a source in [0, 1]
   * @return the wait in milliseconds
   */
  public static long backoffDelay(int attempt, RetryOptions opts, DoubleSupplier random) {
    long base = opts.baseDelay().toMillis();
    long max = opts.maxDelay().toMillis();
    long ceiling = attempt > 40 ? max : Math.min(max, base * (1L << (attempt - 1)));
    return (long) Math.floor(random.getAsDouble() * ceiling);
  }

  /**
   * Runs {@code attempt} until it yields a non-transient response. The hub sends no Retry-After, so
   * the wait is capped exponential backoff with jitter. When the budget runs out this throws {@link
   * HubUnavailableException} rather than handing back the last 429/5xx: a transient failure must
   * never be mistaken for an answer. Only {@link IOException}s count as transient; anything else
   * propagates at once.
   *
   * @param attempt the request to make
   * @param opts the retry budget
   * @param <T> the body type
   * @return the first non-transient response
   * @throws InterruptedException the thread was interrupted while waiting
   */
  public static <T> HttpResponse<T> send(Attempt<T> attempt, RetryOptions opts)
      throws InterruptedException {
    return send(attempt, opts, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
  }

  static <T> HttpResponse<T> send(
      Attempt<T> attempt, RetryOptions opts, Sleeper sleeper, DoubleSupplier random)
      throws InterruptedException {
    Integer lastStatus = null;
    Throwable lastError = null;
    for (int n = 1; ; n++) {
      Integer status = null;
      IOException error = null;
      try {
        HttpResponse<T> res = attempt.run(opts.timeout());
        if (!isTransientStatus(res.statusCode())) return res;
        status = res.statusCode();
        if (res.body() instanceof InputStream in) closeQuietly(in);
      } catch (IOException e) {
        error = e;
      }
      lastStatus = status;
      lastError = error;
      if (n >= opts.maxAttempts()) break;
      long delay = backoffDelay(n, opts, random);
      if (opts.onRetry() != null) {
        opts.onRetry().accept(new RetryOptions.RetryInfo(n, delay, status, error));
      }
      sleeper.sleep(delay);
    }
    throw new HubUnavailableException(
        lastStatus != null
            ? "hub kept answering " + lastStatus + " after " + opts.maxAttempts() + " attempts"
            : "hub unreachable after " + opts.maxAttempts() + " attempts",
        lastStatus,
        lastError);
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // the response is being discarded anyway
    }
  }
}
