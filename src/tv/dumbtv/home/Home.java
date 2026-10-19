package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    private FrameLayout root;
    private ImageView wall;
    private View shade;
    private TextView clock, date;
    private Row apps, inputs;
    private Tile moving;
    private String wallKey;

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

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
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
        if (resumed) focusFirst();
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    View below = nearest(apps.strip, focused);
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(40), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(12));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 52, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(8), 0, 0);
        when.addView(clock);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(chip("Options", v -> startActivity(new Intent(this, Options.class))));
        head.addView(chip("Settings", v -> openSettings()));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        apps = new Row(Catalog.APPS, "Apps", column);
        inputs = new Row(Catalog.INPUTS, "Inputs", column);
        return root;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, accent, 0x14FFFFFF, dp(18));
        render(apps);
        render(inputs);
        background();
        tick();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        row.strip.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all) : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnLongClickListener(v -> {
                menu((Tile) v);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = dp(18);
            row.strip.addView(t, lp);
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && !it.artRequested) loadArt(it);
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
                for (int i = 0; i < apps.strip.getChildCount(); i++) apps.strip.getChildAt(i).invalidate();
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
        apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = apps.strip.getChildAt(0);
        if (first == null) first = inputs.strip.getChildAt(0);
        landed = apps.strip.getChildCount() > 0;
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
        launch(new Intent(t.item.intent), Catalog.APPS.equals(t.kind) ? t.item.id : null);
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
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
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
