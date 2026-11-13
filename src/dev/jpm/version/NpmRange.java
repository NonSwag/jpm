package dev.jpm.version;

import java.util.ArrayList;
import java.util.List;

/**
 * npm-style semver range used for Git tags: {@code ^1.2}, {@code ~1.2.3}, {@code 1.x}, {@code *},
 * {@code >=1.0 <2.0}, {@code 1.2.3}, {@code 1.0 - 2.0} and {@code ||} alternatives.
 */
public final class NpmRange implements VersionSpec {
    private record Cmp(String op, int[] v, String pre) {
        boolean matches(int[] x) {
            int c = compare(x, v);
            return switch (op) {
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                default -> c == 0;
            };
        }
    }

    private final String text;
    private final List<List<Cmp>> alternatives;
    private final boolean exact;

    private NpmRange(String text, List<List<Cmp>> alternatives, boolean exact) {
        this.text = text;
        this.alternatives = alternatives;
        this.exact = exact;
    }

    /** True if the text looks like a semver range or version (as opposed to a tag name or hash). */
    public static boolean looksLikeRange(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        if (s.equals("*") || s.equals("latest")) return true;
        return s.matches("[\\^~<>=v\\d*xX|. -]+") && s.matches(".*[\\d*xX].*");
    }

    public static NpmRange parse(String text) {
        String s = text.trim();
        if (s.equals("latest") || s.isEmpty()) s = "*";
        List<List<Cmp>> alts = new ArrayList<>();
        boolean exact = false;
        for (String alt : s.split("\\|\\|")) {
            alt = alt.trim();
            List<Cmp> set = new ArrayList<>();
            String[] hy = alt.split("\\s+-\\s+");
            if (hy.length == 2) {
                set.add(new Cmp(">=", fill(partial(hy[0])), null));
                int[] hi = partial(hy[1]);
                set.add(upperFromPartial(hi));
            } else {
                for (String tok : alt.split("\\s+")) {
                    if (tok.isEmpty()) continue;
                    set.addAll(comparators(tok));
                }
            }
            if (set.isEmpty()) set.add(new Cmp(">=", new int[]{0, 0, 0}, null));
            alts.add(set);
        }
        if (alts.size() == 1 && alts.get(0).size() == 1 && alts.get(0).get(0).op.equals("=")) exact = true;
        return new NpmRange(text, alts, exact);
    }

    private static Cmp upperFromPartial(int[] p) {
        // "1.2 - 2.3" means <2.4.0; "1.2 - 2.3.4" means <=2.3.4
        if (p[2] >= 0) return new Cmp("<=", fill(p), null);
        if (p[1] >= 0) return new Cmp("<", new int[]{p[0], p[1] + 1, 0}, null);
        return new Cmp("<", new int[]{p[0] + 1, 0, 0}, null);
    }

    private static List<Cmp> comparators(String tok) {
        String op = "";
        int i = 0;
        while (i < tok.length() && "<>=^~".indexOf(tok.charAt(i)) >= 0) i++;
        op = tok.substring(0, i);
        String ver = tok.substring(i);
        if (ver.startsWith("v")) ver = ver.substring(1);
        String pre = null;
        int dash = ver.indexOf('-');
        if (dash >= 0) { pre = ver.substring(dash + 1); ver = ver.substring(0, dash); }
        int[] p = partial(ver);
        List<Cmp> out = new ArrayList<>();
        switch (op) {
            case "^" -> {
                int[] lo = fill(p);
                int[] hi;
                if (p[0] > 0 || p[1] < 0) hi = new int[]{p[0] + 1, 0, 0};
                else if (p[1] > 0 || p[2] < 0) hi = new int[]{0, p[1] + 1, 0};
                else hi = new int[]{0, 0, p[2] + 1};
                out.add(new Cmp(">=", lo, pre));
                out.add(new Cmp("<", hi, null));
            }
            case "~" -> {
                out.add(new Cmp(">=", fill(p), pre));
                if (p[1] < 0) out.add(new Cmp("<", new int[]{p[0] + 1, 0, 0}, null));
                else out.add(new Cmp("<", new int[]{p[0], p[1] + 1, 0}, null));
            }
            case "", "=" -> {
                if (p[0] < 0) {
                    out.add(new Cmp(">=", new int[]{0, 0, 0}, null));
                } else if (p[1] < 0) {
                    out.add(new Cmp(">=", new int[]{p[0], 0, 0}, null));
                    out.add(new Cmp("<", new int[]{p[0] + 1, 0, 0}, null));
                } else if (p[2] < 0) {
                    out.add(new Cmp(">=", new int[]{p[0], p[1], 0}, null));
                    out.add(new Cmp("<", new int[]{p[0], p[1] + 1, 0}, null));
                } else {
                    out.add(new Cmp("=", p, pre));
                }
            }
            case ">", ">=", "<", "<=" -> {
                if (op.equals(">") && p[2] < 0) {
                    // >1.2 means >=1.3.0
                    int[] lo = p[1] < 0 ? new int[]{p[0] + 1, 0, 0} : new int[]{p[0], p[1] + 1, 0};
                    out.add(new Cmp(">=", lo, null));
                } else if (op.equals("<=") && p[2] < 0) {
                    out.add(upperFromPartial(p));
                } else {
                    out.add(new Cmp(op, fill(p), pre));
                }
            }
            default -> throw new IllegalArgumentException("Invalid range operator \"" + op + "\"");
        }
        return out;
    }

    /** Parses "1", "1.2", "1.2.3", "1.x", "*" into [major, minor, patch] with -1 for wildcards. */
    private static int[] partial(String s) {
        s = s.trim();
        if (s.startsWith("v")) s = s.substring(1);
        int[] out = {-1, -1, -1};
        if (s.isEmpty() || s.equals("*")) return out;
        String[] parts = s.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            String p = parts[i];
            if (p.equals("x") || p.equals("X") || p.equals("*")) break;
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid version \"" + s + "\"");
            }
        }
        return out;
    }

    private static int[] fill(int[] p) {
        return new int[]{Math.max(p[0], 0), Math.max(p[1], 0), Math.max(p[2], 0)};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Parses a tag version into [major, minor, patch] and a pre-release suffix, or null. */
    private static Object[] semver(String v) {
        if (v.startsWith("v")) v = v.substring(1);
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        String pre = null;
        int dash = v.indexOf('-');
        if (dash >= 0) { pre = v.substring(dash + 1); v = v.substring(0, dash); }
        String[] parts = v.split("\\.");
        if (parts.length == 0 || parts.length > 3) return null;
        int[] out = {0, 0, 0};
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { return null; }
        }
        return new Object[]{out, pre};
    }

    @Override
    public boolean matches(Version version) {
        Object[] sv = semver(version.toString());
        if (sv == null) return false;
        int[] x = (int[]) sv[0];
        String pre = (String) sv[1];
        for (List<Cmp> set : alternatives) {
            boolean ok = true;
            boolean preAllowed = pre == null;
            for (Cmp c : set) {
                if (!c.matches(x)) {
                    // pre-release only matches a comparator on the same [major,minor,patch]
                    if (!(pre != null && c.pre != null && compare(x, c.v) == 0 && preCompare(pre, c.pre, c.op))) {
                        ok = false;
                        break;
                    }
                }
                if (pre != null && c.pre != null && compare(x, c.v) == 0) preAllowed = true;
            }
            if (ok && preAllowed) return true;
        }
        return false;
    }

    private static boolean preCompare(String pre, String bound, String op) {
        int c = Version.of("0-" + pre).compareTo(Version.of("0-" + bound));
        return switch (op) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            default -> c == 0;
        };
    }

    @Override
    public String text() { return text; }

    @Override
    public boolean isExact() { return exact; }

    @Override
    public String toString() { return text; }
}
