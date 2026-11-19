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
    private static final long STATUS_POLL_MS = 500;
    private static final long MAX_RETRY_MS = 30_000;
    private static final long[] RETRY_DELAYS_MS = {2_500, 5_000, 10_000, 15_000, MAX_RETRY_MS};
    enum Status {
        IDLE(0), TUNING(R.string.tuning), COMPATIBLE(R.string.trying_compatible),
        PREPARING(R.string.preparing_playback), WAITING_VIDEO(R.string.waiting_video), BUFFERING(R.string.buffering),
        RETRY_WAIT(R.string.waiting_reconnect), RECONNECTING(R.string.reconnecting),
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
    private final Runnable changed;
    private Slot primary;
    private Slot secondary;

    private final class Slot {
        final MpvView surface;
        Slot(MpvView surface) { this.surface = surface; }

        final ExecutorService tuner = Executors.newSingleThreadExecutor();
        volatile long generation;
        TvSource api;
        Jellyfin.Stream stream;
        volatile MpvPlayer player;
        Channels.Channel channel;
        Status status = Status.IDLE;
        boolean fallback;
        boolean playWhenReady = true;
        long reconnectDelay;
        long statusSince;
        long retryAt;
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
        primary = new Slot(mainView);
        secondary = new Slot(insetView);
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
        return slot.active() && !slot.playWhenReady ? Status.PAUSED : slot.status;
    }

    String primaryDetail() { return detail(primary); }
    String secondaryDetail() { return detail(secondary); }
    private String detail(Slot slot) {
        Status status = status(slot);
        if (status == Status.IDLE) return "";
        long now = SystemClock.elapsedRealtime();
        if (status == Status.RETRY_WAIT)
            return context.getString(R.string.retry_countdown, Math.max(0, slot.retryAt - now) / 1000.0);
        if (status.live() || status.failed() || status == Status.PAUSED) return context.getString(status.label);
        long elapsed = Math.max(0, now - slot.statusSince) / 1000;
        if (status == Status.BUFFERING && slot.player != null) {
            double buffered = slot.player.buffered();
            if (Double.isFinite(buffered) && buffered >= 0)
                return context.getString(R.string.buffering_detail, buffered, elapsed);
        }
        return context.getString(R.string.loading_elapsed, context.getString(status.label), elapsed);
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

    void tune(TvSource api, Channels.Channel channel, boolean inset) {
        tune(api, channel, inset, false);
    }

    void tune(TvSource api, Channels.Channel channel, boolean inset, boolean paused) {
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

    void tuneCompatible(TvSource api, Channels.Channel channel) {
        if (secondary.channel != null && secondary.channel.id().equals(channel.id())) stop(secondary);
        resumeOnFocusGain = false;
        primary.playWhenReady = true;
        request(primary, api, channel, true);
    }

    private void request(Slot slot, TvSource api, Channels.Channel channel, boolean fallback) {
        request(slot, api, channel, fallback, false);
    }

    private void request(Slot slot, TvSource api, Channels.Channel channel, boolean fallback, boolean recovering) {
        long generation = ++slot.generation;
        slot.statusSince = SystemClock.elapsedRealtime();
        if (!recovering) slot.reconnectDelay = 0;
        detach(slot);
        slot.api = api;
        slot.channel = channel;
        slot.fallback = fallback;
        slot.status = recovering ? Status.RECONNECTING : fallback ? Status.COMPATIBLE : Status.TUNING;
        changed.run();
        boolean small = slot == secondary;
        slot.tuner.execute(() -> {
            if (slot.generation != generation) return;
            Jellyfin.Stream opened;
            try {
                opened = api.open(channel, fallback, small, recovering);
            } catch (Exception error) {
                ui.post(() -> {
                    if (slot.generation != generation) return;
                    if (!fallback && error instanceof Jellyfin.NoCompatibleStream) {
                        request(slot, api, channel, true, recovering);
                    } else if (recovering && error instanceof java.io.IOException
                            && !(error instanceof Jellyfin.NoCompatibleStream)
                            && (!(error instanceof Jellyfin.HttpFailure http) || http.status == 408
                                    || http.status == 429 || http.status >= 500)) {
                        reconnectLater(slot, MAX_RETRY_MS);
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
                    streamFailed(slot);
                }
            });
        });
    }

    private void start(Slot slot, Jellyfin.Stream stream, long generation) {
        slot.stream = stream;
        slot.status = Status.PREPARING;
        slot.statusSince = SystemClock.elapsedRealtime();
        changed.run();
        MpvView surface = slot.surface;
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
                    ui.post(() -> { if (slot.generation == generation) streamFailed(slot); });
                    return;
                }
                boolean stale;
                synchronized (slot) {
                    stale = slot.generation != generation;
                    if (!stale) slot.player = opened;
                }
                if (stale) { opened.close(); return; }
                ui.post(() -> monitor(slot, opened, generation, stream.compatible()));
            });
        });
    }

    private void monitor(Slot slot, MpvPlayer opened, long generation, boolean compatible) {
        MpvView surface = slot.surface;
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
                    if ((state & 8) != 0) streamFailed(slot);
                    else reconnectLater(slot, 0);
                    return;
                }
                if (progress.stalled(now, !slot.playWhenReady)) {
                    if (progress.started()) request(slot, slot.api, slot.channel, slot.fallback, true);
                    else streamFailed(slot);
                    return;
                }
                Status next = (state & 2) != 0 ? Status.BUFFERING
                        : (state & 4) == 0 || surface.frames == firstFrame ? Status.WAITING_VIDEO
                        : compatible ? Status.LIVE_COMPATIBLE : Status.LIVE;
                if (next.live()) slot.reconnectDelay = 0;
                if (slot.status != next) { slot.status = next; slot.statusSince = now; changed.run(); }
                ui.postDelayed(this, STATUS_POLL_MS);
            }
        };
        slot.progressCheck.run();
    }

    static long nextRetryDelay(long previous) {
        for (long delay : RETRY_DELAYS_MS) if (delay > previous) return delay;
        return MAX_RETRY_MS;
    }

    private void reconnectLater(Slot slot, long delay) {
        if (slot.status == Status.RETRY_WAIT) return;
        slot.reconnectDelay = Math.max(delay, nextRetryDelay(slot.reconnectDelay));
        slot.status = Status.RETRY_WAIT;
        updateRecovery(slot);
        changed.run();
    }

    private void updateRecovery(Slot slot) {
        ui.removeCallbacks(slot.reconnect);
        // Buffering alone is not a failure. Reopen after an error or end-of-stream.
        if (slot.status == Status.RETRY_WAIT && slot.playWhenReady) {
            slot.retryAt = SystemClock.elapsedRealtime() + slot.reconnectDelay;
            ui.postDelayed(slot.reconnect, slot.reconnectDelay);
        }
    }

    private void streamFailed(Slot slot) {
        ++slot.generation;
        detach(slot);
        slot.status = Status.STREAM_FAILED;
        changed.run();
    }

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
        swapped.run();
        if (primary.active() && primary.playWhenReady) {
            if (!requestAudioFocus()) setPlaying(false);
        } else {
            AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
        }
        configure(primary);
        changed.run();
    }

    private void detach(Slot slot) {
        ui.removeCallbacks(slot.reconnect);
        if (slot.progressCheck != null) ui.removeCallbacks(slot.progressCheck);
        slot.progressCheck = null;
        slot.surface.whenReady(null);
        MpvPlayer oldPlayer;
        synchronized (slot) { oldPlayer = slot.player; slot.player = null; }
        if (oldPlayer != null) {
            oldPlayer.volume(0);
            // Teardown must finish before this slot opens another tuner/decoder.
            slot.tuner.execute(oldPlayer::close);
        }
        Jellyfin.Stream old = slot.stream;
        slot.stream = null;
        TvSource source = slot.api;
        if (old != null) slot.tuner.execute(() -> source.close(old));
    }

    private void stop(Slot slot) {
        ++slot.generation;
        detach(slot);
        slot.channel = null;
        slot.status = Status.IDLE;
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
        if (primary.channel != null) changed.run();
        else if (secondary.channel != null) swap();
        else {
            resumeOnFocusGain = false;
            AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
            changed.run();
        }
    }

    private void reconcile(Slot slot, List<Channels.Channel> channels) {
        if (slot.channel == null) return;
        int position = Channels.indexOf(channels, slot.channel.id());
        if (position < 0 || slot.api instanceof PlaylistSource && !slot.channel.name().equals(channels.get(position).name())) stop(slot);
        else slot.channel = channels.get(position);
    }

    void dump(java.io.PrintWriter writer) {
        for (Slot slot : List.of(primary, secondary)) {
            writer.println("TippyMPV " + (slot == primary ? "main" : "inset") + " status=" + status(slot)
                    + " generation=" + slot.generation + " frames=" + slot.surface.frames
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
