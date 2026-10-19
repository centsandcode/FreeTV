package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String HERO = "hero", RECENT = "recent", FAVS = "favs", ALL = "apps";
    private static final int RECENT_SHOWN = 8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private final List<TextView> headChips = new ArrayList<>();
    private final List<Row> appRows = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, toTop;
    private FrameLayout root;
    private ScrollView page;
    private ImageView wall;
    private View shade, glow;
    private TextView greeting, clock, date, stats;
    private LinearLayout quick, appsBox;
    private Row inputs;
    private List<Item> appsAll = new ArrayList<>();
    private Tile moving;
    private String wallKey;

    private LinearLayout heroBox;
    private FrameLayout heroSlot;
    private TextView heroName;
    private Tile heroTile;

    private int glowColor;
    private ValueAnimator glowAnim;

    private MediaSessionManager sessions;
    private MediaController controller;
    private LinearLayout playing;
    private TextView playText, playToggle;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionWatch = this::pickSession;

    private final MediaController.Callback mediaWatch = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            showPlaying();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showPlaying();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
        }
    };

    private final class Row {
        final String key, kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        List<Item> all = new ArrayList<>();

        Row(String key, String kind, String name, LinearLayout parent) {
            this.key = key;
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.getDefault()));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(12), bleed, dp(8));
            scroll = new HorizontalScrollView(Home.this);
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        void setVisible(boolean on) {
            int v = on ? View.VISIBLE : View.GONE;
            title.setVisibility(v);
            scroll.setVisibility(v);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        loadApps(null);
        loadInputs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        watchMedia(true);
        if (toTop) {
            toTop = false;
            focusFirst();
        }
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        watchMedia(false);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        // HOME lands at the top; when this arrives before onResume, onResume does it after re-rendering.
        if (resumed) focusFirst();
        else toTop = true;
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private View build() {
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        glow = new View(this);
        glow.setVisibility(View.GONE);
        root.addView(glow, new FrameLayout.LayoutParams(MATCH, MATCH));

        page = new ScrollView(this);
        page.setFocusable(false);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && headChips.contains(focused)) {
                    View below = quick.getVisibility() == View.VISIBLE ? nearest(quick, focused) : firstTile(focused);
                    if (below != null) return below;
                }
                if (direction == View.FOCUS_DOWN && focused.getParent() == quick) {
                    View below = firstTile(focused);
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(34), 0, dp(32));
        page.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(8));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        greeting.setPadding(dp(2), 0, 0, dp(6));
        clock = Ui.text(this, 52, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(8), 0, 0);
        stats = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        stats.setPadding(dp(2), dp(6), 0, 0);
        when.addView(greeting);
        when.addView(clock);
        when.addView(date);
        when.addView(stats);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        LinearLayout top = new LinearLayout(this);
        top.addView(headChip(chip("Options", v -> startActivity(new Intent(this, Options.class)))));
        top.addView(headChip(chip("Settings", v -> openSettings())));
        side.addView(top);
        playing = new LinearLayout(this);
        playing.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        playing.setPadding(0, dp(14), 0, 0);
        playing.setVisibility(View.GONE);
        playText = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        playText.setSingleLine();
        playText.setEllipsize(TextUtils.TruncateAt.END);
        playText.setMaxWidth(dp(300));
        playing.addView(playText);
        playToggle = chip("", v -> toggleMedia());
        playing.addView(spaced(playToggle, dp(12)));
        playing.addView(spaced(chip(Ui.t("Next", "Siguiente"), v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        }), dp(8)));
        side.addView(playing);
        head.addView(side, new LinearLayout.LayoutParams(WRAP, WRAP));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        quick = new LinearLayout(this);
        quick.setPadding(dp(56), dp(4), dp(56), dp(4));
        quick.setClipChildren(false);
        quick.setClipToPadding(false);
        addQuick(Ui.t("Wi-Fi", "Wi-Fi"), new Intent(Settings.ACTION_WIFI_SETTINGS));
        addQuick(Ui.t("Display & sound", "Pantalla y sonido"), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        addQuick(Ui.t("Remotes & Bluetooth", "Mandos y Bluetooth"), new Intent().setClassName(
                "com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"));
        addQuick(Ui.t("Apps", "Aplicaciones"), new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        column.addView(quick, new LinearLayout.LayoutParams(MATCH, WRAP));

        heroBox = new LinearLayout(this);
        heroBox.setGravity(Gravity.CENTER_VERTICAL);
        heroBox.setPadding(dp(56), dp(18), dp(56), dp(4));
        heroBox.setClipChildren(false);
        heroBox.setClipToPadding(false);
        heroSlot = new FrameLayout(this);
        heroSlot.setClipChildren(false);
        heroBox.addView(heroSlot, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(28), 0, 0, 0);
        TextView heroLabel = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        heroLabel.setText(Ui.t("CONTINUE", "CONTINUAR"));
        heroLabel.setLetterSpacing(0.14f);
        heroName = Ui.text(this, 26, Ui.TEXT, Ui.LIGHT);
        heroName.setPadding(0, dp(8), 0, dp(8));
        TextView heroHint = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        heroHint.setText(Ui.t("Press OK to jump back in", "Pulsa OK para volver donde lo dejaste"));
        heroText.addView(heroLabel);
        heroText.addView(heroName);
        heroText.addView(heroHint);
        heroBox.addView(heroText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        heroBox.setVisibility(View.GONE);
        column.addView(heroBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        appsBox.setClipChildren(false);
        appsBox.setClipToPadding(false);
        column.addView(appsBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        inputs = new Row(Catalog.INPUTS, Catalog.INPUTS, "Inputs", column);
        return root;
    }

    private TextView headChip(TextView v) {
        headChips.add(v);
        return spaced(v, dp(10));
    }

    private static TextView spaced(TextView v, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = left;
        v.setLayoutParams(lp);
        return v;
    }

    private void addQuick(String label, Intent target) {
        if (target.resolveActivity(getPackageManager()) == null) return;
        TextView v = chip(label, c -> launch(new Intent(target), null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(10);
        lp.topMargin = dp(6);
        quick.addView(v, lp);
    }

    /** The first tile under the header: the continue card, else the closest tile of the top row. */
    private View firstTile(View from) {
        if (heroTile != null && heroBox.getVisibility() == View.VISIBLE) return heroTile;
        for (Row r : appRows) {
            if (r.strip.getChildCount() > 0) return nearest(r.strip, from);
        }
        return inputs.strip.getChildCount() > 0 ? nearest(inputs.strip, from) : null;
    }

    /** The child of {@code strip} horizontally closest to {@code from}, or null when empty. */
    private static View nearest(LinearLayout strip, View from) {
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(9), dp(18), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) ->
                view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start());
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, accent, 0x14FFFFFF, dp(18));
        quick.setVisibility(cfg.on(Config.QUICK) && quick.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        greeting.setVisibility(cfg.on(Config.GREETING) ? View.VISIBLE : View.GONE);
        stats.setVisibility(cfg.on(Config.STATS) ? View.VISIBLE : View.GONE);
        if (!cfg.on(Config.AMBIENT)) glow.setVisibility(View.GONE);
        renderApps();
        render(inputs);
        background();
        tick();
        if (resumed) watchMedia(true);
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : appsAll) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.tint = old.tint;
                            fresh.artRequested = true;
                        }
                    }
                }
                appsAll = list;
                renderApps();
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** Continue card, favorites, recents, one row per group, then everything else. */
    private void renderApps() {
        if (moving != null && Catalog.APPS.equals(moving.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keepRow = null, keepId = null;
        if (focused instanceof Tile && Catalog.APPS.equals(((Tile) focused).kind)) {
            keepId = ((Tile) focused).item.id;
            keepRow = focused == heroTile ? HERO : rowOf((Tile) focused);
        }

        List<Item> base = Catalog.visible(cfg, Catalog.APPS, appsAll);
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item it : base) byId.put(it.id, it);

        List<Item> recent = new ArrayList<>();
        for (String id : cfg.recent()) {
            Item it = byId.get(id);
            if (it != null) recent.add(it);
        }
        Item hero = cfg.on(Config.HERO) && !recent.isEmpty() ? recent.remove(0) : null;
        while (recent.size() > RECENT_SHOWN) recent.remove(recent.size() - 1);

        int[] size = cardSize();
        int w = size[0], h = size[1];
        appsBox.removeAllViews();
        appRows.clear();

        heroSlot.removeAllViews();
        heroTile = null;
        if (hero != null) {
            heroTile = tile(hero, Catalog.APPS, Math.round(w * 1.4f), Math.round(h * 1.4f), "never");
            heroSlot.addView(heroTile);
            heroName.setText(Catalog.label(cfg, hero));
            if (!hero.artRequested) loadArt(hero);
        }
        heroBox.setVisibility(hero != null ? View.VISIBLE : View.GONE);

        List<Item> favs = new ArrayList<>(), rest = new ArrayList<>();
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        for (String g : cfg.groups()) grouped.put(g, new ArrayList<>());
        for (Item it : base) {
            String g = cfg.groupOf(it.id);
            if (cfg.favorite(it.id)) favs.add(it);
            else if (g != null && grouped.containsKey(g)) grouped.get(g).add(it);
            else rest.add(it);
        }

        addRow(FAVS, Ui.t("Favorites", "Favoritas"), favs, Math.round(w * 1.25f), Math.round(h * 1.25f));
        if (cfg.on(Config.RECENTS)) addRow(RECENT, Ui.t("Recent", "Recientes"), recent, w, h);
        for (Map.Entry<String, List<Item>> e : grouped.entrySet()) {
            addRow("group:" + e.getKey(), e.getKey(), e.getValue(), w, h);
        }
        addRow(ALL, appRows.isEmpty() ? "Apps" : Ui.t("More apps", "Más apps"), rest, w, h);

        View restore = null;
        if (keepId != null) {
            if (HERO.equals(keepRow) && heroTile != null && heroTile.item.id.equals(keepId)) restore = heroTile;
            for (Row r : appRows) {
                for (int i = 0; i < r.strip.getChildCount() && restore == null; i++) {
                    Tile t = (Tile) r.strip.getChildAt(i);
                    if (t.item.id.equals(keepId) && r.key.equals(keepRow)) restore = t;
                }
            }
        }
        if (restore != null) restore.requestFocus();
        else if (keepId != null || getCurrentFocus() == null || !landed) focusFirst();
    }

    private void addRow(String key, String name, List<Item> items, int w, int h) {
        if (items.isEmpty()) return;
        Row row = new Row(key, Catalog.APPS, name, appsBox);
        String labels = cfg.get(Config.LABELS);
        for (Item it : items) {
            Tile t = tile(it, Catalog.APPS, w, h, labels);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = dp(18);
            row.strip.addView(t, lp);
            if (!it.artRequested) loadArt(it);
        }
        appRows.add(row);
    }

    private Tile tile(Item it, String kind, int w, int h, String labels) {
        Tile t = new Tile(this, it, kind, Catalog.label(cfg, it), w, h, labels, cfg.accent());
        t.setOnClickListener(v -> open((Tile) v));
        t.setOnLongClickListener(v -> {
            menu((Tile) v);
            return true;
        });
        t.setOnFocusChangeListener((v, has) -> {
            if (has) tint(((Tile) v).item);
        });
        return t;
    }

    private String rowOf(Tile t) {
        for (Row r : appRows) {
            if (t.getParent() == r.strip) return r.key;
        }
        return null;
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        row.strip.removeAllViews();

        boolean enabled = "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all) : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Math.round(size[1] * 0.56f);
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = tile(it, row.kind, w, h, cfg.get(Config.LABELS));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = dp(18);
            row.strip.addView(t, lp);
            if (it.id.equals(keep)) restore = index;
            index++;
        }
        row.setVisible(!shown.isEmpty());
        if (restore >= 0) {
            row.strip.getChildAt(restore).requestFocus();
        } else if (keep != null && row.strip.getChildCount() > 0) {
            row.strip.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(() -> {
                if (heroTile != null) heroTile.invalidate();
                for (Row r : appRows) {
                    for (int i = 0; i < r.strip.getChildCount(); i++) r.strip.getChildAt(i).invalidate();
                }
                View f = getCurrentFocus();
                if (f instanceof Tile && ((Tile) f).item == it) tint(it);
            });
        });
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    private void focusFirst() {
        page.scrollTo(0, 0);
        for (Row r : appRows) r.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = heroTile != null && heroBox.getVisibility() == View.VISIBLE ? heroTile : null;
        for (Row r : appRows) {
            if (first == null && r.strip.getChildCount() > 0) first = r.strip.getChildAt(0);
        }
        landed = first != null;
        if (first == null) first = inputs.strip.getChildAt(0);
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour >= 6 && hour < 13 ? Ui.t("Good morning", "Buenos días")
                : hour >= 13 && hour < 21 ? Ui.t("Good afternoon", "Buenas tardes")
                : Ui.t("Good evening", "Buenas noches"));
        if (cfg.on(Config.STATS)) stats.setText(stats());
        background();
    }

    /** Free memory and free storage, read on demand: two cheap system calls, no polling. */
    private String stats() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        return String.format(Locale.getDefault(), "%s %d MB  ·  %s %.1f GB",
                Ui.t("Free RAM", "RAM libre"), mi.availMem / (1024 * 1024),
                Ui.t("Free storage", "Almacenamiento libre"), fs.getAvailableBytes() / 1e9);
    }

    private void background() {
        boolean image = "image".equals(cfg.get(Config.BACKGROUND));
        File file = image ? cfg.wallpaper() : null;
        root.setBackgroundColor(cfg.background());
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Fades a glow from the bottom edge to the focused app's own color. */
    private void tint(Item it) {
        if (!cfg.on(Config.AMBIENT)) return;
        if (it.tint == 0) it.tint = colorOf(it);
        if (it.tint == 0) return;
        int from = glowColor == 0 ? it.tint : glowColor, to = it.tint;
        if (glow.getVisibility() != View.VISIBLE) {
            glow.setVisibility(View.VISIBLE);
            from = to;
        }
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofArgb(from, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> paintGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        glowColor = c;
        int rgb = c & 0x00FFFFFF;
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0x99000000 | rgb, 0x33000000 | rgb, 0x00000000 | rgb}));
    }

    /** The banner's dominant hue, pushed to a calm, saturated glow; 0 while art is loading. */
    private static int colorOf(Item it) {
        Drawable d = it.banner != null ? it.banner : it.icon;
        if (d == null) return 0;
        Bitmap b = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 24, 14);
        d.draw(new Canvas(b));
        d.setBounds(old);
        float[] hsv = new float[3];
        double x = 0, y = 0, weight = 0, sat = 0;
        for (int i = 0; i < 24; i++) {
            for (int j = 0; j < 14; j++) {
                int p = b.getPixel(i, j);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[2] < 0.18f || hsv[1] < 0.2f) continue;
                double wgt = hsv[1] * hsv[2];
                x += Math.cos(Math.toRadians(hsv[0])) * wgt;
                y += Math.sin(Math.toRadians(hsv[0])) * wgt;
                sat += hsv[1] * wgt;
                weight += wgt;
            }
        }
        b.recycle();
        if (weight == 0) return 0xFF5A6070;
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        float s = Math.max(0.45f, Math.min(0.85f, (float) (sat / weight)));
        return Color.HSVToColor(new float[] {hue, s, 0.62f});
    }

    private void watchMedia(boolean on) {
        ComponentName me = new ComponentName(this, Media.class);
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionWatch);
            if (on && cfg.on(Config.PLAYING)) {
                sessions.addOnActiveSessionsChangedListener(sessionWatch, me, ui);
                pickSession(sessions.getActiveSessions(me));
                return;
            }
        } catch (SecurityException e) {
            // listener not enabled over ADB: the widget simply stays hidden
        }
        setController(null);
    }

    private void pickSession(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && c.getMetadata() != null) best = c;
            }
        }
        setController(best);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(mediaWatch);
        controller = c;
        if (c != null) c.registerCallback(mediaWatch, ui);
        showPlaying();
    }

    private void showPlaying() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        boolean show = cfg.on(Config.PLAYING) && !TextUtils.isEmpty(title) && s != null
                && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_PAUSED);
        if (!show) {
            if (playing.hasFocus()) focusFirst();
            playing.setVisibility(View.GONE);
            return;
        }
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        playText.setText("♪  " + title + (TextUtils.isEmpty(artist) ? "" : "  ·  " + artist));
        boolean on = s.getState() == PlaybackState.STATE_PLAYING;
        playToggle.setText(on ? Ui.t("Pause", "Pausa") : Ui.t("Play", "Reanudar"));
        playing.setVisibility(View.VISIBLE);
    }

    private void toggleMedia() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        if (app) cfg.touch(t.item.id);
        launch(new Intent(t.item.intent), app ? t.item.id : null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String row = t == heroTile ? HERO : rowOf(t);
        boolean movable = !HERO.equals(row) && !RECENT.equals(row);
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (movable) {
            names.add("Move");
            actions.add(() -> {
                if (t.getParent() == null) return;
                moving = t;
                t.setMoving(true);
            });
        }
        if (app) {
            boolean fav = cfg.favorite(t.item.id);
            names.add(fav ? Ui.t("Remove from favorites", "Quitar de favoritas")
                    : Ui.t("Add to favorites", "Añadir a favoritas"));
            actions.add(() -> {
                cfg.setFavorite(t.item.id, !fav);
                apply();
            });
            names.add(Ui.t("Category…", "Categoría…"));
            actions.add(() -> pickGroup(t.item));
        }
        names.add("Rename");
        actions.add(() -> rename(t));
        names.add("Hide");
        actions.add(() -> {
            Catalog.setShown(cfg, t.kind, t.item, false);
            if (app) renderApps();
            else render(inputs);
            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
        });
        if (app) {
            names.add("App info");
            actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", t.item.id, null)), null));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(names.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void pickGroup(Item it) {
        List<String> groups = cfg.groups();
        String current = cfg.groupOf(it.id);
        List<String> names = new ArrayList<>(groups);
        names.add(Ui.t("New category…", "Nueva categoría…"));
        if (current != null) names.add(Ui.t("No category", "Sin categoría"));
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Ui.t("Category", "Categoría"))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < groups.size()) {
                        cfg.setGroup(it.id, groups.get(which));
                        apply();
                    } else if (which == groups.size()) {
                        newGroup(it);
                    } else {
                        cfg.setGroup(it.id, null);
                        apply();
                    }
                })
                .show();
    }

    private void newGroup(Item it) {
        EditText field = new EditText(this);
        field.setSingleLine();
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Ui.t("New category", "Nueva categoría"))
                .setView(box)
                .setPositiveButton(Ui.t("Create", "Crear"), (dialog, w) -> {
                    cfg.setGroup(it.id, field.getText().toString());
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        LinearLayout strip = (LinearLayout) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        LinearLayout strip = (LinearLayout) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
