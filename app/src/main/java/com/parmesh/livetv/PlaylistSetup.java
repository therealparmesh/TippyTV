package com.parmesh.livetv;

import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import okhttp3.HttpUrl;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class PlaylistSetup {
    private final MainActivity activity;
    private final Executor worker;
    private Map<String, String> gistFiles = Map.of();
    private String gistPage = "";
    private String selectedPlaylist;
    private String name = "", location = "", guide = "";
    private final boolean xtream;
    private String username = "", password = "";

    PlaylistSetup(MainActivity activity, Executor worker, boolean xtream) {
        this.activity = activity;
        this.worker = worker;
        this.xtream = xtream;
    }

    void fromPhone(JSONObject form) {
        name = form.optString("name");
        location = form.optString("address");
        guide = form.optString("guide");
        username = form.optString("user");
        password = form.optString("password");
        show(true);
    }

    void show() {
        show(false);
    }

    private void show(boolean submitted) {
        TvDialog dialog =
                new TvDialog(
                        activity,
                        activity.getString(xtream ? R.string.add_xtream : R.string.add_playlist));
        EditText title = field(dialog, R.string.profile_name, name, false);
        EditText address =
                field(
                        dialog,
                        xtream ? R.string.xtream_server : R.string.playlist_url,
                        location,
                        true);
        EditText detail =
                field(
                        dialog,
                        xtream ? R.string.username : R.string.playlist_guide_url,
                        xtream ? username : guide,
                        !xtream);
        EditText secret = xtream ? field(dialog, R.string.password, password, false) : null;
        if (secret != null)
            secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button next =
                activity.button(
                        R.string.profile_continue,
                        () -> {
                            name = title.getText().toString().trim();
                            if (name.isEmpty()) {
                                title.setError(activity.getString(R.string.profile_name_required));
                                title.requestFocus();
                                return;
                            }
                            location = address.getText().toString().trim();
                            HttpUrl base = HttpUrl.parse(location);
                            if (base == null) {
                                address.requestFocus();
                                address.setError(
                                        activity.getString(R.string.playlist_url_required));
                                return;
                            }
                            if (xtream) {
                                if (base.query() != null
                                        || base.fragment() != null
                                        || !base.username().isEmpty()
                                        || !base.password().isEmpty()) {
                                    address.setError(
                                            activity.getString(R.string.xtream_server_required));
                                    address.requestFocus();
                                    return;
                                }
                                username = detail.getText().toString().trim();
                                password = secret.getText().toString();
                                if (username.isEmpty()) {
                                    detail.setError(activity.getString(R.string.enter_username));
                                    detail.requestFocus();
                                    return;
                                }
                                if (password.isEmpty()) {
                                    secret.setError(activity.getString(R.string.enter_password));
                                    secret.requestFocus();
                                    return;
                                }
                            } else {
                                guide = detail.getText().toString().trim();
                                if (!guide.isEmpty() && HttpUrl.parse(guide) == null) {
                                    detail.requestFocus();
                                    detail.setError(
                                            activity.getString(R.string.playlist_url_required));
                                    return;
                                }
                            }
                            dialog.dismiss();
                            activity.requestLocalNetwork(ok -> {
                                if (!ok) show();
                                else if (xtream)
                                    checkPlaylist(
                                            Jellyfin.Session.xtream(
                                                    name, base, username, password));
                                else choose(location, false);
                            });
                        });
        MainActivity.submitOnDone(xtream ? secret : detail, next);
        dialog.add(next, 10);
        dialog.show(title);
        if (submitted) next.performClick();
    }

    private EditText field(TvDialog dialog, int label, String initial, boolean url) {
        EditText field =
                activity.edit(
                        activity.getString(label),
                        InputType.TYPE_CLASS_TEXT | (url ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        field.setText(initial);
        dialog.add(field, 8);
        return field;
    }

    private void choose(String address, boolean guideStep) {
        HttpUrl url = HttpUrl.get(address);
        if (!url.host().equals("gist.github.com") || url.pathSegments().contains("raw")) {
            selected(address, guideStep);
            return;
        }
        if (address.equals(gistPage)) {
            pickFile(guideStep);
            return;
        }
        load(
                R.string.reading_gist,
                R.string.gist_read_failed,
                () -> gistFiles(url),
                files -> {
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
        if (!id.matches("[a-fA-F0-9]+") || !revision.isEmpty() && !revision.matches("[a-fA-F0-9]+"))
            throw new IOException();
        JSONObject gist =
                new JSONObject(
                        PlaylistSource.text(
                                HttpUrl.get(
                                        "https://api.github.com/gists/"
                                                + id
                                                + (revision.isEmpty() ? "" : "/" + revision)),
                                8_000_000));
        if (gist.optBoolean("truncated")) throw new IOException();
        JSONObject files = gist.getJSONObject("files");
        Map<String, String> result = new TreeMap<>();
        var filenames = files.keys();
        while (filenames.hasNext()) {
            String filename = filenames.next();
            HttpUrl raw = HttpUrl.get(files.getJSONObject(filename).getString("raw_url"));
            if (!raw.host().equals("gist.githubusercontent.com") || !raw.isHttps())
                throw new IOException();
            // GitHub returns a revision-pinned raw URL. Follow edits for an unpinned page.
            if (revision.isEmpty()
                    && raw.pathSize() == 5
                    && raw.pathSegments().get(2).equals("raw"))
                raw = raw.newBuilder().removePathSegment(3).build();
            result.put(filename, raw.toString());
        }
        if (result.isEmpty()) throw new IOException();
        return result;
    }

    private void pickFile(boolean guideStep) {
        TvDialog picker =
                new TvDialog(
                        activity,
                        guideStep
                                ? activity.getString(R.string.choose_guide_file)
                                : activity.getString(R.string.choose_playlist_file));
        LinearLayout rows = activity.vertical();
        picker.scroll(rows, 190);
        for (var file : gistFiles.entrySet()) {
            String raw = file.getValue();
            Button item =
                    picker.action(
                            file.getKey(),
                            () -> {
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
            if (!guide.isEmpty()) {
                choose(guide, true);
                return;
            }
        }
        checkPlaylist(
                new Jellyfin.Session(
                        "",
                        "",
                        UUID.randomUUID().toString(),
                        "",
                        name,
                        selectedPlaylist,
                        guideStep ? url : "",
                        false));
    }

    private void checkPlaylist(Jellyfin.Session session) {
        load(
                R.string.checking_playlist,
                xtream ? R.string.xtream_read_failed : R.string.playlist_read_failed,
                () -> PlaylistSource.download(session),
                channels -> {
                    activity.checkedPlaylist().set(session, channels);
                    activity.signedIn(session);
                });
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
                finish =
                        () -> {
                            android.widget.Toast.makeText(
                                            activity, error, android.widget.Toast.LENGTH_LONG)
                                    .show();
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
