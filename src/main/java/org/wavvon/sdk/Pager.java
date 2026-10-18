package org.wavvon.sdk;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Walks a keyset-paged list to exhaustion, lazily. The hub's dialect is a bare array plus {@code
 * limit} and {@code cursor}, where the cursor is the key of the previous page's last row ({@code
 * code} for invites, {@code public_key} for users, {@code id} for most others). A page shorter than
 * {@code limit} is the end; one page is not the list.
 *
 * <p>Check the endpoint's capability ({@code list.cursor} or {@code list.cursor.lists}) first: a
 * hub without it ignores the cursor, which this detects and reports rather than looping.
 *
 * @param <T> the row type
 */
public final class Pager<T> implements Iterable<T> {
  /** The hub clamps {@code limit} to this; asking for more would look like a short page. */
  public static final int MAX_PAGE_SIZE = 500;

  /** Fetches one page. It must throw on failure: an error must never read as an empty page. */
  @FunctionalInterface
  public interface PageFetcher<T> {
    /**
     * @param cursor the previous page's last key, or null for the first page
     * @param limit the page size to ask for
     * @return the page
     */
    List<T> fetch(String cursor, int limit);
  }

  private final PageFetcher<T> fetcher;
  private final Function<T, String> key;
  private final int limit;

  private Pager(PageFetcher<T> fetcher, Function<T, String> key, int limit) {
    this.fetcher = Objects.requireNonNull(fetcher);
    this.key = Objects.requireNonNull(key);
    this.limit = Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
  }

  /**
   * @param fetcher makes the request, naming the cursor parameter the endpoint uses
   * @param key the row's cursor key
   * @param limit the page size; clamped to [1, 500]
   * @param <T> the row type
   * @return a pager; nothing is fetched until it is iterated
   */
  public static <T> Pager<T> of(PageFetcher<T> fetcher, Function<T, String> key, int limit) {
    return new Pager<>(fetcher, key, limit);
  }

  /**
   * Same as {@link #of(PageFetcher, Function, int)} with 100 rows a page.
   *
   * @param fetcher makes the request
   * @param key the row's cursor key
   * @param <T> the row type
   * @return a pager
   */
  public static <T> Pager<T> of(PageFetcher<T> fetcher, Function<T, String> key) {
    return new Pager<>(fetcher, key, 100);
  }

  /**
   * @return every row, fetching pages as the stream is consumed
   */
  public Stream<T> stream() {
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
  }

  @Override
  public Iterator<T> iterator() {
    return new Iterator<>() {
      private Iterator<T> page = List.<T>of().iterator();
      private String cursor;
      private String previousFirst;
      private boolean done;

      @Override
      public boolean hasNext() {
        while (!page.hasNext() && !done) fetchNext();
        return page.hasNext();
      }

      @Override
      public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        return page.next();
      }

      private void fetchNext() {
        List<T> rows = fetcher.fetch(cursor, limit);
        if (!rows.isEmpty() && cursor != null && key.apply(rows.get(0)).equals(previousFirst)) {
          throw new WavvonException(
              "the hub returned the same page twice: it does not honour the cursor on this endpoint");
        }
        if (rows.size() < limit) {
          done = true;
        } else {
          previousFirst = key.apply(rows.get(0));
          cursor = key.apply(rows.get(rows.size() - 1));
        }
        page = rows.iterator();
      }
    };
  }
}
