package org.wavvon.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A joined voice room ({@link WavvonClient#joinVoice(String)}), sdk-voice.md. It does what the
 * desktop and web clients do: signalling rides a WebSocket of the room's own, the audio a WebRTC
 * data channel, and every packet is sealed with a per-sender key wrapped to each listener's DH key.
 *
 * <p>The SDK owns the mechanisms (key distribution and rotation, 20 ms pacing, the replay
 * watermark, the proximity rate limit). It decides nothing for the program: mute, deafen, AFK moves
 * and a changed DH key are events and calls. Read events with {@link #next()} from one thread; send
 * from any.
 */
public final class Voice implements AutoCloseable {
  private static final double FRAME_MS = 20;
  private static final long FRAME_SAMPLES = 960;
  // A program this far behind is re-anchored instead of bursting to catch up.
  private static final int MAX_LAG_FRAMES = 3;
  // More than one position every 50 ms risks the hub's silent 30 ms rate drop.
  private static final double POSITION_EVERY_MS = 50;
  private static final double SPEAKING_IDLE_MS = 200;
  private static final long STEP_TIMEOUT_MS = 15_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WavvonClient client;
  private final DhPinStore pins;
  private final String me;
  private final LinkedBlockingQueue<WsEvent> inbox = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<VoiceEvent> out = new LinkedBlockingQueue<>();
  // Socket sends, in order, off the caller's thread and out of the lock.
  private final ExecutorService io = Executors.newSingleThreadExecutor(daemon("wavvon-voice-io"));
  // DH lookups are HTTP calls with retries: never on the socket or WebRTC threads.
  private final ExecutorService work =
      Executors.newSingleThreadExecutor(daemon("wavvon-voice-keys"));
  private final ScheduledExecutorService tick =
      Executors.newSingleThreadScheduledExecutor(daemon("wavvon-voice-tick"));
  private final Map<String, byte[]> dhCache = new HashMap<>();
  private final Object gate = new Object();
  private final long t0 = System.nanoTime();

  private EventStream stream;
  private Thread loop;
  private String channelId;
  private int senderId;
  private boolean inRoom;
  private boolean maySpeak = true;
  private boolean muted;
  private boolean deafened;
  private Map<Integer, VoiceEvent.Participant> roster = new HashMap<>();
  private OwnKey own = new OwnKey(new byte[32], new byte[4], 0);
  private final Map<String, VoiceCrypto.Unwrapped> remote = new HashMap<>();
  private final Map<String, Long> marks = new HashMap<>();
  private List<VoiceEvent.Zone> zones = new ArrayList<>();
  private VoiceLink link;
  private Double pacingNext;
  private long pacingTs;
  private boolean speaking;
  private double lastSend;
  private String positionZone;
  private List<Double> position;
  private double lastPosition = Double.NEGATIVE_INFINITY;
  private boolean leaving;
  private volatile boolean stopped;

  private static final class OwnKey {
    final byte[] key;
    final byte[] salt;
    final long keyId;
    long ctr;

    OwnKey(byte[] key, byte[] salt, long keyId) {
      this.key = key;
      this.salt = salt;
      this.keyId = keyId;
    }

    OwnKey next() {
      byte[] k = new byte[32];
      byte[] s = new byte[4];
      RANDOM.nextBytes(k);
      RANDOM.nextBytes(s);
      return new OwnKey(k, s, keyId + 1);
    }
  }

  Voice(WavvonClient client, DhPinStore pins, String channelId) {
    this.client = client;
    this.pins = pins;
    this.me = client.identity().publicKey();
    this.channelId = channelId;
  }

  /**
   * @return the room's channel. For a spawner room this is the spawned channel, not the one joined.
   */
  public String channelId() {
    synchronized (gate) {
      return channelId;
    }
  }

  /**
   * @return this program's relay id in the room
   */
  public int senderId() {
    synchronized (gate) {
      return senderId;
    }
  }

  /**
   * @return false when the relay drops what this program sends; {@link #sendOpus} then throws
   *     {@link VoiceException.Kind#MAY_NOT_SPEAK}
   */
  public boolean maySpeak() {
    synchronized (gate) {
      return maySpeak;
    }
  }

  /**
   * @return who is in the room, by sender id
   */
  public List<VoiceEvent.Participant> roster() {
    synchronized (gate) {
      return rosterLocked();
    }
  }

  /**
   * @return the room's zones
   */
  public List<VoiceEvent.Zone> zones() {
    synchronized (gate) {
      return List.copyOf(zones);
    }
  }

  /**
   * Waits for the next event.
   *
   * @return the event, or null once the room was left and every event was read
   */
  public VoiceEvent next() {
    while (true) {
      VoiceEvent e = poll(Duration.ofMillis(200));
      if (e != null) return e;
      if (stopped && out.isEmpty()) return null;
    }
  }

  /**
   * @param timeout how long to wait
   * @return the next event, or null if none arrived in time
   */
  public VoiceEvent poll(Duration timeout) {
    try {
      return out.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WavvonException("interrupted", e);
    }
  }

  /**
   * Local: the hub has no self-mute. Stops sending (calls still wait their slot) and ends the
   * speaking indication.
   *
   * @param muted muted or not
   */
  public void setMuted(boolean muted) {
    synchronized (gate) {
      this.muted = muted;
      if (muted && speaking) {
        speaking = false;
        post(frame("voice_speaking", "channel_id", channelId, "speaking", false));
      }
    }
  }

  /**
   * Local: stops delivering audio. Key distribution keeps running, so undeafening is instant.
   *
   * @param deafened deafened or not
   */
  public void setDeafened(boolean deafened) {
    synchronized (gate) {
      this.deafened = deafened;
    }
  }

  /**
   * Sends one 20 ms Opus packet, blocking until its slot on a monotonic clock. A source faster than
   * real time is slowed to it; one more than three frames behind is re-anchored, {@code ts} jumping
   * by the slots it missed.
   *
   * @param packet one Opus packet of exactly 20 ms
   * @throws VoiceException the packet is not 20 ms, too large, or the program cannot send now
   */
  public void sendOpus(byte[] packet) {
    int us;
    try {
      us = VoiceCrypto.opusDurationUs(packet);
    } catch (IllegalArgumentException e) {
      throw new VoiceException(VoiceException.Kind.BAD_FRAME, e.getMessage(), e);
    }
    if (us != 20_000) {
      throw new VoiceException(VoiceException.Kind.BAD_FRAME, us / 1000.0 + " ms, not 20 ms");
    }
    // The hub adds the 3-byte relay prefix on the way out.
    if (VoiceCrypto.HEADER + packet.length + VoiceCrypto.TAG + 3 > VoiceLink.MAX_PACKET) {
      throw new VoiceException(
          VoiceException.Kind.PACKET_TOO_LARGE,
          "a " + packet.length + "-byte packet does not fit one SCTP chunk");
    }
    double slot;
    long ts;
    synchronized (gate) {
      requireInRoom();
      if (!maySpeak) {
        throw new VoiceException(
            VoiceException.Kind.MAY_NOT_SPEAK, "this program may not speak in this room");
      }
      double now = now();
      double next = pacingNext != null ? pacingNext : now;
      if (now > next + FRAME_MS * MAX_LAG_FRAMES) {
        long missed = (long) Math.floor((now - next) / FRAME_MS);
        pacingTs = (pacingTs + FRAME_SAMPLES * missed) & 0xffffffffL;
        next = now;
      }
      slot = next;
      ts = pacingTs;
      pacingNext = next + FRAME_MS;
      pacingTs = (pacingTs + FRAME_SAMPLES) & 0xffffffffL;
    }
    long waitNanos = (long) ((slot - now()) * 1_000_000);
    if (waitNanos > 0) {
      try {
        TimeUnit.NANOSECONDS.sleep(waitNanos);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WavvonException("interrupted", e);
      }
    }
    VoiceLink l;
    byte[] sealed;
    synchronized (gate) {
      requireInRoom();
      if (link == null) throw new VoiceException(VoiceException.Kind.NOT_IN_ROOM, "no audio link");
      if (muted) return;
      l = link;
      try {
        sealed = VoiceCrypto.packetSeal(own.key, own.salt, own.keyId, own.ctr++, ts, packet);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
      lastSend = now();
      if (!speaking) {
        speaking = true;
        post(frame("voice_speaking", "channel_id", channelId, "speaking", true));
      }
    }
    l.send(sealed);
  }

  /**
   * The latest position in a zone of this room. At most one reaches the hub every 50 ms (it
   * silently drops one under 30 ms after the last); the newest is always the one sent. Checked
   * here, because the hub drops a bad one silently.
   *
   * @param zoneId the zone
   * @param position 2 or 3 coordinates, as the zone's coordinate system wants
   * @throws VoiceException no such zone, or the wrong number of coordinates
   */
  public void setPosition(String zoneId, double... position) {
    synchronized (gate) {
      VoiceEvent.Zone z =
          zones.stream()
              .filter(x -> x.zoneId().equals(zoneId))
              .findFirst()
              .orElseThrow(
                  () ->
                      new VoiceException(
                          VoiceException.Kind.UNKNOWN_ZONE, "no zone " + zoneId + " in this room"));
      int want =
          switch (z.coordinateSystem()) {
            case "2d" -> 2;
            case "3d" -> 3;
            default -> Math.max(1, Math.min(3, position.length));
          };
      if (position.length != want) {
        throw new VoiceException(
            VoiceException.Kind.BAD_POSITION,
            position.length + " coordinates, the zone wants " + want);
      }
      List<Double> p = new ArrayList<>();
      for (double d : position) p.add(d);
      positionZone = zoneId;
      this.position = p;
    }
  }

  /**
   * Creates a proximity zone; needs {@code channels.manage} on the channel.
   *
   * @param zoneId the new zone's id
   * @param name its name
   * @param coordinateSystem {@code 2d} or {@code 3d}
   * @param attenuation the hub's {@code {model, max_radius, ref_dist, rolloff}} object; null takes
   *     the defaults
   * @param creatorOnly whether only this program may stand in it
   */
  public void createZone(
      String zoneId,
      String name,
      String coordinateSystem,
      Map<String, Object> attenuation,
      boolean creatorOnly) {
    post(
        frame(
            "voice_zone_create",
            "zone_id",
            zoneId,
            "name",
            name,
            "coordinate_system",
            coordinateSystem,
            "attenuation",
            attenuation != null ? attenuation : Map.of(),
            "auth_mode",
            creatorOnly ? "creator_only" : "any_channel_member"));
  }

  /**
   * Destroys a zone of this room.
   *
   * @param zoneId the zone
   */
  public void destroyZone(String zoneId) {
    post(frame("voice_zone_destroy", "zone_id", zoneId));
  }

  /**
   * {@code voice_leave}, then the audio session and the room's socket close. Closing the session
   * alone would not leave the roster.
   */
  public void leave() {
    VoiceLink l;
    synchronized (gate) {
      if (stopped || leaving) return;
      leaving = true;
      inRoom = false;
      if (speaking) post(frame("voice_speaking", "channel_id", channelId, "speaking", false));
      post(frame("voice_leave", "channel_id", channelId));
      l = link;
      link = null;
    }
    if (l != null) l.close();
    io.shutdown();
    try {
      io.awaitTermination(2, TimeUnit.SECONDS);
      // Lets the hub read voice_leave before the socket closes.
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    shutdown();
  }

  /** Same as {@link #leave()}. */
  @Override
  public void close() {
    leave();
  }

  // ---- internals ----

  private static ThreadFactory daemon(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

  private double now() {
    return (System.nanoTime() - t0) / 1_000_000.0;
  }

  private void requireInRoom() {
    if (!inRoom) throw new VoiceException(VoiceException.Kind.NOT_IN_ROOM, "not in a voice room");
  }

  private List<VoiceEvent.Participant> rosterLocked() {
    List<VoiceEvent.Participant> l = new ArrayList<>(roster.values());
    l.sort(Comparator.comparingInt(VoiceEvent.Participant::senderId));
    return List.copyOf(l);
  }

  private void emit(VoiceEvent e) {
    out.add(e);
  }

  private void fail(Exception e) {
    emit(new VoiceEvent.Error(e));
  }

  private void post(Map<String, Object> message) {
    try {
      io.execute(
          () -> {
            try {
              stream.send(message);
            } catch (RuntimeException e) {
              fail(e);
            }
          });
    } catch (RejectedExecutionException e) {
      // left
    }
  }

  private static Map<String, Object> frame(String type, Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  void start() {
    stream = client.connect(null, inbox::add);
    while (!(nextEvent() instanceof WsEvent.Hello)) {
      // waiting for the greeting
    }
    establish();
    tick.scheduleAtFixedRate(this::housekeeping, 25, 25, TimeUnit.MILLISECONDS);
    loop = daemon("wavvon-voice").newThread(this::run);
    loop.start();
  }

  void shutdown() {
    VoiceLink l;
    synchronized (gate) {
      if (stopped) return;
      stopped = true;
      inRoom = false;
      l = link;
      link = null;
    }
    if (l != null) l.close();
    tick.shutdownNow();
    io.shutdownNow();
    work.shutdownNow();
    if (stream != null) stream.close();
    if (loop != null) loop.interrupt();
  }

  private WsEvent nextEvent() {
    WsEvent ev;
    try {
      ev = inbox.poll(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VoiceException(VoiceException.Kind.NOT_IN_ROOM, "interrupted", e);
    }
    if (ev == null) {
      throw new VoiceException(VoiceException.Kind.TRANSPORT, "timed out waiting for the hub");
    }
    return ev;
  }

  private void run() {
    while (!stopped) {
      WsEvent ev;
      try {
        ev = inbox.poll(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        return;
      }
      if (ev == null) continue;
      try {
        // A second hello: the socket dropped and came back, and the hub already ran leave_voice.
        // The old session is dead; join again.
        if (ev instanceof WsEvent.Hello) {
          rejoin();
        } else {
          handle(ev);
        }
      } catch (RuntimeException e) {
        if (!stopped) fail(e);
      }
    }
  }

  private void rejoin() {
    VoiceLink l;
    synchronized (gate) {
      inRoom = false;
      l = link;
      link = null;
    }
    if (l != null) l.close();
    emit(new VoiceEvent.Rejoining());
    establish();
  }

  /** {@code voice_join} on an open socket: joined, audio connected, key offered. */
  private void establish() {
    String requested = channelId();
    post(frame("voice_join", "channel_id", requested));
    JsonNode joined;
    while (true) {
      WsEvent ev = nextEvent();
      if (ev instanceof WsEvent.Unknown u
          && "voice_joined".equals(u.rawType())
          && u.raw() instanceof JsonNode n) {
        joined = n;
        break;
      }
      if (ev instanceof WsEvent.CommandError e && "voice_join".equals(e.context())) {
        throw new VoiceException(VoiceException.Kind.REFUSED, e.message());
      }
    }

    VoiceLink l = VoiceLink.offer();
    // What arrives while the answer is awaited is kept, a key bundle above all: dropping it would
    // leave this room unable to open that speaker.
    List<WsEvent> deferred = new ArrayList<>();
    try {
      post(frame("voice_rtc_offer", "token", str(joined, "voice_token"), "sdp", l.offerSdp()));
      String answer;
      while (true) {
        WsEvent ev = nextEvent();
        if (ev instanceof WsEvent.Unknown u
            && "voice_rtc_answer".equals(u.rawType())
            && u.raw() instanceof JsonNode n) {
          answer = str(n, "sdp");
          break;
        }
        if (ev instanceof WsEvent.CommandError e && "voice_rtc_offer".equals(e.context())) {
          throw new VoiceException(VoiceException.Kind.REFUSED, e.message());
        }
        deferred.add(ev);
      }
      l.accept(answer, this::onDatagram, () -> onLinkClosed(l));
    } catch (RuntimeException e) {
      l.close();
      throw e;
    }

    Integer sid = u16(joined, "sender_id");
    if (sid == null) {
      l.close();
      throw new VoiceException(
          VoiceException.Kind.TRANSPORT, "voice_joined carries no valid sender_id");
    }
    List<VoiceEvent.Participant> participants = participants(joined.path("participants"));
    List<String> peers = new ArrayList<>();
    synchronized (gate) {
      String channel = str(joined, "channel_id");
      channelId = channel.isEmpty() ? requested : channel;
      senderId = sid;
      // The key id never goes back: a rejoin must not reuse a (sender, key_id) pair whose replay
      // watermark listeners still hold.
      own = own.next();
      // A hub without voice.talk omits the field; absence is not "muted".
      maySpeak = !joined.path("may_speak").isBoolean() || joined.path("may_speak").asBoolean();
      roster = new HashMap<>();
      for (VoiceEvent.Participant p : participants) roster.put(p.senderId(), p);
      remote.clear();
      marks.clear();
      pacingNext = null;
      speaking = false;
      link = l;
      inRoom = true;
      for (VoiceEvent.Participant p : participants) {
        if (!p.publicKey().equals(me)) peers.add(p.publicKey());
      }
    }
    offerTo(peers);
    for (WsEvent ev : deferred) handle(ev);
  }

  private void onLinkClosed(VoiceLink l) {
    boolean ours;
    synchronized (gate) {
      ours = link == l;
      if (ours) link = null;
      // After leave or during a rejoin the close is ours.
      ours &= inRoom;
    }
    if (ours) fail(new VoiceException(VoiceException.Kind.TRANSPORT, "voice data channel closed"));
    // This runs on a WebRTC thread, which must not dispose its own peer connection.
    try {
      work.execute(l::close);
    } catch (RejectedExecutionException e) {
      // shut down; close() already ran
    }
  }

  /**
   * A peer's DH key, looked up once per room: it is pinned, so a second answer would be the same or
   * a refusal.
   */
  private byte[] dhFor(String pubkey) {
    synchronized (gate) {
      byte[] cached = dhCache.get(pubkey);
      if (cached != null) return cached;
    }
    byte[] k = DhKeys.peer(client, pins, pubkey);
    synchronized (gate) {
      dhCache.put(pubkey, k);
    }
    return k;
  }

  private static boolean perPeer(Exception e) {
    return e instanceof RecipientHasNoKeyException
        || e instanceof DhKeyChangedException
        || e instanceof DhKeyUnsignedException;
  }

  /**
   * Wraps the current sender key to each peer and sends one offer. A peer without a usable DH key
   * is skipped and reported.
   */
  private void offerTo(List<String> to) {
    if (to.isEmpty()) return;
    try {
      work.execute(
          () -> {
            String channel;
            OwnKey key;
            synchronized (gate) {
              channel = channelId;
              key = own;
            }
            List<Map<String, Object>> bundles = new ArrayList<>();
            for (String pubkey : to) {
              try {
                byte[] nonce = new byte[12];
                RANDOM.nextBytes(nonce);
                byte[] ct =
                    VoiceCrypto.keyWrap(
                        client.identity(),
                        dhFor(pubkey),
                        channel,
                        key.key,
                        key.salt,
                        key.keyId,
                        nonce);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("recipient_pubkey", pubkey);
                b.put("ciphertext_hex", Bytes.toHex(ct));
                b.put("nonce_hex", Bytes.toHex(nonce));
                bundles.add(b);
              } catch (GeneralSecurityException e) {
                emit(new VoiceEvent.KeySkipped(pubkey, e.getMessage()));
              } catch (RuntimeException e) {
                if (perPeer(e)) {
                  emit(new VoiceEvent.KeySkipped(pubkey, e.getMessage()));
                } else {
                  if (!stopped) fail(e);
                  return;
                }
              }
            }
            if (!bundles.isEmpty()) {
              post(frame("voice_key_offer", "channel_id", channel, "bundles", bundles));
            }
          });
    } catch (RejectedExecutionException e) {
      // left
    }
  }

  private void unwrap(JsonNode raw) {
    String from = str(raw, "from_pubkey");
    byte[] ct;
    byte[] nonce;
    try {
      ct = Bytes.fromHex(str(raw, "ciphertext_hex"));
      nonce = Bytes.fromHex(str(raw, "nonce_hex"));
    } catch (IllegalArgumentException e) {
      return;
    }
    if (nonce.length != 12) return;
    try {
      work.execute(
          () -> {
            String channel = channelId();
            byte[] dh;
            try {
              dh = dhFor(from);
            } catch (RuntimeException e) {
              if (perPeer(e)) {
                emit(new VoiceEvent.KeySkipped(from, e.getMessage()));
              } else if (!stopped) {
                fail(e);
              }
              return;
            }
            VoiceCrypto.Unwrapped k;
            try {
              k = VoiceCrypto.keyUnwrap(client.identity(), dh, channel, ct, nonce);
            } catch (GeneralSecurityException e) {
              // A bundle that does not open is dropped: there is no key to use.
              return;
            }
            synchronized (gate) {
              // A new key for this pair restarts its counter.
              for (VoiceEvent.Participant p : roster.values()) {
                if (p.publicKey().equals(from)) marks.remove(p.senderId() + "/" + k.keyId());
              }
              remote.put(from + "/" + k.keyId(), k);
            }
          });
    } catch (RejectedExecutionException e) {
      // left
    }
  }

  private void handle(WsEvent ev) {
    if (ev instanceof WsEvent.CommandError error) {
      String context = error.context() == null ? "" : error.context();
      if (context.equals("voice_position_update")) {
        emit(new VoiceEvent.PositionRefused(error.message()));
      } else if (context.startsWith("voice")) {
        fail(new VoiceException(VoiceException.Kind.REFUSED, context + ": " + error.message()));
      }
      return;
    }
    if (!(ev instanceof WsEvent.Unknown unknown)
        || unknown.rawType() == null
        || !unknown.rawType().startsWith("voice_")
        || !(unknown.raw() instanceof JsonNode raw)) {
      return;
    }
    String type = unknown.rawType();
    synchronized (gate) {
      // Messages about another room are not this session's.
      JsonNode c = raw.get("channel_id");
      if (c != null && c.isTextual() && !c.asText().equals(channelId)) return;
      switch (type) {
        case "voice_participant_joined" -> {
          VoiceEvent.Participant p = participant(raw.path("participant"));
          if (p != null) {
            roster.put(p.senderId(), p);
            emit(new VoiceEvent.Roster(rosterLocked()));
          }
        }
        case "voice_participant_left" -> {
          String left = str(raw, "public_key");
          roster
              .values()
              .removeIf(
                  p -> {
                    if (!p.publicKey().equals(left)) return false;
                    marks.keySet().removeIf(m -> m.startsWith(p.senderId() + "/"));
                    return true;
                  });
          remote.keySet().removeIf(r -> r.startsWith(left + "/"));
          // Whoever left kept the key: a new one for the rest.
          own = own.next();
          emit(new VoiceEvent.Roster(rosterLocked()));
          List<String> rest = new ArrayList<>();
          for (VoiceEvent.Participant p : roster.values()) {
            if (!p.publicKey().equals(me)) rest.add(p.publicKey());
          }
          offerTo(rest);
        }
        case "voice_roster_update" -> {
          roster = new HashMap<>();
          for (VoiceEvent.Participant p : participants(raw.path("participants"))) {
            roster.put(p.senderId(), p);
          }
          emit(new VoiceEvent.Roster(rosterLocked()));
        }
        case "voice_participant_speaking" ->
            emit(
                new VoiceEvent.Speaking(
                    str(raw, "public_key"), raw.path("speaking").asBoolean(false)));
        case "voice_key_request" -> {
          String newcomer = str(raw, "new_pubkey");
          Integer nid = u16(raw, "new_sender_id");
          if (nid != null && !roster.containsKey(nid)) {
            roster.put(nid, new VoiceEvent.Participant(newcomer, nid, null));
          }
          if (!newcomer.equals(me)) offerTo(List.of(newcomer));
        }
        case "voice_key_received" -> {
          Integer fid = u16(raw, "from_sender_id");
          if (fid != null && !roster.containsKey(fid)) {
            roster.put(fid, new VoiceEvent.Participant(str(raw, "from_pubkey"), fid, null));
          }
          unwrap(raw);
        }
        case "voice_talk_granted" -> {
          if (str(raw, "public_key").equals(me)) {
            maySpeak = true;
            emit(new VoiceEvent.TalkGranted());
          }
        }
        case "voice_move" ->
            emit(
                new VoiceEvent.MoveRequested(
                    str(raw, "target_channel_id"),
                    str(raw, "target_channel_name"),
                    optStr(raw, "source_channel_id"),
                    optStr(raw, "event_id"),
                    raw.path("auto").asBoolean(false)));
        case "voice_zone_state" -> {
          zones = new ArrayList<>();
          for (JsonNode z : raw.path("zones")) {
            VoiceEvent.Zone zone = zone(z);
            if (zone != null) zones.add(zone);
          }
          emit(new VoiceEvent.Zones(List.copyOf(zones)));
        }
        case "voice_zone_created" -> {
          VoiceEvent.Zone created = zone(raw);
          if (created != null) {
            zones.removeIf(z -> z.zoneId().equals(created.zoneId()));
            zones.add(created);
            emit(new VoiceEvent.Zones(List.copyOf(zones)));
          }
        }
        case "voice_zone_destroyed" -> {
          String gone = str(raw, "zone_id");
          zones.removeIf(z -> z.zoneId().equals(gone));
          emit(new VoiceEvent.Zones(List.copyOf(zones)));
        }
        case "voice_position_updated" -> {
          String zoneId = str(raw, "zone_id");
          String who = str(raw, "pubkey");
          List<Double> pos = numbers(raw.path("position"));
          for (int i = 0; i < zones.size(); i++) {
            VoiceEvent.Zone z = zones.get(i);
            if (z.zoneId().equals(zoneId)) {
              Map<String, List<Double>> ps = new HashMap<>(z.positions());
              ps.put(who, pos);
              zones.set(
                  i,
                  new VoiceEvent.Zone(
                      z.zoneId(), z.name(), z.coordinateSystem(), z.attenuation(), Map.copyOf(ps)));
            }
          }
          emit(new VoiceEvent.Position(zoneId, who, pos));
        }
        case "voice_joined", "voice_rtc_answer" -> {
          // handled while joining
        }
        default -> emit(new VoiceEvent.Unknown(type, raw));
      }
    }
  }

  private void onDatagram(byte[] payload) {
    // sender_id[2] || packet_type[1] || key_id[4] || ctr[8] || ts[4] || AEAD
    if (payload.length < 3 + VoiceCrypto.HEADER + VoiceCrypto.TAG) return;
    ByteBuffer b = ByteBuffer.wrap(payload);
    int sid = Short.toUnsignedInt(b.getShort(0));
    boolean whisper = payload[2] == 1;
    long keyId = Integer.toUnsignedLong(b.getInt(3));
    long ctr = b.getLong(7);
    synchronized (gate) {
      VoiceEvent.Participant from = roster.get(sid);
      if (deafened || from == null) return;
      VoiceCrypto.Unwrapped k = remote.get(from.publicKey() + "/" + keyId);
      if (k == null) return;
      String mark = sid + "/" + keyId;
      Long seen = marks.get(mark);
      if (seen != null && Long.compareUnsigned(ctr, seen) <= 0) return;
      VoiceCrypto.Opened opened;
      try {
        opened = VoiceCrypto.packetOpen(k.senderKey(), k.nonceSalt(), payload, 3);
      } catch (GeneralSecurityException e) {
        return;
      }
      // The watermark moves only for a packet that opened: a forged one must not be able to lock
      // the real sender out.
      marks.put(mark, ctr);
      emit(
          new VoiceEvent.Audio(
              from.publicKey(), sid, keyId, opened.ts(), ctr, whisper, opened.opus()));
    }
  }

  /**
   * Every 25 ms: end a transmission that went quiet, and send the trailing position once the hub's
   * limit is safely behind.
   */
  private void housekeeping() {
    synchronized (gate) {
      if (!inRoom) return;
      double now = now();
      if (speaking && now - lastSend > SPEAKING_IDLE_MS) {
        speaking = false;
        post(frame("voice_speaking", "channel_id", channelId, "speaking", false));
      }
      if (position != null && now - lastPosition >= POSITION_EVERY_MS) {
        post(frame("voice_position_update", "zone_id", positionZone, "position", position));
        position = null;
        lastPosition = now;
      }
    }
  }

  // ---- JSON ----

  private static String str(JsonNode e, String name) {
    String s = optStr(e, name);
    return s == null ? "" : s;
  }

  private static String optStr(JsonNode e, String name) {
    JsonNode v = e.get(name);
    return v != null && v.isTextual() ? v.asText() : null;
  }

  private static Integer u16(JsonNode e, String name) {
    JsonNode v = e.get(name);
    if (v == null || !v.isIntegralNumber()) return null;
    long n = v.asLong();
    return n >= 0 && n <= 0xffff ? (int) n : null;
  }

  private static List<Double> numbers(JsonNode e) {
    List<Double> out = new ArrayList<>();
    for (JsonNode x : e) if (x.isNumber()) out.add(x.asDouble());
    return List.copyOf(out);
  }

  private static VoiceEvent.Participant participant(JsonNode e) {
    String key = optStr(e, "public_key");
    Integer id = u16(e, "sender_id");
    return key != null && id != null
        ? new VoiceEvent.Participant(key, id, optStr(e, "display_name"))
        : null;
  }

  private static List<VoiceEvent.Participant> participants(JsonNode a) {
    List<VoiceEvent.Participant> out = new ArrayList<>();
    for (JsonNode e : a) {
      VoiceEvent.Participant p = participant(e);
      if (p != null) out.add(p);
    }
    return out;
  }

  private static VoiceEvent.Zone zone(JsonNode e) {
    String id = optStr(e, "zone_id");
    if (id == null) return null;
    Map<String, List<Double>> positions = new HashMap<>();
    e.path("positions")
        .fields()
        .forEachRemaining(p -> positions.put(p.getKey(), numbers(p.getValue())));
    JsonNode attenuation = e.has("attenuation") ? e.get("attenuation") : NullNode.getInstance();
    return new VoiceEvent.Zone(
        id, str(e, "name"), str(e, "coordinate_system"), attenuation, Map.copyOf(positions));
  }
}
