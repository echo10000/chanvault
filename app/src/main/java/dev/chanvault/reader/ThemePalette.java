package dev.chanvault.reader;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local, no-network appearance preferences. A theme never changes or removes saved media.
 * Yotsuba is a green/clover-inspired original visual theme, not an embedded character image.
 */
final class ThemePalette {
    static final String PREFS = "chanvault_appearance_v1";
    static final String[] THEMES = {"Yotsuba · Clover Night", "Yotsuba · Cream", "Midnight · Classic", "Sakura · Dusk"};
    static final String[] ACCENTS = {"Theme default", "Clover green", "Sunflower gold", "Lavender", "Sky blue"};
    static final int[] ACCENT_COLORS = {0, 0xffaee071, 0xfff4cf6d, 0xffabb7ff, 0xff7ecde8};

    final int background, surface, card, chip, border, text, muted, accent, onAccent, header;
    final boolean light;
    final int index, accentIndex;

    private ThemePalette(int background, int surface, int card, int chip, int border,
                         int text, int muted, int accent, int onAccent, int header,
                         boolean light, int index, int accentIndex) {
        this.background = background;
        this.surface = surface;
        this.card = card;
        this.chip = chip;
        this.border = border;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.onAccent = onAccent;
        this.header = header;
        this.light = light;
        this.index = index;
        this.accentIndex = accentIndex;
    }

    static ThemePalette get(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int t = settings.getInt("theme", 0);
        int a = settings.getInt("accent", 0);
        if (t < 0 || t >= THEMES.length) t = 0;
        if (a < 0 || a >= ACCENTS.length) a = 0;

        ThemePalette base;
        switch (t) {
            case 1:
                base = new ThemePalette(0xfffbf7e8, 0xfffffdf4, 0xfff3efd9,
                        0xffe9eed8, 0xffc7d7b5, 0xff233f2b, 0xff58705c,
                        0xff467b39, 0xffffffff, 0xfff3efd9, true, t, a);
                break;
            case 2:
                base = new ThemePalette(0xff101116, 0xff191e29, 0xff1e2330,
                        0xff2a3041, 0xff343b4c, 0xfff1f3ff, 0xffaab3cb,
                        0xffb6bdff, 0xff101322, 0xff161c29, false, t, a);
                break;
            case 3:
                base = new ThemePalette(0xff231c2b, 0xff302638, 0xff382d42,
                        0xff493b52, 0xff67516d, 0xfffff1f8, 0xffdac5d7,
                        0xfff5abcb, 0xff332437, 0xff30243c, false, t, a);
                break;
            default:
                base = new ThemePalette(0xff102019, 0xff172b21, 0xff203629,
                        0xff2c4735, 0xff496850, 0xfff8f5e6, 0xffb6ceb9,
                        0xffb5e077, 0xff1a2a1c, 0xff193025, false, t, a);
                break;
        }
        if (a == 0) return base;
        int accent = ACCENT_COLORS[a];
        return new ThemePalette(base.background, base.surface, base.card, base.chip,
                base.border, base.text, base.muted, accent,
                (t == 1 && a == 2) ? 0xff293526 : 0xff112019,
                base.header, base.light, t, a);
    }

    static void chooseTheme(Context context, int index) {
        if (index < 0 || index >= THEMES.length) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("theme", index).apply();
    }

    static void chooseAccent(Context context, int index) {
        if (index < 0 || index >= ACCENTS.length) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("accent", index).apply();
    }

    void applyBars(android.app.Activity activity) {
        activity.getWindow().setStatusBarColor(header);
        activity.getWindow().setNavigationBarColor(background);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.view.View decor = activity.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            int lightFlags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? flags | lightFlags : flags & ~lightFlags);
        }
    }
}
