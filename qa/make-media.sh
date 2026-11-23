#!/bin/sh
set -eu
target=${1:-/tmp/jellyfin-live-fixture}
mkdir -p "$target"
generate() {
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "$2=size=${4:-640x360}:rate=25,drawbox=x=iw*0.875:y=ih*0.05:w=iw*0.12:h=ih*0.12:color=magenta:t=fill" \
        -f lavfi -i "sine=frequency=$3:sample_rate=48000" \
        -t 24 -c:v libx264 -preset ultrafast -g 50 -sc_threshold 0 -c:a aac \
        -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod "$target/$1.m3u8"
}
generate a testsrc2 440
generate b smptebars 660

generate a_hd testsrc2 440 1920x1080
generate a_720 testsrc2 440 1280x720
generate a_43 testsrc2 440 640x480
