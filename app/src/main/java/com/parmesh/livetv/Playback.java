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

@OptIn(markerClass = UnstableApi.class)
final class Playback {
    private static final ExecutorService CLEANUP = Executors.newFixedThreadPool(2);
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
        String status = "";
        boolean fallback;
        Runnable bufferTimeout;

        boolean active() { return channel != null && !status.startsWith("Unable"); }
    }

    Playback(Context context, PlayerView mainView, PlayerView insetView, Runnable changed) {
        this.context = context;
        this.mainView = mainView;
        this.insetView = insetView;
        this.changed = changed;
    }

    Channels.Channel primary() { return primary.channel; }
    Channels.Channel secondary() { return secondary.channel; }
    String primaryStatus() { return primary.status; }
    String secondaryStatus() { return secondary.status; }
    boolean hasVideo() {
        return (primary.player != null && primary.player.isPlaying())
                || (secondary.player != null && secondary.player.isPlaying());
    }

    void tune(Jellyfin api, Channels.Channel channel, boolean inset) {
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
        request(target, api, channel, false);
    }

    void tuneCompatible(Jellyfin api, Channels.Channel channel) {
        if (secondary.channel != null && secondary.channel.id().equals(channel.id())) stop(secondary);
        request(primary, api, channel, true);
    }

    private void request(Slot slot, Jellyfin api, Channels.Channel channel, boolean fallback) {
        long generation = ++slot.generation;
        Jellyfin.Stream old = detach(slot);
        Jellyfin previousApi = slot.api;
        slot.api = api;
        slot.channel = channel;
        slot.fallback = fallback;
        slot.status = fallback ? "Trying compatible stream…" : "Tuning…";
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
                        slot.status = "Unable to tune. Select the channel to retry.";
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
                    CLEANUP.execute(() -> api.close(opened));
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
                cancelBufferTimeout(slot);
                if (state == Player.STATE_BUFFERING) {
                    slot.bufferTimeout = () -> failed(slot, generation);
                    ui.postDelayed(slot.bufferTimeout, 24_000);
                }
                if (state == Player.STATE_ENDED) {
                    failed(slot, generation);
                } else {
                    slot.status = state == Player.STATE_READY ? (stream.converted() ? "LIVE · compatible stream" : "LIVE · direct")
                            : state == Player.STATE_BUFFERING ? "Buffering…" : slot.status;
                    changed.run();
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (slot.generation == generation) changed.run();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (slot.generation != generation) return;
                // Decoder exhaustion on the second player shouldn't interrupt the first.
                if (slot == secondary && error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    finishWithError(slot, "Unable to play inset on this device.");
                } else {
                    failed(slot, generation);
                }
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(stream.url().toString());
        if (stream.hls()) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();

    }

    private void failed(Slot slot, long generation) {
        if (slot.generation != generation) return;
        if (!slot.fallback) request(slot, slot.api, slot.channel, true);
        else finishWithError(slot, "Unable to play this stream. Select the channel to retry.");
    }

    private void finishWithError(Slot slot, String message) {
        ++slot.generation;
        Jellyfin.Stream old = detach(slot);
        Jellyfin api = slot.api;
        if (old != null) slot.tuner.execute(() -> api.close(old));
        slot.status = message;
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
        slot.status = "";
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

    void destroy() {
        stopAll();
        primary.tuner.shutdown();
        secondary.tuner.shutdown();
        // Do not remove posted stream handoffs: stale handoffs close their own tuner lease.
    }
}
