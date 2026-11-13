package com.parmesh.livetv;

import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class SignInScreen {
    private static final long POLL_MS = 2_500;
    private static final long CODE_LIFETIME_MS = 5 * 60_000;
    private final MainActivity activity;
    private final ExecutorService accounts;
    private final String device;
    private String server;
    private String username = "";
    private FrameLayout root;
    private LinearLayout card;
    private TextView feedback;
    private ProgressBar spinner;
    private Future<?> pending;
    private Runnable poll;
    private int generation;
    private boolean onServerPage;

    SignInScreen(MainActivity activity, ExecutorService accounts, String device, String server) {
        this.activity = activity;
        this.accounts = accounts;
        this.device = device;
        this.server = server;
    }

    void show(String message, boolean signingOut) {
        cancel();
        onServerPage = true;
        page(R.string.connect_heading, R.string.connect_description);
        EditText address = field(R.string.server_address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(server);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        Button quick = action(R.string.quick_connect, true, null);
        Button password = action(R.string.use_password, false, null);
        connectField(address, quick);
        quick.setEnabled(!signingOut);
        password.setEnabled(!signingOut);
        quick.setOnClickListener(view -> {
            if (!rememberAddress(address)) return;
            startCode();
        });
        password.setOnClickListener(view -> {
            if (rememberAddress(address)) passwordPage();
        });
        status(message, false);
        present(signingOut ? null : server.isBlank() ? address : quick);
    }

    boolean back() {
        if (onServerPage) return false;
        show("", false);
        return true;
    }

    void cancel() {
        generation++;
        if (poll != null) root.removeCallbacks(poll);
        if (pending != null) pending.cancel(false);
        poll = null;
        pending = null;
    }

    private boolean rememberAddress(EditText field) {
        String input = field.getText().toString().trim();
        if (input.isBlank()) {
            status(activity.getString(R.string.enter_server), false);
            field.requestFocus();
            return false;
        }
        try { server = Jellyfin.serverUrl(input).toString(); }
        catch (IllegalArgumentException invalid) {
            status(invalid.getMessage(), false);
            field.requestFocus();
            return false;
        }
        WindowCompat.getInsetsController(activity.getWindow(), root).hide(WindowInsetsCompat.Type.ime());
        return true;
    }

    private void passwordPage() {
        cancel();
        onServerPage = false;
        page(R.string.password_heading, R.string.password_description);
        EditText user = field(R.string.username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText password = field(R.string.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        user.setText(username);
        user.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        user.setNextFocusForwardId(password.getId());
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        Button connect = action(R.string.sign_in, true, null);
        connectField(password, connect);
        action(R.string.change_server, false, this::back);
        connect.setOnClickListener(view -> {
            username = user.getText().toString().trim();
            if (username.isBlank()) {
                status(activity.getString(R.string.enter_username), false);
                user.requestFocus();
                return;
            }
            WindowCompat.getInsetsController(activity.getWindow(), root).hide(WindowInsetsCompat.Type.ime());
            connect.setEnabled(false);
            String name = username, secret = password.getText().toString(), address = server;
            int request = generation;
            activity.requestLocalNetwork(granted -> {
                if (request != generation) return;
                if (!granted) {
                    status(activity.getString(R.string.local_network_access), false);
                    connect.setEnabled(true);
                    connect.requestFocus();
                    return;
                }
                status(activity.getString(R.string.login_connecting), true);
                pending = accounts.submit(() -> {
                    try { authorized(request, Jellyfin.login(address, name, secret, device)); }
                    catch (Exception failure) {
                        activity.runOnUiThread(() -> {
                            if (request != generation) return;
                            status(error(failure, true), false);
                            connect.setEnabled(true);
                            if (failure instanceof Jellyfin.HttpFailure http && http.status == 401) {
                                password.requestFocus();
                                password.selectAll();
                            } else connect.requestFocus();
                        });
                    }
                });
            });
        });
        present(user);
    }

    private void startCode() {
        cancel();
        onServerPage = false;
        page(R.string.quick_connect, R.string.quick_connect_description);
        TextView code = activity.text("", 40, MainActivity.TEXT);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(.16f);
        code.setVisibility(View.GONE);
        code.setBackground(activity.box(MainActivity.BG, 0, 12));
        card.addView(code, spaced(76, 14));
        Button retry = action(R.string.new_code, true, this::startCode);
        retry.setVisibility(View.GONE);
        action(R.string.use_password, false, this::passwordPage);
        Button back = action(R.string.change_server, false, this::back);
        present(back);
        status(activity.getString(R.string.requesting_code), true);
        int request = generation;
        Jellyfin client = Jellyfin.signInClient(server, device);
        activity.requestLocalNetwork(granted -> {
            if (request != generation) return;
            if (!granted) {
                showCodeError(request, activity.getString(R.string.local_network_access), code, retry);
                return;
            }
            pending = accounts.submit(() -> {
                try {
                    Jellyfin.QuickConnect attempt = client.startQuickConnect();
                    activity.runOnUiThread(() -> {
                        if (request != generation) return;
                        code.setText(attempt.code());
                        code.setVisibility(View.VISIBLE);
                        status(activity.getString(R.string.waiting_for_approval), true);
                        waitForApproval(client, attempt, request, SystemClock.elapsedRealtime() + CODE_LIFETIME_MS, code, retry);
                    });
                } catch (Exception failure) {
                    activity.runOnUiThread(() -> showCodeError(request, error(failure, false), code, retry));
                }
            });
        });
    }

    private void waitForApproval(Jellyfin client, Jellyfin.QuickConnect attempt, int request, long deadline,
                                 TextView code, Button retry) {
        poll = () -> {
            if (request != generation) return;
            if (SystemClock.elapsedRealtime() >= deadline) {
                showCodeError(request, activity.getString(R.string.code_expired), code, retry);
                return;
            }
            pending = accounts.submit(() -> {
                try {
                    if (client.quickConnectAuthorized(attempt)) authorized(request, client.finishQuickConnect(attempt));
                    else activity.runOnUiThread(() -> {
                        if (request == generation) waitForApproval(client, attempt, request, deadline, code, retry);
                    });
                } catch (Exception failure) {
                    activity.runOnUiThread(() -> showCodeError(request, error(failure, false), code, retry));
                }
            });
        };
        root.postDelayed(poll, POLL_MS);
    }

    private void showCodeError(int request, String message, TextView code, Button retry) {
        if (request != generation) return;
        code.setVisibility(View.GONE);
        status(message, false);
        retry.setVisibility(View.VISIBLE);
        retry.requestFocus();
    }

    private void authorized(int request, Jellyfin.Session session) {
        activity.runOnUiThread(() -> {
            if (request == generation) activity.signedIn(session);
            else {
                Runnable logout = () -> {
                    try { new Jellyfin(session).logout(); }
                    catch (java.io.IOException ignored) { /* The canceled session was never stored. */ }
                };
                new Thread(logout, "jellyfin-sign-in-cleanup").start();
            }
        });
    }

    private String error(Exception failure, boolean password) {
        if (failure instanceof Jellyfin.HttpFailure http) {
            if (password && http.status == 401) return activity.getString(R.string.invalid_credentials);
            if (!password && (http.status == 401 || http.status == 403)) return activity.getString(R.string.quick_connect_disabled);
            if (!password && http.status == 404) return activity.getString(R.string.code_unavailable);
        }
        if (activity.needsLocalNetworkPermission()) return activity.getString(R.string.local_network_access);
        return activity.getString(R.string.connection_failed);
    }

    private void page(int heading, int description) {
        root = new FrameLayout(activity);
        root.setBackgroundColor(MainActivity.BG);
        card = activity.vertical();
        card.setPadding(activity.dp(28), activity.dp(22), activity.dp(28), activity.dp(22));
        card.setBackground(activity.box(MainActivity.PANEL, MainActivity.EDGE, 16));
        TextView title = activity.text(activity.getString(heading), 29, MainActivity.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        card.addView(activity.text(activity.getString(description), 15, MainActivity.DIM), spaced(-2, 6));
        if (!onServerPage) {
            TextView address = activity.text(server, 13, MainActivity.ACCENT);
            address.setSingleLine();
            address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(address, spaced(-2, 5));
        }
        feedback = activity.text("", 14, MainActivity.ACCENT);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));
        spinner.setVisibility(View.GONE);
    }

    private void present(View target) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spin = new LinearLayout.LayoutParams(activity.dp(18), activity.dp(18));
        spin.rightMargin = activity.dp(8);
        row.addView(spinner, spin);
        row.addView(feedback, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, spaced(-2, 9));
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(card);
        root.addView(scroll, new FrameLayout.LayoutParams(activity.dp(530), -2, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.max(0, Math.min(activity.dp(530), r - l - activity.dp(36)));
            ViewGroup.LayoutParams size = scroll.getLayoutParams();
            if (size.width != width) { size.width = width; scroll.setLayoutParams(size); }
        });
        activity.showScreen(root);
        if (target != null) target.requestFocusFromTouch();
    }

    private void status(String message, boolean busy) {
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        feedback.setText(message);
        feedback.setVisibility(message.isBlank() ? View.GONE : View.VISIBLE);
    }

    private EditText field(int label, int type) {
        card.addView(activity.text(activity.getString(label), 13, MainActivity.DIM), spaced(-2, 13));
        EditText edit = activity.edit(activity.getString(label), type);
        edit.setId(View.generateViewId());
        card.addView(edit, spaced(48, 4));
        return edit;
    }

    private Button action(int label, boolean primary, Runnable listener) {
        Button button = activity.button(activity.getString(label), listener);
        if (primary) button.setBackground(activity.focusBackground(MainActivity.PRIMARY));
        card.addView(button, spaced(44, 8));
        return button;
    }

    private LinearLayout.LayoutParams spaced(int height, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, height < 0 ? height : activity.dp(height));
        size.topMargin = activity.dp(top);
        return size;
    }

    private static void connectField(EditText field, Button connect) {
        field.setOnEditorActionListener((view, action, event) -> {
            if ((action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) && connect.isEnabled()) {
                connect.performClick();
                return true;
            }
            return false;
        });
    }
}
