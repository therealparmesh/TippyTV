package com.parmesh.livetv;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends ComponentActivity {
    private static final int BG = 0xff2b2b2b, PANEL = 0xff313335, BAR = 0xff3c3f41;
    private static final int TEXT = 0xffd6d9df, DIM = 0xffaab1ba, ACCENT = 0xffffc66d, BLUE = 0xff365880;
    private static final int PAGE = 256, GUIDE_BATCH = 16;
    private static final int GUIDE_HOURS = 3;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService accounts = Executors.newSingleThreadExecutor();
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final ExecutorService search = Executors.newSingleThreadExecutor();
    private final ExecutorService guide = Executors.newSingleThreadExecutor();
    private final GuideCache epg = new GuideCache(384);
    private final Map<String, Boolean> favoriteOverrides = new HashMap<>();
    private final Set<String> pendingFavorites = new HashSet<>();
    private final DateFormat clock = DateFormat.getTimeInstance(DateFormat.SHORT);
    private SessionStore store;
    private Jellyfin api;
    private Playback player;
    private List<Channels.Channel> all = List.of();
    private Set<String> favorites = Set.of();
    private Map<String, Integer> jumps = Map.of();
    private Channels.Channel selected;
    private Channels.Channel resumeMain, resumeInset;
    private String query = "";
    private String loadProblem;
    private boolean favoritesOnly, catalogBusy, guideBusy, fullscreen, foreground, destroyed;
    private volatile int visibleGeneration;
    private int expectedCount, offset, focusedPosition;
    private boolean resetSelection;
    private String pendingJump;
    private long noGuideUntil, guideCheckedUntil, guideBackoffUntil;
    private volatile long catalogGeneration;
    private FrameLayout root, video, inset;
    private PlayerView mainSurface, insetSurface;
    private LinearLayout shell, header, body, browser, controls, watchBar;
    private TextView count, empty, selectedName, programText, mainMessage, insetMessage, watchTitle, watchStatus;
    private Button filterButton, queryButton, watchButton, insetButton, swapButton, closeInset, starButton;
    private Button watchSwap, watchClose;
    private RecyclerView list;
    private LinearLayoutManager rows;
    private ChannelAdapter adapter;

    private final Runnable guideRefresh = this::visibleGuide;
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!foreground || destroyed) return;
            refreshGuideRows();
            scheduleGuide();
            ui.postDelayed(this, 30_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        store = new SessionStore(this);
        setContentView(text("Opening Jellyfin Live TV…", 18, ACCENT));
        accounts.execute(() -> {
            Jellyfin.Session saved = store.read();
            ui.post(() -> {
                if (destroyed) return;
                if (saved == null) showLogin("");
                else connected(saved);
            });
        });
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private GradientDrawable box(int color, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(2), border);
        return shape;
    }

    private StateListDrawable focusBackground(int normal) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, box(BG, 0, 5));
        states.addState(new int[]{android.R.attr.state_focused}, box(BLUE, ACCENT, 5));
        states.addState(new int[]{android.R.attr.state_pressed}, box(BLUE, ACCENT, 5));
        states.addState(new int[0], box(normal, 0, 5));
        return states;
    }

    private Button button(String label, Runnable action) {
        Button v = new Button(this);
        v.setText(label); v.setTextSize(13); v.setAllCaps(false);
        v.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xff858991, TEXT}));
        v.setBackground(focusBackground(BAR)); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setMinHeight(0); v.setMinimumHeight(0);
        v.setPadding(dp(12), 0, dp(12), 0); v.setFocusable(true);
        v.setOnClickListener(view -> action.run());
        return v;
    }

    private void addButton(LinearLayout parent, Button v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.rightMargin = dp(6); parent.addView(v, lp);
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }

    private void showLogin(String message) {
        FrameLayout screen = new FrameLayout(this); screen.setBackgroundColor(BG);
        LinearLayout card = vertical(); card.setPadding(dp(22), dp(15), dp(22), dp(14));
        card.setBackground(box(PANEL, 0, 8));
        TextView title = text("Jellyfin Live TV", 25, ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        card.addView(text("Connect to your Jellyfin server", 14, DIM));
        EditText address = edit("Server address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(store.address());
        EditText username = edit("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText password = edit("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(address); card.addView(username); card.addView(password);
        TextView feedback = text(message, 13, ACCENT);
        Button login = button("Connect", () -> {});
        LinearLayout.LayoutParams loginSize = new LinearLayout.LayoutParams(-1, dp(42));
        loginSize.topMargin = dp(7); card.addView(login, loginSize);
        card.addView(feedback);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.addView(card);
        int width = Math.min(dp(470), getResources().getDisplayMetrics().widthPixels - dp(32));
        FrameLayout.LayoutParams cardSize = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
        screen.addView(scroll, cardSize);
        setContentView(screen);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        address.requestFocus();
        login.setOnClickListener(v -> {
            String server = address.getText().toString(), user = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (server.isBlank() || user.isBlank()) { feedback.setText("Enter a server and username."); return; }
            login.setEnabled(false); feedback.setText("Connecting…");
            accounts.execute(() -> {
                try {
                    Jellyfin.Session session = Jellyfin.login(server, user, secret, store.device());
                    boolean persisted;
                    try { store.save(session); persisted = true; }
                    catch (Exception unavailable) { persisted = false; }
                    boolean saved = persisted;
                    ui.post(() -> {
                        if (destroyed) return;
                        password.getText().clear();
                        connected(session);
                        if (!saved) toast("Connected. This device could not save the sign-in.");
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (destroyed) return;
                        feedback.setText(error instanceof Jellyfin.HttpFailure http && http.status == 401
                                ? "Username or password was rejected."
                                : error instanceof IllegalArgumentException ? error.getMessage()
                                : "Could not connect. Check the server address and network.");
                        login.setEnabled(true);
                    });
                }
            });
        });
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        password.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { login.performClick(); return true; }
            return false;
        });
    }

    private EditText edit(String hint, int type) {
        EditText field = new EditText(this);
        field.setSingleLine(); field.setInputType(type); field.setHint(hint); field.setTextSize(16);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        return field;
    }

    private void connected(Jellyfin.Session session) {
        api = new Jellyfin(session);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        shell = vertical(); shell.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("LIVE TV", 20, ACCENT); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        queryButton = button("Search", this::searchDialog); addButton(header, queryButton);
        filterButton = button("Favorites", () -> {
            favoritesOnly = !favoritesOnly; renderFilter(true);
        }); addButton(header, filterButton);
        addButton(header, button("Jump", this::jumpDialog));
        addButton(header, button("Guide", this::scheduleDialog));
        addButton(header, button("Server", this::serverDialog));
        shell.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));

        body = new LinearLayout(this);
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1);
        content.topMargin = dp(8); shell.addView(body, content);
        browser = vertical(); browser.setBackground(box(PANEL, 0, 6));
        body.addView(browser, new LinearLayout.LayoutParams(0, -1, .42f));
        count = text("Loading channels…", 13, DIM);
        count.setPadding(dp(12), dp(8), dp(4), dp(8)); browser.addView(count);
        FrameLayout listArea = new FrameLayout(this);
        browser.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new RecyclerView(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP && isPageKey(event.getKeyCode())) return true;
                return handleRemoteKey(event) || super.dispatchKeyEvent(event);
            }
        };
        list.setId(View.generateViewId());
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.setHasFixedSize(true); list.setItemAnimator(null);
        list.setPreserveFocusAfterLayout(false);
        rows = new LinearLayoutManager(this) {
            @Override public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                String id = pendingJump;
                if (id == null) return;
                list.postOnAnimation(() -> {
                    if (destroyed || !id.equals(pendingJump) || fullscreen) return;
                    List<Channels.Channel> channels = adapter.getCurrentList();
                    if (focusedPosition >= channels.size() || !id.equals(channels.get(focusedPosition).id())) return;
                    RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(focusedPosition);
                    if (holder != null && holder.itemView.requestFocusFromTouch()) pendingJump = null;
                });
            }
        };
        list.setLayoutManager(rows);
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("", 15, DIM); empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), 0, dp(16), 0);
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout pager = new LinearLayout(this);
        pager.setPadding(dp(8), dp(3), dp(4), dp(5));
        addButton(pager, button("−50", () -> page(-50)));
        addButton(pager, button("+50", () -> page(50)));
        browser.addView(pager);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { scheduleGuide(); }
        });
        queryButton.setNextFocusDownId(list.getId());
        filterButton.setNextFocusDownId(list.getId());

        LinearLayout stage = vertical();
        LinearLayout.LayoutParams stageSize = new LinearLayout.LayoutParams(0, -1, .58f);
        stageSize.leftMargin = dp(12); body.addView(stage, stageSize);
        video = new FrameLayout(this); video.setBackgroundColor(0xff171719);
        stage.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        mainSurface = new PlayerView(this); mainSurface.setUseController(false);
        mainSurface.setFocusable(false); mainSurface.setKeepContentOnPlayerReset(false);
        video.addView(mainSurface, new FrameLayout.LayoutParams(-1, -1));
        mainMessage = text("", 16, TEXT); mainMessage.setGravity(Gravity.CENTER);
        video.addView(mainMessage, new FrameLayout.LayoutParams(-1, -1));
        inset = new FrameLayout(this); inset.setBackground(box(0xff171719, ACCENT, 0));
        inset.setPadding(dp(2), dp(2), dp(2), dp(2));
        insetSurface = (PlayerView) getLayoutInflater().inflate(R.layout.inset_player, inset, false);
        insetSurface.setFocusable(false);
        inset.addView(insetSurface, new FrameLayout.LayoutParams(-1, -1));
        insetMessage = text("", 12, TEXT); insetMessage.setGravity(Gravity.CENTER);
        inset.addView(insetMessage, new FrameLayout.LayoutParams(-1, -1));
        video.addView(inset, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.END));
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r-l == or-ol && b-t == ob-ot) return;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) inset.getLayoutParams();
            p.width = (r-l) / 2; p.height = (b-t) / 2;
            p.rightMargin = dp(10); p.bottomMargin = dp(fullscreen ? 56 : 10);
            inset.setLayoutParams(p);
        });

        selectedName = text("Select a channel", 17, TEXT);
        selectedName.setSingleLine(); selectedName.setEllipsize(TextUtils.TruncateAt.END);
        selectedName.setPadding(0, dp(7), 0, 0); stage.addView(selectedName);
        programText = text("", 13, DIM); programText.setMaxLines(2); programText.setEllipsize(TextUtils.TruncateAt.END);
        stage.addView(programText, new LinearLayout.LayoutParams(-1, dp(37)));
        controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        watchButton = button("Watch", () -> {
            if (player.primary() != null) setFullscreen(true);
        }); addButton(controls, watchButton);
        insetButton = button("Add inset", () -> tune(true)); addButton(controls, insetButton);
        swapButton = button("Swap", () -> player.swap()); addButton(controls, swapButton);
        closeInset = button("Close inset", () -> player.stopInset()); addButton(controls, closeInset);
        starButton = button("☆", this::favoriteSelected); addButton(controls, starButton);
        stage.addView(controls);

        watchBar = new LinearLayout(this); watchBar.setGravity(Gravity.CENTER_VERTICAL);
        watchBar.setBackgroundColor(0xdf2b2b2b); watchBar.setPadding(dp(12), 0, dp(8), 0);
        watchTitle = text("", 16, TEXT); watchTitle.setSingleLine(); watchTitle.setEllipsize(TextUtils.TruncateAt.END);
        watchBar.addView(watchTitle, new LinearLayout.LayoutParams(0, -2, 1));
        watchStatus = text("", 12, DIM); watchBar.addView(watchStatus);
        addButton(watchBar, button("Channels", () -> setFullscreen(false)));
        watchSwap = button("Swap", () -> player.swap()); addButton(watchBar, watchSwap);
        watchClose = button("Close inset", () -> player.stopInset()); addButton(watchBar, watchClose);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
        video.addView(watchBar, bottom); watchBar.setVisibility(View.GONE);

        player = new Playback(this, mainSurface, insetSurface, this::updatePlayback);
        setContentView(root);
        queryButton.requestFocusFromTouch();
        updateSelection();
        updatePlayback();
        loadCatalog(true);
    }

    private void loadCatalog(boolean reset) {
        if (api == null || catalogBusy) return;
        Jellyfin server = api;
        long generation = ++catalogGeneration;
        int start = reset ? 0 : offset;
        List<Channels.Channel> previous = reset ? List.of() : all;
        catalogBusy = true; loadProblem = null;
        if (reset) {
            all = List.of(); offset = 0; expectedCount = 0; rebuildFavorites(); renderFilter(false);
        }
        updateCount();
        catalog.execute(() -> {
            var accumulated = new LinkedHashMap<String, Channels.Channel>();
            for (Channels.Channel c : previous) accumulated.put(c.id(), c);
            int cursor = start, total = -1, pages = 0;
            String error = null;
            try {
                while (catalogGeneration == generation) {
                    Jellyfin.Page page = server.channels(cursor, PAGE);
                    if (catalogGeneration != generation) return;
                    total = page.total();
                    int priorSize = accumulated.size();
                    for (Channels.Channel c : page.channels()) accumulated.put(c.id(), c);
                    cursor += page.consumed();
                    boolean done = page.consumed() == 0 || (total >= 0 ? cursor >= total : page.consumed() < PAGE);
                    if (!done && accumulated.size() == priorSize && page.consumed() > 0) {
                        throw new java.io.IOException("Channel paging did not advance.");
                    }
                    if (done || pages++ % 3 == 0) publishCatalog(server, generation, accumulated, cursor, total, done, null);
                    if (done) return;
                }
                return;
            } catch (Exception failure) {
                error = failure instanceof Jellyfin.HttpFailure http && http.status == 403
                        ? "This account cannot access Live TV."
                        : failure instanceof Jellyfin.HttpFailure http && http.status == 401
                        ? "Sign-in expired. Open Server to reconnect."
                        : "Could not load channels. Open Server to retry.";
            }
            publishCatalog(server, generation, accumulated, cursor, total, true, error);
        });
    }

    private void publishCatalog(Jellyfin server, long generation, Map<String, Channels.Channel> channels,
                                int cursor, int total, boolean done, String error) {
        List<Channels.Channel> snapshot = List.copyOf(channels.values());
        ui.post(() -> {
            if (destroyed || api != server || catalogGeneration != generation) return;
            all = snapshot; offset = cursor; expectedCount = total;
            catalogBusy = !done; loadProblem = error;
            rebuildFavorites(); renderFilter(false);
        });
    }

    private void rebuildFavorites() {
        HashSet<String> set = new HashSet<>();
        for (Channels.Channel c : all) if (favoriteOverrides.getOrDefault(c.id(), c.favorite())) set.add(c.id());
        favorites = Set.copyOf(set);
    }

    private void renderFilter(boolean resetPosition) {
        resetSelection |= resetPosition;
        int generation = ++visibleGeneration;
        List<Channels.Channel> channels = all;
        Set<String> stars = favorites;
        boolean starred = favoritesOnly;
        String term = query;
        queryButton.setText(term.isBlank() ? "Search" : "Search •");
        filterButton.setText(starred ? "Show all" : "Favorites");
        search.execute(() -> {
            if (generation != visibleGeneration) return;
            List<Channels.Channel> filtered = Channels.filter(channels, stars, starred, term);
            Map<String, Integer> index = Channels.jumpTargets(filtered);
            ui.post(() -> {
                if (destroyed || api == null || generation != visibleGeneration) return;
                adapter.submitList(filtered, () -> {
                    if (destroyed || generation != visibleGeneration) return;
                    jumps = index;
                    int position = selected == null ? -1 : Channels.indexOf(filtered, selected.id());
                    if (filtered.isEmpty()) {
                        selected = null; pendingJump = null; focusedPosition = 0;
                    } else if (resetSelection || pendingJump != null || (position < 0 && list.hasFocus())) {
                        jump(resetSelection ? 0 : position >= 0 ? position
                                : Channels.jumpPage(focusedPosition, filtered.size(), 0));
                    } else {
                        focusedPosition = position >= 0 ? position : Channels.jumpPage(focusedPosition, filtered.size(), 0);
                        selected = filtered.get(focusedPosition);
                    }
                    resetSelection = false;
                    updateCount();
                    updateSelection();
                    scheduleGuide();
                });
            });
        });
    }

    private void updateCount() {
        int n = adapter.getItemCount();
        String label = n + (favoritesOnly ? (n == 1 ? " favorite" : " favorites") : (n == 1 ? " channel" : " channels"));
        if (catalogBusy) label += " · loading " + all.size() + (expectedCount > 0 ? "/" + expectedCount : "");
        else if (loadProblem != null && n > 0) label += " · partial list";
        count.setText(label);
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        empty.setText(loadProblem != null ? loadProblem : catalogBusy ? "Loading channels…"
                : favoritesOnly ? "No matching favorites" : query.isBlank() ? "No channels available" : "No matching channels");
    }

    private void searchDialog() {
        EditText field = edit("Channel name or number", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(query); field.setSelectAllOnFocus(true);
        LinearLayout wrapper = vertical(); wrapper.setPadding(dp(18), 0, dp(18), 0); wrapper.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Find a channel").setView(wrapper)
                .setPositiveButton("Search", (d, w) -> applySearch(field.getText().toString()))
                .setNeutralButton("Clear", (d, w) -> applySearch(""))
                .setNegativeButton("Cancel", null).create();
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                applySearch(field.getText().toString()); dialog.dismiss(); return true;
            }
            return false;
        });
        dialog.show(); field.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void applySearch(String value) { query = value.trim(); renderFilter(true); }

    private void jumpDialog() {
        if (adapter.getItemCount() == 0) return;
        List<String> labels = new ArrayList<>(List.of("First channel", "Back 50", "Forward 50", "Last channel"));
        labels.addAll(jumps.keySet());
        new AlertDialog.Builder(this).setTitle("Jump to channel").setItems(labels.toArray(new String[0]), (d, i) -> {
            if (i == 0) jump(0);
            else if (i == 1) page(-50);
            else if (i == 2) page(50);
            else if (i == 3) jump(adapter.getItemCount() - 1);
            else {
                Integer position = jumps.get(labels.get(i));
                if (position != null) jump(position);
            }
        }).show();
    }

    private void page(int delta) { jump(Channels.jumpPage(focusedPosition, adapter.getItemCount(), delta)); }

    private void jump(int index) {
        if (adapter.getItemCount() == 0 || index < 0 || index >= adapter.getItemCount()) return;
        focusedPosition = index;
        selected = adapter.getCurrentList().get(index);
        updateSelection();
        // Park focus outside the recycled rows until the target has been laid out.
        // Otherwise RecyclerView can scroll the previously focused row back into view.
        pendingJump = selected.id();
        list.stopScroll();
        if (list.hasFocus()) queryButton.requestFocus();
        rows.scrollToPositionWithOffset(index, 0);
        scheduleGuide();
    }

    private void updateSelection() {
        if (api == null || selectedName == null) return;
        selectedName.setText(selected == null ? "Select a channel" : selected.label());
        programText.setText(selected == null ? "Select to watch · hold Select for channel actions" : guideLine(selected.id(), true));
        starButton.setText(selected != null && favorites.contains(selected.id()) ? "★" : "☆");
        starButton.setContentDescription(selected != null && favorites.contains(selected.id()) ? "Remove favorite" : "Add favorite");
        starButton.setEnabled(selected != null && !pendingFavorites.contains(selected.id()));
        insetButton.setEnabled(selected != null && player != null && player.primary() != null
                && !player.primary().id().equals(selected.id()));
    }

    private void favoriteSelected() {
        if (selected == null || api == null) return;
        Channels.Channel channel = selected;
        Jellyfin server = api;
        if (!pendingFavorites.add(channel.id())) return;
        boolean value = !favorites.contains(channel.id());
        updateSelection();
        accounts.execute(() -> {
            boolean ok;
            try { server.favorite(channel.id(), value); ok = true; }
            catch (Exception failure) { ok = false; }
            boolean saved = ok;
            ui.post(() -> {
                if (destroyed || api != server) return;
                pendingFavorites.remove(channel.id());
                if (saved) {
                    favoriteOverrides.put(channel.id(), value); rebuildFavorites();
                    if (favoritesOnly) renderFilter(false);
                    else refreshGuideRows();
                } else toast("Could not update favorite.");
                updateSelection();
            });
        });
    }

    private void channelActions(Channels.Channel channel) {
        int position = Channels.indexOf(adapter.getCurrentList(), channel.id());
        if (position >= 0) focusedPosition = position;
        selected = channel; updateSelection();
        boolean canInset = player.primary() != null && !player.primary().id().equals(channel.id());
        List<String> entries = new ArrayList<>(List.of("Watch", favorites.contains(channel.id()) ? "Remove favorite" : "Add favorite", "Programme guide"));
        if (canInset) entries.add("Play in inset");
        entries.add("Try compatible stream");
        new AlertDialog.Builder(this).setTitle(channel.label()).setItems(entries.toArray(new String[0]), (d, i) -> {
            if (i == 0) tune(false);
            else if (i == 1) favoriteSelected();
            else if (i == 2) scheduleDialog();
            else if (canInset && i == 3) tune(true);
            else player.tuneCompatible(api, channel);
        }).show();
    }

    private void tune(boolean small) {
        if (selected == null || api == null) return;
        if (small && player.primary() == null) small = false;
        player.tune(api, selected, small);
    }

    private void updatePlayback() {
        if (api == null || player == null) return;
        Channels.Channel main = player.primary(), small = player.secondary();
        String status = player.primaryStatus(), smallStatus = player.secondaryStatus();
        boolean mainLive = status.startsWith("LIVE");
        mainMessage.setText(main == null ? "Choose a channel to watch" : main.label() + "\n" + status);
        mainMessage.setVisibility(mainLive ? View.GONE : View.VISIBLE);
        inset.setVisibility(small == null ? View.GONE : View.VISIBLE);
        insetMessage.setText(small == null ? "" : small.label() + "\n" + smallStatus);
        insetMessage.setVisibility(smallStatus.startsWith("LIVE") ? View.GONE : View.VISIBLE);
        watchTitle.setText(main == null ? "" : main.label()
                + (small == null ? "" : "   ·   Inset: " + small.label()));
        watchStatus.setText(main == null ? "" : status + "   ");
        watchButton.setEnabled(main != null);
        swapButton.setVisibility(small == null ? View.GONE : View.VISIBLE);
        closeInset.setVisibility(small == null ? View.GONE : View.VISIBLE);
        watchSwap.setVisibility(small == null ? View.GONE : View.VISIBLE);
        watchClose.setVisibility(small == null ? View.GONE : View.VISIBLE);
        if (root != null) root.setKeepScreenOn(foreground && player.hasVideo());
        updateSelection();
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled || video == null) return;
        fullscreen = enabled;
        ViewGroup previous = (ViewGroup) video.getParent();
        if (enabled) {
            previous.removeView(video); shell.setVisibility(View.GONE);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));
            watchBar.setVisibility(View.VISIBLE);
            watchBar.getChildAt(2).requestFocus();
        } else {
            root.removeView(video);
            // Stage is the second child of the channel browser row.
            LinearLayout stage = (LinearLayout) body.getChildAt(1);
            stage.addView(video, 0, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.setVisibility(View.VISIBLE); watchBar.setVisibility(View.GONE);
            jump(Channels.jumpPage(focusedPosition, adapter.getItemCount(), 0));
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) inset.getLayoutParams();
        p.bottomMargin = dp(fullscreen ? 56 : 10); inset.setLayoutParams(p);
        scheduleGuide();
    }

    private void scheduleGuide() {
        ui.removeCallbacks(guideRefresh);
        if (foreground && !fullscreen && api != null) ui.postDelayed(guideRefresh, 220);
    }

    private void visibleGuide() {
        if (api == null || fullscreen || guideBusy || !foreground) return;
        long elapsed = SystemClock.elapsedRealtime();
        if (elapsed < noGuideUntil || elapsed < guideBackoffUntil) return;
        int first = rows.findFirstVisibleItemPosition(), last = rows.findLastVisibleItemPosition();
        if (first < 0 || last < first) return;
        long window = GuideCache.window(System.currentTimeMillis());
        List<String> ids = new ArrayList<>(GUIDE_BATCH);
        List<Channels.Channel> snapshot = adapter.getCurrentList();
        for (int i = first; i <= last && i < snapshot.size() && ids.size() < GUIDE_BATCH; i++) {
            String id = snapshot.get(i).id();
            if (epg.get(window, id, elapsed) == null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        boolean probe = elapsed >= guideCheckedUntil;
        Jellyfin server = api;
        guideBusy = true;
        guide.execute(() -> {
            try {
                boolean available = !probe || server.hasGuide(window, GUIDE_HOURS);
                Map<String, List<Channels.Program>> result = available ? server.programs(ids, window, GUIDE_HOURS) : Map.of();
                ui.post(() -> {
                    if (destroyed || api != server) return;
                    guideBusy = false;
                    long time = SystemClock.elapsedRealtime();
                    if (probe) guideCheckedUntil = time + GuideCache.FRESH;
                    if (!available) noGuideUntil = time + GuideCache.FRESH;
                    else result.forEach((id, programs) -> epg.put(window, id, programs, time));
                    refreshGuideRows();
                    if (available) scheduleGuide();
                });
            } catch (Exception failure) {
                ui.post(() -> {
                    if (destroyed || api != server) return;
                    guideBusy = false; guideBackoffUntil = SystemClock.elapsedRealtime() + 15_000;
                    refreshGuideRows();
                });
            }
        });
    }

    private void refreshGuideRows() {
        if (adapter == null || rows == null) return;
        int first = rows.findFirstVisibleItemPosition(), last = rows.findLastVisibleItemPosition();
        if (first >= 0 && last >= first && last < adapter.getItemCount()) {
            adapter.notifyItemRangeChanged(first, last - first + 1, "guide");
        }
        updateSelection();
    }

    private String guideLine(String id, boolean details) {
        long time = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        List<Channels.Program> schedule = epg.get(GuideCache.window(time), id, elapsed);
        if (schedule == null) {
            if (elapsed < noGuideUntil) return "No programme guide from server";
            if (elapsed < guideBackoffUntil) return "Guide temporarily unavailable";
            return details ? "Guide · select a channel to watch" : "—";
        }
        Channels.Program current = Channels.now(schedule, time), next = Channels.next(schedule, time);
        if (current == null && next == null) return "No programme data";
        String info = current == null ? "Next " + clock.format(new Date(next.start())) + " · " + next.title()
                : "Now · " + current.title();
        return details && current != null && next != null ? info + "\nNext " + clock.format(new Date(next.start())) + " · " + next.title() : info;
    }

    private void scheduleDialog() {
        if (selected == null || api == null) { toast("Select a channel first."); return; }
        Channels.Channel channel = selected;
        Jellyfin server = api;
        long initial = GuideCache.window(System.currentTimeMillis());
        long[] window = { initial };
        LinearLayout content = vertical(); content.setPadding(dp(18), dp(5), dp(18), dp(10));
        TextView caption = text("", 14, ACCENT); content.addView(caption);
        TextView entries = text("", 15, TEXT);
        ScrollView scroll = new ScrollView(this); scroll.addView(entries);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(153)));
        LinearLayout actions = new LinearLayout(this); content.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(channel.label()).setView(content)
                .setPositiveButton("Close", null).create();
        Runnable load = () -> {
            long from = window[0];
            caption.setText(clock.format(new Date(from)) + " – " + clock.format(new Date(from + GUIDE_HOURS * 3_600_000L)));
            List<Channels.Program> cached = epg.get(from, channel.id(), SystemClock.elapsedRealtime());
            if (cached != null) { entries.setText(scheduleText(cached, from)); return; }
            entries.setText("Loading guide…");
            guide.execute(() -> {
                try {
                    List<Channels.Program> result = server.programs(List.of(channel.id()), from, GUIDE_HOURS).get(channel.id());
                    ui.post(() -> {
                        if (destroyed || server != api) return;
                        epg.put(from, channel.id(), result, SystemClock.elapsedRealtime());
                        if (dialog.isShowing() && window[0] == from) entries.setText(scheduleText(result, from));
                    });
                } catch (Exception error) {
                    ui.post(() -> { if (dialog.isShowing() && window[0] == from) entries.setText("Guide request failed."); });
                }
            });
        };
        addButton(actions, button("Earlier", () -> { window[0] = Math.max(initial, window[0] - 10_800_000L); load.run(); }));
        addButton(actions, button("Later", () -> {
            window[0] = Math.min(initial + 7 * 86_400_000L, window[0] + 10_800_000L); load.run();
        }));
        dialog.show(); load.run();
    }

    private String scheduleText(List<Channels.Program> entries, long from) {
        StringBuilder result = new StringBuilder();
        for (Channels.Program p : entries) {
            if (p.end() <= from) continue;
            result.append(clock.format(new Date(p.start()))).append("   ").append(p.title()).append('\n');
        }
        return result.length() == 0 ? "No programme data for this time." : result.toString();
    }

    private void serverDialog() {
        if (api == null) return;
        new AlertDialog.Builder(this).setTitle("Jellyfin server").setMessage(api.session.server())
                .setPositiveButton("Refresh channels", (d, w) -> {
                    epg.clear(); noGuideUntil = 0; guideCheckedUntil = 0; loadCatalog(offset == 0 || loadProblem == null);
                })
                .setNeutralButton("Disconnect", (d, w) -> disconnect())
                .setNegativeButton("Close", null).show();
    }

    private void disconnect() {
        ++catalogGeneration; ++visibleGeneration;
        player.destroy(); player = null;
        api = null; selected = null; resumeMain = null; resumeInset = null;
        all = List.of(); favorites = Set.of(); favoriteOverrides.clear(); pendingFavorites.clear();
        pendingJump = null; focusedPosition = 0; resetSelection = false;
        query = ""; favoritesOnly = false; fullscreen = false; catalogBusy = false; guideBusy = false;
        noGuideUntil = 0; guideCheckedUntil = 0; guideBackoffUntil = 0;
        epg.clear(); store.clear(); showLogin("");
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onStart() {
        super.onStart(); foreground = true;
        if (api != null && player != null) {
            if (resumeMain != null) player.tune(api, resumeMain, false);
            if (resumeInset != null && resumeMain != null) player.tune(api, resumeInset, true);
            resumeMain = null; resumeInset = null;
            scheduleGuide();
        }
        ui.postDelayed(minute, 30_000);
    }

    @Override protected void onStop() {
        foreground = false; ui.removeCallbacks(minute); ui.removeCallbacks(guideRefresh);
        if (player != null) {
            resumeMain = player.primary(); resumeInset = player.secondary();
            player.stopAll();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true; ++catalogGeneration; ++visibleGeneration;
        if (player != null) player.destroy();
        accounts.shutdownNow(); catalog.shutdownNow(); search.shutdownNow(); guide.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        return handleRemoteKey(event) || super.onKeyDown(key, event);
    }

    private static boolean isPageKey(int key) {
        return key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP;
    }

    private boolean handleRemoteKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && api != null && !fullscreen) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                page(50); return true;
            }
            if (key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                page(-50); return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                if (selected != null) channelActions(selected); else jumpDialog();
                return true;
            }
            if (key == KeyEvent.KEYCODE_GUIDE) { scheduleDialog(); return true; }
            if (key == KeyEvent.KEYCODE_SEARCH) { searchDialog(); return true; }
        }
        return false;
    }

    private void back() {
        if (fullscreen) { setFullscreen(false); return; }
        if (!query.isBlank()) { applySearch(""); return; }
        if (player != null && player.primary() != null) { player.stopAll(); return; }
        finish();
    }

    private final class ChannelAdapter extends ListAdapter<Channels.Channel, ChannelRow> {
        ChannelAdapter() {
            super(new DiffUtil.ItemCallback<>() {
                @Override public boolean areItemsTheSame(Channels.Channel a, Channels.Channel b) { return a.id().equals(b.id()); }
                @Override public boolean areContentsTheSame(Channels.Channel a, Channels.Channel b) { return a.equals(b); }
            });
        }
        @Override public ChannelRow onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = vertical();
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(56)));
            row.setPadding(dp(12), dp(6), dp(8), dp(5)); row.setBackground(focusBackground(PANEL));
            row.setFocusable(true); row.setClickable(true);
            TextView title = text("", 15, TEXT); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
            TextView info = text("", 12, DIM); info.setSingleLine(); info.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title); row.addView(info);
            ChannelRow holder = new ChannelRow(row, title, info);
            row.setOnFocusChangeListener((view, focused) -> {
                int p = holder.getBindingAdapterPosition();
                if (focused && p != RecyclerView.NO_POSITION) {
                    if (pendingJump != null && !pendingJump.equals(getItem(p).id())) return;
                    focusedPosition = p; selected = getItem(p); updateSelection(); scheduleGuide();
                }
            });
            row.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) {
                    pendingJump = null;
                    focusedPosition = p; selected = getItem(p); updateSelection(); tune(false);
                }
            });
            row.setOnLongClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return false;
                channelActions(getItem(p)); return true;
            });
            return holder;
        }

        @Override public void onBindViewHolder(ChannelRow holder, int position) {
            Channels.Channel channel = getItem(position);
            holder.title.setText((favorites.contains(channel.id()) ? "★  " : "") + channel.label());
            holder.guide.setText(guideLine(channel.id(), false));
        }

        @Override public void onBindViewHolder(ChannelRow holder, int position, List<Object> payloads) {
            onBindViewHolder(holder, position);
        }
    }

    private static final class ChannelRow extends RecyclerView.ViewHolder {
        final TextView title, guide;
        ChannelRow(View item, TextView title, TextView guide) { super(item); this.title = title; this.guide = guide; }
    }
}
