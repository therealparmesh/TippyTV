package com.parmesh.livetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** One local HTTPS process per visible pairing dialog. Closing the dialog revokes the QR. */
final class PhoneSetup {
    private final MainActivity activity;
    private TvDialog dialog;
    private Process process;
    private ConnectivityManager.NetworkCallback networkWatch;

    PhoneSetup(MainActivity activity) {
        this.activity = activity;
    }

    void show() {
        close();
        TvDialog screen = new TvDialog(activity, activity.getString(R.string.phone_setup));
        dialog = screen;
        TextView status =
                activity.text(activity.getString(R.string.phone_consent), 17, MainActivity.TEXT);
        screen.add(status, 8);
        ImageView qr = new ImageView(activity);
        qr.setKeepScreenOn(true);
        qr.setVisibility(android.view.View.GONE);
        qr.setContentDescription(activity.getString(R.string.phone_scan));
        screen.add(qr, 8);
        Button start = activity.button(R.string.phone_start, null);
        start.setOnClickListener(
                v -> {
                    start.setEnabled(false);
                    activity.requestLocalNetwork(
                            allowed -> {
                                if (dialog != screen) return;
                                if (!allowed) {
                                    status.setText(R.string.local_network_access);
                                    start.setEnabled(true);
                                    return;
                                }
                                start.setVisibility(android.view.View.GONE);
                                status.setText(R.string.phone_preparing);
                                new Thread(() -> serve(screen, status, qr), "phone-setup").start();
                            });
                });
        screen.add(start, 12);
        screen.add(activity.button(R.string.cancel, screen::dismiss), 8);
        screen.onDismiss(
                () -> {
                    if (dialog == screen) close();
                });
        screen.show(start);
    }

    synchronized void close() {
        TvDialog previous = dialog;
        dialog = null;
        if (networkWatch != null) {
            activity.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(networkWatch);
            networkWatch = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (previous != null) previous.dismiss();
    }

    private void serve(TvDialog screen, TextView status, ImageView qr) {
        Process child = null;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            var network = cm.getActiveNetwork();
            LinkProperties link = cm.getLinkProperties(network);
            if (link == null || link.getDnsServers().isEmpty()) throw new java.io.IOException();
            InetAddress ip =
                    link.getLinkAddresses().stream()
                            .map(android.net.LinkAddress::getAddress)
                            .filter(a -> a instanceof Inet4Address && a.isSiteLocalAddress())
                            .findFirst()
                            .orElseThrow();
            File exe = new File(activity.getApplicationInfo().nativeLibraryDir, "libphonesetup.so");
            synchronized (this) {
                if (dialog != screen) return;
                var watch =
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onLost(android.net.Network lost) {
                                if (lost.equals(network))
                                    activity.runOnUiThread(
                                            () -> {
                                                if (dialog == screen) close();
                                            });
                            }

                            @Override
                            public void onLinkPropertiesChanged(
                                    android.net.Network changed, LinkProperties properties) {
                                if (changed.equals(network)
                                        && properties.getLinkAddresses().stream()
                                                .noneMatch(a -> a.getAddress().equals(ip)))
                                    onLost(changed);
                            }
                        };
                cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().build(), watch);
                networkWatch = watch;
                if (!network.equals(cm.getActiveNetwork())) throw new java.io.IOException();
                child =
                        new ProcessBuilder(
                                        exe.toString(),
                                        ip.getHostAddress(),
                                        new File(activity.getNoBackupFilesDir(), "phone-tls")
                                                .toString(),
                                        link.getDnsServers().get(0).getHostAddress())
                                .start();
                process = child;
            }
            try (BufferedReader out =
                    new BufferedReader(
                            new InputStreamReader(
                                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String url = new JSONObject(out.readLine()).getString("url");
                var bits = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 450, 450);
                int[] pixels = new int[450 * 450];
                for (int y = 0; y < 450; y++)
                    for (int x = 0; x < 450; x++)
                        pixels[y * 450 + x] = bits.get(x, y) ? 0xff000000 : 0xffffffff;
                Bitmap image = Bitmap.createBitmap(pixels, 450, 450, Bitmap.Config.ARGB_8888);
                activity.runOnUiThread(
                        () -> {
                            if (dialog != screen) return;
                            status.setText(R.string.phone_scan);
                            qr.getLayoutParams().height = activity.dp(186);
                            qr.setImageBitmap(image);
                            qr.setVisibility(android.view.View.VISIBLE);
                        });
                JSONObject form = new JSONObject(out.readLine());
                activity.runOnUiThread(
                        () -> {
                            if (dialog != screen) return;
                            close();
                            activity.phoneSubmitted(form);
                        });
            }
        } catch (Exception failure) {
            activity.runOnUiThread(
                    () -> {
                        if (dialog == screen) {
                            status.setText(R.string.phone_unavailable);
                            qr.setVisibility(android.view.View.GONE);
                        }
                    });
        } finally {
            if (child != null) child.destroy();
        }
    }
}
