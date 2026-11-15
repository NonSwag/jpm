package dev.jpm.importer;

import dev.jpm.json.Jsonc;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.VersionSpecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Gradle build into jpm manifests.
 *
 * <p>Two ways to read the build. {@link #runModel} runs Gradle with an init script that reports the
 * evaluated model, which is exact but executes the build's scripts, so it is used only for an explicit
 * {@code jpm import} of your own project. {@link #runStatic} reads {@code settings.gradle(.kts)},
 * {@code build.gradle(.kts)}, {@code gradle.properties} and {@code gradle/libs.versions.toml} as text
 * and never executes anything; it is used for Git dependencies and when Gradle is not available.
 */
public final class GradleImport {
    private GradleImport() {}

    /** One Gradle project, as either reader sees it. */
    static final class GProject {
        String path = ":";
        String name;
        String dir = "";
        String group;
        String version;
        boolean java;
        boolean kotlin;
        Integer release;
        String mainClass;
        /** configuration name to dependencies */
        final Map<String, List<GDep>> deps = new LinkedHashMap<>();
        /** group:name to resolved version (model reader only) */
        final Map<String, String> resolved = new TreeMap<>();
        final List<String> repositories = new ArrayList<>();
        List<String> mainJava = List.of();
        List<String> testJava = List.of();
    }

    record GDep(String project, String group, String name, String version, String category, String raw,
                String classifier, List<String> excludes) {
        GDep(String project, String group, String name, String version, String category, String raw) {
            this(project, group, name, version, category, raw, null, List.of());
        }
    }

    public static Imported run(Path dir, dev.jpm.maven.MavenRepo repo) {
        Path launcher = launcher(dir);
        if (launcher == null) {
            Imported imp = runStatic(dir, repo);
            imp.warn(null, "Gradle was not found (no gradlew and no gradle on PATH), so the build files were read as text;"
                    + " check the result");
            return imp;
        }
        return runModel(dir, launcher, repo);
    }

    static Path launcher(Path dir) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path w = dir.resolve(win ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(w)) return w;
        return Exec.which("gradle");
    }

    // ---------------------------------------------------------------- reading the evaluated model

    @SuppressWarnings("unchecked")
    public static Imported runModel(Path dir, Path launcher, dev.jpm.maven.MavenRepo repo) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("jpm-gradle");
        } catch (IOException e) {
            throw new JpmException("Could not create a temporary directory: " + e.getMessage());
        }
        try {
            Path script = tmp.resolve("jpm-model.gradle");
            Path out = tmp.resolve("model");
            Fs.mkdirs(out);
            Fs.write(script, GradleModelScript.TEXT);
            Jdk jdk = Toolchain.require(JavaSpec.any());
            List<String> cmd = new ArrayList<>();
            if (launcher.getFileName().toString().equals("gradlew") && !Files.isExecutable(launcher)) cmd.add("sh");
            cmd.add(launcher.toString());
            // every project runs its own jpmModel task; the configuration cache cannot hold what they read
            cmd.addAll(List.of("-q", "--no-daemon", "--console=plain", "--init-script", script.toString(),
                    "-Djpm.model.output=" + out, "-Dorg.gradle.configuration-cache=false",
                    "-Dorg.gradle.unsafe.isolated-projects=false", "jpmModel"));
            Log.step("Reading", "the Gradle build with " + launcher.getFileName() + Log.dim(" (runs the build scripts)"));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("JAVA_HOME", jdk.home().toString());
            Exec.Result r = Exec.capture(dir, cmd, env);
            List<Path> reports = Fs.files(out, ".json");
            if (!r.ok() || reports.isEmpty()) {
                throw new JpmException("Gradle could not evaluate the build:\n" + (r.err() + r.out()).strip()
                        + "\nFix the build, or import without running Gradle: `jpm import --static`.");
            }
            List<Map<String, Object>> models = new ArrayList<>();
            for (Path f : reports) models.add((Map<String, Object>) Jsonc.parse(Fs.read(f)));
            // the root first, then the subprojects in path order, as Gradle lists them
            models.sort(java.util.Comparator.comparing((Map<String, Object> m) -> !":".equals(m.get("path")))
                    .thenComparing(m -> String.valueOf(m.get("path"))));
            List<GProject> projects = new ArrayList<>();
            Imported imp = new Imported("Gradle");
            for (Map<String, Object> pm : models) {
                projects.add(fromModel(pm));
                if (pm.get("resolveError") != null) {
                    imp.warn((String) pm.get("dir"), "Gradle could not resolve " + pm.get("resolveError")
                            + "; versions it did not resolve are read from the platforms' POMs instead");
                }
            }
            applyPlatforms(projects, repo, imp);
            convert(projects, imp);
            return imp;
        } finally {
            Fs.deleteTree(tmp);
        }
    }

    @SuppressWarnings("unchecked")
    private static GProject fromModel(Map<String, Object> m) {
        GProject p = new GProject();
        p.path = (String) m.get("path");
        p.name = (String) m.get("name");
        p.dir = (String) m.getOrDefault("dir", "");
        p.group = blankToNull((String) m.get("group"));
        p.version = blankToNull((String) m.get("version"));
        if ("unspecified".equals(p.version)) p.version = null;
        p.java = Boolean.TRUE.equals(m.get("java"));
        p.kotlin = Boolean.TRUE.equals(m.get("kotlin"));
        Object rel = m.get("release");
        if (rel == null) rel = m.get("toolchain");
        if (rel == null) rel = m.get("target");
        p.release = rel == null ? null : Imported.major(rel.toString());
        p.mainClass = blankToNull((String) m.get("mainClass"));
        Map<String, Object> deps = (Map<String, Object>) m.getOrDefault("deps", Map.of());
        for (Map.Entry<String, Object> e : deps.entrySet()) {
            List<GDep> list = new ArrayList<>();
            for (Object o : (List<Object>) e.getValue()) {
                Map<String, Object> d = (Map<String, Object>) o;
                List<String> ex = new ArrayList<>();
                for (Object x : (List<Object>) d.getOrDefault("excludes", List.of())) {
                    Map<String, Object> xm = (Map<String, Object>) x;
                    String g = (String) xm.get("group"), mod = (String) xm.get("module");
                    if (g != null) ex.add(g + ":" + (mod == null ? "*" : mod));
                }
                list.add(new GDep((String) d.get("project"), (String) d.get("group"), (String) d.get("name"),
                        (String) d.get("version"), (String) d.get("category"), null, (String) d.get("classifier"), ex));
            }
            p.deps.put(e.getKey(), list);
        }
        Map<String, Object> resolved = (Map<String, Object>) m.getOrDefault("resolved", Map.of());
        for (Map.Entry<String, Object> e : resolved.entrySet()) p.resolved.put(e.getKey(), String.valueOf(e.getValue()));
        for (Object r : (List<Object>) m.getOrDefault("repositories", List.of())) p.repositories.add(r.toString());
        Map<String, Object> ss = (Map<String, Object>) m.getOrDefault("sourceSets", Map.of());
        if (ss.get("main") instanceof Map<?, ?> main) p.mainJava = (List<String>) main.get("java");
        if (ss.get("test") instanceof Map<?, ?> test) p.testJava = (List<String>) test.get("java");
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------- reading the files as text

    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern CONF = Pattern.compile(
            "\\b(api|implementation|compileOnlyApi|compileOnly|runtimeOnly|annotationProcessor|testImplementation|"
                    + "testCompileOnly|testRuntimeOnly|testAnnotationProcessor)\\b\\s*\\(?\\s*(.+)");

    /** Removes {@code name { ... }} blocks (balanced braces) from build script text. */
    static String withoutBlocks(String text, String... names) {
        String out = text;
        for (String name : names) {
            Matcher m = Pattern.compile("\\b" + name + "\\s*\\{").matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find(last)) {
                int i = m.end(), depth = 1;
                while (i < out.length() && depth > 0) {
                    char c = out.charAt(i++);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
                sb.append(out, last, m.start());
                last = i;
            }
            sb.append(out.substring(Math.min(last, out.length())));
            out = sb.toString();
        }
        return out;
    }

    public static Imported runStatic(Path dir) {
        return runStatic(dir, null);
    }

    /** Reads the build as text. With a repository, versions managed by platforms (BOMs) are looked up in their POMs. */
    public static Imported runStatic(Path dir, dev.jpm.maven.MavenRepo repo) {
        Imported imp = new Imported("Gradle");
        Map<String, String> props = gradleProperties(dir.resolve("gradle.properties"));
        Catalog catalog = Catalog.read(dir.resolve("gradle").resolve("libs.versions.toml"));
        String settings = firstExisting(dir, "settings.gradle.kts", "settings.gradle");
        List<GProject> projects = new ArrayList<>();
        GProject root = new GProject();
        root.name = dir.getFileName().toString();
        if (settings != null) {
            Matcher rn = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").matcher(settings);
            if (rn.find()) root.name = rn.group(1);
        }
        projects.add(root);
        if (settings != null) {
            Matcher inc = Pattern.compile("(?m)^\\s*include\\s*\\(?(.*)$").matcher(settings);
            while (inc.find()) {
                Matcher q = QUOTED.matcher(inc.group(1));
                while (q.find()) {
                    String path = q.group(1).startsWith(":") ? q.group(1) : ":" + q.group(1);
                    GProject p = new GProject();
                    p.path = path;
                    p.name = path.substring(path.lastIndexOf(':') + 1);
                    p.dir = path.substring(1).replace(':', '/');
                    projects.add(p);
                }
            }
            if (settings.contains("projectDir")) imp.warn(null, "custom projectDir settings are not supported; modules are assumed to sit at their path");
        }
        String rootBuild = firstExisting(dir, "build.gradle.kts", "build.gradle");
        // defaults set in allprojects/subprojects blocks, or gradle.properties
        String defGroup = props.get("group"), defVersion = props.get("version");
        if (rootBuild != null) {
            String g = assignment(rootBuild, "group");
            String v = assignment(rootBuild, "version");
            if (g != null) defGroup = g;
            if (v != null) defVersion = v;
        }
        for (GProject p : projects) {
            Path pd = p.dir.isEmpty() ? dir : dir.resolve(p.dir);
            String build = p.dir.isEmpty() ? rootBuild : firstExisting(pd, "build.gradle.kts", "build.gradle");
            p.group = defGroup;
            p.version = defVersion;
            if (build == null) continue;
            if (!p.dir.isEmpty()) {
                String g = assignment(build, "group");
                String v = assignment(build, "version");
                if (g != null) p.group = g;
                if (v != null) p.version = v;
            }
            String noComments = build.replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
            // what the root sets up for subprojects is not about the root itself
            String own = p.dir.isEmpty() ? withoutBlocks(noComments, "subprojects", "allprojects") : noComments;
            p.java = Pattern.compile("\\b(java|java-library|application)\\b|id\\s*\\(?\\s*[\"'](java|java-library|application)[\"']|apply\\s+plugin:\\s*[\"']java")
                    .matcher(own).find() || own.contains("libs.plugins.");
            p.kotlin = noComments.contains("kotlin(\"jvm\")") || noComments.contains("org.jetbrains.kotlin.jvm");
            p.release = javaVersion(noComments);
            p.mainClass = mainClass(noComments);
            readDependencies(noComments, p, catalog, imp);
            readRepositories(noComments, p);
        }
        // Java settings in subprojects {} or allprojects {} of the root apply to every module.
        if (rootBuild != null) {
            String rb = rootBuild.replaceAll("(?m)//.*$", "");
            boolean shared = rb.contains("subprojects") || rb.contains("allprojects");
            if (shared) {
                Integer rel = javaVersion(rb);
                for (GProject p : projects) {
                    if (!p.dir.isEmpty()) {
                        if (p.release == null) p.release = rel;
                        if (rb.matches("(?s).*(subprojects|allprojects)\\s*\\{.*(java|java-library).*")) p.java = true;
                    }
                }
            }
        }
        for (GProject p : projects) {
            Path pd = p.dir.isEmpty() ? dir : dir.resolve(p.dir);
            if (!p.java && Files.isDirectory(pd.resolve("src/main/java"))) p.java = true;
        }
        applyPlatforms(projects, repo, imp);
        imp.warn(null, "the Gradle build was read without running it; check the result");
        convert(projects, imp);
        return imp;
    }

    /**
     * Fills in versions that platforms (BOMs) manage: each platform's POM is read from the repository,
     * which executes nothing.
     */
    private static void applyPlatforms(List<GProject> projects, dev.jpm.maven.MavenRepo repo, Imported imp) {
        if (repo == null) return;
        dev.jpm.maven.Pom.Loader loader = new dev.jpm.maven.Pom.Loader(repo);
        for (GProject p : projects) {
            Map<String, String> managed = new LinkedHashMap<>();
            for (List<GDep> list : p.deps.values()) {
                for (GDep d : list) {
                    if (!"platform".equals(d.category())) continue;
                    String[] coords = platformCoords(d);
                    if (coords == null) continue;
                    try {
                        for (dev.jpm.maven.Pom.Dep m : loader.load(coords[0], coords[1], coords[2]).management.values()) {
                            if (m.version() != null) managed.putIfAbsent(m.key(), m.version());
                        }
                    } catch (JpmException e) {
                        imp.warn(p.dir, "could not read platform " + String.join(":", coords) + ": " + e.getMessage());
                    }
                }
            }
            for (Map.Entry<String, String> e : managed.entrySet()) p.resolved.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** {group, name, version} of a platform dependency read as text. */
    private static String[] platformCoords(GDep d) {
        if (d.group() != null && d.version() != null) return new String[]{d.group(), d.name(), d.version()};
        return null;
    }

    private static String firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path f = dir.resolve(n);
            if (Files.isRegularFile(f)) return Fs.read(f);
        }
        return null;
    }

    private static Map<String, String> gradleProperties(Path f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        for (String line : Fs.read(f).split("\r?\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) eq = line.indexOf(':');
            if (eq > 0) out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    private static String assignment(String build, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:project\\.)?" + key + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    static Integer javaVersion(String build) {
        String[] patterns = {
                "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)",
                "options\\.release(?:\\.set)?\\s*[=(]\\s*(\\d+)",
                "release(?:\\.set)?\\s*[=(]\\s*(\\d+)",
                "(?:source|target)Compatibility\\s*=\\s*(JavaVersion\\.VERSION_[\\d_]+|[\"']?[\\d.]+[\"']?)",
                "jvmToolchain\\(\\s*(\\d+)\\s*\\)"};
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(build);
            if (m.find()) {
                Integer r = Imported.major(m.group(1).replace("\"", "").replace("'", ""));
                if (r != null) return r;
            }
        }
        return null;
    }

    static String mainClass(String build) {
        Matcher m = Pattern.compile("mainClass(?:Name)?(?:\\.set)?\\s*[=(]\\s*[\"']([\\w.$]+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    private static void readDependencies(String build, GProject p, Catalog catalog, Imported imp) {
        Matcher blocks = Pattern.compile("dependencies\\s*\\{").matcher(build);
        while (blocks.find()) {
            int start = blocks.end(), depth = 1, i = start;
            while (i < build.length() && depth > 0) {
                char c = build.charAt(i++);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            String block = build.substring(start, Math.max(start, i - 1));
            // skip dependency blocks of buildscript {}, which are for the build itself
            String before = build.substring(Math.max(0, blocks.start() - 200), blocks.start());
            if (before.matches("(?s).*buildscript\\s*\\{[^}]*$")) continue;
            for (String line : block.split("\r?\n")) {
                Matcher m = CONF.matcher(line.strip());
                if (!m.find()) continue;
                String conf = m.group(1), arg = m.group(2).strip();
                List<GDep> list = p.deps.computeIfAbsent(conf, k -> new ArrayList<>());
                if (arg.startsWith("platform(") || arg.startsWith("enforcedPlatform(")) {
                    String inner = arg.substring(arg.indexOf('(') + 1).strip();
                    String[] c = null;
                    Matcher lib = Pattern.compile("^(libs\\.[\\w.]+)").matcher(inner);
                    Matcher q = QUOTED.matcher(inner);
                    if (lib.find()) c = catalog.library(lib.group(1).substring(5));
                    else if (q.find()) c = q.group(1).split(":").length >= 3 ? q.group(1).split(":") : null;
                    if (c == null) list.add(new GDep(null, null, null, null, "platform", arg));
                    else list.add(new GDep(null, c[0], c[1], c[2], "platform", arg));
                    continue;
                }
                Matcher proj = Pattern.compile("project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']").matcher(arg);
                if (proj.find()) {
                    list.add(new GDep(proj.group(1), null, null, null, null, arg));
                    continue;
                }
                Matcher lib = Pattern.compile("^(libs\\.[\\w.]+)").matcher(arg);
                if (lib.find()) {
                    String accessor = lib.group(1).substring(5);
                    if (accessor.startsWith("bundles.")) {
                        List<String[]> bundle = catalog.bundle(accessor.substring(8));
                        if (bundle == null) imp.warn(p.dir, "version catalog bundle " + accessor + " not found");
                        else for (String[] c : bundle) list.add(new GDep(null, c[0], c[1], c[2], null, arg));
                    } else {
                        String[] c = catalog.library(accessor);
                        if (c == null) imp.warn(p.dir, "version catalog entry libs." + accessor + " not found");
                        else list.add(new GDep(null, c[0], c[1], c[2], null, arg));
                    }
                    continue;
                }
                Matcher q = QUOTED.matcher(arg);
                if (q.find() && q.start() <= 1) {
                    String[] parts = q.group(1).split(":");
                    if (parts.length >= 2) {
                        list.add(new GDep(null, parts[0], parts[1], parts.length >= 3 ? parts[2] : null, null, arg,
                                parts.length >= 4 ? parts[3] : null, List.of()));
                        continue;
                    }
                }
                Matcher map = Pattern.compile("group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"']"
                        + "(?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?").matcher(arg);
                if (map.find()) {
                    list.add(new GDep(null, map.group(1), map.group(2), map.group(3), null, arg));
                    continue;
                }
                imp.warn(p.dir, "could not read dependency `" + conf + " " + arg + "`");
            }
        }
    }

    private static void readRepositories(String build, GProject p) {
        Matcher m = Pattern.compile("maven\\s*(?:\\{[^}]*?url\\s*[=(]?\\s*(?:uri\\s*\\()?|\\(\\s*(?:url\\s*=\\s*)?(?:uri\\s*\\()?)[\"']([^\"']+)[\"']").matcher(build);
        while (m.find()) p.repositories.add(m.group(1));
        if (build.contains("google()")) p.repositories.add("https://dl.google.com/dl/android/maven2/");
    }

    /** A minimal reader of gradle/libs.versions.toml. */
    static final class Catalog {
        final Map<String, String> versions = new LinkedHashMap<>();
        /** normalized alias to {group, name, version} */
        final Map<String, String[]> libraries = new LinkedHashMap<>();
        final Map<String, List<String>> bundles = new LinkedHashMap<>();

        static String norm(String alias) {
            return alias.replaceAll("[-_.]", ".").toLowerCase();
        }

        static Catalog read(Path f) {
            Catalog c = new Catalog();
            if (!Files.isRegularFile(f)) return c;
            return parse(Fs.read(f));
        }

        static Catalog parse(String text) {
            Catalog c = new Catalog();
            String section = "";
            for (String raw : text.split("\r?\n")) {
                String line = raw.replaceAll("\\s+#.*$", "").strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    section = line.replaceAll("[\\[\\]\\s]", "");
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).strip().replace("\"", "");
                String value = line.substring(eq + 1).strip();
                switch (section) {
                    case "versions" -> {
                        if (value.startsWith("\"")) c.versions.put(key, unquote(value));
                        else {
                            String strict = field(value, "strictly");
                            String req = field(value, "require");
                            String pref = field(value, "prefer");
                            c.versions.put(key, strict != null ? strict : req != null ? req : pref);
                        }
                    }
                    case "libraries" -> {
                        String group, name, version = null;
                        if (value.startsWith("\"")) {
                            String[] parts = unquote(value).split(":");
                            if (parts.length < 2) continue;
                            group = parts[0];
                            name = parts[1];
                            if (parts.length > 2) version = parts[2];
                        } else {
                            String module = field(value, "module");
                            if (module != null && module.contains(":")) {
                                group = module.substring(0, module.indexOf(':'));
                                name = module.substring(module.indexOf(':') + 1);
                            } else {
                                group = field(value, "group");
                                name = field(value, "name");
                            }
                            String ref = field(value, "version.ref");
                            if (ref == null) {
                                Matcher vr = Pattern.compile("version\\s*=\\s*\\{[^}]*ref\\s*=\\s*\"([^\"]+)\"").matcher(value);
                                if (vr.find()) ref = vr.group(1);
                            }
                            version = ref != null ? c.versions.get(ref) : field(value, "version");
                        }
                        if (group != null && name != null) c.libraries.put(norm(key), new String[]{group, name, version});
                    }
                    case "bundles" -> {
                        List<String> members = new ArrayList<>();
                        Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(value);
                        while (q.find()) members.add(norm(q.group(1)));
                        c.bundles.put(norm(key), members);
                    }
                    default -> {
                    }
                }
            }
            return c;
        }

        private static String unquote(String v) {
            v = v.strip();
            return v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2 ? v.substring(1, v.length() - 1) : v;
        }

        private static String field(String table, String name) {
            Matcher m = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(table);
            return m.find() ? m.group(1) : null;
        }

        String[] library(String accessor) {
            return libraries.get(norm(accessor));
        }

        List<String[]> bundle(String accessor) {
            List<String> members = bundles.get(norm(accessor));
            if (members == null) return null;
            List<String[]> out = new ArrayList<>();
            for (String m : members) {
                String[] lib = libraries.get(m);
                if (lib != null) out.add(lib);
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- model to manifests

    static void convert(List<GProject> projects, Imported imp) {
        GProject root = projects.stream().filter(p -> p.path.equals(":")).findFirst().orElse(projects.get(0));
        List<GProject> modules = new ArrayList<>();
        for (GProject p : projects) {
            if (!(p.java || p.kotlin)) continue;
            if (p == root && projects.size() > 1 && projects.stream().anyMatch(q -> q != root && (q.java || q.kotlin))) {
                imp.warn("root", "the root project applies the java plugin; a jpm workspace root holds no sources, so its own sources are not imported");
                continue;
            }
            modules.add(p);
        }
        if (modules.isEmpty()) throw new JpmException("The Gradle build has no Java projects to import");
        Map<String, String> byPath = new LinkedHashMap<>();
        for (GProject p : projects) byPath.put(p.path, p.name);
        boolean workspace = !(modules.size() == 1 && modules.get(0) == root);

        if (workspace) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
            ws.put("name", root.name);
            if (root.version != null) ws.put("version", root.version);
            ws.put("workspace", modules.stream().map(p -> p.dir).toList());
            List<String> repos = repositories(projects);
            if (!repos.isEmpty()) ws.put("repositories", repos);
            imp.manifests.put("", ws);
        }
        for (GProject p : modules) {
            String where = p.dir;
            Map<String, Object> man = new LinkedHashMap<>();
            man.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
            man.put("name", p.name);
            if (p.group != null) man.put("group", p.group);
            if (p.version != null && (!workspace || !p.version.equals(root.version))) man.put("version", p.version);
            man.put("layout", "maven");
            if (p.release != null) {
                man.put("java", Imported.jdkFor(p.release));
                man.put("target", Integer.toString(p.release));
            }
            if (p.mainClass != null) man.put("main", p.mainClass);
            if (p.kotlin) imp.warn(where, "Kotlin sources are not compiled by jpm; only Java sources are built");
            String expectedMain = (p.dir.isEmpty() ? "" : p.dir + "/") + "src/main/java";
            for (String d : p.mainJava) {
                if (!d.equals(expectedMain) && !d.endsWith("src/main/kotlin")) imp.warn(where, "source directory " + d + " is not built; jpm uses " + expectedMain);
            }
            Map<String, Object> runtime = new TreeMap<>(), dev = new TreeMap<>(), test = new TreeMap<>();
            for (Map.Entry<String, List<GDep>> e : p.deps.entrySet()) {
                String conf = e.getKey();
                Map<String, Object> target = switch (conf) {
                    case "api", "implementation", "runtimeOnly" -> runtime;
                    case "compileOnly", "compileOnlyApi", "annotationProcessor" -> dev;
                    default -> test;
                };
                for (GDep d : e.getValue()) {
                    if ("platform".equals(d.category) || "enforced-platform".equals(d.category)) {
                        imp.warn(where, "platform " + (d.group != null ? d.group + ":" + d.name : d.raw)
                                + " is not carried over; the versions it manages are written out instead");
                        continue;
                    }
                    // jpm runs tests with its own JUnit Platform launcher
                    if ("org.junit.platform".equals(d.group) && "junit-platform-launcher".equals(d.name)) continue;
                    if (d.project != null) {
                        String name = byPath.get(d.project.startsWith(":") ? d.project : ":" + d.project);
                        if (name == null) {
                            imp.warn(where, "dependency on unknown project " + d.project + " skipped");
                        } else {
                            target.put("workspace:" + name, "*");
                        }
                        continue;
                    }
                    String key = d.group + ":" + d.name;
                    String version = d.version;
                    // Dynamic versions (1.+, latest.release, ranges) are jpm versions too, and stay dynamic;
                    // what Gradle resolved fills in versions that are missing (managed by a platform) or unreadable.
                    boolean readable = version != null && !version.isBlank();
                    if (readable) {
                        try {
                            VersionSpecs.parse(version);
                        } catch (IllegalArgumentException unreadable) {
                            readable = false;
                        }
                    }
                    if (!readable) {
                        String res = p.resolved.get(key);
                        if (res != null) {
                            version = res;
                        } else {
                            imp.warn(where, "no version for " + key + (d.version != null ? " (" + d.version + ")" : "") + "; skipped");
                            continue;
                        }
                    }
                    String k = d.classifier() != null && !d.classifier().isEmpty() ? key + ":" + d.classifier() : key;
                    // compileOnlyApi: a dev dependency that consumers compile against too
                    target.put(k, MavenImport.depValue(version, d.excludes(), conf.equals("runtimeOnly"), conf.equals("compileOnlyApi")));
                }
            }
            if (!runtime.isEmpty()) man.put("runtime", runtime);
            if (!dev.isEmpty()) man.put("dev", dev);
            if (!test.isEmpty()) man.put("test", test);
            if (!workspace) {
                List<String> repos = repositories(projects);
                if (!repos.isEmpty()) man.put("repositories", repos);
            }
            imp.manifests.put(p.dir, man);
        }
    }

    private static List<String> repositories(List<GProject> projects) {
        List<String> out = new ArrayList<>();
        for (GProject p : projects) {
            for (String r : p.repositories) {
                if (r.contains("repo.maven.apache.org") || r.contains("repo1.maven.org") || r.contains("plugins.gradle.org")) continue;
                String u = r.endsWith("/") ? r : r + "/";
                if (!out.contains(u)) out.add(u);
            }
        }
        return out;
    }
}
