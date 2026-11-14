package dev.jpm.build;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Writes reproducible jars. */
public final class Jars {
    private Jars() {}

    /** 1980-02-01, the conventional reproducible-build timestamp. */
    private static final long EPOCH = 315532800000L + 31L * 24 * 3600 * 1000;

    /**
     * Writes a jar from directories (merged in order, earlier wins) with the given manifest attributes.
     */
    public static void write(Path jar, List<Path> dirs, Map<String, String> attrs) {
        Map<String, Path> entries = new TreeMap<>();
        for (Path d : dirs) {
            for (Path f : Fs.files(d, null)) {
                String name = Fs.slash(d.relativize(f));
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                entries.putIfAbsent(name, f);
            }
        }
        Fs.mkdirs(jar.getParent());
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream out = new JarOutputStream(os)) {
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Created-By", "jpm");
            for (Map.Entry<String, String> e : attrs.entrySet()) mf.getMainAttributes().putValue(e.getKey(), e.getValue());
            JarEntry me = new JarEntry("META-INF/MANIFEST.MF");
            me.setTime(EPOCH);
            out.putNextEntry(me);
            mf.write(out);
            out.closeEntry();
            Set<String> dirsWritten = new java.util.HashSet<>();
            dirsWritten.add("META-INF/");
            for (Map.Entry<String, Path> e : entries.entrySet()) {
                String name = e.getKey();
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                    String dir = name.substring(0, i + 1);
                    if (dirsWritten.add(dir)) {
                        JarEntry de = new JarEntry(dir);
                        de.setTime(EPOCH);
                        out.putNextEntry(de);
                        out.closeEntry();
                    }
                }
                JarEntry je = new JarEntry(name);
                je.setTime(EPOCH);
                out.putNextEntry(je);
                Files.copy(e.getValue(), out);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
        try {
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
    }

    /** Copies a jar, dropping entries that match {@code drop} and adding files from {@code overlay}. */
    public static void rewrite(Path in, Path outJar, Predicate<String> drop, Path overlay) {
        Fs.mkdirs(outJar.getParent());
        Map<String, Path> add = new LinkedHashMap<>();
        for (Path f : Fs.files(overlay, null)) add.put(Fs.slash(overlay.relativize(f)), f);
        Path tmp = outJar.resolveSibling(outJar.getFileName() + ".tmp");
        try (ZipFile zf = new ZipFile(in.toFile());
             OutputStream os = Files.newOutputStream(tmp);
             JarOutputStream out = new JarOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (add.containsKey(n) || drop.test(n)) continue;
                // signatures would no longer match patched classes
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue;
                ZipEntry ne = new ZipEntry(n);
                ne.setTime(EPOCH);
                out.putNextEntry(ne);
                if (!e.isDirectory()) try (InputStream is = zf.getInputStream(e)) { is.transferTo(out); }
                out.closeEntry();
            }
            for (Map.Entry<String, Path> e : add.entrySet()) {
                ZipEntry ne = new ZipEntry(e.getKey());
                ne.setTime(EPOCH);
                out.putNextEntry(ne);
                Files.copy(e.getValue(), out);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new JpmException("Could not rewrite " + in + ": " + e.getMessage());
        }
        try {
            Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not write " + outJar + ": " + e.getMessage());
        }
    }

    public static void extract(Path jar, Path dir) {
        dir = dir.toAbsolutePath().normalize();
        Fs.mkdirs(dir);
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path target = dir.resolve(e.getName()).normalize();
                if (!target.startsWith(dir)) continue; // zip slip
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream is = zf.getInputStream(e)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new JpmException("Could not extract " + jar + ": " + e.getMessage());
        }
    }

    /** Main-Class from a jar's manifest, or null. */
    public static String mainClass(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            java.util.jar.Manifest mf = jf.getManifest();
            return mf == null ? null : mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        } catch (IOException e) {
            return null;
        }
    }
}
