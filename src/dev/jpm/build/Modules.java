package dev.jpm.build;

import dev.jpm.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Java Platform Module System support. A source root with {@code module-info.java} is a named module;
 * javac then resolves its {@code requires} from the module path, so dependencies are split:
 *
 * <ul>
 *   <li>module path: jars that are modules (a {@code module-info.class}, also in
 *       {@code META-INF/versions/N/}, or an {@code Automatic-Module-Name}), and any jar whose
 *       filename-derived automatic module name the module {@code requires};</li>
 *   <li>class path: the rest, which the module reads through {@code --add-reads <name>=ALL-UNNAMED}.</li>
 * </ul>
 */
public final class Modules {
    private Modules() {}

    /** A module declaration: its name and the modules it requires. */
    public record Declaration(String name, Set<String> requires) {}

    private static final Pattern MODULE = Pattern.compile("\\b(?:open\\s+)?module\\s+([\\w.]+)\\s*\\{");
    private static final Pattern REQUIRES = Pattern.compile("\\brequires\\s+(?:(?:static|transitive)\\s+)*([\\w.]+)\\s*;");

    /** The module-info.java directly in one of the source roots, or null. */
    public static Path moduleInfo(List<Path> sourceRoots) {
        for (Path root : sourceRoots) {
            Path f = root.resolve("module-info.java");
            if (Files.isRegularFile(f)) return f;
        }
        return null;
    }

    public static Declaration parse(Path moduleInfo) {
        String src = Fs.read(moduleInfo)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");
        Matcher m = MODULE.matcher(src);
        String name = m.find() ? m.group(1) : null;
        Set<String> requires = new LinkedHashSet<>();
        Matcher r = REQUIRES.matcher(src);
        while (r.find()) requires.add(r.group(1));
        return new Declaration(name, requires);
    }

    /** The module name a jar declares (module-info or Automatic-Module-Name), or null if it is not a module. */
    public static String declaredName(Path jar) {
        if (Files.isDirectory(jar)) {
            Path mi = jar.resolve("module-info.class");
            return Files.exists(mi) ? nameFromClass(mi) : null;
        }
        if (!Files.isRegularFile(jar)) return null;
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry mi = jf.getEntry("module-info.class");
            if (mi == null) {
                // multi-release jars may keep it under a version directory only
                for (var en = jf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().matches("META-INF/versions/\\d+/module-info\\.class")) { mi = e; break; }
                }
            }
            if (mi != null) {
                try (var in = jf.getInputStream(mi)) {
                    String n = moduleNameFromBytes(in.readAllBytes());
                    if (n != null) return n;
                }
            }
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf != null) {
                String auto = mf.getMainAttributes().getValue("Automatic-Module-Name");
                if (auto != null && !auto.isBlank()) return auto.trim();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static String nameFromClass(Path moduleInfoClass) {
        try {
            return moduleNameFromBytes(Files.readAllBytes(moduleInfoClass));
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads the module name from a module-info.class (its Module attribute's first entry). */
    static String moduleNameFromBytes(byte[] b) {
        try {
            java.lang.module.ModuleDescriptor d = java.lang.module.ModuleDescriptor.read(java.nio.ByteBuffer.wrap(b));
            return d.name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The automatic module name the JDK derives from a jar file name: drop ".jar" and a trailing version,
     * turn other characters into dots.
     */
    public static String automaticName(Path jar) {
        String n = jar.getFileName().toString();
        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) n = n.substring(0, n.length() - 4);
        Matcher v = Pattern.compile("-(\\d+(\\.|$))").matcher(n);
        if (v.find()) n = n.substring(0, v.start());
        n = n.replaceAll("[^A-Za-z0-9]", ".").replaceAll("\\.{2,}", ".").replaceAll("^\\.|\\.$", "");
        return n;
    }

    /** Splits dependencies into module path and class path for compiling the declared module. */
    public static List<List<Path>> split(Declaration module, List<Path> deps) {
        List<Path> modulePath = new ArrayList<>(), classPath = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (Path p : deps) {
            String name = declaredName(p);
            if (name == null && module.requires().contains(automaticName(p))) name = automaticName(p);
            // one module of each name: two jars with the same module would stop javac
            if (name != null && placed.add(name)) modulePath.add(p);
            else classPath.add(p);
        }
        return List.of(modulePath, classPath);
    }
}
