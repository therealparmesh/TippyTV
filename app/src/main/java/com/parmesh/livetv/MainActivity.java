package com.parmesh.livetv;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
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

public final class MainActivity extends ComponentActivity {
    static final int BG = 0xff282a36, PANEL = 0xff30323f, EDGE = 0xff565968;
    private static final int BAR = 0xff44475a, FOCUS = 0xff514965;
    static final int TEXT = 0xfff8f8f2, DIM = 0xffc8c9d4, ACCENT = 0xffbd93f9, PRIMARY = 0xff49415e;
    private static final int GUIDE_BATCH = 16;
    private static final int GUIDE_HOURS = 3, WATCH_BAR_HEIGHT = 81;
    private static final long LOADING_REFRESH_MS = 1_000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService accounts = Executors.newSingleThreadExecutor();
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final ExecutorService search = Executors.newSingleThreadExecutor();
    private final ExecutorService guide = Executors.newSingleThreadExecutor();
    private final GuideCache epg = new GuideCache(384);

    private record FavoriteEdit(String name, boolean value, long revision) {}

    private final Map<String, FavoriteEdit> favoriteOverrides = new HashMap<>();
    private long favoriteRevision;
    private final Set<String> pendingFavorites = new HashSet<>();
    private final DateFormat clock = DateFormat.getTimeInstance(DateFormat.SHORT);
    private final DateFormat guideDate = DateFormat.getDateInstance(DateFormat.MEDIUM);
    private SessionStore store;
    private LineupCache lineupCache;
    private SignInScreen signIn;
    final List<TvDialog> dialogs = new ArrayList<>();
    private final PhoneSetup phone = new PhoneSetup(this);
    private TvSource api;
    private Jellyfin.Session profile;
    private Playback player;
    private boolean qualityFilter;
    private List<Channels.Channel> fullLineup = List.of(), filteredLineup = List.of();
    private Set<String> favorites = Set.of();
    private Map<String, Integer> jumps = Map.of();
    private Channels.Channel selected;
    private Channels.Channel resumeMain, resumeInset;
    private boolean resumeMainPaused, resumeInsetPaused;
    private String query = "";
    private String loadProblem;
    private boolean favoritesOnly,
            catalogBusy,
            guideBusy,
            fullscreen,
            foreground,
            destroyed,
            signingOut;
    private volatile int visibleGeneration;
    private int focusedPosition;
    private boolean resetSelection;
    private String pendingJump;
    private long guideWindow = Long.MIN_VALUE;
    private long noGuideUntil, guideCheckedUntil, guideBackoffUntil;
    private int guideGeneration;
    private volatile long catalogGeneration;
    private FrameLayout root, video, inset;
    private LinearLayout shell, emptyState, pager;
    private View previewSlot;
    private FrameLayout mainPicture;
    private HorizontalScrollView watchOverlay;
    private int revealKey = KeyEvent.KEYCODE_UNKNOWN;
    private TextView count,
            empty,
            selectedName,
            programText,
            mainMessage,
            insetMessage,
            watchTitle,
            watchStatus,
            previewLabel;
    private Button filterButton, queryButton, jumpButton, guideButton;
    private Button watchSwap, watchClose, watchRetry, watchPause;
    private Button returnToChannels, emptyAction;
    private Consumer<Boolean> pendingNetworkAction;
    private final ActivityResultLauncher<String> networkPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) finishNetworkRequest(true);
                        else if (!destroyed && pendingNetworkAction != null) showNetworkSettings();
                    });
    private final ActivityResultLauncher<Intent> networkSettings =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> finishNetworkRequest(!needsLocalNetworkPermission()));
    private RecyclerView list;
    private LinearLayoutManager rows;
    private ChannelAdapter adapter;

    private final Runnable hideWatchControls =
            () -> {
                if (!fullscreen
                        || !foreground
                        || player == null
                        || !player.primaryStatus().live()
                        || player.secondaryStatus().failed()
                        || !hasWindowFocus()) return;
                video.requestFocus();
                watchOverlay.setVisibility(View.GONE);
                layoutInset();
            };
    private final Runnable loadingRefresh =
            new Runnable() {
                @Override
                public void run() {
                    if (!foreground || destroyed || player == null || api == null) return;
                    updatePlaybackDetails();
                    if (loading(player.primaryStatus()) || loading(player.secondaryStatus()))
                        ui.postDelayed(this, LOADING_REFRESH_MS);
                }
            };

    private static boolean loading(Playback.Status status) {
        return status != Playback.Status.IDLE
                && status != Playback.Status.PAUSED
                && !status.live()
                && !status.failed();
    }

    private final Runnable guideRefresh = this::visibleGuide;
    private final Runnable minute =
            new Runnable() {
                @Override
                public void run() {
                    if (!foreground || destroyed) return;
                    refreshGuideRows();
                    scheduleGuide();
                    ui.postDelayed(this, 30_000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                back();
                            }
                        });
        WindowInsetsControllerCompat bars =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        store = new SessionStore(this);
        qualityFilter = getPreferences(MODE_PRIVATE).getBoolean("qualityFilter", true);
        TextView opening = text(getString(R.string.opening), 19, TEXT);
        opening.setGravity(Gravity.CENTER);
        setContentView(opening);
        accounts.execute(
                () -> {
                    Jellyfin.Session saved = store.read();
                    ui.post(
                            () -> {
                                if (destroyed) return;
                                if (saved == null
                                        || getIntent().getBooleanExtra("addAccount", false))
                                    showLogin("");
                                else
                                    requestLocalNetwork(
                                            granted -> {
                                                if (granted) connected(saved);
                                                else
                                                    showLogin(
                                                            getString(
                                                                    R.string.local_network_access));
                                            });
                            });
                });
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    boolean needsLocalNetworkPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
                && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        != PackageManager.PERMISSION_GRANTED;
    }

    void requestLocalNetwork(Consumer<Boolean> next) {
        if (needsLocalNetworkPermission()) {
            pendingNetworkAction = next;
            networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
        } else next.accept(true);
    }

    private void finishNetworkRequest(boolean granted) {
        var action = pendingNetworkAction;
        pendingNetworkAction = null;
        if (action != null && !destroyed) action.accept(granted);
    }

    private void showNetworkSettings() {
        TvDialog dialog = new TvDialog(this, getString(R.string.network_permission_title));
        dialog.add(text(getString(R.string.local_network_access), 17, DIM), 12);
        boolean[] openingSettings = {false};
        Button settings =
                button(
                        R.string.open_settings,
                        () -> {
                            openingSettings[0] = true;
                            dialog.dismiss();
                            try {
                                networkSettings.launch(
                                        new Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:" + getPackageName())));
                            } catch (ActivityNotFoundException unavailable) {
                                finishNetworkRequest(false);
                            }
                        });
        dialog.add(settings, 16);
        dialog.add(button(R.string.cancel, dialog::dismiss), 8);
        dialog.onDismiss(
                () -> {
                    if (!openingSettings[0]) finishNetworkRequest(false);
                });
        dialog.show(settings);
    }

    private void dismissDialogs() {
        // A disappearing screen cannot receive focus when its dialogs finish dismissing.
        while (!dialogs.isEmpty()) dialogs.remove(dialogs.size() - 1).dismiss();
    }

    void showScreen(FrameLayout screen) {
        dismissDialogs();
        ViewCompat.setOnApplyWindowInsetsListener(
                screen,
                (view, insets) -> {
                    var safe =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.displayCutout()
                                            | WindowInsetsCompat.Type.ime());
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

    View dialogOpener() {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            TvDialog dialog = dialogs.get(i);
            if (dialog.isShowing()) return dialog.window().getCurrentFocus();
        }
        View focused = getCurrentFocus();
        if (focused != null) return focused;
        if (fullscreen) return returnToChannels;
        return queryButton;
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    GradientDrawable box(int color, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(2), border);
        return shape;
    }

    StateListDrawable focusBackground(int normal) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, box(BG, 0, 5));
        states.addState(new int[] {android.R.attr.state_focused}, box(FOCUS, ACCENT, 5));
        states.addState(new int[] {android.R.attr.state_pressed}, box(FOCUS, ACCENT, 5));
        states.addState(new int[0], box(normal, 0, 5));
        return states;
    }

    Button button(int label, Runnable action) {
        return button(getString(label), action);
    }

    Button button(String label, Runnable action) {
        Button v = new Button(this);
        v.setText(label);
        v.setTextSize(15);
        v.setAllCaps(false);
        v.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                        new int[] {0xff858991, TEXT}));
        v.setBackground(focusBackground(BAR));
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setMinHeight(0);
        v.setMinimumHeight(0);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setFocusable(true);
        if (action != null) v.setOnClickListener(view -> action.run());
        return v;
    }

    private void addButton(LinearLayout parent, Button v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.rightMargin = dp(8);
        parent.addView(v, lp);
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    boolean hasSavedProfiles() {
        return !store.ids().isEmpty();
    }

    private void showLogin(String message) {
        if (signIn == null)
            signIn =
                    new SignInScreen(
                            this,
                            accounts,
                            java.util.UUID.randomUUID().toString(),
                            store.address());
        if (getIntent().getBooleanExtra("addAccount", false)) signIn.serverPage(message, true);
        else signIn.sources(message, signingOut, false);
    }

    void signedIn(Jellyfin.Session session) {
        dismissDialogs();
        if (signIn != null) signIn.cancel();
        signIn = null;
        accounts.execute(
                () -> {
                    boolean persisted;
                    try {
                        store.save(session);
                        persisted = true;
                    } catch (Exception failure) {
                        persisted = false;
                    }
                    boolean saved = persisted;
                    ui.post(
                            () -> {
                                if (destroyed) return;
                                if (!saved) {
                                    if (session.direct()) checkedPlaylist().take(session);
                                    if (api == null)
                                        showLogin(getString(R.string.sign_in_not_saved));
                                    else toast(getString(R.string.sign_in_not_saved));
                                    return;
                                }
                                getIntent().removeExtra("addAccount");
                                if (api == null) connected(session);
                                else recreate();
                            });
                });
    }

    EditText edit(String hint, int type) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(type);
        field.setHint(hint);
        field.setTextSize(16);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextColor(TEXT);
        field.setHintTextColor(DIM);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(focusBackground(BG));
        return field;
    }

    static void submitOnDone(EditText field, Button submit) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnEditorActionListener(
                (view, action, event) -> {
                    if ((action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE)
                            && submit.isEnabled()) {
                        submit.performClick();
                        return true;
                    }
                    return false;
                });
    }

    PlaylistSource.CheckedPlaylist checkedPlaylist() {
        return new androidx.lifecycle.ViewModelProvider(this)
                .get(PlaylistSource.CheckedPlaylist.class);
    }

    private void connected(Jellyfin.Session session) {
        profile = session;
        lineupCache = new LineupCache(this, session);
        api =
                session.direct()
                        ? new PlaylistSource(this, session, checkedPlaylist().take(session))
                        : new Jellyfin(session);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        shell = vertical();
        shell.setPadding(dp(48), dp(27), dp(48), dp(27));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        buildHeader();
        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1);
        content.topMargin = dp(8);
        shell.addView(body, content);
        LinearLayout browser = buildChannelBrowser(body);
        buildVideoStage(body, browser);
        showScreen(root);
        queryButton.requestFocusFromTouch();
        updateSelection();
        updatePlayback();
        loadCatalog();
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text(getString(R.string.app_name), 22, TEXT);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setSingleLine();
        brand.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        queryButton = button(R.string.search, this::searchDialog);
        addButton(header, queryButton);
        filterButton =
                button(
                        R.string.favorites,
                        () -> {
                            query = "";
                            favoritesOnly = !favoritesOnly;
                            renderFilter(true);
                        });
        addButton(header, filterButton);
        jumpButton = button(R.string.jump, this::jumpDialog);
        guideButton = button(R.string.guide, () -> scheduleDialog(selected));
        addButton(header, jumpButton);
        addButton(header, guideButton);
        addButton(header, button(R.string.server, this::serverDialog));
        shell.addView(scrollBar(header), new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private LinearLayout buildChannelBrowser(LinearLayout body) {
        LinearLayout browser = vertical();
        browser.setBackground(box(PANEL, EDGE, 12));
        browser.setPadding(dp(2), dp(2), dp(2), dp(2));
        body.addView(browser, new LinearLayout.LayoutParams(0, -1, .42f));
        count = text("", 15, DIM);
        count.setSingleLine();
        count.setEllipsize(TextUtils.TruncateAt.END);
        count.setPadding(dp(12), dp(8), dp(4), dp(8));
        browser.addView(count);
        FrameLayout listArea = new FrameLayout(this);
        browser.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        list =
                new RecyclerView(this) {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent event) {
                        if (event.getAction() == KeyEvent.ACTION_UP
                                && isPageKey(event.getKeyCode())) return true;
                        if (event.getAction() == KeyEvent.ACTION_DOWN
                                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                                && video != null
                                && video.isFocusable()) {
                            return video.requestFocus() || super.dispatchKeyEvent(event);
                        }
                        if (event.getAction() == KeyEvent.ACTION_DOWN
                                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                                && pager != null
                                && pager.isShown()) return pager.getChildAt(0).requestFocus();
                        return handleRemoteKey(event) || super.dispatchKeyEvent(event);
                    }
                };
        list.setId(View.generateViewId());
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.setHasFixedSize(true);
        list.setItemAnimator(null);
        list.setPreserveFocusAfterLayout(false);
        rows =
                new LinearLayoutManager(this) {
                    @Override
                    public void onLayoutCompleted(RecyclerView.State state) {
                        super.onLayoutCompleted(state);
                        String id = pendingJump;
                        if (id == null) return;
                        list.postOnAnimation(
                                () -> {
                                    if (destroyed || !id.equals(pendingJump) || fullscreen) return;
                                    List<Channels.Channel> channels = adapter.channels;
                                    if (focusedPosition >= channels.size()
                                            || !id.equals(channels.get(focusedPosition).id()))
                                        return;
                                    RecyclerView.ViewHolder holder =
                                            list.findViewHolderForAdapterPosition(focusedPosition);
                                    if (holder != null && holder.itemView.requestFocusFromTouch())
                                        pendingJump = null;
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
        emptyAction =
                button(
                        R.string.clear_search,
                        () -> {
                            if (fullLineup.isEmpty() && loadProblem != null) loadCatalog();
                            else if (!query.isBlank()) applySearch("");
                            else {
                                favoritesOnly = false;
                                renderFilter(true);
                            }
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
        for (Button pageButton : List.of(previous, next)) {
            pageButton.setOnKeyListener(
                    (view, key, event) -> {
                        if (event.getAction() != KeyEvent.ACTION_DOWN
                                || key != KeyEvent.KEYCODE_DPAD_UP) return false;
                        returnToSelectedChannel();
                        return true;
                    });
        }
        TextView channelHelp = text(getString(R.string.channel_actions_hint), 14, DIM);
        channelHelp.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        channelHelp.setSingleLine();
        channelHelp.setEllipsize(TextUtils.TruncateAt.END);
        channelHelp.setPadding(0, 0, dp(8), 0);
        pager.addView(channelHelp, new LinearLayout.LayoutParams(0, -1, 1));
        browser.addView(pager);
        list.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView view, int dx, int dy) {
                        scheduleGuide();
                    }
                });
        return browser;
    }

    private void buildVideoStage(LinearLayout body, LinearLayout browser) {
        LinearLayout stage = vertical();
        LinearLayout.LayoutParams stageSize = new LinearLayout.LayoutParams(0, -1, .58f);
        stageSize.leftMargin = dp(12);
        body.addView(stage, stageSize);
        video =
                new FrameLayout(this) {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent event) {
                        return handleWatchKey(event) || super.dispatchKeyEvent(event);
                    }
                };
        video.setBackground(box(0xff171719, 0, 12));
        video.setOnClickListener(
                view -> {
                    if (fullscreen) showWatchControls();
                    else if (player.primary() != null || selected != null) {
                        if (player.primary() == null) tune(selected, false);
                        setFullscreen(true);
                    }
                });
        video.setFocusable(true);
        video.setContentDescription(getString(R.string.full_screen));
        video.setOnFocusChangeListener(
                (view, focused) ->
                        video.setForeground(focused && !fullscreen ? box(0, ACCENT, 12) : null));
        video.setOnKeyListener(
                (view, key, event) -> {
                    if (fullscreen
                            || event.getAction() != KeyEvent.ACTION_DOWN
                            || key != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                    returnToSelectedChannel();
                    return true;
                });
        previewSlot = new View(this);
        stage.addView(previewSlot, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(video, new FrameLayout.LayoutParams(0, 0));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());
        previewSlot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());
        MpvView mainSurface = new MpvView(this);
        mainPicture = new FrameLayout(this);
        video.addView(mainPicture, new FrameLayout.LayoutParams(-1, -1));
        mainPicture.addView(mainSurface, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        mainMessage = text("", 18, TEXT);
        mainMessage.setGravity(Gravity.CENTER);
        mainMessage.setPadding(dp(22), dp(12), dp(22), dp(12));
        mainPicture.addView(mainMessage, new FrameLayout.LayoutParams(-1, -1));
        previewLabel = text("", 14, TEXT);
        previewLabel.setSingleLine();
        previewLabel.setEllipsize(TextUtils.TruncateAt.END);
        previewLabel.setBackground(box(0xcc171719, 0, 5));
        previewLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams previewCaption =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        previewCaption.setMargins(dp(12), dp(12), dp(12), 0);
        video.addView(previewLabel, previewCaption);
        inset = new FrameLayout(this);
        inset.setBackground(box(0xff171719, ACCENT, 0));
        inset.setContentDescription(getString(R.string.muted_inset));
        inset.setPadding(dp(2), dp(2), dp(2), dp(2));
        MpvView insetSurface = new MpvView(this);
        inset.addView(insetSurface, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        insetMessage = text("", 14, TEXT);
        insetMessage.setGravity(Gravity.CENTER);
        inset.addView(insetMessage, new FrameLayout.LayoutParams(-1, -1));
        video.addView(inset, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.END));
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutInset());

        selectedName = text("", 20, TEXT);
        selectedName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        selectedName.setSingleLine();
        selectedName.setEllipsize(TextUtils.TruncateAt.END);
        selectedName.setPadding(0, dp(7), 0, 0);
        stage.addView(selectedName);
        programText = text("", 15, DIM);
        programText.setMaxLines(2);
        programText.setEllipsize(TextUtils.TruncateAt.END);
        stage.addView(programText, new LinearLayout.LayoutParams(-1, dp(43)));
        shell.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (r - l == or - ol) return;
                    boolean compact = r - l < dp(700);
                    body.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    browser.setLayoutParams(
                            compact
                                    ? new LinearLayout.LayoutParams(-1, 0, .58f)
                                    : new LinearLayout.LayoutParams(0, -1, .42f));
                    LinearLayout.LayoutParams size =
                            compact
                                    ? new LinearLayout.LayoutParams(-1, 0, .42f)
                                    : new LinearLayout.LayoutParams(0, -1, .58f);
                    if (compact) size.topMargin = dp(8);
                    else size.leftMargin = dp(12);
                    stage.setLayoutParams(size);
                });
        buildWatchControls();
        player =
                new Playback(
                        this, mainSurface, insetSurface, this::updatePlayback, this::swapPictures);
    }

    private void buildWatchControls() {
        LinearLayout watchBar = new LinearLayout(this);
        watchBar.setGravity(Gravity.CENTER_VERTICAL);
        watchBar.setBackgroundColor(0xdf282a36);
        watchBar.setPadding(dp(48), 0, dp(40), dp(27));
        watchTitle = text("", 16, TEXT);
        watchTitle.setSingleLine();
        watchTitle.setEllipsize(TextUtils.TruncateAt.END);
        watchBar.addView(watchTitle, new LinearLayout.LayoutParams(0, -2, 1));
        watchStatus = text("", 14, DIM);
        watchStatus.setMaxLines(2);
        watchStatus.setEllipsize(TextUtils.TruncateAt.END);
        watchStatus.setMaxWidth(dp(155));
        watchStatus.setPadding(dp(8), 0, dp(12), 0);
        watchBar.addView(watchStatus);
        returnToChannels = button(R.string.channels, () -> setFullscreen(false));
        addButton(watchBar, returnToChannels);
        watchPause =
                button(
                        R.string.pause,
                        () -> player.setPlaying(player.primaryStatus() == Playback.Status.PAUSED));
        addButton(watchBar, watchPause);
        watchRetry = button(R.string.retry, () -> player.tune(api, player.primary(), false));
        addButton(watchBar, watchRetry);
        watchSwap = button(R.string.swap, () -> player.swap());
        addButton(watchBar, watchSwap);
        watchClose = button(R.string.close_inset, () -> closeInset(returnToChannels));
        addButton(watchBar, watchClose);
        FrameLayout.LayoutParams bottom =
                new FrameLayout.LayoutParams(-1, dp(WATCH_BAR_HEIGHT), Gravity.BOTTOM);
        watchOverlay = scrollBar(watchBar);
        video.addView(watchOverlay, bottom);
        watchOverlay.setVisibility(View.GONE);
    }

    private void loadCatalog() {
        if (api == null || catalogBusy) return;
        TvSource server = api;
        long generation = ++catalogGeneration;
        long favoriteBaseline = favoriteRevision;
        LineupCache saved = lineupCache;
        boolean hasLineup = !fullLineup.isEmpty();
        catalogBusy = true;
        loadProblem = null;
        updateCount();
        catalog.execute(
                () -> {
                    boolean replace = hasLineup;
                    if (!replace) {
                        List<Channels.Channel> cached = saved.read(server);
                        if (catalogGeneration != generation) return;
                        if (!cached.isEmpty()) {
                            publishCatalog(
                                    server, generation, favoriteBaseline, cached, false, null);
                            replace = true;
                        }
                    }
                    var accumulated = new LinkedHashMap<String, Channels.Channel>();
                    int cursor = 0, pages = 0;
                    try {
                        while (catalogGeneration == generation) {
                            TvSource.Page page = server.channels(cursor);
                            if (catalogGeneration != generation) return;
                            cursor = page.nextStart();
                            boolean done = cursor < 0;
                            List<Channels.Channel> snapshot;
                            if (server instanceof PlaylistSource) {
                                snapshot = page.channels();
                            } else {
                                for (Channels.Channel c : page.channels()) {
                                    if (accumulated.putIfAbsent(c.id(), c) != null)
                                        throw new java.io.IOException(
                                                "Jellyfin repeated a channel.");
                                }
                                if (!done && (replace || pages++ % 3 != 0)) continue;
                                snapshot = List.copyOf(accumulated.values());
                            }
                            publishCatalog(
                                    server, generation, favoriteBaseline, snapshot, done, null);
                            if (done) {
                                saved.write(snapshot, server);
                                return;
                            }
                        }
                    } catch (Exception failure) {
                        boolean expired =
                                failure instanceof Jellyfin.HttpFailure http && http.status == 401;
                        boolean denied =
                                failure instanceof Jellyfin.HttpFailure http && http.status == 403;
                        String error =
                                denied
                                        ? getString(R.string.no_live_tv_access)
                                        : needsLocalNetworkPermission()
                                                ? getString(R.string.local_network_access)
                                                : getString(R.string.channels_load_failed);
                        if (catalogGeneration != generation) return;
                        if (denied) {
                            saved.clear();
                            publishCatalog(
                                    server, generation, favoriteBaseline, List.of(), true, error);
                        } else if (expired || replace) {
                            ui.post(
                                    () -> {
                                        if (destroyed
                                                || api != server
                                                || catalogGeneration != generation) return;
                                        if (expired) {
                                            signOut(false);
                                            return;
                                        }
                                        catalogBusy = false;
                                        loadProblem = error;
                                        updateCount();
                                        toast(loadProblem);
                                    });
                        } else
                            publishCatalog(
                                    server,
                                    generation,
                                    favoriteBaseline,
                                    List.copyOf(accumulated.values()),
                                    true,
                                    error);
                    }
                });
    }

    private void publishCatalog(
            TvSource server,
            long generation,
            long favoriteBaseline,
            List<Channels.Channel> snapshot,
            boolean done,
            String error) {
        List<Channels.Channel> filtered =
                snapshot.stream()
                        .filter(c -> !c.adult())
                        .collect(java.util.stream.Collectors.toList());
        ui.post(
                () -> {
                    if (destroyed || api != server || catalogGeneration != generation) return;
                    fullLineup = snapshot;
                    filteredLineup = filtered;
                    catalogBusy = !done;
                    loadProblem = error;
                    if (done && (error == null || snapshot.isEmpty())) {
                        ++guideGeneration;
                        epg.clear();
                        noGuideUntil = guideCheckedUntil = guideBackoffUntil = 0;
                        favoriteOverrides
                                .values()
                                .removeIf(edit -> edit.revision() <= favoriteBaseline);
                        reconcilePlayback();
                    }
                    rebuildFavorites();
                    renderFilter(false);
                });
    }

    private List<Channels.Channel> visibleLineup() {
        return qualityFilter ? filteredLineup : fullLineup;
    }

    private void reconcilePlayback() {
        List<Channels.Channel> all = visibleLineup();
        player.reconcileChannels(all);
        resumeMain = currentChannel(resumeMain);
        resumeInset = currentChannel(resumeInset);
        if (resumeMain == null && resumeInset != null) {
            resumeMain = resumeInset;
            resumeMainPaused = resumeInsetPaused;
            resumeInset = null;
        }
    }

    private Channels.Channel currentChannel(Channels.Channel channel) {
        if (channel == null) return null;
        List<Channels.Channel> lineup = visibleLineup();
        int position = Channels.indexOf(lineup, channel.id());
        if (position < 0) return null;
        Channels.Channel current = lineup.get(position);
        return profile.direct() && !channel.name().equals(current.name()) ? null : current;
    }

    private Channels.Channel availableChannel(Channels.Channel channel) {
        if (api == null || channel == null) return null;
        Channels.Channel current = currentChannel(channel);
        if (current == null) toast(getString(R.string.channel_unavailable));
        return current;
    }

    private void rebuildFavorites() {
        HashSet<String> set = new HashSet<>();
        for (Channels.Channel c : visibleLineup()) {
            FavoriteEdit edit = favoriteOverrides.get(c.id());
            if (edit == null || !edit.name().equals(c.name()) ? c.favorite() : edit.value())
                set.add(c.id());
        }
        favorites = Set.copyOf(set);
    }

    private void renderFilter(boolean resetPosition) {
        resetSelection |= resetPosition;
        int generation = ++visibleGeneration;
        List<Channels.Channel> channels = visibleLineup();
        Set<String> stars = favorites;
        boolean starred = favoritesOnly;
        String term = query;
        queryButton.setText(term.isBlank() ? R.string.search : R.string.search_active);
        filterButton.setText(starred ? R.string.all_channels : R.string.favorites);
        search.execute(
                () -> {
                    if (generation != visibleGeneration) return;
                    List<Channels.Channel> filtered =
                            Channels.filter(channels, stars, starred, term);
                    Map<String, Integer> index = Channels.jumpTargets(filtered);
                    ui.post(
                            () -> {
                                if (destroyed || api == null || generation != visibleGeneration)
                                    return;
                                View focus = list.getFocusedChild();
                                int focusPosition =
                                        focus == null
                                                ? RecyclerView.NO_POSITION
                                                : list.getChildAdapterPosition(focus);
                                adapter.replaceChannels(filtered);
                                jumps = index;
                                int position =
                                        selected == null
                                                ? -1
                                                : Channels.indexOf(filtered, selected.id());
                                int restoredPosition =
                                        position >= 0
                                                ? position
                                                : Channels.jumpPage(
                                                        focusedPosition, filtered.size(), 0);
                                if (filtered.isEmpty()) {
                                    if (list.hasFocus())
                                        (starred && term.isBlank() ? filterButton : queryButton)
                                                .requestFocusFromTouch();
                                    selected = null;
                                    pendingJump = null;
                                    focusedPosition = 0;
                                } else if (resetSelection
                                        || pendingJump != null
                                        || (focus != null && restoredPosition != focusPosition)) {
                                    jump(resetSelection ? 0 : restoredPosition);
                                } else {
                                    focusedPosition = restoredPosition;
                                    selected = filtered.get(focusedPosition);
                                }
                                boolean focusRecovery = resetSelection && filtered.isEmpty();
                                resetSelection = false;
                                updateCount();
                                if (focusRecovery && emptyAction.isShown())
                                    emptyAction.requestFocus();
                                updateSelection();
                                scheduleGuide();
                            });
                });
    }

    private void updateCount() {
        int n = adapter.getItemCount();
        boolean hasLineup = !fullLineup.isEmpty();
        boolean canClear = !query.isBlank() || favoritesOnly;
        boolean canRetry = !hasLineup && loadProblem != null;
        if (jumpButton.hasFocus() && n == 0) queryButton.requestFocusFromTouch();
        String label =
                getResources()
                        .getQuantityString(
                                favoritesOnly ? R.plurals.favorite_count : R.plurals.channel_count,
                                n,
                                n);
        if (!hasLineup && (catalogBusy || canRetry)) label = getString(R.string.channels);
        else if (catalogBusy) label = getString(R.string.refreshing_channel_count, label);
        else if (loadProblem != null && hasLineup)
            label = getString(R.string.partial_channel_count, label);
        else if (!query.isBlank()) label = getString(R.string.search_channel_count, label, query);
        count.setText(label);
        jumpButton.setEnabled(n > 0);
        emptyState.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        pager.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        boolean canRecover = n == 0 && (hasLineup || !catalogBusy) && (canRetry || canClear);
        if (!canRecover && emptyAction.hasFocus()) queryButton.requestFocus();
        emptyAction.setVisibility(canRecover ? View.VISIBLE : View.GONE);
        emptyAction.setText(
                canRetry
                        ? R.string.retry
                        : !query.isBlank() ? R.string.clear_search : R.string.all_channels);
        queryButton.setNextFocusDownId(canRecover ? emptyAction.getId() : list.getId());
        filterButton.setNextFocusDownId(canRecover ? emptyAction.getId() : list.getId());
        empty.setText(
                canRetry
                        ? loadProblem
                        : getString(
                                catalogBusy && !hasLineup
                                        ? R.string.loading_channels
                                        : !query.isBlank()
                                                ? R.string.no_matching_channels
                                                : favoritesOnly
                                                        ? R.string.no_favorites
                                                        : qualityFilter && !fullLineup.isEmpty()
                                                                ? R.string.no_filtered_channels
                                                                : R.string.no_channels));
    }

    private void searchDialog() {
        EditText field =
                edit(
                        getString(R.string.search_hint),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(query);
        field.setSelectAllOnFocus(true);
        field.setMinimumHeight(dp(52));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        TvDialog dialog = new TvDialog(this, getString(R.string.find_channel));
        dialog.add(field, 16);
        LinearLayout actions = new LinearLayout(this);
        Button submit =
                dialog.action(R.string.search, () -> applySearch(field.getText().toString()));
        submit.setId(View.generateViewId());
        field.setNextFocusDownId(submit.getId());
        addButton(actions, submit);
        if (!query.isBlank())
            addButton(actions, dialog.action(R.string.clear_search, () -> applySearch("")));
        addButton(actions, button(R.string.cancel, dialog::dismiss));
        dialog.add(actions, 12);
        field.setOnEditorActionListener(
                (view, action, event) -> {
                    if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                    submit.performClick();
                    return true;
                });
        dialog.show(field);
        WindowCompat.getInsetsController(dialog.window(), field)
                .show(WindowInsetsCompat.Type.ime());
    }

    private void applySearch(String value) {
        query = value.trim();
        renderFilter(true);
    }

    private void jumpDialog() {
        if (adapter.getItemCount() == 0) return;
        TvDialog dialog = new TvDialog(this, getString(R.string.jump_to_channel));
        LinearLayout edges = new LinearLayout(this);
        Button first = dialog.action(R.string.first_channel, () -> jump(0));
        addButton(edges, first);
        addButton(
                edges,
                dialog.action(R.string.last_channel, () -> jump(adapter.getItemCount() - 1)));
        dialog.add(edges, 14);
        int column = 0;
        int columns = root.getWidth() >= dp(620) ? 6 : 4;
        LinearLayout letters = null;
        for (String letter : jumps.keySet()) {
            if (column++ % columns == 0) {
                letters = new LinearLayout(this);
                dialog.add(letters, 7);
            }
            Button choice =
                    dialog.action(
                            letter,
                            () -> {
                                Integer position = jumps.get(letter);
                                if (position != null) jump(position);
                            });
            choice.setContentDescription(getString(R.string.jump_to_letter, letter));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(44), 1);
            cell.rightMargin = dp(8);
            letters.addView(choice, cell);
        }
        while (column++ % columns != 0) {
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(44), 1);
            cell.rightMargin = dp(8);
            letters.addView(new View(this), cell);
        }
        dialog.show(first);
    }

    private void page(int delta) {
        jump(Channels.jumpPage(focusedPosition, adapter.getItemCount(), delta), !pager.hasFocus());
    }

    private void jump(int index) {
        jump(index, true);
    }

    private void jump(int index, boolean focusRow) {
        if (adapter.getItemCount() == 0 || index < 0 || index >= adapter.getItemCount()) return;
        focusedPosition = index;
        selected = adapter.channels.get(index);
        updateSelection();
        // Park focus outside the recycled rows until the target has been laid out.
        // Otherwise RecyclerView can scroll the previously focused row back into view.
        pendingJump = focusRow ? selected.id() : null;
        list.stopScroll();
        if (list.hasFocus()) queryButton.requestFocus();
        rows.scrollToPositionWithOffset(index, 0);
        scheduleGuide();
    }

    private boolean isPlayingSelection() {
        return selected != null
                && player.primary() != null
                && selected.id().equals(player.primary().id());
    }

    private void updateSelection() {
        if (api == null || selectedName == null) return;
        selectedName.setText(
                selected == null ? getString(R.string.no_channel_selected) : selected.label());
        programText.setText(selected == null ? "" : guideLine(selected.id(), true));
        if (guideButton.hasFocus() && selected == null) queryButton.requestFocusFromTouch();
        guideButton.setEnabled(selected != null);
        boolean tuned = isPlayingSelection();
        Channels.Channel playing = player.primary();
        boolean paused = player.primaryStatus() == Playback.Status.PAUSED;
        previewLabel.setText(
                playing == null
                        ? ""
                        : getString(
                                paused ? R.string.paused_channel : R.string.watching_channel,
                                playing.label()));
        previewLabel.setVisibility(
                !fullscreen
                                && playing != null
                                && (paused || (!tuned && player.primaryStatus().live()))
                        ? View.VISIBLE
                        : View.GONE);
    }

    private void setFavorite(Channels.Channel channel, boolean value) {
        Channels.Channel current = availableChannel(channel);
        if (current == null) return;
        TvSource server = api;
        if (!pendingFavorites.add(current.id())) return;
        Runnable save =
                () -> {
                    boolean ok;
                    try {
                        server.favorite(current, value);
                        ok = true;
                    } catch (Exception failure) {
                        ok = false;
                    }
                    boolean saved = ok;
                    ui.post(
                            () -> {
                                if (destroyed || api != server) return;
                                pendingFavorites.remove(current.id());
                                if (saved) {
                                    favoriteOverrides.put(
                                            current.id(),
                                            new FavoriteEdit(
                                                    current.name(), value, ++favoriteRevision));
                                    rebuildFavorites();
                                    if (favoritesOnly) renderFilter(false);
                                    else refreshGuideRows();
                                } else toast(getString(R.string.favorite_update_failed));
                            });
                };
        // Playlist favorites only update SharedPreferences. Apply before a switch can
        // destroy this activity and cancel its queued account work.
        if (server instanceof PlaylistSource) save.run();
        else accounts.execute(save);
    }

    private void channelActions(Channels.Channel selection) {
        Channels.Channel channel = availableChannel(selection);
        if (channel == null) return;
        int position = Channels.indexOf(adapter.channels, channel.id());
        if (position >= 0) focusedPosition = position;
        selected = channel;
        updateSelection();
        TvDialog dialog = new TvDialog(this, channel.label());
        Button watch = dialog.action(R.string.play_channel, () -> tune(channel, false));
        dialog.add(watch, 16);
        if (player.primary() != null && !player.primary().id().equals(channel.id())) {
            Channels.Channel insetChannel = player.secondary();
            boolean inInset = insetChannel != null && insetChannel.id().equals(channel.id());
            if (!inInset || player.secondaryStatus().failed()) {
                dialog.add(
                        dialog.action(
                                inInset ? R.string.retry_inset : R.string.play_in_inset,
                                () -> tune(channel, true)),
                        8);
            }
        }
        dialog.add(dialog.action(R.string.guide, () -> scheduleDialog(channel)), 8);
        boolean starred = favorites.contains(channel.id());
        Button favorite =
                dialog.action(
                        starred ? R.string.remove_favorite : R.string.add_favorite,
                        () -> setFavorite(channel, !starred));
        favorite.setEnabled(!pendingFavorites.contains(channel.id()));
        dialog.add(favorite, 8);
        if (!profile.direct())
            dialog.add(
                    dialog.action(
                            R.string.compatible_stream,
                            () -> {
                                Channels.Channel current = availableChannel(channel);
                                if (current != null) player.tuneCompatible(api, current);
                            }),
                    8);
        dialog.show(watch);
    }

    private void tune(Channels.Channel channel, boolean small) {
        Channels.Channel current = availableChannel(channel);
        if (current == null) return;
        if (small && player.primary() == null) small = false;
        player.tune(api, current, small);
    }

    private void updatePlaybackDetails() {
        Channels.Channel main = player.primary(), small = player.secondary();
        mainMessage.setText(
                main == null
                        ? getString(R.string.choose_channel)
                        : getString(
                                R.string.playback_message, main.label(), player.primaryDetail()));
        insetMessage.setText(
                small == null
                        ? ""
                        : getString(
                                R.string.playback_message,
                                small.label(),
                                player.secondaryDetail()));
    }

    private void updatePlayback() {
        if (api == null || player == null) return;
        Channels.Channel main = player.primary(), small = player.secondary();
        Playback.Status status = player.primaryStatus(), smallStatus = player.secondaryStatus();
        ui.removeCallbacks(loadingRefresh);
        updatePlaybackDetails();
        if (foreground && (loading(status) || loading(smallStatus)))
            ui.postDelayed(loadingRefresh, LOADING_REFRESH_MS);
        mainMessage.setGravity(main == null ? Gravity.CENTER : Gravity.TOP | Gravity.START);
        mainMessage.setBackgroundColor(main == null ? 0x00000000 : 0xaa171719);
        mainMessage.setVisibility(
                status.live() || status == Playback.Status.PAUSED ? View.GONE : View.VISIBLE);
        inset.setVisibility(small == null ? View.GONE : View.VISIBLE);
        insetMessage.setBackgroundColor(0xaa171719);
        insetMessage.setVisibility(smallStatus.live() ? View.GONE : View.VISIBLE);
        watchTitle.setText(
                main == null
                        ? ""
                        : small == null
                                ? main.label()
                                : getString(R.string.watch_title, main.label(), small.label()));
        watchStatus.setText(
                main == null
                        ? ""
                        : getString(
                                status.failed()
                                        ? R.string.unavailable
                                        : status == Playback.Status.LIVE_COMPATIBLE
                                                ? R.string.live_compatible_short
                                                : status.label));
        if (!status.failed() && watchRetry.hasFocus()) returnToChannels.requestFocus();
        watchRetry.setVisibility(status.failed() ? View.VISIBLE : View.GONE);
        if (!player.canPause() && watchPause.hasFocus()) returnToChannels.requestFocus();
        watchPause.setEnabled(player.canPause());
        watchPause.setText(
                status == Playback.Status.PAUSED ? R.string.resume_live : R.string.pause);
        watchSwap.setVisibility(small == null ? View.GONE : View.VISIBLE);
        watchClose.setVisibility(small == null ? View.GONE : View.VISIBLE);
        root.setKeepScreenOn(foreground && player.hasVideo());
        updateSelection();
        if (fullscreen) {
            if (status.failed() || smallStatus.failed() || status == Playback.Status.PAUSED)
                showWatchControls();
            else scheduleWatchControls();
        }
    }

    private void scheduleWatchControls() {
        ui.removeCallbacks(hideWatchControls);
        if (fullscreen && foreground && watchOverlay.isShown())
            ui.postDelayed(hideWatchControls, 5_000);
    }

    private void showWatchControls() {
        if (!fullscreen) return;
        boolean hidden = watchOverlay.getVisibility() != View.VISIBLE;
        watchOverlay.setVisibility(View.VISIBLE);
        layoutInset();
        if (hidden || video.isFocused()) returnToChannels.requestFocus();
        scheduleWatchControls();
    }

    private void closeInset(Button fallback) {
        fallback.requestFocusFromTouch();
        player.stopInset();
    }

    private void swapPictures() {
        FrameLayout previous = mainPicture;
        mainPicture = inset;
        inset = previous;
        TextView message = mainMessage;
        mainMessage = insetMessage;
        insetMessage = message;
        mainPicture.setPadding(0, 0, 0, 0);
        mainPicture.setBackground(null);
        mainPicture.setContentDescription(null);
        mainPicture.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mainMessage.setTextSize(18);
        mainMessage.setPadding(
                dp(fullscreen ? 48 : 22),
                dp(fullscreen ? 27 : 12),
                dp(fullscreen ? 48 : 22),
                dp(fullscreen ? 27 : 12));
        inset.setPadding(dp(2), dp(2), dp(2), dp(2));
        inset.setBackground(box(0xff171719, ACCENT, 0));
        inset.setContentDescription(getString(R.string.muted_inset));
        inset.setLayoutParams(new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.END));
        insetMessage.setTextSize(14);
        insetMessage.setPadding(0, 0, 0, 0);
        inset.bringToFront();
        previewLabel.bringToFront();
        layoutInset();
    }

    private void layoutInset() {
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) inset.getLayoutParams();
        int width = Math.round(video.getWidth() / (float) Math.sqrt(8));
        int height = Math.round(video.getHeight() / (float) Math.sqrt(8));
        int margin = dp(12);
        int bottom =
                margin
                        + (fullscreen && watchOverlay.getVisibility() == View.VISIBLE
                                ? dp(WATCH_BAR_HEIGHT)
                                : 0);
        if (size.width == width
                && size.height == height
                && size.rightMargin == margin
                && size.bottomMargin == bottom) return;
        size.width = width;
        size.height = height;
        size.rightMargin = margin;
        size.bottomMargin = bottom;
        inset.setLayoutParams(size);
    }

    private void layoutVideo() {
        // Keep both native surfaces attached while changing the video area's bounds.
        int width = root.getWidth(), height = root.getHeight(), left = 0, top = 0;
        if (!fullscreen) {
            int[] anchor = new int[2], origin = new int[2];
            previewSlot.getLocationInWindow(anchor);
            root.getLocationInWindow(origin);
            left = anchor[0] - origin[0];
            top = anchor[1] - origin[1];
            width = previewSlot.getWidth();
            height = previewSlot.getHeight();
        }
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (size.width == width
                && size.height == height
                && size.leftMargin == left
                && size.topMargin == top) return;
        size.width = width;
        size.height = height;
        size.leftMargin = left;
        size.topMargin = top;
        video.setLayoutParams(size);
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled || video == null) return;
        fullscreen = enabled;
        video.setForeground(null);
        revealKey = KeyEvent.KEYCODE_UNKNOWN;
        ui.removeCallbacks(hideWatchControls);
        shell.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
        watchOverlay.setVisibility(View.GONE);
        layoutVideo();
        if (enabled) video.requestFocus();
        else returnToSelectedChannel();
        layoutInset();
        mainMessage.setPadding(
                dp(fullscreen ? 48 : 22),
                dp(fullscreen ? 27 : 12),
                dp(fullscreen ? 48 : 22),
                dp(fullscreen ? 27 : 12));
        updatePlayback();
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
        List<Channels.Channel> snapshot = adapter.channels;
        for (int i = first; i <= last && i < snapshot.size() && ids.size() < GUIDE_BATCH; i++) {
            String id = snapshot.get(i).id();
            if (epg.get(window, id, elapsed) == null) ids.add(id);
        }
        if (ids.isEmpty()) return;
        boolean probe = elapsed >= guideCheckedUntil;
        int generation = guideGeneration;
        TvSource server = api;
        guideBusy = true;
        guide.execute(
                () -> {
                    try {
                        boolean available = !probe || server.hasGuide(window, GUIDE_HOURS);
                        Map<String, List<Channels.Program>> result =
                                available ? server.programs(ids, window, GUIDE_HOURS) : Map.of();
                        ui.post(
                                () -> {
                                    if (destroyed || api != server) return;
                                    guideBusy = false;
                                    if (generation != guideGeneration) {
                                        scheduleGuide();
                                        return;
                                    }
                                    long time = SystemClock.elapsedRealtime();
                                    if (probe) guideCheckedUntil = time + GuideCache.FRESH;
                                    if (!available) noGuideUntil = time + GuideCache.FRESH;
                                    else
                                        result.forEach(
                                                (id, programs) ->
                                                        epg.put(window, id, programs, time));
                                    refreshGuideRows();
                                    if (available) scheduleGuide();
                                });
                    } catch (Exception failure) {
                        ui.post(
                                () -> {
                                    if (destroyed || api != server) return;
                                    guideBusy = false;
                                    if (generation != guideGeneration) {
                                        scheduleGuide();
                                        return;
                                    }
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
            if (window == guideWindow && elapsed < noGuideUntil)
                return details ? getString(R.string.no_server_guide) : "";
            if (window == guideWindow && elapsed < guideBackoffUntil)
                return details ? getString(R.string.guide_unavailable) : "";
            return "";
        }
        Channels.Program current = Channels.now(schedule, time),
                next = Channels.next(schedule, time);
        if (current == null && next == null) return details ? getString(R.string.no_listings) : "";
        String info =
                current == null
                        ? nextProgram(next)
                        : getString(R.string.guide_now, current.title());
        return details && current != null && next != null ? info + '\n' + nextProgram(next) : info;
    }

    private String nextProgram(Channels.Program program) {
        return getString(
                R.string.guide_next, clock.format(new Date(program.start())), program.title());
    }

    private void scheduleDialog(Channels.Channel selection) {
        Channels.Channel channel = availableChannel(selection);
        if (channel == null) return;
        TvSource server = api;
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
        Button now = button(R.string.guide_current, null);
        Button close = button(R.string.close, dialog::dismiss);
        Button retry = button(R.string.retry, null);
        addButton(actions, earlier);
        addButton(actions, now);
        addButton(actions, later);
        addButton(actions, close);
        dialog.add(actions, 14);
        Future<?>[] request = {null};
        Runnable fetch =
                new Runnable() {
                    @Override
                    public void run() {
                        long from = window[0];
                        int generation = guideGeneration;
                        request[0] =
                                guide.submit(
                                        () -> {
                                            List<Channels.Program> result;
                                            try {
                                                result =
                                                        server.programs(
                                                                        List.of(channel.id()),
                                                                        from,
                                                                        GUIDE_HOURS)
                                                                .get(channel.id());
                                            } catch (Exception failure) {
                                                result = null;
                                            }
                                            List<Channels.Program> programs = result;
                                            ui.post(
                                                    () -> {
                                                        if (destroyed
                                                                || server != api
                                                                || !dialog.isShowing()
                                                                || window[0] != from) return;
                                                        if (generation != guideGeneration) {
                                                            run();
                                                        } else if (programs == null) {
                                                            scheduleMessage(
                                                                    entries,
                                                                    R.string.guide_load_failed);
                                                            LinearLayout.LayoutParams size =
                                                                    new LinearLayout.LayoutParams(
                                                                            -2, dp(44));
                                                            size.topMargin = dp(12);
                                                            entries.addView(retry, size);
                                                        } else {
                                                            epg.put(
                                                                    from,
                                                                    channel.id(),
                                                                    programs,
                                                                    SystemClock.elapsedRealtime());
                                                            showSchedule(entries, programs);
                                                            schedule.post(
                                                                    () ->
                                                                            schedule.setFocusable(
                                                                                    entries
                                                                                                    .getHeight()
                                                                                            > schedule
                                                                                                    .getHeight()));
                                                        }
                                                    });
                                        });
                    }
                };
        Runnable cancel =
                () -> {
                    ui.removeCallbacks(fetch);
                    if (request[0] != null) request[0].cancel(false);
                };
        Runnable load =
                () -> {
                    cancel.run();
                    long from = window[0];
                    boolean current = from == GuideCache.window(System.currentTimeMillis());
                    if ((from == initial && earlier.hasFocus()) || (current && now.hasFocus()))
                        later.requestFocus();
                    if (from == limit && later.hasFocus()) earlier.requestFocus();
                    earlier.setEnabled(from > initial);
                    later.setEnabled(from < limit);
                    now.setEnabled(!current);
                    Date date = new Date(from);
                    Date end = new Date(from + step);
                    String startDate = guideDate.format(date), endDate = guideDate.format(end);
                    caption.setText(
                            startDate.equals(endDate)
                                    ? getString(
                                            R.string.guide_window,
                                            startDate,
                                            clock.format(date),
                                            clock.format(end))
                                    : getString(
                                            R.string.guide_window_overnight,
                                            startDate,
                                            clock.format(date),
                                            endDate,
                                            clock.format(end)));
                    if (retry.hasFocus() || schedule.hasFocus())
                        (later.isEnabled() ? later : earlier).requestFocus();
                    schedule.scrollTo(0, 0);
                    List<Channels.Program> cached =
                            epg.get(from, channel.id(), SystemClock.elapsedRealtime());
                    schedule.setFocusable(false);
                    if (cached != null) {
                        showSchedule(entries, cached);
                        schedule.post(
                                () ->
                                        schedule.setFocusable(
                                                entries.getHeight() > schedule.getHeight()));
                    } else {
                        scheduleMessage(entries, R.string.guide_loading);
                        ui.postDelayed(fetch, 150);
                    }
                };
        retry.setOnClickListener(view -> load.run());
        earlier.setOnClickListener(
                view -> {
                    window[0] = Math.max(initial, window[0] - step);
                    load.run();
                });
        later.setOnClickListener(
                view -> {
                    window[0] = Math.min(limit, window[0] + step);
                    load.run();
                });
        now.setOnClickListener(
                view -> {
                    window[0] =
                            Math.max(
                                    initial,
                                    Math.min(limit, GuideCache.window(System.currentTimeMillis())));
                    load.run();
                });
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

    void openPhoneSetup() {
        phone.show();
    }

    void phoneSubmitted(org.json.JSONObject form) {
        String method = form.optString("method");
        if (method.equals("cancel")) return;
        if (method.equals("expired")) {
            toast(getString(R.string.phone_expired));
            return;
        }
        if (method.equals("playlist") || method.equals("xtream")) {
            new PlaylistSetup(this, accounts, method.equals("xtream")).fromPhone(form);
        } else {
            if (api != null) {
                if (player != null) player.stopAll();
                getIntent().putExtra("addAccount", true);
            }
            if (signIn == null)
                signIn =
                        new SignInScreen(
                                this, accounts, java.util.UUID.randomUUID().toString(), "");
            signIn.fromPhone(form);
        }
    }

    void addPlaylist(boolean xtream) {
        new PlaylistSetup(this, accounts, xtream).show();
    }

    void profiles() {
        TvDialog dialog = new TvDialog(this, getString(R.string.profiles));
        LinearLayout rows = vertical();
        View initial = null;
        String active = api == null ? "" : store.activeId();
        for (String id : store.ids()) {
            boolean current = id.equals(active);
            String label = store.label(id);
            Button item =
                    dialog.action(
                            current ? getString(R.string.current_profile, label) : label,
                            () -> {
                                if (current) return;
                                ++catalogGeneration;
                                store.activate(id);
                                getIntent().removeExtra("addAccount");
                                recreate();
                            });
            item.setMinimumHeight(dp(44));
            rows.addView(item);
            if (current || initial == null) initial = item;
        }
        if (initial != null) dialog.scroll(rows, Math.min(185, rows.getChildCount() * 44 + 16));
        if (api != null) {
            dialog.add(text(getString(R.string.add_profile), 16, DIM), 16);
            dialog.add(button(R.string.phone_setup, this::openPhoneSetup), 8);
            dialog.add(
                    dialog.action(
                            R.string.source_jellyfin,
                            () -> {
                                getIntent().putExtra("addAccount", true);
                                recreate();
                            }),
                    8);
            dialog.add(button(R.string.source_xtream, () -> addPlaylist(true)), 8);
            dialog.add(button(R.string.source_playlist, () -> addPlaylist(false)), 8);
        }
        dialog.show(initial);
    }

    void showPrivacyPolicy() {
        TvDialog dialog = new TvDialog(this, getString(R.string.privacy_policy));
        TextView policy = text(
                TextUtils.join("\n\n", getResources().getStringArray(R.array.privacy_policy_sections)),
                17,
                TEXT);
        var scroll = dialog.scroll(policy, 280);
        scroll.setFocusable(true);
        dialog.add(button(R.string.close, dialog::dismiss), 8);
        dialog.show(scroll);
    }

    private void serverDialog() {
        if (api == null) return;
        TvDialog dialog = new TvDialog(this, getString(R.string.server));
        TextView address = text(profile.label(), 17, DIM);
        address.setSingleLine();
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        dialog.add(address, 8);
        Button refresh =
                dialog.action(
                        R.string.refresh_channels,
                        () ->
                                requestLocalNetwork(
                                        granted -> {
                                            if (!granted) {
                                                toast(getString(R.string.local_network_access));
                                                return;
                                            }
                                            loadCatalog();
                                        }));
        Button switchProfile = dialog.action(R.string.switch_profile, this::profiles);
        dialog.add(switchProfile, 16);
        dialog.add(refresh, 8);
        android.widget.CheckBox filter = new android.widget.CheckBox(this);
        filter.setText(R.string.quality_filter);
        filter.setTextSize(15);
        filter.setTextColor(TEXT);
        filter.setBackground(focusBackground(BAR));
        filter.setChecked(qualityFilter);
        filter.setOnCheckedChangeListener(
                (button, checked) -> {
                    qualityFilter = checked;
                    getPreferences(MODE_PRIVATE)
                            .edit()
                            .putBoolean("qualityFilter", checked)
                            .apply();
                    reconcilePlayback();
                    rebuildFavorites();
                    renderFilter(false);
                });
        dialog.add(filter, 8);
        dialog.add(button(R.string.privacy_policy, this::showPrivacyPolicy), 8);
        dialog.add(button(R.string.close, dialog::dismiss), 8);
        dialog.add(
                dialog.action(
                        profile.direct() ? R.string.remove_profile : R.string.sign_out,
                        this::confirmSignOut),
                16);
        dialog.show(switchProfile);
    }

    private void confirmSignOut() {
        TvDialog dialog =
                new TvDialog(
                        this,
                        getString(
                                profile.direct()
                                        ? R.string.remove_profile_title
                                        : R.string.sign_out_title));
        dialog.add(
                text(
                        profile.direct()
                                ? getString(R.string.remove_profile_detail, profile.name())
                                : getString(R.string.sign_out_description),
                        17,
                        DIM),
                8);
        LinearLayout actions = new LinearLayout(this);
        Button cancel =
                button(
                        profile.direct() ? R.string.cancel : R.string.stay_signed_in,
                        dialog::dismiss);
        addButton(actions, cancel);
        Button signOut =
                dialog.action(
                        profile.direct() ? R.string.remove_profile : R.string.sign_out,
                        () -> signOut(true));
        addButton(actions, signOut);
        dialog.add(actions, 16);
        dialog.show(cancel);
    }

    private void signOut(boolean revoke) {
        ++catalogGeneration;
        ++visibleGeneration;
        TvSource server = api;
        signingOut = revoke;
        player.destroy(
                revoke
                        ? () -> {
                            String message;
                            try {
                                server.logout();
                                message =
                                        getString(
                                                server instanceof PlaylistSource
                                                        ? R.string.profile_removed
                                                        : R.string.signed_out);
                            } catch (java.io.IOException failure) {
                                message =
                                        getString(
                                                failure instanceof Jellyfin.HttpFailure http
                                                                && http.status == 401
                                                        ? R.string.signed_out
                                                        : R.string.signed_out_locally);
                            }
                            String result = message;
                            ui.post(
                                    () -> {
                                        signingOut = false;
                                        if (!destroyed && api == null) showLogin(result);
                                    });
                        }
                        : null);
        player = null;
        api = null;
        selected = null;
        resumeMain = null;
        resumeInset = null;
        fullLineup = filteredLineup = List.of();
        favorites = Set.of();
        favoriteOverrides.clear();
        pendingFavorites.clear();
        pendingJump = null;
        focusedPosition = 0;
        resetSelection = false;
        query = "";
        favoritesOnly = false;
        fullscreen = false;
        catalogBusy = false;
        guideBusy = false;
        ++guideGeneration;
        noGuideUntil = guideCheckedUntil = guideBackoffUntil = 0;
        epg.clear();
        lineupCache.close(true);
        store.clear(profile);
        int pending = profile.direct() ? R.string.removing_profile : R.string.signing_out;
        showLogin(getString(revoke ? pending : R.string.sign_in_expired));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        if (api != null && player != null) {
            if (resumeMain != null) player.tune(api, resumeMain, false, resumeMainPaused);
            if (resumeInset != null && resumeMain != null)
                player.tune(api, resumeInset, true, resumeInsetPaused);
            resumeMain = null;
            resumeInset = null;
            scheduleGuide();
        }
        ui.postDelayed(minute, 30_000);
    }

    @Override
    protected void onStop() {
        phone.close();
        foreground = false;
        ui.removeCallbacks(loadingRefresh);
        ui.removeCallbacks(minute);
        ui.removeCallbacks(guideRefresh);
        ui.removeCallbacks(hideWatchControls);
        if (player != null) {
            resumeMain = player.primary();
            resumeInset = player.secondary();
            resumeMainPaused = player.primaryStatus() == Playback.Status.PAUSED;
            resumeInsetPaused = player.secondaryStatus() == Playback.Status.PAUSED;
            player.stopAll();
        }
        super.onStop();
    }

    @Override
    public void dump(
            String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (player != null) player.dump(writer);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        dismissDialogs();
        if (lineupCache != null) lineupCache.close(false);
        ui.removeCallbacks(loadingRefresh);
        ++catalogGeneration;
        ++visibleGeneration;
        ui.removeCallbacks(hideWatchControls);
        if (signIn != null) signIn.cancel();
        if (player != null) player.destroy(null);
        accounts.shutdownNow();
        catalog.shutdownNow();
        search.shutdownNow();
        guide.shutdownNow();
        super.onDestroy();
    }

    private boolean handleWatchKey(KeyEvent event) {
        if (signIn != null) return false;
        if (handleMediaKey(event)) return true;
        int key = event.getKeyCode();
        if (key == revealKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) revealKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (fullscreen && event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean navigation =
                    key == KeyEvent.KEYCODE_DPAD_CENTER
                            || key == KeyEvent.KEYCODE_ENTER
                            || key == KeyEvent.KEYCODE_DPAD_UP
                            || key == KeyEvent.KEYCODE_DPAD_DOWN
                            || key == KeyEvent.KEYCODE_DPAD_LEFT
                            || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (navigation && watchOverlay.getVisibility() != View.VISIBLE) {
                revealKey = key;
                showWatchControls();
                return true;
            }
            scheduleWatchControls();
        }
        return false;
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) scheduleWatchControls();
        else ui.removeCallbacks(hideWatchControls);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        return handleRemoteKey(event) || super.onKeyDown(key, event);
    }

    private static boolean isPageKey(int key) {
        return key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP;
    }

    private boolean handleMediaKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (player == null
                || player.primary() == null
                || (key != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        && key != KeyEvent.KEYCODE_MEDIA_PLAY
                        && key != KeyEvent.KEYCODE_MEDIA_PAUSE)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            player.setPlaying(
                    key == KeyEvent.KEYCODE_MEDIA_PLAY
                            || (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                    && player.primaryStatus() == Playback.Status.PAUSED));
        }
        return true;
    }

    private boolean handleRemoteKey(KeyEvent event) {
        if (signIn != null) return false;
        if (handleMediaKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && api != null && !fullscreen) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_PAGE_DOWN
                    || key == KeyEvent.KEYCODE_CHANNEL_DOWN
                    || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                page(50);
                return true;
            }
            if (key == KeyEvent.KEYCODE_PAGE_UP
                    || key == KeyEvent.KEYCODE_CHANNEL_UP
                    || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
                page(-50);
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                if (selected != null) channelActions(selected);
                else jumpDialog();
                return true;
            }
            if (key == KeyEvent.KEYCODE_GUIDE) {
                scheduleDialog(selected);
                return true;
            }
            if (key == KeyEvent.KEYCODE_SEARCH) {
                searchDialog();
                return true;
            }
        }
        return false;
    }

    private void back() {
        if (signIn != null && signIn.back()) return;
        if (getIntent().getBooleanExtra("addAccount", false)) {
            getIntent().removeExtra("addAccount");
            recreate();
            return;
        }
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (!query.isBlank()) {
            applySearch("");
            return;
        }
        if (favoritesOnly) {
            favoritesOnly = false;
            renderFilter(true);
            return;
        }
        if (player != null && player.primary() != null) {
            player.stopAll();
            return;
        }
        finish();
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelRow> {
        private List<Channels.Channel> channels = List.of();

        void replaceChannels(List<Channels.Channel> replacement) {
            if (channels == replacement) return;
            int oldSize = channels.size();
            channels = replacement;
            // Filtering replaces the rows by position. A move-detecting diff can take
            // quadratic time when switching between a full lineup and a few matches.
            int common = Math.min(oldSize, channels.size());
            if (common > 0) notifyItemRangeChanged(0, common);
            if (oldSize > common) notifyItemRangeRemoved(common, oldSize - common);
            if (channels.size() > common) notifyItemRangeInserted(common, channels.size() - common);
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }

        @Override
        public ChannelRow onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = vertical();
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(56)));
            row.setPadding(dp(14), dp(7), dp(10), dp(5));
            row.setBackground(focusBackground(PANEL));
            row.setFocusable(true);
            row.setClickable(true);
            TextView title = text("", 16, TEXT);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView info = text("", 13, DIM);
            info.setSingleLine();
            info.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);
            row.addView(info);
            ChannelRow holder = new ChannelRow(row, title, info);
            row.setOnFocusChangeListener(
                    (view, focused) -> {
                        int p = holder.getBindingAdapterPosition();
                        if (focused && p != RecyclerView.NO_POSITION) {
                            if (pendingJump != null && !pendingJump.equals(channels.get(p).id()))
                                return;
                            focusedPosition = p;
                            selected = channels.get(p);
                            updateSelection();
                            scheduleGuide();
                        }
                    });
            row.setOnClickListener(
                    v -> {
                        int p = holder.getBindingAdapterPosition();
                        if (p != RecyclerView.NO_POSITION) {
                            pendingJump = null;
                            focusedPosition = p;
                            selected = channels.get(p);
                            updateSelection();
                            if (isPlayingSelection() && !player.primaryStatus().failed())
                                setFullscreen(true);
                            else tune(selected, false);
                        }
                    });
            row.setOnLongClickListener(
                    v -> {
                        int p = holder.getBindingAdapterPosition();
                        if (p == RecyclerView.NO_POSITION) return false;
                        channelActions(channels.get(p));
                        return true;
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(ChannelRow holder, int position) {
            Channels.Channel channel = channels.get(position);
            String label = channel.label();
            holder.title.setText(
                    favorites.contains(channel.id())
                            ? getString(R.string.favorite_channel_label, label)
                            : label);
            holder.guide.setText(guideLine(channel.id(), false));
        }
    }

    private static final class ChannelRow extends RecyclerView.ViewHolder {
        final TextView title, guide;

        ChannelRow(View item, TextView title, TextView guide) {
            super(item);
            this.title = title;
            this.guide = guide;
        }
    }
}
