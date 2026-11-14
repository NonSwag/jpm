package dev.jpm.build;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModulesTest {
    @Test
    void parsesModuleInfo(@TempDir Path dir) {
        Fs.write(dir.resolve("module-info.java"), """
                /* comment: module fake { */
                open module com.example.app {
                    requires static org.jspecify; // nullness
                    requires transitive java.sql;
                    requires org.apache.commons.lang3;
                    exports com.example.app.api;
                }
                """);
        Modules.Declaration d = Modules.parse(Modules.moduleInfo(List.of(dir)));
        assertEquals("com.example.app", d.name());
        assertEquals(Set.of("org.jspecify", "java.sql", "org.apache.commons.lang3"), d.requires());
    }

    @Test
    void derivesAutomaticNames() {
        assertEquals("commons.lang3", Modules.automaticName(Path.of("commons-lang3-3.14.0.jar")));
        assertEquals("javax.inject", Modules.automaticName(Path.of("javax.inject-1.jar")));
        assertEquals("foo.bar", Modules.automaticName(Path.of("foo_bar-2.0-SNAPSHOT.jar")));
    }

    @Test
    void splitsModulePathFromClassPath(@TempDir Path dir) throws Exception {
        Path named = jar(dir.resolve("named-1.0.jar"), "org.named");
        Path plain = jar(dir.resolve("plain-lib-2.0.jar"), null);
        Path required = jar(dir.resolve("needed-3.0.jar"), null);
        assertEquals("org.named", Modules.declaredName(named));
        assertNull(Modules.declaredName(plain));
        Modules.Declaration d = new Modules.Declaration("app", Set.of("needed"));
        List<List<Path>> split = Modules.split(d, List.of(named, plain, required));
        assertEquals(List.of(named, required), split.get(0));
        assertEquals(List.of(plain), split.get(1));
    }

    private static Path jar(Path p, String automaticName) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticName != null) mf.getMainAttributes().putValue("Automatic-Module-Name", automaticName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p), mf)) {
            out.putNextEntry(new java.util.zip.ZipEntry("x/Y.class"));
            out.closeEntry();
        }
        return p;
    }
}
