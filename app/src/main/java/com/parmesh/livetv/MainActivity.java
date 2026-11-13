package com.parmesh.livetv;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
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
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
import java.util.concurrent.Future;
import java.util.function.Consumer;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends ComponentActivity {
    static final int BG = 0xff252629, PANEL = 0xff303237, EDGE = 0xff45484f;
    private static final int BAR = 0xff40434a, BLUE = 0xff3d526e;
    static final int TEXT = 0xffececf0, DIM = 0xffb8bbc5, ACCENT = 0xffffc66d, PRIMARY = 0xff4a576c;
    private static final int PAGE = 256, GUIDE_BATCH = 16;
    private static final int GUIDE_HOURS = 3;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService accounts = Executors.newSingleThreadExecutor();
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final ExecutorService search = Executors.newSingleThreadExecutor();
    private final ExecutorService guide = Executors.newSingleThreadExecutor();
    private final GuideCache epg = new GuideCache(384);
    private record FavoriteEdit(boolean value, long revision) {}
    private final Map<String, FavoriteEdit> favoriteOverrides = new HashMap<>();
    private long favoriteRevision;
    private final Set<String> pendingFavorites = new HashSet<>();
    private final DateFormat clock = DateFormat.getTimeInstance(DateFormat.SHORT);
    private final DateFormat guideDate = DateFormat.getDateInstance(DateFormat.MEDIUM);
    private SessionStore store;
    private SignInScreen signIn;
    private Jellyfin api;
    private Playback player;
    private List<Channels.Channel> all = List.of();
    private Set<String> favorites = Set.of();
    private Map<String, Integer> jumps = Map.of();
    private Channels.Channel selected;
    private Channels.Channel resumeMain, resumeInset;
    private String query = "";
    private String loadProblem;
    private boolean favoritesOnly, catalogBusy, guideBusy, fullscreen, foreground, destroyed, signingOut;
    private volatile int visibleGeneration;
    private int focusedPosition;
    private boolean resetSelection;
    private String pendingJump;
    private long guideWindow = Long.MIN_VALUE;
    private long noGuideUntil, guideCheckedUntil, guideBackoffUntil;
    private int guideGeneration;
    private volatile long catalogGeneration;
    private FrameLayout root, video, inset;
    private PlayerView mainSurface, insetSurface;
    private LinearLayout shell, stage, emptyState, pager;
    private HorizontalScrollView watchOverlay;
    private int revealKey = KeyEvent.KEYCODE_UNKNOWN;
    private TextView count, empty, selectedName, programText, mainMessage, insetMessage, watchTitle, watchStatus;
    private Button filterButton, queryButton, jumpButton, guideButton, watchButton, insetButton, swapButton, closeInset, starButton;
    private Button watchSwap, watchClose, watchRetry;
    private Button returnToChannels, emptyAction;
    private Consumer<Boolean> pendingNetworkAction;
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                var action = pendingNetworkAction;
                pendingNetworkAction = null;
                if (action != null && !destroyed) action.accept(granted);
            });
    private RecyclerView list;
    private LinearLayoutManager rows;
    private ChannelAdapter adapter;

    private final Runnable hideWatchControls = () -> {
        if (!fullscreen || !foreground || player == null || !player.primaryStatus().live()
                || player.secondaryStatus().failed() || !hasWindowFocus()) return;
        video.requestFocus();
        watchOverlay.setVisibility(View.GONE);
    };
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
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        store = new SessionStore(this);
        TextView opening = text(getString(R.string.opening), 19, TEXT);
        opening.setGravity(Gravity.CENTER);
        setContentView(opening);
        accounts.execute(() -> {
            Jellyfin.Session saved = store.read();
            ui.post(() -> {
                if (destroyed) return;
                if (saved == null) showLogin("");
                else requestLocalNetwork(granted -> {
                    if (granted) connected(saved);
                    else showLogin(getString(R.string.local_network_access));
                });
            });
        });
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    boolean needsLocalNetworkPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
                && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED;
    }

    void requestLocalNetwork(Consumer<Boolean> next) {
        if (needsLocalNetworkPermission()) {
            pendingNetworkAction = next;
            networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
        } else next.accept(true);
    }

    void showScreen(FrameLayout screen) {
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            var safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        setContentView(screen);
    }

    private HorizontalScrollView scrollBar(LinearLayout row) {
        HorizontalScrollView bar = new HorizontalScrollView(this);
        bar.setFillViewport(true);
        bar.setHorizontalScrollBarEnabled(false);
        bar.addView(row);
        return bar;
    }

    private void returnToSelectedChannel() {
        if (adapter.getItemCount() == 0) queryButton.requestFocusFromTouch();
        else jump(Channels.jumpPage(focusedPosition, adapter.getItemCount(), 0));
    }

    View defaultBrowserFocus() {
        if (fullscreen) return returnToChannels;
        return queryButton;
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    GradientDrawable box(int color, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(2), border);
        return shape;
    }

    StateListDrawable focusBackground(int normal) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, box(BG, 0, 5));
        states.addState(new int[]{android.R.attr.state_focused}, box(BLUE, ACCENT, 5));
        states.addState(new int[]{android.R.attr.state_pressed}, box(BLUE, ACCENT, 5));
        states.addState(new int[0], box(normal, 0, 5));
        return states;
    }

    Button button(int label, Runnable action) {
        return button(getString(label), action);
    }

    Button button(String label, Runnable action) {
        Button v = new Button(this);
        v.setText(label); v.setTextSize(15); v.setAllCaps(false);
        v.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{0xff858991, TEXT}));
        v.setBackground(focusBackground(BAR)); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setMinHeight(0); v.setMinimumHeight(0);
        v.setPadding(dp(12), 0, dp(12), 0); v.setFocusable(true);
        if (action != null) v.setOnClickListener(view -> action.run());
        return v;
    }

    private void addButton(LinearLayout parent, Button v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.rightMargin = dp(8); parent.addView(v, lp);
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }

    private void showLogin(String message) {
        if (signIn == null) signIn = new SignInScreen(this, accounts, store.device(), store.address());
        signIn.show(message, signingOut);
    }

    void signedIn(Jellyfin.Session session) {
        signIn.cancel();
        signIn = null;
        accounts.execute(() -> {
            boolean persisted;
            try { store.save(session); persisted = true; }
            catch (Exception failure) { persisted = false; }
            boolean saved = persisted;
            ui.post(() -> {
                if (destroyed) return;
                connected(session);
                if (!saved) toast(getString(R.string.sign_in_not_saved));
            });
        });
    }

    EditText edit(String hint, int type) {
        EditText field = new EditText(this);
        field.setSingleLine(); field.setInputType(type); field.setHint(hint); field.setTextSize(16);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextColor(TEXT); field.setHintTextColor(DIM);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(focusBackground(BG));
        return field;
    }

    private void connected(Jellyfin.Session session) {
        api = new Jellyfin(session);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        shell = vertical();
        shell.setPadding(dp(30), dp(16), dp(30), dp(20));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text(getString(R.string.app_name), 22, TEXT);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setSingleLine();
        brand.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        queryButton = button(R.string.search, this::searchDialog); addButton(header, queryButton);
        filterButton = button(R.string.favorites, () -> {
            favoritesOnly = !favoritesOnly; renderFilter(true);
        }); addButton(header, filterButton);
        jumpButton = button(R.string.jump, this::jumpDialog);
        guideButton = button(R.string.guide, this::scheduleDialog);
        addButton(header, jumpButton);
        addButton(header, guideButton);
        addButton(header, button(R.string.server, this::serverDialog));
        shell.addView(scrollBar(header), new LinearLayout.LayoutParams(-1, dp(50)));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1);
        content.topMargin = dp(8); shell.addView(body, content);
        LinearLayout browser = vertical(); browser.setBackground(box(PANEL, EDGE, 12));
        body.addView(browser, new LinearLayout.LayoutParams(0, -1, .42f));
        count = text("", 15, DIM);
        count.setSingleLine();
        count.setEllipsize(TextUtils.TruncateAt.END);
        count.setPadding(dp(12), dp(8), dp(4), dp(8)); browser.addView(count);
        FrameLayout listArea = new FrameLayout(this);
        browser.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new RecyclerView(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP && isPageKey(event.getKeyCode())) return true;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                        && watchButton != null && watchButton.isEnabled()) {
                    return watchButton.requestFocus() || super.dispatchKeyEvent(event);
                }
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
        emptyState = vertical();
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(20), dp(12), dp(20), dp(12));
        empty = text("", 17, DIM);
        empty.setGravity(Gravity.CENTER);
        emptyState.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        emptyAction = button(R.string.clear_search, () -> {
            if (loadProblem != null) loadCatalog();
            else if (!query.isBlank()) applySearch("");
            else { favoritesOnly = false; renderFilter(true); }
        });
        emptyAction.setId(View.generateViewId());
        LinearLayout.LayoutParams recovery = new LinearLayout.LayoutParams(-2, dp(44));
        recovery.topMargin = dp(18);
        emptyState.addView(emptyAction, recovery);
        listArea.addView(emptyState, new FrameLayout.LayoutParams(-1, -1));
        pager = new LinearLayout(this);
        pager.setPadding(dp(8), dp(3), dp(4), dp(5));
        Button previous = button("−50", () -> page(-50));
        previous.setContentDescription(getString(R.string.skip_backward));
        addButton(pager, previous);
        Button next = button("+50", () -> page(50));
        next.setContentDescription(getString(R.string.skip_forward));
        addButton(pager, next);
        TextView channelHelp = text(getString(R.string.channel_actions_hint), 14, DIM);
        channelHelp.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        channelHelp.setSingleLine();
        channelHelp.setEllipsize(TextUtils.TruncateAt.END);
        channelHelp.setPadding(0, 0, dp(8), 0);
        pager.addView(channelHelp, new LinearLayout.LayoutParams(0, -1, 1));
        browser.addView(pager);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { scheduleGuide(); }
        });
        queryButton.setNextFocusDownId(list.getId());
        filterButton.setNextFocusDownId(list.getId());

        stage = vertical();
        LinearLayout.LayoutParams stageSize = new LinearLayout.LayoutParams(0, -1, .58f);
        stageSize.leftMargin = dp(12); body.addView(stage, stageSize);
        video = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return handleWatchKey(event) || super.dispatchKeyEvent(event);
            }
        };
        video.setBackground(box(0xff171719, 0, 12));
        video.setOnClickListener(view -> {
            if (fullscreen) showWatchControls();
        });
        video.setFocusable(false);
        stage.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        mainSurface = new PlayerView(this); mainSurface.setUseController(false);
        mainSurface.setFocusable(false); mainSurface.setKeepContentOnPlayerReset(false);
        video.addView(mainSurface, new FrameLayout.LayoutParams(-1, -1));
        mainMessage = text("", 18, TEXT);
        mainMessage.setGravity(Gravity.CENTER);
        mainMessage.setPadding(dp(22), dp(12), dp(22), dp(12));
        video.addView(mainMessage, new FrameLayout.LayoutParams(-1, -1));
        inset = new FrameLayout(this); inset.setBackground(box(0xff171719, ACCENT, 0));
        inset.setContentDescription(getString(R.string.muted_inset));
        inset.setPadding(dp(2), dp(2), dp(2), dp(2));
        insetSurface = (PlayerView) getLayoutInflater().inflate(R.layout.inset_player, inset, false);
        insetSurface.setFocusable(false);
        inset.addView(insetSurface, new FrameLayout.LayoutParams(-1, -1));
        insetMessage = text("", 14, TEXT); insetMessage.setGravity(Gravity.CENTER);
        inset.addView(insetMessage, new FrameLayout.LayoutParams(-1, -1));
        video.addView(inset, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.END));
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r-l == or-ol && b-t == ob-ot) return;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) inset.getLayoutParams();
            p.width = (r-l) / 2; p.height = (b-t) / 2;
            p.rightMargin = dp(12); p.bottomMargin = dp(fullscreen ? 64 : 12);
            inset.setLayoutParams(p);
        });

        selectedName = text("", 20, TEXT);
        selectedName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        selectedName.setSingleLine(); selectedName.setEllipsize(TextUtils.TruncateAt.END);
        selectedName.setPadding(0, dp(7), 0, 0); stage.addView(selectedName);
        programText = text("", 15, DIM); programText.setMaxLines(2); programText.setEllipsize(TextUtils.TruncateAt.END);
        stage.addView(programText, new LinearLayout.LayoutParams(-1, dp(43)));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        watchButton = button(R.string.play_channel, () -> {
            if (selected == null) return;
            if (!isPlayingSelection() || player.primaryStatus().failed()) tune(false);
            else setFullscreen(true);
        }); addButton(controls, watchButton);
        watchButton.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            returnToSelectedChannel();
            return true;
        });
        insetButton = button(R.string.play_in_inset, () -> tune(true)); addButton(controls, insetButton);
        swapButton = button(R.string.swap, () -> player.swap()); addButton(controls, swapButton);
        closeInset = button(R.string.close_inset, () -> closeInset(watchButton)); addButton(controls, closeInset);
        starButton = button("☆", this::favoriteSelected); addButton(controls, starButton);
        stage.addView(scrollBar(controls));

        shell.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol) return;
            boolean compact = r - l < dp(700);
            body.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            browser.setLayoutParams(compact ? new LinearLayout.LayoutParams(-1, 0, .58f)
                    : new LinearLayout.LayoutParams(0, -1, .42f));
            LinearLayout.LayoutParams size = compact ? new LinearLayout.LayoutParams(-1, 0, .42f)
                    : new LinearLayout.LayoutParams(0, -1, .58f);
            if (compact) size.topMargin = dp(8); else size.leftMargin = dp(12);
            stage.setLayoutParams(size);
        });

        LinearLayout watchBar = new LinearLayout(this); watchBar.setGravity(Gravity.CENTER_VERTICAL);
        watchBar.setBackgroundColor(0xdf2b2b2b); watchBar.setPadding(dp(12), 0, dp(8), 0);
        watchTitle = text("", 16, TEXT); watchTitle.setSingleLine(); watchTitle.setEllipsize(TextUtils.TruncateAt.END);
        watchBar.addView(watchTitle, new LinearLayout.LayoutParams(0, -2, 1));
        watchStatus = text("", 14, DIM);
        watchStatus.setSingleLine();
        watchStatus.setEllipsize(TextUtils.TruncateAt.END);
        watchStatus.setMaxWidth(dp(155));
        watchStatus.setPadding(dp(8), 0, dp(12), 0);
        watchBar.addView(watchStatus);
        returnToChannels = button(R.string.channels, () -> setFullscreen(false));
        addButton(watchBar, returnToChannels);
        watchRetry = button(R.string.retry, () -> player.tune(api, player.primary(), false));
        addButton(watchBar, watchRetry);
        watchSwap = button(R.string.swap, () -> player.swap()); addButton(watchBar, watchSwap);
        watchClose = button(R.string.close_inset, () -> closeInset(returnToChannels)); addButton(watchBar, watchClose);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM);
        watchOverlay = scrollBar(watchBar);
        video.addView(watchOverlay, bottom);
        watchOverlay.setVisibility(View.GONE);

        player = new Playback(this, mainSurface, insetSurface, this::updatePlayback);
        showScreen(root);
        queryButton.requestFocusFromTouch();
        updateCount();
        updateSelection();
        updatePlayback();
        loadCatalog();
    }

    private void loadCatalog() {
        if (api == null || catalogBusy) return;
        Jellyfin server = api;
        long generation = ++catalogGeneration;
        long favoriteBaseline = favoriteRevision;
        boolean replace = !all.isEmpty();
        catalogBusy = true; loadProblem = null;
        updateCount();
        catalog.execute(() -> {
            var accumulated = new LinkedHashMap<String, Channels.Channel>();
            int cursor = 0, pages = 0;
            try {
                while (catalogGeneration == generation) {
                    Jellyfin.Page page = server.channels(cursor, PAGE);
                    if (catalogGeneration != generation) return;
                    int total = page.total();
                    int priorSize = accumulated.size();
                    for (Channels.Channel c : page.channels()) accumulated.put(c.id(), c);
                    cursor += page.consumed();
                    boolean done = page.consumed() == 0 || (total >= 0 ? cursor >= total : page.consumed() < PAGE);
                    if (!done && accumulated.size() == priorSize && page.consumed() > 0) {
                        throw new java.io.IOException("Channel paging did not advance.");
                    }
                    if (done || (!replace && pages++ % 3 == 0)) {
                        publishCatalog(server, generation, favoriteBaseline, accumulated, done, null);
                    }
                    if (done) return;
                }
                return;
            } catch (Exception failure) {
                boolean expired = failure instanceof Jellyfin.HttpFailure http && http.status == 401;
                String error = failure instanceof Jellyfin.HttpFailure http && http.status == 403
                        ? getString(R.string.no_live_tv_access)
                        : needsLocalNetworkPermission() ? getString(R.string.local_network_access)
                        : getString(R.string.channels_load_failed);
                if (expired || replace) {
                    ui.post(() -> {
                        if (destroyed || api != server || catalogGeneration != generation) return;
                        if (expired) { signOut(false); return; }
                        catalogBusy = false;
                        loadProblem = error;
                        updateCount();
                        toast(loadProblem);
                    });
                } else publishCatalog(server, generation, favoriteBaseline, accumulated, true, error);
            }
        });
    }

    private void publishCatalog(Jellyfin server, long generation, long favoriteBaseline, Map<String, Channels.Channel> channels,
                                boolean done, String error) {
        List<Channels.Channel> snapshot = List.copyOf(channels.values());
        ui.post(() -> {
            if (destroyed || api != server || catalogGeneration != generation) return;
            all = snapshot;
            catalogBusy = !done; loadProblem = error;
            if (done && error == null) {
                favoriteOverrides.values().removeIf(edit -> edit.revision() <= favoriteBaseline);
                player.reconcileChannels(snapshot);
                if (resumeMain != null && Channels.indexOf(snapshot, resumeMain.id()) < 0) {
                    resumeMain = resumeInset;
                    resumeInset = null;
                }
                if (resumeMain != null && Channels.indexOf(snapshot, resumeMain.id()) < 0) resumeMain = null;
                if (resumeInset != null && Channels.indexOf(snapshot, resumeInset.id()) < 0) resumeInset = null;
            }
            rebuildFavorites(); renderFilter(false);
        });
    }

    private void rebuildFavorites() {
        HashSet<String> set = new HashSet<>();
        for (Channels.Channel c : all) {
            FavoriteEdit edit = favoriteOverrides.get(c.id());
            if (edit == null ? c.favorite() : edit.value()) set.add(c.id());
        }
        favorites = Set.copyOf(set);
    }

    private void renderFilter(boolean resetPosition) {
        resetSelection |= resetPosition;
        int generation = ++visibleGeneration;
        List<Channels.Channel> channels = all;
        Set<String> stars = favorites;
        boolean starred = favoritesOnly;
        String term = query;
        queryButton.setText(term.isBlank() ? R.string.search : R.string.search_active);
        filterButton.setText(starred ? R.string.all_channels : R.string.favorites);
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
                        if (list.hasFocus()) (starred && term.isBlank() ? filterButton : queryButton).requestFocusFromTouch();
                        selected = null; pendingJump = null; focusedPosition = 0;
                    } else if (resetSelection || pendingJump != null || (position < 0 && list.hasFocus())) {
                        jump(resetSelection ? 0 : position >= 0 ? position
                                : Channels.jumpPage(focusedPosition, filtered.size(), 0));
                    } else {
                        focusedPosition = position >= 0 ? position : Channels.jumpPage(focusedPosition, filtered.size(), 0);
                        selected = filtered.get(focusedPosition);
                    }
                    boolean focusRecovery = resetSelection && filtered.isEmpty();
                    resetSelection = false;
                    updateCount();
                    if (focusRecovery && emptyAction.isShown()) emptyAction.requestFocus();
                    updateSelection();
                    scheduleGuide();
                });
            });
        });
    }

    private void updateCount() {
        int n = adapter.getItemCount();
        if (jumpButton.hasFocus() && n == 0) queryButton.requestFocusFromTouch();
        String label = getResources().getQuantityString(favoritesOnly ? R.plurals.favorite_count : R.plurals.channel_count, n, n);
        if (catalogBusy) label = getString(R.string.loading_channel_count, label);
        else if (loadProblem != null && n > 0) label = getString(R.string.partial_channel_count, label);
        else if (!query.isBlank()) label = getString(R.string.search_channel_count, label, query);
        count.setText(label);
        jumpButton.setEnabled(n > 0);
        emptyState.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        pager.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        boolean canRecover = n == 0 && !catalogBusy && (loadProblem != null || !query.isBlank() || favoritesOnly);
        if (!canRecover && emptyAction.hasFocus()) queryButton.requestFocus();
        emptyAction.setVisibility(canRecover ? View.VISIBLE : View.GONE);
        emptyAction.setText(loadProblem != null ? R.string.retry : !query.isBlank() ? R.string.clear_search : R.string.all_channels);
        queryButton.setNextFocusDownId(canRecover ? emptyAction.getId() : list.getId());
        filterButton.setNextFocusDownId(canRecover ? emptyAction.getId() : list.getId());
        empty.setText(loadProblem != null ? loadProblem : getString(catalogBusy ? R.string.loading_channels
                : !query.isBlank() ? R.string.no_matching_channels
                : favoritesOnly ? R.string.no_favorites : R.string.no_channels));
    }

    private void searchDialog() {
        EditText field = edit(getString(R.string.search_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(query);
        field.setSelectAllOnFocus(true);
        field.setMinimumHeight(dp(52));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        TvDialog dialog = new TvDialog(this, getString(R.string.find_channel));
        dialog.add(field, 16);
        LinearLayout actions = new LinearLayout(this);
        Button submit = dialog.action(R.string.search, () -> applySearch(field.getText().toString()));
        addButton(actions, submit);
        if (!query.isBlank()) addButton(actions, dialog.action(R.string.clear_search, () -> applySearch("")));
        addButton(actions, button(R.string.cancel, dialog::dismiss));
        dialog.add(actions, 12);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.performClick();
            return true;
        });
        dialog.show(field);
        WindowCompat.getInsetsController(dialog.window(), field).show(WindowInsetsCompat.Type.ime());
    }

    private void applySearch(String value) { query = value.trim(); renderFilter(true); }

    private void jumpDialog() {
        if (adapter.getItemCount() == 0) return;
        TvDialog dialog = new TvDialog(this, getString(R.string.jump_to_channel));
        LinearLayout edges = new LinearLayout(this);
        Button first = dialog.action(R.string.first_channel, () -> jump(0));
        addButton(edges, first);
        addButton(edges, dialog.action(R.string.last_channel, () -> jump(adapter.getItemCount() - 1)));
        dialog.add(edges, 14);
        int column = 0;
        int columns = root.getWidth() >= dp(620) ? 6 : 4;
        LinearLayout letters = null;
        for (String letter : jumps.keySet()) {
            if (column++ % columns == 0) {
                letters = new LinearLayout(this);
                dialog.add(letters, 7);
            }
            Button choice = dialog.action(letter, () -> {
                Integer position = jumps.get(letter);
                if (position != null) jump(position);
            });
            choice.setContentDescription(getString(R.string.jump_to_letter, letter));
            addButton(letters, choice);
        }
        dialog.show(first);
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

    private boolean isPlayingSelection() {
        return selected != null && player.primary() != null && selected.id().equals(player.primary().id());
    }

    private void updateSelection() {
        if (api == null || selectedName == null) return;
        selectedName.setText(selected == null ? getString(R.string.no_channel_selected) : selected.label());
        programText.setText(selected == null ? "" : guideLine(selected.id(), true));
        boolean starred = selected != null && favorites.contains(selected.id());
        boolean saving = selected != null && pendingFavorites.contains(selected.id());
        starButton.setText(saving ? R.string.saving : starred ? R.string.remove_favorite : R.string.add_favorite);
        starButton.setEnabled(selected != null);
        if (guideButton.hasFocus() && selected == null) queryButton.requestFocusFromTouch();
        guideButton.setEnabled(selected != null);
        boolean tuned = isPlayingSelection();
        watchButton.setText(!tuned ? R.string.play_channel : player.primaryStatus().failed() ? R.string.retry : R.string.full_screen);
        watchButton.setEnabled(selected != null);
        insetButton.setVisibility(player.primary() == null ? View.GONE : View.VISIBLE);
        Channels.Channel insetChannel = player.secondary();
        boolean inInset = selected != null && insetChannel != null && selected.id().equals(insetChannel.id());
        insetButton.setText(inInset && player.secondaryStatus().failed() ? R.string.retry_inset : R.string.play_in_inset);
        insetButton.setEnabled(selected != null && !tuned && (!inInset || player.secondaryStatus().failed()));
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
                    favoriteOverrides.put(channel.id(), new FavoriteEdit(value, ++favoriteRevision));
                    rebuildFavorites();
                    if (favoritesOnly) renderFilter(false);
                    else refreshGuideRows();
                } else toast(getString(R.string.favorite_update_failed));
                updateSelection();
            });
        });
    }

    private void channelActions(Channels.Channel channel) {
        int position = Channels.indexOf(adapter.getCurrentList(), channel.id());
        if (position >= 0) focusedPosition = position;
        selected = channel;
        updateSelection();
        TvDialog dialog = new TvDialog(this, channel.label());
        Button watch = dialog.action(R.string.play_channel, () -> tune(false));
        dialog.add(watch, 16);
        if (player.primary() != null && !player.primary().id().equals(channel.id())) {
            Channels.Channel insetChannel = player.secondary();
            boolean inInset = insetChannel != null && insetChannel.id().equals(channel.id());
            if (!inInset || player.secondaryStatus().failed()) {
                dialog.add(dialog.action(inInset ? R.string.retry_inset : R.string.play_in_inset, () -> tune(true)), 8);
            }
        }
        dialog.add(dialog.action(R.string.guide, this::scheduleDialog), 8);
        dialog.add(dialog.action(favorites.contains(channel.id()) ? R.string.remove_favorite : R.string.add_favorite,
                this::favoriteSelected), 8);
        dialog.add(dialog.action(R.string.compatible_stream, () -> player.tuneCompatible(api, channel)), 8);
        dialog.show(watch);
    }

    private void tune(boolean small) {
        if (selected == null || api == null) return;
        if (small && player.primary() == null) small = false;
        player.tune(api, selected, small);
    }

    private void updatePlayback() {
        if (api == null || player == null) return;
        Channels.Channel main = player.primary(), small = player.secondary();
        Playback.Status status = player.primaryStatus(), smallStatus = player.secondaryStatus();
        mainMessage.setText(main == null ? getString(R.string.choose_channel)
                : getString(R.string.playback_message, main.label(), getString(status.label)));
        mainMessage.setVisibility(status.live() ? View.GONE : View.VISIBLE);
        inset.setVisibility(small == null ? View.GONE : View.VISIBLE);
        insetMessage.setText(small == null ? ""
                : getString(R.string.playback_message, small.label(), getString(smallStatus.label)));
        insetMessage.setVisibility(smallStatus.live() ? View.GONE : View.VISIBLE);
        watchTitle.setText(main == null ? "" : small == null ? main.label()
                : getString(R.string.watch_title, main.label(), small.label()));
        watchStatus.setText(main == null ? "" : getString(status.failed() ? R.string.unavailable
                : status == Playback.Status.LIVE_COMPATIBLE ? R.string.live_compatible_short : status.label));
        if (!status.failed() && watchRetry.hasFocus()) returnToChannels.requestFocus();
        watchRetry.setVisibility(status.failed() ? View.VISIBLE : View.GONE);
        swapButton.setVisibility(small == null ? View.GONE : View.VISIBLE);
        closeInset.setVisibility(small == null ? View.GONE : View.VISIBLE);
        watchSwap.setVisibility(small == null ? View.GONE : View.VISIBLE);
        watchClose.setVisibility(small == null ? View.GONE : View.VISIBLE);
        root.setKeepScreenOn(foreground && player.hasVideo());
        updateSelection();
        if (fullscreen) {
            if (status.failed() || smallStatus.failed()) showWatchControls();
            else scheduleWatchControls();
        }
    }

    private void scheduleWatchControls() {
        ui.removeCallbacks(hideWatchControls);
        if (fullscreen && foreground && watchOverlay.isShown()) ui.postDelayed(hideWatchControls, 5_000);
    }

    private void showWatchControls() {
        if (!fullscreen) return;
        boolean hidden = watchOverlay.getVisibility() != View.VISIBLE;
        watchOverlay.setVisibility(View.VISIBLE);
        if (hidden || video.isFocused()) returnToChannels.requestFocus();
        scheduleWatchControls();
    }

    private void closeInset(Button fallback) {
        fallback.requestFocusFromTouch();
        player.stopInset();
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled || video == null) return;
        fullscreen = enabled;
        video.setFocusable(enabled);
        revealKey = KeyEvent.KEYCODE_UNKNOWN;
        ui.removeCallbacks(hideWatchControls);
        ViewGroup previous = (ViewGroup) video.getParent();
        if (enabled) {
            previous.removeView(video); shell.setVisibility(View.GONE);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));
            showWatchControls();
            returnToChannels.requestFocus();
        } else {
            root.removeView(video);
            stage.addView(video, 0, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.setVisibility(View.VISIBLE); watchOverlay.setVisibility(View.GONE);
            returnToSelectedChannel();
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) inset.getLayoutParams();
        p.bottomMargin = dp(fullscreen ? 64 : 12); inset.setLayoutParams(p);
        scheduleGuide();
    }

    private void scheduleGuide() {
        ui.removeCallbacks(guideRefresh);
        if (foreground && !fullscreen && api != null) ui.postDelayed(guideRefresh, 220);
    }

    private void visibleGuide() {
        if (api == null || fullscreen || guideBusy || !foreground) return;
        long elapsed = SystemClock.elapsedRealtime();
        long window = GuideCache.window(System.currentTimeMillis());
        if (window != guideWindow) {
            guideWindow = window;
            noGuideUntil = guideCheckedUntil = guideBackoffUntil = 0;
        }
        if (elapsed < noGuideUntil || elapsed < guideBackoffUntil) return;
        int first = rows.findFirstVisibleItemPosition(), last = rows.findLastVisibleItemPosition();
        if (first < 0 || last < first) return;
        List<String> ids = new ArrayList<>(GUIDE_BATCH);
        List<Channels.Channel> snapshot = adapter.getCurrentList();
        for (int i = first; i <= last && i < snapshot.size() && ids.size() < GUIDE_BATCH; i++) {
            String id = snapshot.get(i).id();
            if (epg.get(window, id, elapsed) == null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        boolean probe = elapsed >= guideCheckedUntil;
        int generation = guideGeneration;
        Jellyfin server = api;
        guideBusy = true;
        guide.execute(() -> {
            try {
                boolean available = !probe || server.hasGuide(window, GUIDE_HOURS);
                Map<String, List<Channels.Program>> result = available ? server.programs(ids, window, GUIDE_HOURS) : Map.of();
                ui.post(() -> {
                    if (destroyed || api != server) return;
                    guideBusy = false;
                    if (generation != guideGeneration) { scheduleGuide(); return; }
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
                    guideBusy = false;
                    if (generation != guideGeneration) { scheduleGuide(); return; }
                    guideBackoffUntil = SystemClock.elapsedRealtime() + 15_000;
                    refreshGuideRows();
                });
            }
        });
    }

    private void refreshGuideRows() {
        if (adapter == null || rows == null) return;
        int first = rows.findFirstVisibleItemPosition(), last = rows.findLastVisibleItemPosition();
        if (first >= 0 && last >= first && last < adapter.getItemCount()) {
            adapter.notifyItemRangeChanged(first, last - first + 1);
        }
        updateSelection();
    }

    private String guideLine(String id, boolean details) {
        long time = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        long window = GuideCache.window(time);
        List<Channels.Program> schedule = epg.get(window, id, elapsed);
        if (schedule == null) {
            if (window == guideWindow && elapsed < noGuideUntil) return details ? getString(R.string.no_server_guide) : "";
            if (window == guideWindow && elapsed < guideBackoffUntil) return details ? getString(R.string.guide_unavailable) : "";
            return "";
        }
        Channels.Program current = Channels.now(schedule, time), next = Channels.next(schedule, time);
        if (current == null && next == null) return details ? getString(R.string.no_listings) : "";
        String info = current == null ? nextProgram(next) : getString(R.string.guide_now, current.title());
        return details && current != null && next != null ? info + '\n' + nextProgram(next) : info;
    }

    private String nextProgram(Channels.Program program) {
        return getString(R.string.guide_next, clock.format(new Date(program.start())), program.title());
    }

    private void scheduleDialog() {
        if (selected == null || api == null) return;
        Channels.Channel channel = selected;
        Jellyfin server = api;
        long initial = GuideCache.window(System.currentTimeMillis());
        long step = GUIDE_HOURS * 3_600_000L;
        long limit = initial + 7 * 86_400_000L;
        long[] window = {initial};
        TvDialog dialog = new TvDialog(this, channel.label());
        TextView caption = text("", 16, ACCENT);
        dialog.add(caption, 8);
        LinearLayout entries = vertical();
        android.widget.ScrollView schedule = dialog.scroll(entries, 160);
        LinearLayout actions = new LinearLayout(this);
        Button earlier = button(R.string.earlier, null);
        Button later = button(R.string.later, null);
        Button close = button(R.string.close, dialog::dismiss);
        Button retry = button(R.string.retry, null);
        addButton(actions, earlier);
        addButton(actions, later);
        addButton(actions, close);
        dialog.add(actions, 14);
        Future<?>[] request = {null};
        Runnable fetch = () -> {
            long from = window[0];
            int generation = guideGeneration;
            request[0] = guide.submit(() -> {
                try {
                    List<Channels.Program> result = server.programs(List.of(channel.id()), from, GUIDE_HOURS).get(channel.id());
                    ui.post(() -> {
                        if (destroyed || server != api || generation != guideGeneration) return;
                        epg.put(from, channel.id(), result, SystemClock.elapsedRealtime());
                        if (dialog.isShowing() && window[0] == from) {
                            showSchedule(entries, result);
                            schedule.post(() -> schedule.setFocusable(entries.getHeight() > schedule.getHeight()));
                        }
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (!destroyed && server == api && generation == guideGeneration
                                && dialog.isShowing() && window[0] == from) {
                            scheduleMessage(entries, R.string.guide_load_failed);
                            LinearLayout.LayoutParams retrySize = new LinearLayout.LayoutParams(-2, dp(44));
                            retrySize.topMargin = dp(12);
                            entries.addView(retry, retrySize);
                        }
                    });
                }
            });
        };
        Runnable cancel = () -> {
            ui.removeCallbacks(fetch);
            if (request[0] != null) request[0].cancel(false);
        };
        Runnable load = () -> {
            cancel.run();
            long from = window[0];
            if (from == initial && earlier.hasFocus()) later.requestFocus();
            if (from == limit && later.hasFocus()) earlier.requestFocus();
            earlier.setEnabled(from > initial);
            later.setEnabled(from < limit);
            Date date = new Date(from);
            caption.setText(getString(R.string.guide_window, guideDate.format(date), clock.format(date),
                    clock.format(new Date(from + step))));
            if (retry.hasFocus() || schedule.hasFocus()) (later.isEnabled() ? later : earlier).requestFocus();
            schedule.scrollTo(0, 0);
            List<Channels.Program> cached = epg.get(from, channel.id(), SystemClock.elapsedRealtime());
            schedule.setFocusable(false);
            if (cached != null) {
                showSchedule(entries, cached);
                schedule.post(() -> schedule.setFocusable(entries.getHeight() > schedule.getHeight()));
            }
            else {
                scheduleMessage(entries, R.string.guide_loading);
                ui.postDelayed(fetch, 150);
            }
        };
        retry.setOnClickListener(view -> load.run());
        earlier.setOnClickListener(view -> { window[0] -= step; load.run(); });
        later.setOnClickListener(view -> { window[0] += step; load.run(); });
        dialog.onDismiss(cancel);
        load.run();
        dialog.show(later);
    }

    private void showSchedule(LinearLayout entries, List<Channels.Program> programs) {
        if (programs.isEmpty()) {
            scheduleMessage(entries, R.string.no_listings);
            return;
        }
        entries.removeAllViews();
        long now = System.currentTimeMillis();
        for (Channels.Program program : programs) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(5), 0, dp(5));
            boolean live = program.onAir(now);
            TextView time = text(clock.format(new Date(program.start())), 15, live ? ACCENT : DIM);
            row.addView(time, new LinearLayout.LayoutParams(dp(98), -2));
            TextView title = text(program.title(), 17, TEXT);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (live) row.addView(text(getString(R.string.now), 14, ACCENT));
            entries.addView(row);
        }
    }

    private void scheduleMessage(LinearLayout entries, int message) {
        entries.removeAllViews();
        entries.addView(text(getString(message), 17, DIM));
    }

    private void serverDialog() {
        if (api == null) return;
        TvDialog dialog = new TvDialog(this, getString(R.string.jellyfin_server));
        TextView address = text(api.session.server(), 17, DIM);
        address.setSingleLine();
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        dialog.add(address, 8);
        Button refresh = dialog.action(R.string.refresh_channels, () -> requestLocalNetwork(granted -> {
            if (!granted) { toast(getString(R.string.local_network_access)); return; }
            ++guideGeneration;
            epg.clear(); noGuideUntil = guideCheckedUntil = guideBackoffUntil = 0;
            refreshGuideRows(); scheduleGuide();
            loadCatalog();
        }));
        dialog.add(refresh, 16);
        dialog.add(dialog.action(R.string.sign_out, this::confirmSignOut), 8);
        dialog.add(button(R.string.close, dialog::dismiss), 8);
        dialog.show(refresh);
    }

    private void confirmSignOut() {
        TvDialog dialog = new TvDialog(this, getString(R.string.sign_out_title));
        dialog.add(text(getString(R.string.sign_out_description), 17, DIM), 8);
        LinearLayout actions = new LinearLayout(this);
        Button cancel = button(R.string.stay_signed_in, dialog::dismiss);
        addButton(actions, cancel);
        Button signOut = dialog.action(R.string.sign_out, () -> signOut(true));
        addButton(actions, signOut);
        dialog.add(actions, 16);
        dialog.show(cancel);
    }

    private void signOut(boolean revoke) {
        ++catalogGeneration; ++visibleGeneration;
        Jellyfin server = api;
        signingOut = revoke;
        player.destroy(revoke ? () -> {
            String message;
            try {
                server.logout();
                message = getString(R.string.signed_out);
            } catch (java.io.IOException failure) {
                message = getString(failure instanceof Jellyfin.HttpFailure http && http.status == 401
                        ? R.string.signed_out : R.string.signed_out_locally);
            }
            String result = message;
            ui.post(() -> {
                signingOut = false;
                if (!destroyed && api == null) showLogin(result);
            });
        } : null);
        player = null;
        api = null; selected = null; resumeMain = null; resumeInset = null;
        all = List.of(); favorites = Set.of(); favoriteOverrides.clear(); pendingFavorites.clear();
        pendingJump = null; focusedPosition = 0; resetSelection = false;
        query = ""; favoritesOnly = false; fullscreen = false; catalogBusy = false; guideBusy = false;
        ++guideGeneration;
        noGuideUntil = guideCheckedUntil = guideBackoffUntil = 0;
        epg.clear(); store.clear();
        showLogin(getString(revoke ? R.string.signing_out : R.string.sign_in_expired));
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
        ui.removeCallbacks(hideWatchControls);
        if (player != null) {
            resumeMain = player.primary(); resumeInset = player.secondary();
            player.stopAll();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true; ++catalogGeneration; ++visibleGeneration;
        ui.removeCallbacks(hideWatchControls);
        if (signIn != null) signIn.cancel();
        if (player != null) player.destroy(null);
        accounts.shutdownNow(); catalog.shutdownNow(); search.shutdownNow(); guide.shutdownNow();
        super.onDestroy();
    }

    private boolean handleWatchKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == revealKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) revealKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (fullscreen && event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean navigation = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (navigation && watchOverlay.getVisibility() != View.VISIBLE) {
                revealKey = key;
                showWatchControls();
                return true;
            }
            scheduleWatchControls();
        }
        return false;
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) scheduleWatchControls();
        else ui.removeCallbacks(hideWatchControls);
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
        if (signIn != null && signIn.back()) return;
        if (fullscreen) { setFullscreen(false); return; }
        if (!query.isBlank()) { applySearch(""); return; }
        if (favoritesOnly) { favoritesOnly = false; renderFilter(true); return; }
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
            row.setPadding(dp(14), dp(7), dp(10), dp(5)); row.setBackground(focusBackground(PANEL));
            row.setFocusable(true); row.setClickable(true);
            TextView title = text("", 16, TEXT); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
            TextView info = text("", 13, DIM); info.setSingleLine(); info.setEllipsize(TextUtils.TruncateAt.END);
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
            String label = channel.label();
            holder.title.setText(favorites.contains(channel.id()) ? getString(R.string.favorite_channel_label, label) : label);
            holder.guide.setText(guideLine(channel.id(), false));
        }

    }

    private static final class ChannelRow extends RecyclerView.ViewHolder {
        final TextView title, guide;
        ChannelRow(View item, TextView title, TextView guide) { super(item); this.title = title; this.guide = guide; }
    }
}
