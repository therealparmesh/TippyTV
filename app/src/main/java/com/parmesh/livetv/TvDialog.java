package com.parmesh.livetv;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A TV-sized dialog with explicit initial focus and focus restoration on dismissal. */
final class TvDialog {
    private final MainActivity activity;
    private final Dialog dialog;
    private final LinearLayout card;
    private final View opener;
    private Runnable onDismiss;

    TvDialog(MainActivity activity, String title) {
        this.activity = activity;
        View focused = activity.getCurrentFocus();
        opener = focused == null ? activity.defaultBrowserFocus() : focused;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        card = activity.vertical();
        card.setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20));
        card.setBackground(activity.box(MainActivity.PANEL, MainActivity.EDGE, 16));
        TextView heading = activity.text(title, 25, MainActivity.TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        card.addView(heading);
        dialog.setTitle(title);
        dialog.setContentView(card);
        dialog.setOnDismissListener(unused -> {
            if (opener != null && opener.isShown() && opener.isEnabled()) opener.requestFocusFromTouch();
            if (onDismiss != null) onDismiss.run();
        });
    }

    void add(View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, view instanceof Button ? activity.dp(44) : -2);
        size.topMargin = activity.dp(top);
        card.addView(view, size);
    }

    Button action(int label, Runnable action) {
        return action(activity.getString(label), action);
    }

    Button action(String label, Runnable action) {
        return activity.button(label, () -> {
            dialog.dismiss();
            activity.getWindow().getDecorView().post(action);
        });
    }

    void onDismiss(Runnable action) { onDismiss = action; }
    void dismiss() { dialog.dismiss(); }
    boolean isShowing() { return dialog.isShowing(); }
    Window window() { return dialog.getWindow(); }

    ScrollView scroll(View content, int height) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.setBackground(activity.focusBackground(MainActivity.BG));
        scroll.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8));
        scroll.addView(content);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, activity.dp(height));
        size.topMargin = activity.dp(12);
        card.addView(scroll, size);
        return scroll;
    }

    void show(View focus) {
        Window window = window();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.65f);
        window.setGravity(Gravity.CENTER);
        dialog.show();
        int width = activity.getWindow().getDecorView().getWidth();
        window.setLayout(Math.min(activity.dp(580), Math.max(activity.dp(240), width - activity.dp(32))),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (focus != null) {
            focus.requestFocusFromTouch();
            card.post(() -> {
                if (dialog.isShowing()) focus.requestFocusFromTouch();
            });
        }
    }
}
