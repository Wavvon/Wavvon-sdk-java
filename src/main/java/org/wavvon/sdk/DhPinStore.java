package org.wavvon.sdk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Where pinned peer DH keys live (trust on first use). The default keeps them in memory for the
 * life of the client; a program that wants pins across restarts passes its own to {@link
 * WavvonClient.Builder#dhPins(DhPinStore)}.
 */
public interface DhPinStore {
  /**
   * @param key the entry
   * @return the value stored under it, or null
   */
  String get(String key);

  /**
   * Stores a value, replacing any.
   *
   * @param key the entry
   * @param value the value
   */
  void put(String key, String value);

  /**
   * @return a store that keeps pins in memory
   */
  static DhPinStore inMemory() {
    ConcurrentHashMap<String, String> pins = new ConcurrentHashMap<>();
    return new DhPinStore() {
      @Override
      public String get(String key) {
        return pins.get(key);
      }

      @Override
      public void put(String key, String value) {
        pins.put(key, value);
      }
    };
  }
}
