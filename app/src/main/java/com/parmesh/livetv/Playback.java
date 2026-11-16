package com.parmesh.livetv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioManager;
import android.net.Uri;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
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
    private final LibVLC engine;
    private final AudioManager audio;
    private final AudioFocusRequestCompat audioFocus;
    private boolean resumeOnFocusGain;
    private final VLCVideoLayout mainView;
    private final VLCVideoLayout insetView;
    private final Runnable changed;
    private Slot primary = new Slot();
    private Slot secondary = new Slot();

    private final class Slot {
        final ExecutorService tuner = Executors.newSingleThreadExecutor();
        volatile long generation;
        Jellyfin api;
        Jellyfin.Stream stream;
        MediaPlayer player;
        Media media;
        Channels.Channel channel;
        Status status = Status.IDLE;
        boolean fallback;
        boolean playWhenReady = true;
        boolean reconnectPending;
        Runnable progressCheck;
        final Runnable reconnect = () -> request(this, api, channel, fallback, true);

        boolean active() { return channel != null && !status.failed(); }
    }

    Playback(Context context, VLCVideoLayout mainView, VLCVideoLayout insetView, Runnable changed) {
        engine = new LibVLC(context.getApplicationContext(), new java.util.ArrayList<>(List.of("--no-video-title-show")));
        engine.setUserAgent("TippyTV", "TippyTV/" + BuildConfig.VERSION_NAME);
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
            if (primary.player != null && primary.player.isPlaying()) primary.player.pause();
            updateRecovery(primary);
            if (change == AudioManager.AUDIOFOCUS_LOSS) AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
            changed.run();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && primary.player != null) {
            primary.player.setVolume(20);
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
            if (!playing && primary.player != null && primary.player.isPlaying()) primary.player.pause();
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
        Media media = new Media(engine, Uri.parse(stream.url().toString()));
        slot.media = media;
        media.addOption(":network-caching=2000");
        media.addOption(":adaptive-livedelay=6000");
        media.addOption(":adaptive-maxbuffer=6000");
        media.addOption(":http-reconnect");
        if (stream.userAgent() != null) media.addOption(":http-user-agent=" + stream.userAgent());
        media.setHWDecoderEnabled(true, false);
        if (slot == primary && slot.playWhenReady && !requestAudioFocus()) slot.playWhenReady = false;
        if (!slot.playWhenReady) media.addOption(":start-paused");
        MediaPlayer player = new MediaPlayer(engine);
        slot.player = player;
        player.attachViews(view(slot), null, false, true);
        player.setAudioOutputDevice("stereo");
        configure(slot);
        player.setEventListener(event -> {
            if (slot.generation != generation) return;
            Status previous = slot.status;
            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    slot.status = Status.BUFFERING;
                    break;
                case MediaPlayer.Event.Buffering:
                    slot.status = event.getBuffering() < 100 ? Status.BUFFERING
                            : stream.converted() ? Status.LIVE_COMPATIBLE : Status.LIVE;
                    break;
                case MediaPlayer.Event.Playing:
                    if (!slot.playWhenReady) { player.pause(); return; }
                    slot.reconnectPending = false;
                    updateRecovery(slot);
                    slot.status = stream.converted() ? Status.LIVE_COMPATIBLE : Status.LIVE;
                    break;
                case MediaPlayer.Event.EndReached:
                case MediaPlayer.Event.EncounteredError:
                    reconnectLater(slot);
                    return;
                case MediaPlayer.Event.Paused:
                    break;
                default:
                    return;
            }
            if (slot.status != previous || event.type == MediaPlayer.Event.Paused) changed.run();
        });
        FrameProgress progress = new FrameProgress();
        slot.progressCheck = new Runnable() {
            @Override public void run() {
                if (slot.generation != generation) return;
                long now = SystemClock.elapsedRealtime();
                var stats = media.getStats();
                if (stats != null && stats.displayedPictures > 0) progress.frame(stats.displayedPictures, now);
                if (progress.stalled(now, !slot.playWhenReady)) {
                    request(slot, slot.api, slot.channel, slot.fallback, true);
                    return;
                }
                ui.postDelayed(this, 5_000);
            }
        };
        ui.postDelayed(slot.progressCheck, 5_000);
        player.setMedia(media);
        player.play();
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

    private VLCVideoLayout view(Slot slot) { return slot == primary ? mainView : insetView; }

    private void configure(Slot slot) {
        if (slot.player != null) slot.player.setVolume(slot == secondary ? 0 : 100);
    }

    void swap() {
        if (secondary.channel == null) return;
        if (primary.player != null) primary.player.detachViews();
        if (secondary.player != null) secondary.player.detachViews();
        resumeOnFocusGain = false;
        Slot old = primary;
        primary = secondary;
        secondary = old;
        configure(secondary);
        configure(primary);
        if (primary.player != null) primary.player.attachViews(mainView, null, false, true);
        if (secondary.player != null) secondary.player.attachViews(insetView, null, false, true);
        if (primary.active() && primary.playWhenReady) {
            if (!requestAudioFocus()) setPlaying(false);
        } else {
            AudioManagerCompat.abandonAudioFocusRequest(audio, audioFocus);
        }
        changed.run();
    }

    private Jellyfin.Stream detach(Slot slot) {
        ui.removeCallbacks(slot.reconnect);
        slot.reconnectPending = false;
        if (slot.progressCheck != null) ui.removeCallbacks(slot.progressCheck);
        slot.progressCheck = null;
        MediaPlayer oldPlayer = slot.player;
        Media oldMedia = slot.media;
        slot.player = null;
        slot.media = null;
        if (oldPlayer != null) {
            oldPlayer.setEventListener(null);
            oldPlayer.setVolume(0);
            oldPlayer.detachViews();
        }
        // Native stop can wait for network/decoder teardown. Keep it off the UI
        // thread, and finish it before this slot opens its next tuner.
        if (oldPlayer != null || oldMedia != null) slot.tuner.execute(() -> {
            try {
                if (oldPlayer != null) {
                    try { oldPlayer.stop(); } finally { oldPlayer.release(); }
                }
            } finally {
                if (oldMedia != null) oldMedia.release();
            }
        });
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

    void destroy(Runnable afterClose) {
        stopAll();
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable closed = () -> {
            if (remaining.decrementAndGet() == 0) {
                engine.release();
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
