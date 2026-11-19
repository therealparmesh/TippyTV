package com.parmesh.livetv;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.json.JSONException;

interface TvSource {
    /** nextStart is -1 when the source has returned its entire lineup. */
    record Page(List<Channels.Channel> channels, int nextStart) {}

    Page channels(int start) throws IOException;
    void logout() throws IOException;
    void favorite(Channels.Channel channel, boolean value) throws IOException;
    boolean hasGuide(long time, int hours) throws IOException;
    Map<String, List<Channels.Program>> programs(List<String> ids, long time, int hours) throws IOException;
    Jellyfin.Stream open(Channels.Channel channel, boolean fallback, boolean inset, boolean recovering) throws IOException, JSONException;
    void close(Jellyfin.Stream stream);
}
