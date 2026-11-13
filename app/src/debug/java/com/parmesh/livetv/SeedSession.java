package com.parmesh.livetv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.FileInputStream;
import org.json.JSONObject;

public final class SeedSession extends Instrumentation {
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        var context = getTargetContext();
        var file = new java.io.File(context.getFilesDir(), "qa-session.json");
        try (var stream = new FileInputStream(file)) {
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int size;
            while ((size = stream.read(buffer)) != -1) bytes.write(buffer, 0, size);
            var data = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            new SessionStore(context).save(new Jellyfin.Session(
                    data.getString("base"), data.getString("token"), data.getString("userId"),
                    new SessionStore(context).device()));
            file.delete();
            finish(android.app.Activity.RESULT_OK, new Bundle());
        } catch (Exception error) {
            Bundle outcome = new Bundle();
            outcome.putString("error", error.getClass().getSimpleName());
            file.delete();
            finish(android.app.Activity.RESULT_CANCELED, outcome);
        } finally { file.delete(); }
    }
}
