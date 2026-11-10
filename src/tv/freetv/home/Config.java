package tv.freetv.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * All settings live in one plain {@code key=value} file in the app's external files dir,
 * so {@code adb pull}/{@code adb push} is the whole export/import story and a hand edit is
 * picked up on the next return to the home screen.
 */
final class Config {
    static final String FILE = "home.conf";

    static final class Choice {
        final String key, title;
        final String[] values, names;
        final String fallback;

        Choice(String key, String title, String fallback, String[] values, String[] names) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.values = values;
            this.names = names;
        }

        String name(String value) {
            int i = Arrays.asList(values).indexOf(value);
            return i < 0 ? value : names[i];
        }
    }

    static final Choice ACCENT = new Choice("accent", Ui.t("Accent color", "Color de acento"), "blue",
            new String[] {"white", "blue", "cyan", "green", "amber", "red", "pink", "violet"},
            new String[] {Ui.t("White", "Blanco"), Ui.t("Blue", "Azul"), Ui.t("Cyan", "Cian"),
                Ui.t("Green", "Verde"), Ui.t("Amber", "Ámbar"), Ui.t("Red", "Rojo"), Ui.t("Pink", "Rosa"),
                Ui.t("Violet", "Violeta")});
    static final Choice TILES = new Choice("tiles", Ui.t("Tile size", "Tamaño de las fichas"), "medium",
            new String[] {"small", "medium", "large"},
            new String[] {Ui.t("Small", "Pequeño"), Ui.t("Medium", "Mediano"), Ui.t("Large", "Grande")});
    static final Choice LABELS = new Choice("labels", Ui.t("App names", "Nombres de las apps"), "focus",
            new String[] {"focus", "always", "never"},
            new String[] {Ui.t("On focus", "Al enfocar"), Ui.t("Always", "Siempre"), Ui.t("Never", "Nunca")});
    static final Choice CLOCK = new Choice("clock", Ui.t("Clock", "Reloj"), "auto",
            new String[] {"auto", "24h", "12h"},
            new String[] {Ui.t("System", "Del sistema"), Ui.t("24-hour", "24 horas"), Ui.t("12-hour", "12 horas")});
    static final Choice INPUTS = new Choice("inputs", Ui.t("Inputs row", "Fila de entradas"), "on",
            new String[] {"on", "off"},
            new String[] {Ui.t("Shown", "Visible"), Ui.t("Hidden", "Oculta")});
    // defaults to the bundled wallpapers: they ship in the APK and are unpacked on first run
    static final Choice BACKGROUND = new Choice("background", Ui.t("Background", "Fondo"), "image",
            new String[] {"black", "graphite", "midnight", "image"},
            new String[] {Ui.t("Black", "Negro"), Ui.t("Graphite", "Grafito"), Ui.t("Midnight", "Medianoche"), Ui.t("Image file", "Imagen")});
    static final Choice DIM = new Choice("dim", Ui.t("Image dim", "Oscurecer la imagen"), "40",
            new String[] {"0", "20", "40", "60", "80"},
            new String[] {"0%", "20%", "40%", "60%", "80%"});

    static final Choice ROTATE = new Choice("rotate", Ui.t("Wallpaper rotation", "Cambio de fondo"), "off",
            new String[] {"off", "visit", "hourly", "daily", "daytime"},
            new String[] {Ui.t("Off", "No"), Ui.t("Every return home", "Al volver al inicio"),
                Ui.t("Every hour", "Cada hora"), Ui.t("Every day", "Cada día"),
                Ui.t("By time of day", "Según la hora del día")});
    static final Choice HERO = onOff("hero", Ui.t("Continue card", "Tarjeta Continuar"));
    static final Choice RECENTS = onOff("recents", Ui.t("Recent apps row", "Fila de recientes"));
    static final Choice GREETING = onOff("greeting", Ui.t("Greeting", "Saludo"));
    static final Choice STATS = onOff("stats", Ui.t("Free memory and storage", "Memoria y espacio libres"));
    static final Choice QUICK = onOff("quick", Ui.t("Quick settings", "Ajustes rápidos"));
    static final Choice AMBIENT = onOff("ambient", Ui.t("Ambient color", "Color ambiental"));
    static final Choice PLAYING = onOff("playing", Ui.t("Now playing", "Sonando ahora"));

    static final Choice[] CHOICES = {ACCENT, TILES, LABELS, CLOCK, INPUTS, BACKGROUND, DIM, ROTATE,
        HERO, RECENTS, GREETING, STATS, QUICK, AMBIENT, PLAYING};

    private static Choice onOff(String key, String title) {
        return new Choice(key, title, "on", new String[] {"on", "off"}, new String[] {Ui.t("On", "Sí"), Ui.t("Off", "No")});
    }

    boolean on(Choice c) {
        return "on".equals(get(c));
    }

    private static final String RECENT = "recent";
    private static final int RECENT_MAX = 10;
    static final String FAVORITES = "apps.fav";
    /** Set once the first run has seeded favorites, so clearing them keeps them clear. */
    private static final String SEEDED = "apps.fav.seeded";
    /**
     * Streaming apps to favorite on the first run, in the order they should appear.
     * Package names verified on a Fire TV Stick running Fire OS 8; apps that are not
     * installed are skipped. Everything else is one long press away.
     */
    private static final String[] SEED = {
        "com.netflix.ninja",            // Netflix
        "com.amazon.firebat",           // Prime Video
        "com.amazon.firetv.youtube",    // YouTube
        "com.disney.disneyplus",        // Disney+
        "com.hbo.hbonow",               // HBO Max
        "com.dazn",                     // DAZN
        "com.amazon.bueller.music",     // Amazon Music
    };

    /**
     * Wallpapers shipped in res/raw and unpacked on the first run, two per time of day.
     * They are copied out rather than read in place so that the rotation, the Options
     * screen and a hand-edited folder all see one kind of file in one place.
     */
    private static final String[] SEED_WALLPAPERS = {
        "afternoon_indigo",
        "afternoon_violet",
        "morning_dawn",
        "morning_waves",
        "night_galaxy",
        "night_nebula",
    };
    private static final String WALLPAPERS_SEEDED = "wallpapers.seeded";

    /** First run only: unpack the bundled wallpapers next to home.conf. */
    void seedWallpapers(Context c) {
        if (!get(WALLPAPERS_SEEDED, "").isEmpty()) return;
        File folder = new File(dir, WALLPAPERS);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            Log.w("freetv", "could not create " + folder);
            return;
        }
        for (String name : SEED_WALLPAPERS) {
            int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
            if (id == 0) continue;
            File out = new File(folder, name + ".jpg");
            if (out.isFile()) continue;
            try (InputStream in = c.getResources().openRawResource(id);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            } catch (IOException e) {
                Log.w("freetv", "could not unpack " + name, e);
            }
        }
        set(WALLPAPERS_SEEDED, "1");
    }

    /** First run only: favorite the apps from {@link #SEED} that are installed. */
    void seedFavorites(PackageManager pm) {
        if (!get(SEEDED, "").isEmpty()) return;
        List<String> favs = list(FAVORITES);
        for (String pkg : SEED) {
            if (favs.contains(pkg)) continue;
            try {
                pm.getPackageInfo(pkg, 0);
                favs.add(pkg);
            } catch (PackageManager.NameNotFoundException notInstalled) {
                // skip it
            }
        }
        setList(FAVORITES, favs);
        set(SEEDED, "1");
    }
    private static final String GROUPS = "groups";

    /** Puts {@code pkg} first in the recent list; a no-op when it already is. */
    void touch(String pkg) {
        List<String> recent = list(RECENT);
        if (!recent.isEmpty() && recent.get(0).equals(pkg)) return;
        recent.remove(pkg);
        recent.add(0, pkg);
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
        setList(RECENT, recent);
    }

    List<String> recent() {
        return list(RECENT);
    }

    boolean favorite(String id) {
        return list(FAVORITES).contains(id);
    }

    void setFavorite(String id, boolean on) {
        List<String> favs = list(FAVORITES);
        favs.remove(id);
        if (on) favs.add(id);
        setList(FAVORITES, favs);
    }

    List<String> groups() {
        return list(GROUPS);
    }

    List<String> group(String name) {
        return list("group." + name);
    }

    String groupOf(String id) {
        for (String g : groups()) {
            if (group(g).contains(id)) return g;
        }
        return null;
    }

    /** Moves {@code id} into {@code name} (null: no group); groups left empty are dropped. */
    void setGroup(String id, String name) {
        List<String> names = groups();
        if (name != null) {
            name = name.replaceAll("[,=\\r\\n]", " ").trim();
            if (name.isEmpty()) name = null;
        }
        for (String g : new ArrayList<>(names)) {
            List<String> members = group(g);
            if (members.remove(id) || g.equals(name)) {
                if (g.equals(name)) members.add(id);
                if (members.isEmpty()) {
                    names.remove(g);
                    values.remove("group." + g);
                } else {
                    values.put("group." + g, String.join(",", members));
                }
            }
        }
        if (name != null && !names.contains(name)) {
            names.add(name);
            values.put("group." + name, id);
        }
        setList(GROUPS, names);
    }

    /** Folder of wallpapers, next to home.conf, that Background › Image file rotates through. */
    static final String WALLPAPERS = "wallpapers";
    private static final String OFFSET = "wallpaper.offset";

    private static final int[] ACCENTS = {
        0xFFFFFFFF, 0xFF3D8BFF, 0xFF2BC4E8, 0xFF34C759, 0xFFFFB340, 0xFFFF5147, 0xFFFF5C9D, 0xFFA07CFF,
    };
    private static final int[] BACKGROUNDS = {0xFF000000, 0xFF141518, 0xFF0B1220, 0xFF000000};

    private static Config instance;

    final File dir;
    private final File file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private long signature = Long.MIN_VALUE;
    private int version;
    private long visits;

    static Config get(Context c) {
        if (instance == null) instance = new Config(c.getApplicationContext());
        return instance;
    }

    private Config(Context c) {
        File external = c.getExternalFilesDir(null);
        dir = external != null ? external : c.getFilesDir();
        file = new File(dir, FILE);
        refresh();
    }

    /** Changes every time a setting changes, from the UI or from the file on disk. */
    int version() {
        return version;
    }

    void refresh() {
        long sig = file.lastModified() * 31 + file.length();
        if (sig == signature) return;
        signature = sig;
        values.clear();
        if (file.isFile()) read();
        version++;
    }

    private void read() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.w("freetv", "could not read " + file, e);
        }
    }

    private void save() {
        File tmp = new File(dir, FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# FreeTV. Edit on the TV (Options) or here; unknown keys are kept.\n");
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Log.w("freetv", "could not write " + tmp, e);
            return;
        }
        if (!tmp.renameTo(file)) Log.w("freetv", "could not replace " + file);
        signature = file.lastModified() * 31 + file.length();
    }

    String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    String get(Choice c) {
        String v = values.get(c.key);
        return v != null && Arrays.asList(c.values).contains(v) ? v : c.fallback;
    }

    void set(String key, String value) {
        if (value == null || value.isEmpty()) values.remove(key);
        else values.put(key, value);
        version++;
        save();
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    void setList(String key, List<String> items) {
        set(key, String.join(",", items));
    }

    void removePrefix(String... prefixes) {
        for (Iterator<String> it = values.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            for (String p : prefixes) {
                if (k.startsWith(p)) {
                    it.remove();
                    break;
                }
            }
        }
        version++;
        save();
    }

    int accent() {
        String v = values.get(ACCENT.key);
        if (v != null && v.startsWith("#")) {
            try {
                return Color.parseColor(v) | 0xFF000000;
            } catch (IllegalArgumentException ignored) {
                // falls through to the named palette
            }
        }
        return ACCENTS[Arrays.asList(ACCENT.values).indexOf(get(ACCENT))];
    }

    int background() {
        return BACKGROUNDS[Arrays.asList(BACKGROUND.values).indexOf(get(BACKGROUND))];
    }

    /** Counts returns to the home screen, for the "visit" rotation. */
    void visited() {
        visits++;
    }

    /** Steps the rotation forward by one, so the next wallpaper shows now. */
    void nextWallpaper() {
        set(OFFSET, String.valueOf(offset() + 1));
    }

    private int offset() {
        try {
            return Integer.parseInt(get(OFFSET, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 0 morning (6-13 h), 1 afternoon (13-20 h), 2 night. */
    static int period() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 13 ? 0 : h >= 13 && h < 20 ? 1 : 2;
    }

    private static final String[][] PERIOD_WORDS = {
        {"morning", "day", "manana", "dia"},
        {"afternoon", "evening", "tarde"},
        {"night", "noche"},
    };

    /**
     * Files whose name carries a word for {@code period}; empty when others are tagged but not
     * this one, null when no file is tagged at all (then the period just indexes the pool).
     */
    private static List<File> forPeriod(List<File> pool, int period) {
        List<File> fit = new ArrayList<>();
        boolean tagged = false;
        for (File f : pool) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (int p = 0; p < PERIOD_WORDS.length; p++) {
                for (String w : PERIOD_WORDS[p]) {
                    if (n.contains(w)) {
                        tagged = true;
                        if (p == period && !fit.contains(f)) fit.add(f);
                    }
                }
            }
        }
        return tagged ? fit : null;
    }

    List<File> wallpapers() {
        List<File> out = new ArrayList<>();
        File[] files = new File(dir, WALLPAPERS).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
        Collections.sort(out);
        return out;
    }

    File wallpaper() {
        List<File> pool = wallpapers();
        if (!pool.isEmpty()) {
            long now = System.currentTimeMillis();
            long step;
            String mode = get(ROTATE);
            if ("daytime".equals(mode)) {
                int period = period();
                List<File> fit = forPeriod(pool, period);
                if (fit == null) return pool.get(period % pool.size());
                if (!fit.isEmpty()) pool = fit;
                mode = "visit";
            }
            switch (mode) {
                case "visit": step = visits; break;
                case "hourly": step = now / 3_600_000L; break;
                case "daily": step = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L; break;
                default: step = 0;
            }
            int n = pool.size();
            return pool.get((int) (((step + offset()) % n + n) % n));
        }
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }
}
