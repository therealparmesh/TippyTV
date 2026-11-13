#!/bin/sh
set -eu
target=${1:-/tmp/jellyfin-live-fixture}
mkdir -p "$target"
generate() {
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "$2=size=640x360:rate=25" \
        -f lavfi -i "sine=frequency=$3:sample_rate=48000" \
        -t 24 -c:v libx264 -preset ultrafast -g 50 -sc_threshold 0 -c:a aac \
        -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod "$target/$1.m3u8"
}
generate a testsrc2 440
generate b smptebars 660
