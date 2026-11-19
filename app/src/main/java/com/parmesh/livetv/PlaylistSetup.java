package com.parmesh.livetv;

import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import okhttp3.HttpUrl;
import org.json.JSONObject;

final class PlaylistSetup {
    private final MainActivity activity;
    private final Executor worker;
    private Map<String, String> gistFiles = Map.of();
    private String gistPage = "";
    private String selectedPlaylist;
    private String name = "", playlist = "", guide = "";
    PlaylistSetup(MainActivity activity, Executor worker) { this.activity = activity; this.worker = worker; }

    void show() {
        TvDialog dialog = new TvDialog(activity, activity.getString(R.string.add_playlist));
        EditText title = field(dialog, R.string.profile_name, name, false);
        EditText address = field(dialog, R.string.playlist_url, playlist, true);
        EditText listings = field(dialog, R.string.playlist_guide_url, guide, true);
        Button next = activity.button(R.string.profile_continue, () -> {
            name = title.getText().toString().trim();
            playlist = address.getText().toString().trim();
            guide = listings.getText().toString().trim();
            if (name.isEmpty()) { title.setError(activity.getString(R.string.profile_name_required)); title.requestFocus(); return; }
            if (HttpUrl.parse(playlist) == null) { address.requestFocus(); address.setError(activity.getString(R.string.playlist_url_required)); return; }
            if (!guide.isEmpty() && HttpUrl.parse(guide) == null) { listings.requestFocus(); listings.setError(activity.getString(R.string.playlist_url_required)); return; }
            dialog.dismiss();
            activity.requestLocalNetwork(ok -> { if (ok) choose(playlist, false); else show(); });
        });
        dialog.add(next, 10);
        dialog.show(title);
    }

    private EditText field(TvDialog dialog, int label, String initial, boolean url) {
        EditText field = activity.edit(activity.getString(label), InputType.TYPE_CLASS_TEXT | (url ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        field.setText(initial);
        dialog.add(field, 8);
        return field;
    }

    private void choose(String address, boolean guideStep) {
        HttpUrl url = HttpUrl.get(address);
        if (!url.host().equals("gist.github.com") || url.pathSegments().contains("raw")) { selected(address, guideStep); return; }
        if (address.equals(gistPage)) { pickFile(guideStep); return; }
        load(R.string.reading_gist, R.string.gist_read_failed, () -> gistFiles(url), files -> {
            gistPage = address;
            gistFiles = files;
            pickFile(guideStep);
        });
    }

    private static Map<String, String> gistFiles(HttpUrl url) throws Exception {
        List<String> parts = new ArrayList<>(url.pathSegments());
        parts.removeIf(String::isEmpty);
        if (parts.isEmpty() || parts.size() > 3) throw new IOException();
        String id = parts.get(parts.size() == 1 ? 0 : 1);
        String revision = parts.size() == 3 ? parts.get(2) : "";
        if (!id.matches("[a-fA-F0-9]+") || !revision.isEmpty() && !revision.matches("[a-fA-F0-9]+")) throw new IOException();
        JSONObject gist = new JSONObject(PlaylistSource.text(HttpUrl.get("https://api.github.com/gists/" + id +
                (revision.isEmpty() ? "" : "/" + revision)), 8_000_000));
        if (gist.optBoolean("truncated")) throw new IOException();
        JSONObject files = gist.getJSONObject("files");
        Map<String, String> result = new TreeMap<>();
        var filenames = files.keys();
        while (filenames.hasNext()) {
            String filename = filenames.next();
            HttpUrl raw = HttpUrl.get(files.getJSONObject(filename).getString("raw_url"));
            if (!raw.host().equals("gist.githubusercontent.com") || !raw.isHttps()) throw new IOException();
            // GitHub returns a revision-pinned raw URL. Follow edits for an unpinned page.
            if (revision.isEmpty() && raw.pathSize() == 5 && raw.pathSegments().get(2).equals("raw"))
                raw = raw.newBuilder().removePathSegment(3).build();
            result.put(filename, raw.toString());
        }
        if (result.isEmpty()) throw new IOException();
        return result;
    }

    private void pickFile(boolean guideStep) {
        TvDialog picker = new TvDialog(activity, guideStep ? activity.getString(R.string.choose_guide_file) : activity.getString(R.string.choose_playlist_file));
        LinearLayout rows = activity.vertical();
        picker.scroll(rows, 190);
        for (var file : gistFiles.entrySet()) {
            String raw = file.getValue();
            Button item = picker.action(file.getKey(), () -> {
                if (!guideStep && guide.isEmpty()) {
                    selectedPlaylist = raw;
                    pickFile(true);
                } else selected(raw, guideStep);
            });
            item.setMinimumHeight(activity.dp(44));
            rows.addView(item);
        }
        if (guideStep) picker.add(picker.action(R.string.skip_guide, () -> selected("", true)), 8);
        picker.onCancel(() -> {
            if (guideStep && guide.isEmpty()) pickFile(false);
            else show();
        });
        picker.show(rows.getChildAt(0));
    }

    private void selected(String url, boolean guideStep) {
        if (!guideStep) {
            selectedPlaylist = url;
            if (!guide.isEmpty()) { choose(guide, true); return; }
        }
        Jellyfin.Session session = new Jellyfin.Session("", "", UUID.randomUUID().toString(), "", name, selectedPlaylist, guideStep ? url : "");
        load(R.string.checking_playlist, R.string.playlist_read_failed, () -> {
            PlaylistSource.download(session);
            return session;
        }, activity::signedIn);
    }

    private <T> void load(int title, int error, Callable<T> request, Consumer<T> success) {
        TvDialog dialog = new TvDialog(activity, activity.getString(title));
        Button cancel = dialog.action(R.string.cancel, this::show);
        dialog.add(cancel, 8);
        dialog.onCancel(this::show);
        dialog.show(cancel);
        worker.execute(() -> {
            Runnable finish;
            try {
                T value = request.call();
                finish = () -> success.accept(value);
            } catch (Exception failure) {
                finish = () -> {
                    android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show();
                    show();
                };
            }
            Runnable result = finish;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !dialog.isShowing()) return;
                dialog.dismiss();
                result.run();
            });
        });
    }
}
