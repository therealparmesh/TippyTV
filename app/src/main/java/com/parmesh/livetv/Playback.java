package com.parmesh.livetv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@OptIn(markerClass = UnstableApi.class)
final class Playback {
    enum Status {
        IDLE(0), TUNING(R.string.tuning), COMPATIBLE(R.string.trying_compatible), BUFFERING(R.string.buffering),
        LIVE(R.string.live), LIVE_COMPATIBLE(R.string.live_compatible), PAUSED(R.string.paused),
        TUNE_FAILED(R.string.tune_failed), STREAM_FAILED(R.string.stream_failed), INSET_FAILED(R.string.inset_failed);

        final int label;
        Status(int label) { this.label = label; }
        boolean live() { return this == LIVE || this == LIVE_COMPATIBLE; }
        boolean failed() { return this == TUNE_FAILED || this == STREAM_FAILED || this == INSET_FAILED; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context context;
    private final PlayerView mainView;
    private final PlayerView insetView;
    private final Runnable changed;
    private Slot primary = new Slot();
    private Slot secondary = new Slot();

    private final class Slot {
        final ExecutorService tuner = Executors.newSingleThreadExecutor();
        volatile long generation;
        Jellyfin api;
        Jellyfin.Stream stream;
        ExoPlayer player;
        Channels.Channel channel;
        Status status = Status.IDLE;
        boolean fallback;
        boolean playWhenReady = true;
        Runnable bufferTimeout;

        boolean active() { return channel != null && !status.failed(); }
    }

    Playback(Context context, PlayerView mainView, PlayerView insetView, Runnable changed) {
        this.context = context;
        this.mainView = mainView;
        this.insetView = insetView;
        this.changed = changed;
    }

    Channels.Channel primary() { return primary.channel; }
    Channels.Channel secondary() { return secondary.channel; }
    Status primaryStatus() { return status(primary); }
    Status secondaryStatus() { return status(secondary); }
    private Status status(Slot slot) {
        boolean paused = slot.player == null ? !slot.playWhenReady : !slot.player.getPlayWhenReady();
        return slot.channel != null && !slot.status.failed() && paused ? Status.PAUSED : slot.status;
    }

    boolean canPause() { return primary.player != null && (primary.status.live() || primary.status == Status.BUFFERING); }

    void setPlaying(boolean playing) {
        if (!canPause()) return;
        if (playing && !primary.player.getPlayWhenReady()) primary.player.seekToDefaultPosition();
        primary.playWhenReady = playing;
        primary.player.setPlayWhenReady(playing);
    }
    boolean hasVideo() {
        return (primary.player != null && primary.player.isPlaying())
                || (secondary.player != null && secondary.player.isPlaying());
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
        target.playWhenReady = !paused;
        request(target, api, channel, false);
    }

    void tuneCompatible(Jellyfin api, Channels.Channel channel) {
        if (secondary.channel != null && secondary.channel.id().equals(channel.id())) stop(secondary);
        primary.playWhenReady = true;
        request(primary, api, channel, true);
    }

    private void request(Slot slot, Jellyfin api, Channels.Channel channel, boolean fallback) {
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
                    if (!fallback && !(error instanceof Jellyfin.HttpFailure http && (http.status == 401 || http.status == 403))) {
                        request(slot, api, channel, true);
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
                    failed(slot, generation);
                }
            });
        });
    }

    private void start(Slot slot, Jellyfin.Stream stream, long generation) {
        slot.stream = stream;
        var dataSource = new OkHttpDataSource.Factory(slot.api.streamClient());
        var renderers = new DefaultRenderersFactory(context).setEnableDecoderFallback(true);
        var load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(6_000, 16_000, 750, 1_500).build();
        ExoPlayer player = new ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource)).setLoadControl(load).build();
        slot.player = player;
        configure(slot);
        view(slot).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (slot.generation != generation) return;
                updateBufferTimeout(slot, generation);
                if (state == Player.STATE_ENDED) {
                    failed(slot, generation);
                } else {
                    slot.status = state == Player.STATE_READY ? (stream.converted() ? Status.LIVE_COMPATIBLE : Status.LIVE)
                            : state == Player.STATE_BUFFERING ? Status.BUFFERING : slot.status;
                    changed.run();
                }
            }

            @Override public void onPlayWhenReadyChanged(boolean playing, int reason) {
                if (slot.generation != generation) return;
                slot.playWhenReady = playing;
                updateBufferTimeout(slot, generation);
                changed.run();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (slot.generation == generation) changed.run();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (slot.generation != generation) return;
                // Decoder exhaustion on the second player shouldn't interrupt the first.
                if (slot == secondary && error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    finishWithError(slot, Status.INSET_FAILED);
                } else {
                    failed(slot, generation);
                }
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(stream.url().toString());
        if (stream.hls()) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(slot.playWhenReady);
        player.prepare();

    }

    private void failed(Slot slot, long generation) {
        if (slot.generation != generation) return;
        if (!slot.fallback) request(slot, slot.api, slot.channel, true);
        else finishWithError(slot, Status.STREAM_FAILED);
    }

    private void finishWithError(Slot slot, Status status) {
        ++slot.generation;
        Jellyfin.Stream old = detach(slot);
        Jellyfin api = slot.api;
        if (old != null) slot.tuner.execute(() -> api.close(old));
        slot.status = status;
        changed.run();
    }

    private PlayerView view(Slot slot) { return slot == primary ? mainView : insetView; }

    private void configure(Slot slot) {
        if (slot.player == null) return;
        boolean muted = slot == secondary;
        slot.player.setVolume(muted ? 0f : 1f);
        slot.player.setAudioAttributes(AudioAttributes.DEFAULT, !muted);
        slot.player.setTrackSelectionParameters(slot.player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted).build());
    }

    void swap() {
        if (secondary.channel == null) return;
        mainView.setPlayer(null);
        insetView.setPlayer(null);
        Slot old = primary;
        primary = secondary;
        secondary = old;
        configure(secondary);
        configure(primary);
        mainView.setPlayer(primary.player);
        insetView.setPlayer(secondary.player);
        changed.run();
    }

    private void updateBufferTimeout(Slot slot, long generation) {
        cancelBufferTimeout(slot);
        if (slot.player.getPlayWhenReady() && slot.player.getPlaybackState() == Player.STATE_BUFFERING) {
            slot.bufferTimeout = () -> failed(slot, generation);
            ui.postDelayed(slot.bufferTimeout, 24_000);
        }
    }

    private void cancelBufferTimeout(Slot slot) {
        if (slot.bufferTimeout != null) {
            ui.removeCallbacks(slot.bufferTimeout);
            slot.bufferTimeout = null;
        }
    }

    private Jellyfin.Stream detach(Slot slot) {
        cancelBufferTimeout(slot);
        ExoPlayer oldPlayer = slot.player;
        slot.player = null;
        view(slot).setPlayer(null);
        if (oldPlayer != null) oldPlayer.release();
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
    void stopAll() { stop(primary); stop(secondary); changed.run(); }

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
            if (remaining.decrementAndGet() == 0 && afterClose != null) afterClose.run();
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
