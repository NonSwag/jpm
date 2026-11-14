package dev.jpm.build;

import dev.jpm.util.Fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Copies a module's resources next to its compiled classes, so the classes folder is everything the jar,
 * the classpath and the module path need.
 *
 * <p>Everything in the resource folder ({@code src/assets/}, {@code test/assets/}) is copied as is.
 * Resources removed from it are removed from the output again.
 */
public final class Resources {
    private Resources() {}

    /** The resources under {@code roots}, by path relative to their root; earlier roots win. */
    public static TreeMap<String, Path> find(List<Path> roots) {
        TreeMap<String, Path> out = new TreeMap<>();
        for (Path root : roots) {
            for (Path f : Fs.files(root, null)) {
                out.putIfAbsent(Fs.slash(root.relativize(f)), f);
            }
        }
        return out;
    }

    /**
     * Makes {@code out} hold exactly the resources under {@code roots} that were copied by earlier runs
     * (recorded in {@code stateFile}), without touching the classes javac wrote there. Returns true if
     * anything changed.
     */
    public static boolean sync(List<Path> roots, Path out, Path stateFile) {
        TreeMap<String, Path> now = find(roots);
        TreeSet<String> before = new TreeSet<>();
        if (Files.isRegularFile(stateFile)) {
            for (String line : Fs.read(stateFile).split("\n")) if (!line.isBlank()) before.add(line);
        }
        boolean changed = false;
        try {
            for (String rel : before) {
                if (!now.containsKey(rel) && Files.deleteIfExists(out.resolve(rel))) changed = true;
            }
            for (var e : now.entrySet()) {
                Path target = out.resolve(e.getKey());
                Path src = e.getValue();
                if (same(src, target)) continue;
                Fs.mkdirs(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                changed = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String list = String.join("\n", new ArrayList<>(now.keySet()));
        if (!Files.isRegularFile(stateFile) || !Fs.read(stateFile).equals(list)) Fs.write(stateFile, list);
        return changed;
    }

    /** Same size and time (to the millisecond, as file systems round differently), else same bytes. */
    private static boolean same(Path src, Path target) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) != Files.size(src)) return false;
        if (Files.getLastModifiedTime(target).toMillis() == Files.getLastModifiedTime(src).toMillis()) return true;
        if (Files.mismatch(src, target) != -1) return false;
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(src));
        return true;
    }

}
