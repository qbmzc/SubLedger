package com.subledger.mobile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Text badges for subscriptions. A badge is a short text on a coloured circle: the user's own
 * text and colour when set, else a known service's abbreviation and brand colour, else the first
 * character of the title on a colour picked from {@link #AUTO} by a stable hash of the title.
 * Only abbreviations and colours are stored here, never logos.
 */
final class Icons {
    static final int MAX_TEXT = 8;

    /** Mid-tone colours for unknown services; each title always gets the same one. */
    static final int[] AUTO = {0x3E7C59, 0x3D6FB6, 0x7A5AB5, 0xB04A63, 0xA85F1F, 0x2A7A7A, 0x7A6A22, 0x5F6B7A};

    /** Colours offered in the picker: the automatic ones plus common brand colours. */
    static final int[] SWATCHES = {0x3E7C59, 0x3D6FB6, 0x7A5AB5, 0xB04A63, 0xA85F1F, 0x2A7A7A, 0x7A6A22, 0x5F6B7A,
            0x000000, 0xFFFFFF, 0xD97757, 0x10A37F, 0xE50914, 0x1DB954, 0x4285F4, 0xFB7299};

    static final class Badge {
        final String text;
        /** RGB without alpha. */
        final int color;

        Badge(String text, int color) {
            this.text = text;
            this.color = color;
        }

        /** Black or white, whichever reads better on {@link #color}. */
        int textColor() {
            return contrast(color, 0xFFFFFF) >= contrast(color, 0x1A1A1A) ? 0xFFFFFF : 0x1A1A1A;
        }
    }

    static final class Preset {
        final List<String> keys;
        final String text;
        final int color;

        Preset(String text, int color, String... keys) {
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
            this.text = text;
            this.color = color;
        }
    }

    /** Matched in order against the lower-case title, so more specific names come first. */
    static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("Cl", 0xD97757, "claude"),
            new Preset("Cx", 0x000000, "codex"),
            new Preset("GPT", 0x10A37F, "chatgpt", "openai"),
            new Preset("Ge", 0x4285F4, "gemini"),
            new Preset("G1", 0x4285F4, "google one"),
            new Preset("YT", 0xFF0000, "youtube"),
            new Preset("Cu", 0x000000, "cursor"),
            new Preset("Co", 0x24292F, "copilot"),
            new Preset("GH", 0x24292F, "github"),
            new Preset("Px", 0x20808D, "perplexity"),
            new Preset("MJ", 0x000000, "midjourney"),
            new Preset("Gk", 0x000000, "grok"),
            new Preset("N", 0xE50914, "netflix"),
            new Preset("Sp", 0x1DB954, "spotify"),
            new Preset("♪", 0xFA243C, "apple music"),
            new Preset("tv", 0x000000, "apple tv"),
            new Preset("A1", 0x000000, "apple one"),
            new Preset("iC", 0x3693F3, "icloud"),
            new Preset("D+", 0x113CCF, "disney"),
            new Preset("Pr", 0x00A8E1, "prime video", "amazon prime"),
            new Preset("365", 0xD83B01, "microsoft 365", "office 365"),
            new Preset("Xb", 0x107C10, "xbox", "game pass"),
            new Preset("PS", 0x003791, "playstation", "ps plus"),
            new Preset("NS", 0xE60012, "nintendo", "switch online"),
            new Preset("N", 0x000000, "notion"),
            new Preset("Db", 0x0061FF, "dropbox"),
            new Preset("Ad", 0xFA0F00, "adobe"),
            new Preset("JB", 0x000000, "jetbrains"),
            new Preset("Du", 0x58CC02, "duolingo"),
            new Preset("Tg", 0x229ED9, "telegram"),
            new Preset("爱", 0x00BE06, "爱奇艺", "iqiyi"),
            new Preset("腾", 0xFF7A00, "腾讯视频"),
            new Preset("优", 0x1F8FFF, "优酷", "youku"),
            new Preset("B", 0xFB7299, "哔哩哔哩", "bilibili", "b站", "大会员"),
            new Preset("芒", 0xFF5F00, "芒果"),
            new Preset("Q", 0x31C27C, "qq音乐", "qq 音乐"),
            new Preset("云", 0xE60026, "网易云"),
            new Preset("百", 0x06A7FF, "百度网盘"),
            new Preset("W", 0xD8282B, "wps"),
            new Preset("JD", 0xE1251B, "京东"),
            new Preset("88", 0xFF5000, "88vip", "淘宝")));

    private Icons() {
    }

    static Preset preset(String title) {
        String name = title.trim().toLowerCase(Locale.ROOT);
        for (Preset preset : PRESETS) {
            for (String key : preset.keys) {
                if (name.contains(key)) {
                    return preset;
                }
            }
        }
        return null;
    }

    static Badge badge(Subscription entry) {
        return badge(entry.title, entry.iconText, entry.iconColor);
    }

    /** The badge for a title, with the user's text and colour (either may be null) taking priority. */
    static Badge badge(String title, String text, Integer color) {
        Preset preset = preset(title);
        String shown = text != null ? text : preset != null ? preset.text : initial(title);
        int fill = color != null ? color : preset != null ? preset.color : autoColor(title);
        return new Badge(shown, fill);
    }

    /** First character (a whole code point, upper-cased) of the title, or "?" for a blank title. */
    static String initial(String title) {
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        return new String(Character.toChars(trimmed.codePointAt(0))).toUpperCase(Locale.ROOT);
    }

    static int autoColor(String title) {
        return AUTO[Math.floorMod(title.trim().toLowerCase(Locale.ROOT).hashCode(), AUTO.length)];
    }

    /** Trimmed badge text, null when blank. Throws when it is too long to fit a badge. */
    static String validText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException("图标文字最多 " + MAX_TEXT + " 个字符");
        }
        return trimmed;
    }

    /** Parses "#RRGGBB" or "RRGGBB", or returns null. */
    static Integer parseColor(String text) {
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        return value.matches("[0-9a-fA-F]{6}") ? Integer.valueOf(Integer.parseInt(value, 16)) : null;
    }

    static String formatColor(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** WCAG contrast ratio between two RGB colours. */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int value) {
        double c = (value & 0xFF) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
