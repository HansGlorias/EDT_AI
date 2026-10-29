package io.github.hansglorias.edtai.core.documents;

import java.util.Map;

/**
 * Имена глифов PDF ({@code /Differences}) → Unicode: {@code uni0410},
 * {@code u1F600}, однобуквенные, кириллица Adobe ({@code afii10017}…) и
 * частые знаки. Полный список Adobe не нужен: русские PDF обычно идут с
 * {@code ToUnicode}.
 */
final class Glyphs {

    private static final Map<String, String> NAMES = Map.ofEntries(Map.entry("space", " "), Map.entry("exclam", "!"),
            Map.entry("quotedbl", "\""), Map.entry("numbersign", "#"), Map.entry("dollar", "$"), Map.entry("percent", "%"),
            Map.entry("ampersand", "&"), Map.entry("quotesingle", "'"), Map.entry("quoteright", "’"), Map.entry("quoteleft", "‘"),
            Map.entry("parenleft", "("), Map.entry("parenright", ")"), Map.entry("asterisk", "*"), Map.entry("plus", "+"),
            Map.entry("comma", ","), Map.entry("hyphen", "-"), Map.entry("minus", "−"), Map.entry("period", "."),
            Map.entry("slash", "/"), Map.entry("colon", ":"), Map.entry("semicolon", ";"), Map.entry("less", "<"),
            Map.entry("equal", "="), Map.entry("greater", ">"), Map.entry("question", "?"), Map.entry("at", "@"),
            Map.entry("bracketleft", "["), Map.entry("backslash", "\\"), Map.entry("bracketright", "]"), Map.entry("underscore", "_"),
            Map.entry("braceleft", "{"), Map.entry("bar", "|"), Map.entry("braceright", "}"), Map.entry("endash", "–"),
            Map.entry("emdash", "—"), Map.entry("quotedblleft", "“"), Map.entry("quotedblright", "”"),
            Map.entry("guillemotleft", "«"), Map.entry("guillemotright", "»"), Map.entry("bullet", "•"), Map.entry("ellipsis", "…"),
            Map.entry("numero", "№"), Map.entry("afii61352", "№"), Map.entry("degree", "°"), Map.entry("zero", "0"),
            Map.entry("one", "1"), Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("five", "5"),
            Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"),
            Map.entry("nbspace", " "), Map.entry("fi", "fi"), Map.entry("fl", "fl"));

    private Glyphs() {
    }

    static String unicode(String name) {
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (base.length() == 1) {
            return base;
        }
        String known = NAMES.get(base);
        if (known != null) {
            return known;
        }
        try {
            if (base.startsWith("uni") && base.length() >= 7) {
                StringBuilder text = new StringBuilder();
                for (int i = 3; i + 4 <= base.length(); i += 4) {
                    text.append((char) Integer.parseInt(base.substring(i, i + 4), 16));
                }
                return text.toString();
            }
            if (base.startsWith("u") && base.length() >= 5 && base.length() <= 7) {
                return new String(Character.toChars(Integer.parseInt(base.substring(1), 16)));
            }
            if (base.startsWith("afii100")) {
                return cyrillic(Integer.parseInt(base.substring(4)));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** Кириллица по списку Adobe: afii10017 — «А», afii10023 — «Ё», afii10065 — «а», afii10071 — «ё». */
    private static String cyrillic(int code) {
        int c;
        if (code >= 10017 && code <= 10022) {
            c = 0x0410 + code - 10017;
        } else if (code == 10023) {
            c = 0x0401;
        } else if (code >= 10024 && code <= 10049) {
            c = 0x0416 + code - 10024;
        } else if (code >= 10065 && code <= 10070) {
            c = 0x0430 + code - 10065;
        } else if (code == 10071) {
            c = 0x0451;
        } else if (code >= 10072 && code <= 10097) {
            c = 0x0436 + code - 10072;
        } else {
            return null;
        }
        return String.valueOf((char) c);
    }
}
