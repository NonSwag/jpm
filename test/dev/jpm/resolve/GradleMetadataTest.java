package dev.jpm.resolve;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Gradle library's compileOnlyApi dependency is "compile" in its POM; its module metadata says it is only
 * needed to compile. jpm keeps it off runtime classpaths and out of bundles.
 */
class GradleMetadataTest {
    private final String oldHome = System.getProperty("jpm.home");

    @AfterEach
    void restore() {
        if (oldHome == null) System.clearProperty("jpm.home"); else System.setProperty("jpm.home", oldHome);
    }

    private static void publish(Path repo, String group, String artifact, String version, String pomDeps, String module, String cls) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Fs.write(dir.resolve(version + "/" + artifact + "-" + version + ".pom"), "<project>"
                + (module != null ? "<!-- do_not_remove: published-with-gradle-metadata -->" : "")
                + "<modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><dependencies>" + pomDeps + "</dependencies></project>");
        if (module != null) Fs.write(dir.resolve(version + "/" + artifact + "-" + version + ".module"), module);
        Path jar = dir.resolve(version + "/" + artifact + "-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(cls));
            z.write(new byte[]{1});
        }
        Fs.write(dir.resolve("maven-metadata.xml"), "<metadata><versioning><versions><version>" + version + "</version></versions></versioning></metadata>");
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version><scope>compile</scope></dependency>";
    }

    @Test
    void compileOnlyApiDependenciesStayOutAtRunTime(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "net.kyori", "adventure-api", "4.17.0", "", null, "net/kyori/Adventure.class");
        publish(repo, "org.example", "util", "1.0", "", null, "org/example/Util.class");
        String module = """
                { "formatVersion": "1.1",
                  "component": { "group": "net.thenextlvl", "module": "i18n", "version": "1.2.0" },
                  "variants": [
                    { "name": "apiElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencies": [ { "group": "net.kyori", "module": "adventure-api", "version": { "requires": "4.17.0" } },
                                        { "group": "org.example", "module": "util", "version": { "requires": "1.0" } } ] },
                    { "name": "runtimeElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencies": [ { "group": "org.example", "module": "util", "version": { "requires": "1.0" } } ] },
                    { "name": "javadocElements", "attributes": { "org.gradle.category": "documentation" } }
                  ] }
                """;
        publish(repo, "net.thenextlvl", "i18n", "1.2.0",
                dep("net.kyori", "adventure-api", "4.17.0") + dep("org.example", "util", "1.0"), module, "net/thenextlvl/I18n.class");

        Path project = tmp.resolve("bedwars");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"bedwars\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"runtime\": { \"net.thenextlvl:i18n\": \"1.2.0\" } }");
        Fs.write(project.resolve("src/bw/BedWars.java"), "package bw; public class BedWars {}");

        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Graph.Edge adventure = g.get("net.thenextlvl:i18n").deps.get("net.kyori:adventure-api");
        assertTrue(adventure.compileOnly(), "adventure is compile only for i18n");
        assertFalse(g.get("net.thenextlvl:i18n").deps.get("org.example:util").compileOnly());

        var roots = Resolver.rootEdges(p.modules.get(0), dev.jpm.Manifest.Scope.RUNTIME);
        assertTrue(g.closure(roots, true).contains("net.kyori:adventure-api"), "on the compile classpath");
        assertFalse(g.closure(roots, false).contains("net.kyori:adventure-api"), "not on the runtime classpath");
        assertTrue(g.closure(roots, false).contains("org.example:util"));

        // the lockfile keeps it
        Graph locked = Lockfile.read(p.lockFile()).graph;
        assertTrue(locked.get("net.thenextlvl:i18n").deps.get("net.kyori:adventure-api").compileOnly());

        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(b.flatBundle(p.modules.get(0)).toFile())) {
            zf.stream().forEach(e -> names.add(e.getName()));
        }
        assertTrue(names.contains("net/thenextlvl/I18n.class") && names.contains("org/example/Util.class"), names.toString());
        assertFalse(names.contains("net/kyori/Adventure.class"), "the platform provides adventure");
        assertEquals(1, names.stream().filter(n -> n.equals("bw/BedWars.class")).count());
    }
}
