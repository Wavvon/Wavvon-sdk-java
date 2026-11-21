package org.wavvon.sdk.smoke;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import org.wavvon.sdk.Voice;
import org.wavvon.sdk.VoiceEvent;
import org.wavvon.sdk.VoiceException;
import org.wavvon.sdk.WavvonClient;
import org.wavvon.sdk.generated.api.ChannelsApi;
import org.wavvon.sdk.generated.api.InvitesApi;
import org.wavvon.sdk.generated.model.CreateChannelRequest;
import org.wavvon.sdk.generated.model.CreateInviteRequest;

/**
 * Smoke test of voice against a real hub: the owner and a second identity share a room, and every
 * assertion needs a decrypted frame whose bytes match what the other side sent, never a roster row.
 * Nothing is played. Without an Opus encoder the frames are fixed 20 ms TOC bytes with a numbered
 * payload; the hub relays them sealed and never decodes them.
 *
 * <pre>
 * WAVVON_SMOKE_HUB=http://localhost:3400 [WAVVON_SMOKE_SEED=&lt;64 hex&gt;] \
 *   ./mvnw -B -Psmoke verify -Dsmoke.main=org.wavvon.sdk.smoke.VoiceSmoke
 * </pre>
 *
 * The seed identity (default 5a repeated) must be a hub owner, so it can create the channel and
 * mint the invite the second identity joins with.
 */
public final class VoiceSmoke {
  private VoiceSmoke() {}

  static void say(String s) {
    System.out.println("voice smoke: " + s);
  }

  public static void main(String[] args) throws Exception {
    String hub = System.getenv("WAVVON_SMOKE_HUB");
    if (hub == null || hub.isEmpty()) {
      System.err.println("set WAVVON_SMOKE_HUB");
      System.exit(2);
    }
    String seed = System.getenv().getOrDefault("WAVVON_SMOKE_SEED", "5a".repeat(32));
    WavvonClient a = WavvonClient.builder(hub).seed(seed).build();
    a.login();
    byte[] bSeed = new byte[32];
    new SecureRandom().nextBytes(bSeed);
    WavvonClient probe = WavvonClient.builder(hub).seed(bSeed).build();
    String bKey = probe.identity().publicKey();
    String code =
        new InvitesApi(a.api()).createInvite(new CreateInviteRequest().boundPubkey(bKey)).getCode();
    WavvonClient b = WavvonClient.builder(hub).seed(bSeed).inviteCode(code).build();
    b.login();
    String aKey = a.identity().publicKey();

    String channelId =
        new ChannelsApi(a.api())
            .createChannel(
                new CreateChannelRequest()
                    .name("voice-smoke-" + bKey.substring(0, 6))
                    .channelType("voice"))
            .getId();

    Voice va = a.joinVoice(channelId);
    Voice vb = b.joinVoice(channelId);
    say("both joined: A is sender " + va.senderId() + ", B is " + vb.senderId());
    Seen seenA = Seen.watch(va);
    Seen seenB = Seen.watch(vb);

    // A speaks; B must open what it hears, byte for byte.
    CompletableFuture<Void> sending = speak(va, 0xa1, 200);
    until("B to open 100 frames from A", () -> seenB.heard(aKey, 0xa1, 1) >= 100);
    sending.join();
    for (VoiceEvent.Audio f : seenB.frames()) {
      if (f.from().equals(aKey) && !Clip.sameAs(f, 0xa1)) {
        throw new IllegalStateException("B opened a frame that is not A's");
      }
    }
    say("B opened " + seenB.heard(aKey, 0xa1, 1) + " of A's 200 frames, each byte-identical");

    sending = speak(vb, 0xb2, 200);
    until("A to open 100 frames from B", () -> seenA.heard(bKey, 0xb2, 1) >= 100);
    sending.join();
    say("A opened " + seenA.heard(bKey, 0xb2, 1) + " of B's 200 frames, each byte-identical");

    // A frame that is not 20 ms is refused before anything is sent.
    try {
      va.sendOpus(new byte[] {(byte) 0xf9, 0, 0});
      throw new IllegalStateException("a 40 ms packet was accepted");
    } catch (VoiceException e) {
      if (e.kind() != VoiceException.Kind.BAD_FRAME) throw e;
      say("a 40 ms packet is refused (" + e.getMessage() + ")");
    }

    // Rotation: B leaves, so A's key changes to 2; B rejoins and must open it.
    vb.leave();
    Voice vb2 = b.joinVoice(channelId);
    Seen seenB2 = Seen.watch(vb2);
    sending = speak(va, 0xa3, 200);
    until("B's second session to open key_id >= 2", () -> seenB2.heard(aKey, 0xa3, 2) >= 50);
    sending.join();
    long maxKey = seenB2.frames().stream().mapToLong(VoiceEvent.Audio::keyId).max().orElse(0);
    say(
        "after B left, A rotated to key_id "
            + maxKey
            + " and B opened "
            + seenB2.heard(aKey, 0xa3, 2)
            + " frames under it");

    // Proximity: ten positions in 100 ms end at the last one.
    va.createZone("smoke-zone", "smoke", "2d", null, false);
    until(
        "both to see the zone",
        () ->
            seenB2.hasZone("smoke-zone")
                && va.zones().stream().anyMatch(z -> z.zoneId().equals("smoke-zone")));
    long t0 = System.nanoTime();
    for (int i = 0; i < 10; i++) {
      va.setPosition("smoke-zone", 10 + i, 50);
      Thread.sleep(10);
    }
    // One update per 50 ms window the loop spanned, plus the first and the final
    // flush; timer slop stretches the loop, and nine keeps it a coalescing test.
    int limit = (int) Math.min(9, Math.ceil((System.nanoTime() - t0) / 50e6) + 2);
    until(
        "B to see the last position",
        () -> {
          List<List<Double>> p = seenB2.positions();
          return !p.isEmpty() && p.get(p.size() - 1).equals(List.of(19.0, 50.0));
        });
    int n = seenB2.positions().size();
    if (n > limit) {
      throw new IllegalStateException(
          "coalescing let " + n + " of 10 positions through (limit " + limit + ")");
    }
    say("ten positions in 100 ms coalesced to " + n + ", ending at the last");

    // A position in someone else's creator-only zone is refused, as an event.
    va.createZone("own-zone", "own", "2d", null, true);
    until(
        "B to see the creator-only zone",
        () -> vb2.zones().stream().anyMatch(z -> z.zoneId().equals("own-zone")));
    vb2.setPosition("own-zone", 1, 1);
    until("B to be refused", () -> !seenB2.refused.isEmpty());
    say("B's position was refused: " + seenB2.refused.peek());

    if (!seenA.skipped.isEmpty()) throw new IllegalStateException("A skipped " + seenA.skipped);
    if (seenA.rejoining > 0) throw new IllegalStateException("A rejoined");
    va.leave();
    vb2.leave();
    say("VOICE SMOKE OK");
    System.exit(0);
  }

  static void until(String what, BooleanSupplier cond) throws InterruptedException {
    for (int i = 0; i < 600; i++) {
      if (cond.getAsBoolean()) return;
      Thread.sleep(50);
    }
    throw new IllegalStateException("timed out waiting for " + what);
  }

  static CompletableFuture<Void> speak(Voice voice, int tag, int n) {
    return CompletableFuture.runAsync(
        () -> {
          for (int i = 0; i < n; i++) voice.sendOpus(Clip.frame(tag, i));
        });
  }

  static final class Seen {
    private final List<VoiceEvent.Audio> frames = new ArrayList<>();
    private final List<List<Double>> positions = new ArrayList<>();
    private List<String> zones = List.of();
    final ConcurrentLinkedQueue<String> refused = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> skipped = new ConcurrentLinkedQueue<>();
    volatile int rejoining;

    static Seen watch(Voice voice) {
      Seen seen = new Seen();
      Thread t =
          new Thread(
              () -> {
                VoiceEvent ev;
                while ((ev = voice.next()) != null) {
                  synchronized (seen) {
                    if (ev instanceof VoiceEvent.Audio a) {
                      seen.frames.add(a);
                    } else if (ev instanceof VoiceEvent.Zones z) {
                      seen.zones = z.zones().stream().map(VoiceEvent.Zone::zoneId).toList();
                    } else if (ev instanceof VoiceEvent.Position p) {
                      seen.positions.add(p.position());
                    } else if (ev instanceof VoiceEvent.PositionRefused r) {
                      seen.refused.add(r.reason());
                    } else if (ev instanceof VoiceEvent.Rejoining) {
                      seen.rejoining++;
                    } else if (ev instanceof VoiceEvent.KeySkipped k) {
                      seen.skipped.add(k.publicKey());
                    } else if (ev instanceof VoiceEvent.Error e) {
                      System.err.println("voice error: " + e.error().getMessage());
                    }
                  }
                }
              });
      t.setDaemon(true);
      t.start();
      return seen;
    }

    synchronized List<VoiceEvent.Audio> frames() {
      return List.copyOf(frames);
    }

    synchronized List<List<Double>> positions() {
      return List.copyOf(positions);
    }

    synchronized boolean hasZone(String id) {
      return zones.contains(id);
    }

    long heard(String from, int tag, long minKey) {
      return frames().stream()
          .filter(f -> f.from().equals(from) && f.keyId() >= minKey && Clip.sameAs(f, tag))
          .count();
    }
  }

  static final class Clip {
    private Clip() {}

    // CELT fullband, one 20 ms frame; the payload carries a tag and the frame number.
    static byte[] frame(int tag, int i) {
      byte[] f = new byte[80];
      f[0] = (byte) 0xf8;
      f[1] = (byte) tag;
      ByteBuffer.wrap(f).putInt(2, i);
      for (int n = 6; n < f.length; n++) f[n] = (byte) (n * 7 + i);
      return f;
    }

    static boolean sameAs(VoiceEvent.Audio a, int tag) {
      return a.opus().length >= 6
          && Arrays.equals(a.opus(), frame(tag, ByteBuffer.wrap(a.opus()).getInt(2)));
    }
  }
}
