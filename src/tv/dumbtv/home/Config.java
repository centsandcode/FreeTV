package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    static final Choice ACCENT = new Choice("accent", "Accent color", "blue",
            new String[] {"white", "blue", "cyan", "green", "amber", "red", "pink", "violet"},
            new String[] {"White", "Blue", "Cyan", "Green", "Amber", "Red", "Pink", "Violet"});
    static final Choice TILES = new Choice("tiles", "Tile size", "medium",
            new String[] {"small", "medium", "large"},
            new String[] {"Small", "Medium", "Large"});
    static final Choice LABELS = new Choice("labels", "App names", "focus",
            new String[] {"focus", "always", "never"},
            new String[] {"On focus", "Always", "Never"});
    static final Choice CLOCK = new Choice("clock", "Clock", "auto",
            new String[] {"auto", "24h", "12h"},
            new String[] {"System", "24-hour", "12-hour"});
    static final Choice INPUTS = new Choice("inputs", "Inputs row", "on",
            new String[] {"on", "off"},
            new String[] {"Shown", "Hidden"});
    static final Choice BACKGROUND = new Choice("background", "Background", "black",
            new String[] {"black", "graphite", "midnight", "image"},
            new String[] {"Black", "Graphite", "Midnight", "Image file"});
    static final Choice DIM = new Choice("dim", "Image dim", "40",
            new String[] {"0", "20", "40", "60", "80"},
            new String[] {"0%", "20%", "40%", "60%", "80%"});

    static final Choice[] CHOICES = {ACCENT, TILES, LABELS, CLOCK, INPUTS, BACKGROUND, DIM};

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
            Log.w("dumbtv", "could not read " + file, e);
        }
    }

    private void save() {
        File tmp = new File(dir, FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("# dumbtv home. Edit on the TV (Options) or here; unknown keys are kept.\n");
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            Log.w("dumbtv", "could not write " + tmp, e);
            return;
        }
        if (!tmp.renameTo(file)) Log.w("dumbtv", "could not replace " + file);
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

    File wallpaper() {
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }
}
