package org.wavvon.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PagerTest {
  record Row(String code) {}

  static final List<Row> ROWS =
      IntStream.range(0, 7).mapToObj(i -> new Row("c" + i)).collect(Collectors.toList());

  /** A hub-like keyset endpoint: rows after the cursor, at most {@code limit}. */
  static List<Row> endpoint(String cursor, int limit) {
    int from = 0;
    if (cursor != null) {
      for (int i = 0; i < ROWS.size(); i++) if (ROWS.get(i).code().equals(cursor)) from = i + 1;
    }
    return ROWS.subList(from, Math.min(ROWS.size(), from + limit));
  }

  @Test
  void walksEveryPageNotJustTheFirst() {
    assertEquals(ROWS, Pager.of(PagerTest::endpoint, Row::code, 3).stream().toList());
  }

  @Test
  void stopsOnAnExactlyFullLastPageAfterOneEmptyFetch() {
    List<String> cursors = new ArrayList<>();
    List<Row> got =
        Pager.<Row>of(
                (c, l) -> {
                  cursors.add(c);
                  return endpoint(c, l);
                },
                Row::code,
                7)
            .stream()
            .toList();
    assertEquals(ROWS, got);
    assertEquals(java.util.Arrays.asList(null, "c6"), cursors);
  }

  @Test
  void clampsThePageSizeToWhatTheHubServes() {
    List<Integer> limits = new ArrayList<>();
    long n =
        Pager.<Row>of(
                (c, l) -> {
                  limits.add(l);
                  return endpoint(c, l);
                },
                Row::code,
                10_000)
            .stream()
            .count();
    assertEquals(7, n);
    assertEquals(500, limits.get(0));
  }

  @Test
  void refusesToLoopOnAHubThatIgnoresTheCursor() {
    Pager<Row> p = Pager.of((c, l) -> ROWS.subList(0, l), Row::code, 3);
    assertThrows(WavvonException.class, () -> p.stream().toList());
  }

  @Test
  void propagatesAFailedPageInsteadOfEndingTheList() {
    Pager<Row> p =
        Pager.of(
            (c, l) -> {
              if (c != null) throw new HubUnavailableException("503", 503, null);
              return endpoint(c, l);
            },
            Row::code,
            3);
    assertThrows(HubUnavailableException.class, () -> p.stream().toList());
  }
}
