package io.github.kiyo29.origami.internal;

/**
 * 旧式の色コード (&amp; / §) の変換。Minecraft 本体のクラスには依存しない。
 */
public final class LegacyText {
    private static final String CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    private LegacyText() { }

    /** altChar + 色コード文字 を § + 色コード文字 に変える (Bukkit の ChatColor.translateAlternateColorCodes と同じ規則)。 */
    public static String translate(char altChar, String text) {
        if (text == null) {
            return null;
        }
        char[] b = text.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == altChar && CODES.indexOf(b[i + 1]) > -1) {
                b[i] = '\u00A7';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    /** § 色コードを取り除く。 */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
