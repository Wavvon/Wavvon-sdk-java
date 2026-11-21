package org.wavvon.sdk;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The voice transport (voice-transport-v3.md) and the only WebRTC code in the SDK: one data
 * channel, negotiated on stream 0, unordered, no retransmission, over webrtc-java (libwebrtc). The
 * hub is ICE-lite and answers with its host candidates, so no STUN or TURN server is configured.
 */
final class VoiceLink implements AutoCloseable {
  /**
   * A packet that still fits one SCTP chunk in a 1280-byte path MTU; a split one is lost whole if
   * any piece is.
   */
  static final int MAX_PACKET = 1200;

  private static final long STEP_SECONDS = 10;

  private final PeerConnectionFactory factory;
  private final RTCPeerConnection pc;
  private final RTCDataChannel dc;
  private final CompletableFuture<Void> gathered = new CompletableFuture<>();
  private final CompletableFuture<Void> opened = new CompletableFuture<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Consumer<byte[]> onMessage = b -> {};
  private volatile Runnable onClose = () -> {};
  private String offerSdp;
  private boolean disposed;

  private VoiceLink() {
    // No sound card is touched: audio never goes through libwebrtc's media path.
    factory =
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(new HeadlessAudioDeviceModule())
            .build();
    pc =
        factory.createPeerConnection(
            new RTCConfiguration(),
            new PeerConnectionObserver() {
              @Override
              public void onIceCandidate(RTCIceCandidate candidate) {}

              @Override
              public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null);
              }

              @Override
              public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED
                    || state == RTCPeerConnectionState.CLOSED) {
                  opened.completeExceptionally(
                      new IllegalStateException("peer connection " + state));
                  ended();
                }
              }
            });
    RTCDataChannelInit init = new RTCDataChannelInit();
    init.negotiated = true;
    init.id = 0;
    init.ordered = false;
    init.maxRetransmits = 0;
    dc = pc.createDataChannel("voice", init);
    dc.registerObserver(
        new RTCDataChannelObserver() {
          @Override
          public void onBufferedAmountChange(long previous) {}

          @Override
          public void onStateChange() {
            RTCDataChannelState s = dc.getState();
            if (s == RTCDataChannelState.OPEN) {
              opened.complete(null);
            } else if (s == RTCDataChannelState.CLOSED) {
              opened.completeExceptionally(new IllegalStateException("data channel closed"));
              ended();
            }
          }

          @Override
          public void onMessage(RTCDataChannelBuffer buffer) {
            if (!buffer.binary) return;
            // The buffer is only valid during the callback.
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            onMessage.accept(data);
          }
        });
  }

  /** Builds the peer connection and its complete offer: the hub takes no trickled candidates. */
  static VoiceLink offer() {
    VoiceLink link;
    try {
      link = new VoiceLink();
    } catch (RuntimeException | UnsatisfiedLinkError e) {
      throw new VoiceException(
          VoiceException.Kind.TRANSPORT, "could not start WebRTC: " + e.getMessage(), e);
    }
    try {
      CompletableFuture<RTCSessionDescription> desc = new CompletableFuture<>();
      link.pc.createOffer(
          new RTCOfferOptions(),
          new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription d) {
              desc.complete(d);
            }

            @Override
            public void onFailure(String error) {
              desc.completeExceptionally(new IllegalStateException(error));
            }
          });
      link.setLocal(desc.get(STEP_SECONDS, TimeUnit.SECONDS));
      if (link.pc.getIceGatheringState() == RTCIceGatheringState.COMPLETE) {
        link.gathered.complete(null);
      }
      link.gathered.get(STEP_SECONDS, TimeUnit.SECONDS);
      link.offerSdp = link.pc.getLocalDescription().sdp;
      return link;
    } catch (Exception e) {
      link.close();
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new VoiceException(
          VoiceException.Kind.TRANSPORT, "could not build the WebRTC offer: " + e, e);
    }
  }

  String offerSdp() {
    return offerSdp;
  }

  private void setLocal(RTCSessionDescription d) throws Exception {
    CompletableFuture<Void> done = new CompletableFuture<>();
    pc.setLocalDescription(d, observer(done));
    done.get(STEP_SECONDS, TimeUnit.SECONDS);
  }

  private static SetSessionDescriptionObserver observer(CompletableFuture<Void> done) {
    return new SetSessionDescriptionObserver() {
      @Override
      public void onSuccess() {
        done.complete(null);
      }

      @Override
      public void onFailure(String error) {
        done.completeExceptionally(new IllegalStateException(error));
      }
    };
  }

  /**
   * Applies the hub's answer and waits for the channel to open. {@code onClose} fires once, never
   * after {@link #close()}, and on a WebRTC thread: it must not close the link itself.
   */
  void accept(String answerSdp, Consumer<byte[]> onMessage, Runnable onClose) {
    this.onMessage = onMessage;
    this.onClose = onClose;
    try {
      CompletableFuture<Void> done = new CompletableFuture<>();
      pc.setRemoteDescription(
          new RTCSessionDescription(RTCSdpType.ANSWER, answerSdp), observer(done));
      done.get(STEP_SECONDS, TimeUnit.SECONDS);
      if (dc.getState() == RTCDataChannelState.OPEN) opened.complete(null);
      opened.get(STEP_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      close();
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new VoiceException(
          VoiceException.Kind.TRANSPORT, "voice data channel did not open: " + e, e);
    }
  }

  void send(byte[] packet) {
    if (closed.get() || dc.getState() != RTCDataChannelState.OPEN) return;
    try {
      dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap(packet), true));
    } catch (Exception e) {
      // An unreliable channel: a packet that cannot go out now is a lost packet.
    }
  }

  private void ended() {
    if (closed.compareAndSet(false, true)) onClose.run();
  }

  @Override
  public synchronized void close() {
    closed.set(true);
    if (disposed) return;
    disposed = true;
    try {
      dc.unregisterObserver();
      dc.close();
      dc.dispose();
      pc.close();
      factory.dispose();
    } catch (RuntimeException e) {
      // already disposed
    }
  }
}
