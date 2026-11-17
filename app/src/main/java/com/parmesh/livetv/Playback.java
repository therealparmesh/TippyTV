package com.parmesh.livetv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioManager;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class Playback {
    enum Status {
        IDLE(0), TUNING(R.string.tuning), COMPATIBLE(R.string.trying_compatible), BUFFERING(R.string.buffering),
        LIVE(R.string.live), LIVE_COMPATIBLE(R.string.live_compatible), PAUSED(R.string.paused),
        TUNE_FAILED(R.string.tune_failed), STREAM_FAILED(R.string.stream_failed);

        final int label;
        Status(int label) { this.label = label; }
        boolean live() { return this == LIVE || this == LIVE_COMPATIBLE; }
        boolean failed() { return this == TUNE_FAILED || this == STREAM_FAILED; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Runnable swapped;
    private final AudioManager audio;
    private final AudioFocusRequestCompat audioFocus;
    private boolean resumeOnFocusGain;
    private MpvView mainView;
    private MpvView insetView;
    private final Runnable changed;
    private Slot primary = new Slot();
    private Slot secondary = new Slot();

    private final class Slot {
        final ExecutorService tuner = Executors.newSingleThreadExecutor();
        volatile long generation;
        Jellyfin api;
        Jellyfin.Stream stream;
        volatile MpvPlayer player;
        Channels.Channel channel;
        Status status = Status.IDLE;
        boolean fallback;
        boolean playWhenReady = true;
        boolean reconnectPending;
        Runnable progressCheck;
        final Runnable reconnect = () -> request(this, api, channel, fallback, true);

        boolean active() { return channel != null && !status.failed(); }
    }

    Playback(Context context, MpvView mainView, MpvView insetView, Runnable changed, Runnable swapped) {
        this.context = context.getApplicationContext();
        this.swapped = swapped;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioFocus = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(this::audioFocusChanged).build();
        this.mainView = mainView;
        this.insetView = insetView;
        this.changed = changed;
    }

    private void audioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocusGain) setPlaying(true);
            configure(primary);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && primary.playWhenReady;
            primary.playWhenReady = false;
            if (primary.player != null) primary.player.pause();
            updateRecovery(primary);
            if (change == AudioManager.AUDIOFOCUS_LOSS) AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
            changed.run();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && primary.player != null) {
            primary.player.volume(20);
        }
    }

    private boolean requestAudioFocus() {
        return AudioManagerCompat.requestAudioFocus(audio, audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    Channels.Channel primary() { return primary.channel; }
    Channels.Channel secondary() { return secondary.channel; }
    Status primaryStatus() { return status(primary); }
    Status secondaryStatus() { return status(secondary); }
    private Status status(Slot slot) {
        boolean paused = !slot.playWhenReady;
        return slot.channel != null && !slot.status.failed() && paused ? Status.PAUSED : slot.status;
    }

    boolean canPause() { return primary.active(); }

    void setPlaying(boolean playing) {
        if (!canPause()) return;
        resumeOnFocusGain = false;
        if (playing && !requestAudioFocus()) return;
        boolean wasPlaying = primary.playWhenReady;
        primary.playWhenReady = playing;
        if (playing && !wasPlaying && primary.player != null) {
            // Reopen at the live edge rather than replaying data buffered before pause.
            request(primary, primary.api, primary.channel, primary.fallback);
        } else {
            if (!playing && primary.player != null) primary.player.pause();
            updateRecovery(primary);
            changed.run();
        }
        if (!playing) AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
    }
    boolean hasVideo() {
        return (primary.active() && primary.playWhenReady) || (secondary.active() && secondary.playWhenReady);
    }

    void tune(Jellyfin api, Channels.Channel channel, boolean inset) {
        tune(api, channel, inset, false);
    }

    void tune(Jellyfin api, Channels.Channel channel, boolean inset, boolean paused) {
        Slot target = inset ? secondary : primary;
        Slot other = inset ? primary : secondary;
        if (other.channel != null && other.channel.id().equals(channel.id())) {
            if (other.active()) {
                if (!inset) swap();
                return;
            }
            if (inset) {
                tune(api, channel, false);
                return;
            }
            stop(other);
        }
        if (target.active() && target.channel.id().equals(channel.id())) return;
        if (!inset) resumeOnFocusGain = false;
        target.playWhenReady = !paused;
        request(target, api, channel, false);
    }

    void tuneCompatible(Jellyfin api, Channels.Channel channel) {
        if (secondary.channel != null && secondary.channel.id().equals(channel.id())) stop(secondary);
        resumeOnFocusGain = false;
        primary.playWhenReady = true;
        request(primary, api, channel, true);
    }

    private void request(Slot slot, Jellyfin api, Channels.Channel channel, boolean fallback) {
        request(slot, api, channel, fallback, false);
    }

    private void request(Slot slot, Jellyfin api, Channels.Channel channel, boolean fallback, boolean recovering) {
        long generation = ++slot.generation;
        Jellyfin.Stream old = detach(slot);
        Jellyfin previousApi = slot.api;
        slot.api = api;
        slot.channel = channel;
        slot.fallback = fallback;
        slot.status = fallback ? Status.COMPATIBLE : Status.TUNING;
        changed.run();
        boolean small = slot == secondary;
        slot.tuner.execute(() -> {
            if (old != null) previousApi.close(old);
            if (slot.generation != generation) return;
            Jellyfin.Stream opened;
            try {
                opened = api.open(channel.id(), fallback, small);
            } catch (Exception error) {
                ui.post(() -> {
                    if (slot.generation != generation) return;
                    if (!fallback && error instanceof Jellyfin.NoCompatibleStream) {
                        request(slot, api, channel, true, recovering);
                    } else if (recovering && error instanceof java.io.IOException
                            && !(error instanceof Jellyfin.NoCompatibleStream)
                            && (!(error instanceof Jellyfin.HttpFailure http) || http.status == 408
                                    || http.status == 429 || http.status >= 500)) {
                        reconnectLater(slot);
                    } else {
                        slot.status = Status.TUNE_FAILED;
                        changed.run();
                    }
                });
                return;
            }
            if (slot.generation != generation) {
                api.close(opened);
                return;
            }
            ui.post(() -> {
                if (slot.generation != generation) {
                    slot.tuner.execute(() -> api.close(opened));
                    return;
                }
                try {
                    start(slot, opened, generation);
                } catch (RuntimeException error) {
                    finishWithError(slot, Status.STREAM_FAILED);
                }
            });
        });
    }

    private void start(Slot slot, Jellyfin.Stream stream, long generation) {
        slot.stream = stream;
        MpvView surface = view(slot);
        surface.whenReady(() -> {
            if (slot.generation != generation) return;
            MpvView.Target target = surface.acquire();
            if (target == null) return;
            slot.tuner.execute(() -> {
                MpvPlayer opened;
                try {
                    opened = new MpvPlayer(context, target, stream);
                } catch (java.io.IOException | RuntimeException | LinkageError error) {
                    target.release();
                    android.util.Log.e("TippyMpv", "Player initialization failed", error);
                    ui.post(() -> { if (slot.generation == generation) finishWithError(slot, Status.STREAM_FAILED); });
                    return;
                }
                boolean stale;
                synchronized (slot) {
                    stale = slot.generation != generation;
                    if (!stale) slot.player = opened;
                }
                if (stale) { opened.close(); return; }
                ui.post(() -> {
                    if (slot.generation != generation) return;
                    if (slot == primary && slot.playWhenReady && !requestAudioFocus()) slot.playWhenReady = false;
                    long firstFrame = surface.frames;
                    configure(slot);
                    opened.setPaused(!slot.playWhenReady);
                    FrameProgress progress = new FrameProgress(SystemClock.elapsedRealtime());
                    slot.progressCheck = new Runnable() {
                        @Override public void run() {
                            if (slot.generation != generation) return;
                            int state = opened.state();
                            surface.aspect(opened.aspect());
                            long now = SystemClock.elapsedRealtime();
                            if ((state & 4) != 0 && surface.frames > firstFrame) progress.frame(surface.frames, now);
                            if ((state & 1) != 0) {
                                if ((state & 8) != 0) finishWithError(slot, Status.STREAM_FAILED);
                                else reconnectLater(slot);
                                return;
                            }
                            if (progress.stalled(now, !slot.playWhenReady)) {
                                if (progress.started()) request(slot, slot.api, slot.channel, slot.fallback, true);
                                else finishWithError(slot, Status.STREAM_FAILED);
                                return;
                            }
                            Status next = (state & 4) == 0 || (state & 2) != 0 || surface.frames == firstFrame ? Status.BUFFERING
                                    : stream.compatible() ? Status.LIVE_COMPATIBLE : Status.LIVE;
                            if (slot.status != next) { slot.status = next; changed.run(); }
                            ui.postDelayed(this, 500);
                        }
                    };
                    slot.progressCheck.run();
                });
            });
        });
    }

    private void reconnectLater(Slot slot) {
        if (slot.reconnectPending) return;
        slot.reconnectPending = true;
        slot.status = Status.BUFFERING;
        updateRecovery(slot);
        changed.run();
    }

    private void updateRecovery(Slot slot) {
        ui.removeCallbacks(slot.reconnect);
        // Buffering alone is not a failure. Reopen after an error or end-of-stream.
        if (slot.reconnectPending && slot.playWhenReady) {
            ui.postDelayed(slot.reconnect, 30_000);
        }
    }

    private void finishWithError(Slot slot, Status status) {
        ++slot.generation;
        Jellyfin.Stream old = detach(slot);
        Jellyfin api = slot.api;
        if (old != null) slot.tuner.execute(() -> api.close(old));
        slot.status = status;
        changed.run();
    }

    private MpvView view(Slot slot) { return slot == primary ? mainView : insetView; }

    private void configure(Slot slot) {
        if (slot.player != null) slot.player.volume(slot == secondary ? 0 : 100);
    }

    void swap() {
        if (secondary.channel == null) return;
        resumeOnFocusGain = false;
        Slot old = primary;
        primary = secondary;
        secondary = old;
        configure(secondary);
        MpvView oldView = mainView; mainView = insetView; insetView = oldView;
        swapped.run();
        if (primary.active() && primary.playWhenReady) {
            if (!requestAudioFocus()) setPlaying(false);
        } else {
            AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
        }
        configure(primary);
        changed.run();
    }

    private Jellyfin.Stream detach(Slot slot) {
        ui.removeCallbacks(slot.reconnect);
        slot.reconnectPending = false;
        if (slot.progressCheck != null) ui.removeCallbacks(slot.progressCheck);
        slot.progressCheck = null;
        MpvView surface = view(slot);
        surface.whenReady(null);
        MpvPlayer oldPlayer;
        synchronized (slot) { oldPlayer = slot.player; slot.player = null; }
        if (oldPlayer != null) {
            oldPlayer.volume(0);
            // Teardown must finish before this slot opens another tuner/decoder.
            slot.tuner.execute(oldPlayer::close);
        }
        Jellyfin.Stream old = slot.stream;
        slot.stream = null;
        return old;
    }

    private void stop(Slot slot) {
        ++slot.generation;
        Jellyfin.Stream stream = detach(slot);
        Jellyfin api = slot.api;
        slot.channel = null;
        slot.status = Status.IDLE;
        if (stream != null) slot.tuner.execute(() -> api.close(stream));
    }

    void stopInset() { stop(secondary); changed.run(); }
    void stopAll() {
        stop(primary); stop(secondary);
        resumeOnFocusGain = false;
        AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
        changed.run();
    }

    void reconcileChannels(List<Channels.Channel> channels) {
        if (primary.channel == null && secondary.channel == null) return;
        reconcile(primary, channels);
        reconcile(secondary, channels);
        if (primary.channel == null && secondary.channel != null) swap();
        else changed.run();
    }

    private void reconcile(Slot slot, List<Channels.Channel> channels) {
        if (slot.channel == null) return;
        int position = Channels.indexOf(channels, slot.channel.id());
        if (position < 0) stop(slot);
        else slot.channel = channels.get(position);
    }

    void dump(java.io.PrintWriter writer) {
        for (Slot slot : List.of(primary, secondary)) {
            writer.println("TippyMPV " + (slot == primary ? "main" : "inset") + " status=" + status(slot)
                    + " generation=" + slot.generation + " frames=" + view(slot).frames
                    + (slot.player == null ? "" : " " + slot.player.diagnostics()));
        }
    }

    void destroy(Runnable afterClose) {
        stopAll();
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable closed = () -> {
            if (remaining.decrementAndGet() == 0) {
                if (afterClose != null) afterClose.run();
            }
        };
        closeTuner(primary, closed);
        closeTuner(secondary, closed);
    }

    private void closeTuner(Slot slot, Runnable closed) {
        // A finished open can already have posted its stream handoff to the UI.
        // Give that handoff time to queue its release before shutting down the tuner.
        slot.tuner.execute(() -> ui.post(() -> {
            slot.tuner.execute(closed);
            slot.tuner.shutdown();
        }));
    }
}
