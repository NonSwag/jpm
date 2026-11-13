package dev.jpm.build;

import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A JDK on this machine. Discovery looks at {@code JAVA_HOME}, JDKs managed by jpm in
 * {@code ~/.jpm/jdks}, {@code java} on PATH and the usual install locations.
 *
 * <p>Automatic installation of a missing JDK belongs to roadmap step 2 ({@code jpm java}).
 */
public record Jdk(Path home, int major, String version, boolean graal, String vendor) {

    public Path bin(String tool) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return home.resolve("bin").resolve(win ? tool + ".exe" : tool);
    }

    public String java() { return bin("java").toString(); }
    public String javac() { return bin("javac").toString(); }

    public String id() {
        return (graal ? "graalvm-" : "") + version;
    }

    private static List<Jdk> cached;

    public static synchronized List<Jdk> discover() {
        if (cached != null) return cached;
        List<Path> homes = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) homes.add(Path.of(jh));
        addChildren(homes, Fs.home().resolve("jdks"));
        Path onPath = Exec.which("javac");
        if (onPath != null) {
            try {
                homes.add(onPath.toRealPath().getParent().getParent());
            } catch (IOException ignored) {
            }
        }
        String uh = System.getProperty("user.home");
        addChildren(homes, Path.of("/usr/lib/jvm"));
        addChildren(homes, Path.of("/opt/java"));
        addChildren(homes, Path.of(uh, ".sdkman", "candidates", "java"));
        addChildren(homes, Path.of(uh, ".jdks"));
        for (Path p : children(Path.of("/Library/Java/JavaVirtualMachines"))) homes.add(p.resolve("Contents/Home"));
        for (Path p : children(Path.of(uh, "Library/Java/JavaVirtualMachines"))) homes.add(p.resolve("Contents/Home"));
        addChildren(homes, Path.of("C:\\Program Files\\Java"));
        addChildren(homes, Path.of("C:\\Program Files\\Eclipse Adoptium"));

        Map<Path, Jdk> found = new LinkedHashMap<>();
        for (Path h : homes) {
            try {
                Path real = h.toRealPath();
                if (found.containsKey(real)) continue;
                Jdk j = probe(real);
                if (j != null) found.put(real, j);
            } catch (IOException ignored) {
            }
        }
        cached = new ArrayList<>(found.values());
        return cached;
    }

    private static void addChildren(List<Path> out, Path dir) {
        out.addAll(children(dir));
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static Jdk probe(Path home) {
        Path release = home.resolve("release");
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        if (!Files.exists(home.resolve("bin").resolve(win ? "javac.exe" : "javac"))) return null;
        if (!Files.exists(release)) return null;
        String text = Fs.read(release);
        String version = prop(text, "JAVA_VERSION");
        if (version == null) return null;
        String vendor = prop(text, "IMPLEMENTOR");
        boolean graal = text.contains("GRAALVM_VERSION") || (vendor != null && vendor.toLowerCase().contains("graalvm"))
                || Files.exists(home.resolve("bin").resolve(win ? "native-image.cmd" : "native-image"));
        String v = version.startsWith("1.") ? version.substring(2) : version;
        int major;
        try {
            major = Integer.parseInt(v.split("[.+_-]")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new Jdk(home, major, version, graal, vendor == null ? "unknown" : vendor);
    }

    private static String prop(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim().replace("\"", "");
        }
        return null;
    }

    /**
     * Picks a JDK for a manifest's {@code java} value ("21", "graalvm-21", or null for any).
     * A plain JDK of the same major version stands in for GraalVM outside of native builds.
     */
    public static Jdk select(String spec, Integer major, boolean needGraal) {
        List<Jdk> all = discover();
        boolean wantGraal = spec != null && spec.toLowerCase().contains("graal");
        Jdk best = null;
        for (Jdk j : all) {
            if (major != null && j.major != major) continue;
            if (needGraal && !j.graal) continue;
            if (best == null) { best = j; continue; }
            if (wantGraal && j.graal && !best.graal) best = j;
            else if (major == null && j.major > best.major && best != all.get(0)) best = j;
        }
        if (best == null) {
            StringBuilder sb = new StringBuilder();
            sb.append(needGraal ? "GraalVM " : "JDK ").append(major == null ? "" : major + " ").append("was not found.");
            sb.append(all.isEmpty() ? " No JDKs were found." : " Found: " + String.join(", ", all.stream().map(j -> j.id() + " (" + j.home + ")").toList()) + ".");
            sb.append("\nInstall one or point JAVA_HOME at it. Automatic installation is coming with `jpm java` (roadmap step 2).");
            throw new JpmException(sb.toString());
        }
        if (wantGraal && !best.graal) {
            Log.debug("using " + best.id() + " in place of GraalVM " + major + " (only native builds need GraalVM)");
        }
        return best;
    }
}
