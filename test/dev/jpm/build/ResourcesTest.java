package dev.jpm.build;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcesTest {
    @Test
    void resourcesAmongSourcesAreCopiedNextToClasses(@TempDir Path dir) {
        Path src = dir.resolve("src");
        Fs.write(src.resolve("p/App.java"), "package p; class App {}");
        Fs.write(src.resolve("p/icon.png"), "png");
        Fs.write(src.resolve("META-INF/services/p.Api"), "p.Impl");
        Fs.write(src.resolve(".gitkeep"), "");
        Fs.write(src.resolve("p/.DS_Store"), "x");
        Fs.write(src.resolve(".idea/x.xml"), "x");
        Path out = dir.resolve("classes");
        Fs.write(out.resolve("p/App.class"), "class");
        Path state = dir.resolve("resources.list");
        List<Resources.Root> roots = List.of(new Resources.Root(src, true));

        assertTrue(Resources.sync(roots, out, state));
        assertEquals("png", Fs.read(out.resolve("p/icon.png")));
        assertEquals("p.Impl", Fs.read(out.resolve("META-INF/services/p.Api")));
        assertFalse(Files.exists(out.resolve("p/App.java")));
        assertFalse(Files.exists(out.resolve(".gitkeep")));
        assertFalse(Files.exists(out.resolve("p/.DS_Store")));
        assertFalse(Files.exists(out.resolve(".idea")));
        assertFalse(Resources.sync(roots, out, state), "nothing changed");

        // a removed resource disappears, compiled classes stay
        try {
            Files.delete(src.resolve("p/icon.png"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(Resources.sync(roots, out, state));
        assertFalse(Files.exists(out.resolve("p/icon.png")));
        assertTrue(Files.exists(out.resolve("p/App.class")));
    }

    @Test
    void separateResourceFoldersKeepJavaFiles(@TempDir Path dir) {
        Path res = dir.resolve("src/main/resources");
        Fs.write(res.resolve("templates/Gen.java"), "template");
        Resources.sync(List.of(new Resources.Root(res, false)), dir.resolve("out"), dir.resolve("state"));
        assertEquals("template", Fs.read(dir.resolve("out/templates/Gen.java")));
    }
}
