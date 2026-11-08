package com.tasin.shares;

/**
 * The colour themes, shared by the TV screens and the web page (which has the same ids as CSS theme blocks).
 * Ember is the default: warm near-black with one orange accent.
 */
final class Theme {
    final String id, name;
    final int paper, paper2, paper3, ink, ink2, muted, rule, accent, accentInk, ok, warn, error;
    final boolean light;

    private Theme(String id, String name, boolean light, int paper, int paper2, int paper3, int ink, int ink2, int muted,
                  int accent, int accentInk) {
        this.id = id;
        this.name = name;
        this.light = light;
        this.paper = paper;
        this.paper2 = paper2;
        this.paper3 = paper3;
        this.ink = ink;
        this.ink2 = ink2;
        this.muted = muted;
        this.rule = light ? 0x1F000000 : 0x1FFFFFFF;
        this.accent = accent;
        this.accentInk = accentInk;
        this.ok = light ? 0xFF1E8A4A : 0xFF79CF94;
        this.warn = light ? 0xFF9A6A00 : 0xFFEAC56E;
        this.error = light ? 0xFFC0392B : 0xFFF0877B;
    }

    static final Theme[] ALL = {
            new Theme("ember", "Ember", false, 0xFF120C09, 0xFF1D1612, 0xFF29201B, 0xFFF3EDE4, 0xFFDAD0C3, 0xFFB0A293,
                    0xFFF26B1D, 0xFF1A0F0A),
            new Theme("ocean", "Ocean", false, 0xFF0A0F15, 0xFF131B24, 0xFF1C2733, 0xFFE8F0F7, 0xFFC9D6E2, 0xFF93A4B5,
                    0xFF3FA7F2, 0xFF04121F),
            new Theme("forest", "Forest", false, 0xFF0A110D, 0xFF121C16, 0xFF1B2820, 0xFFE9F2EC, 0xFFC9DACF, 0xFF94AA9C,
                    0xFF43C47F, 0xFF04170C),
            new Theme("dark", "Dark", false, 0xFF0F0F10, 0xFF19191B, 0xFF242427, 0xFFF2F2F3, 0xFFD2D2D6, 0xFFA0A0A7,
                    0xFFEDEDEF, 0xFF111113),
            new Theme("light", "Light", true, 0xFFF6F1EA, 0xFFFDFBF8, 0xFFEDE5DA, 0xFF1D1611, 0xFF3B322A, 0xFF6B5F54,
                    0xFFB8460A, 0xFFFFFFFF),
    };

    static Theme of(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }
}
