package org.wavvon.sdk;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * How hard to retry a transient failure (429, 5xx, timeout, network error) before giving up with
 * {@link HubUnavailableException}.
 *
 * @param maxAttempts attempts before giving up; {@link Integer#MAX_VALUE} keeps trying. Default 8.
 * @param baseDelay the first backoff ceiling. Default 500 ms.
 * @param maxDelay the backoff ceiling never grows past this. Default 30 s.
 * @param timeout per-attempt timeout; a timeout counts as transient. Default 15 s.
 * @param onRetry called before each wait, so a program can show that it is retrying; may be null
 */
public record RetryOptions(
    int maxAttempts,
    Duration baseDelay,
    Duration maxDelay,
    Duration timeout,
    Consumer<RetryInfo> onRetry) {

  /** The defaults: 8 attempts, 500 ms base, 30 s cap, 15 s per attempt. */
  public static final RetryOptions DEFAULTS =
      new RetryOptions(
          8, Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(15), null);

  /**
   * @param n attempts before giving up
   * @return a copy with {@code maxAttempts} replaced
   */
  public RetryOptions withMaxAttempts(int n) {
    return new RetryOptions(n, baseDelay, maxDelay, timeout, onRetry);
  }

  /**
   * @param base the first backoff ceiling
   * @param max the largest backoff ceiling
   * @return a copy with the backoff replaced
   */
  public RetryOptions withDelays(Duration base, Duration max) {
    return new RetryOptions(maxAttempts, base, max, timeout, onRetry);
  }

  /**
   * @param t per-attempt timeout
   * @return a copy with {@code timeout} replaced
   */
  public RetryOptions withTimeout(Duration t) {
    return new RetryOptions(maxAttempts, baseDelay, maxDelay, t, onRetry);
  }

  /**
   * @param fn called before each wait
   * @return a copy with {@code onRetry} replaced
   */
  public RetryOptions withOnRetry(Consumer<RetryInfo> fn) {
    return new RetryOptions(maxAttempts, baseDelay, maxDelay, timeout, fn);
  }

  /**
   * One retry about to happen.
   *
   * @param attempt the attempt that just failed, from 1
   * @param delayMs how long the SDK waits before the next one
   * @param status the HTTP status, or null for a network error or timeout
   * @param error the transport failure, or null when the hub answered
   */
  public record RetryInfo(int attempt, long delayMs, Integer status, Throwable error) {}
}
