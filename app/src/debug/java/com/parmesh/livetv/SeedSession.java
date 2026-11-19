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
        Bundle outcome = new Bundle();
        int result = android.app.Activity.RESULT_OK;
        try (var stream = new FileInputStream(file)) {
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int size;
            while ((size = stream.read(buffer)) != -1) bytes.write(buffer, 0, size);
            var data = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            SessionStore store = new SessionStore(context);
            store.save(new Jellyfin.Session(
                    data.getString("base"), data.getString("token"), data.getString("userId"),
                    store.device()));
        } catch (Exception error) {
            result = android.app.Activity.RESULT_CANCELED;
            outcome.putString("error", error.getClass().getSimpleName());
        } finally { file.delete(); }
        finish(result, outcome);
    }
}
