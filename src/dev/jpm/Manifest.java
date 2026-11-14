package dev.jpm;

import dev.jpm.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The parsed {@code project.jsonc}. */
public final class Manifest {
    public static final String FILE = "project.jsonc";
    public static final String LOCK = "project.lock";

    public enum Scope {
        RUNTIME("runtime"), DEV("dev"), TEST("test");
        public final String key;
        Scope(String key) { this.key = key; }
    }

    public enum DefineType { STRING, INT, BOOLEAN }

    /** A {@code define} entry. {@code value}/{@code env}/{@code defaultValue} may be null. */
    public record Define(String name, DefineType type, Object value, String env, Object defaultValue, int line) {
        public boolean hasSource() { return value != null || env != null; }
    }

    /**
     * A declared dependency. Written as a version string, or as an object
     * {@code {"version": "...", "exclude": [...], "runtimeOnly": true}}.
     */
    public record Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly) {
        public Dependency(DepKey key, String spec, Scope scope, int line) {
            this(key, spec, scope, line, Set.of(), false);
        }
    }

    public final Path file;
    public final String source;
    public final Jsonc.Node root;

    public final String name;
    public final String version;
    public final String group;
    public final String java;
    public final String target;
    /** Entry points: name to class. A single {@code main} string is stored under "main". */
    public final Map<String, String> mains;
    public final List<Dependency> dependencies;
    public final Map<String, Define> defines;
    /** Values this project provides for defines declared by its dependencies: dep key to (name to value). */
    public final Map<String, Map<String, Object>> libraryDefines;
    public final Map<String, String> scripts;
    public final Map<String, String> hooks;
    public final Map<String, String> patches;
    public final List<String> workspace;
    public final String visibility;
    public final List<String> repositories;
    public final String defineClass;
    public final List<String> javacArgs;
    public final List<String> nativeArgs;
    public final Layout layout;
    /** What `jpm build` produces: "jar" (default) or "bundle" (jar-in-jar with isolated modules). */
    public final String packaging;
    public final String description;
    public final String license;
    public final String url;
    /** Settings for extensions, by extension name (e.g. "publish"); jpm passes them through untouched. */
    public final Map<String, Object> extensions;

    private Manifest(Path file, String source) {
        this.file = file;
        this.source = source;
        try {
            this.root = Jsonc.parseNode(source);
        } catch (Jsonc.ParseException e) {
            throw new JpmException(file + ": " + e.getMessage());
        }
        if (!root.isObject()) throw err(root, "the manifest must be a JSON object");

        Set<String> known = Set.of("name", "version", "group", "java", "target", "main", "runtime", "dev", "test",
                "define", "scripts", "hooks", "patches", "workspace", "visibility", "repositories", "defineClass",
                "javacArgs", "nativeArgs", "layout", "package", "description", "license", "url", "extensions", "$schema");
        for (Jsonc.Member m : root.members.values()) {
            if (!known.contains(m.key)) {
                throw new JpmException(file + ":" + line(m.keyStart) + ": unknown field \"" + m.key + "\"");
            }
        }

        this.name = str("name", true);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw err(root.get("name"), "invalid name \"" + name + "\"");
        this.version = str("version", false);
        this.group = str("group", false);
        this.java = str("java", false);
        this.target = str("target", false);
        this.visibility = str("visibility", false) == null ? "public" : str("visibility", false);
        if (!visibility.equals("public") && !visibility.equals("internal")) {
            throw err(root.get("visibility"), "visibility must be \"public\" or \"internal\"");
        }
        this.defineClass = str("defineClass", false);

        Map<String, String> m = new LinkedHashMap<>();
        Jsonc.Node mainNode = root.get("main");
        if (mainNode != null) {
            if (mainNode.isString()) m.put("main", (String) mainNode.value);
            else if (mainNode.isObject()) {
                for (Jsonc.Member e : mainNode.members.values()) {
                    if (!e.value.isString()) throw err(e.value, "main." + e.key + " must be a class name");
                    m.put(e.key, (String) e.value.value);
                }
            } else throw err(mainNode, "main must be a class name or an object of names to classes");
        }
        this.mains = m;

        List<Dependency> deps = new ArrayList<>();
        for (Scope s : Scope.values()) {
            Jsonc.Node n = root.get(s.key);
            if (n == null) continue;
            if (!n.isObject()) throw err(n, s.key + " must be an object of dependency to version");
            for (Jsonc.Member e : n.members.values()) {
                DepKey key;
                try {
                    key = DepKey.parse(e.key);
                } catch (JpmException ex) {
                    throw new JpmException(file + ":" + line(e.keyStart) + ": " + ex.getMessage());
                }
                deps.add(dependency(key, e, s));
            }
        }
        Map<DepKey, Scope> seen = new TreeMap<>();
        for (Dependency d : deps) {
            Scope prev = seen.put(d.key, d.scope);
            if (prev != null) {
                throw new JpmException(file + ":" + d.line + ": " + d.key + " is declared in both \"" + prev.key + "\" and \"" + d.scope.key + "\"");
            }
        }
        this.dependencies = deps;

        Map<String, Define> defs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> libDefs = new LinkedHashMap<>();
        Jsonc.Node dn = root.get("define");
        if (dn != null) {
            if (!dn.isObject()) throw err(dn, "define must be an object");
            for (Jsonc.Member e : dn.members.values()) {
                if (e.key.contains(":")) {
                    if (!e.value.isObject()) throw err(e.value, "define values for " + e.key + " must be an object of name to value");
                    Map<String, Object> vals = new LinkedHashMap<>();
                    for (Jsonc.Member v : e.value.members.values()) vals.put(v.key, v.value.value);
                    libDefs.put(DepKey.parse(e.key).toString(), vals);
                    continue;
                }
                if (!e.key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw err(e.value, "define name \"" + e.key + "\" is not a valid Java identifier");
                defs.put(e.key, parseDefine(e));
            }
        }
        this.defines = defs;
        this.libraryDefines = libDefs;

        this.scripts = stringMap("scripts");
        this.hooks = stringMap("hooks");
        this.patches = stringMap("patches");
        for (String h : hooks.keySet()) {
            if (!h.matches("(pre|post)(build|test|install|start)(:[A-Za-z0-9_-]+)?")) {
                throw err(root.get("hooks").get(h), "unknown hook \"" + h + "\" (expected e.g. prebuild, postbuild:release, pretest)");
            }
        }
        this.workspace = stringList("workspace");
        this.repositories = stringList("repositories");
        this.javacArgs = stringList("javacArgs");
        this.nativeArgs = stringList("nativeArgs");
        this.packaging = str("package", false) == null ? "jar" : str("package", false);
        this.description = str("description", false);
        this.license = str("license", false);
        this.url = str("url", false);
        Jsonc.Node ext = root.get("extensions");
        if (ext != null && !ext.isObject()) throw err(ext, "extensions must be an object of extension name to its settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> extMap = ext == null ? Map.of() : (Map<String, Object>) ext.value;
        this.extensions = extMap;
        if (!packaging.equals("jar") && !packaging.equals("bundle")) throw err(root.get("package"), "package must be \"jar\" or \"bundle\"");
        this.layout = Layout.named(str("layout", false));
        if (layout == null) throw err(root.get("layout"), "layout must be \"jpm\" or \"maven\"");
    }

    private Dependency dependency(DepKey key, Jsonc.Member e, Scope s) {
        int ln = line(e.keyStart);
        Jsonc.Node v = e.value;
        if (v.isString()) return new Dependency(key, (String) v.value, s, ln);
        if (!v.isObject()) {
            throw err(v, "dependency " + e.key + " must be a version string or {\"version\": ..., \"exclude\": [...], \"runtimeOnly\": true}");
        }
        for (String k : v.members.keySet()) {
            if (!Set.of("version", "exclude", "runtimeOnly").contains(k)) throw err(v.get(k), "unknown dependency option \"" + k + "\"");
        }
        Jsonc.Node ver = v.get("version");
        if (ver == null || !ver.isString()) throw err(v, "dependency " + e.key + " needs a \"version\" string");
        Set<String> excl = new java.util.TreeSet<>();
        Jsonc.Node ex = v.get("exclude");
        if (ex != null) {
            if (!ex.isArray()) throw err(ex, "exclude must be an array of \"group:artifact\" (artifact may be *)");
            for (Jsonc.Node i : ex.items) {
                if (!i.isString() || !((String) i.value).matches("[^:\\s]+(:[^:\\s]+)?")) {
                    throw err(i, "exclude entries look like \"group:artifact\", \"group:*\" or \"group\"");
                }
                excl.add((String) i.value);
            }
        }
        Jsonc.Node ro = v.get("runtimeOnly");
        boolean runtimeOnly = ro != null && Boolean.TRUE.equals(ro.value);
        if (ro != null && !(ro.value instanceof Boolean)) throw err(ro, "runtimeOnly must be true or false");
        if (runtimeOnly && s != Scope.RUNTIME) throw err(ro, "runtimeOnly applies to \"runtime\" dependencies only");
        return new Dependency(key, (String) ver.value, s, ln, excl, runtimeOnly);
    }

    private Define parseDefine(Jsonc.Member e) {
        Jsonc.Node v = e.value;
        int ln = line(e.keyStart);
        if (!v.isObject()) {
            // Shorthand: "NAME": literal
            DefineType t = v.value instanceof Boolean ? DefineType.BOOLEAN
                    : v.value instanceof BigDecimal ? DefineType.INT : DefineType.STRING;
            if (v.value == null) throw err(v, "define " + e.key + " needs a value or {\"type\": ...}");
            return new Define(e.key, t, v.value, null, null, ln);
        }
        Jsonc.Node tn = v.get("type");
        if (tn == null || !tn.isString()) throw err(v, "define " + e.key + " needs a \"type\" (string, int or boolean)");
        DefineType type = switch ((String) tn.value) {
            case "string" -> DefineType.STRING;
            case "int" -> DefineType.INT;
            case "boolean" -> DefineType.BOOLEAN;
            default -> throw err(tn, "define type must be string, int or boolean");
        };
        for (String k : v.members.keySet()) {
            if (!Set.of("type", "value", "env", "default").contains(k)) throw err(v.get(k), "unknown define field \"" + k + "\"");
        }
        Jsonc.Node env = v.get("env");
        if (env != null && !env.isString()) throw err(env, "env must be a variable name");
        return new Define(e.key, type, v.get("value") == null ? null : v.get("value").value,
                env == null ? null : (String) env.value,
                v.get("default") == null ? null : v.get("default").value, ln);
    }

    public static Manifest load(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) {
            throw new JpmException("No " + FILE + " found in " + dir.toAbsolutePath()
                    + ". Run `jpm init` to create one. Importing Maven and Gradle projects is not supported yet.");
        }
        return new Manifest(f, Fs.read(f));
    }

    public static Manifest parse(Path file, String source) {
        return new Manifest(file, source);
    }

    public Path dir() {
        return file.getParent();
    }

    public boolean isWorkspaceRoot() {
        return !workspace.isEmpty();
    }

    public int line(int offset) {
        return Jsonc.lineOf(source, offset);
    }

    public JpmException err(Jsonc.Node n, String msg) {
        return new JpmException(file + ":" + (n == null ? 1 : line(n.start)) + ": " + msg);
    }

    private String str(String key, boolean required) {
        Jsonc.Node n = root.get(key);
        if (n == null) {
            if (required) throw new JpmException(file + ": missing required field \"" + key + "\"");
            return null;
        }
        if (n.value instanceof BigDecimal bd && (key.equals("java") || key.equals("target"))) return bd.toPlainString();
        if (!n.isString()) throw err(n, key + " must be a string");
        return (String) n.value;
    }

    private Map<String, String> stringMap(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isObject()) throw err(n, key + " must be an object");
        for (Jsonc.Member e : n.members.values()) {
            if (!e.value.isString()) throw err(e.value, key + "." + e.key + " must be a string");
            out.put(e.key, (String) e.value.value);
        }
        return out;
    }

    private List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isArray()) throw err(n, key + " must be an array of strings");
        for (Jsonc.Node i : n.items) {
            if (!i.isString()) throw err(i, key + " must be an array of strings");
            out.add((String) i.value);
        }
        return out;
    }

    /** Major Java version requested ("graalvm-21" gives 21), or null for "any". */
    public Integer javaMajor() {
        if (java == null) return null;
        String digits = java.replaceAll(".*?(\\d+).*", "$1");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new JpmException(file + ": invalid java version \"" + java + "\"");
        }
    }

    /** Bytecode target, defaults to the java version. */
    public Integer targetMajor() {
        if (target != null) {
            try {
                return Integer.parseInt(target.replaceFirst("^1\\.", ""));
            } catch (NumberFormatException e) {
                throw new JpmException(file + ": invalid target \"" + target + "\"");
            }
        }
        return javaMajor();
    }

    public List<Dependency> deps(Scope s) {
        return dependencies.stream().filter(d -> d.scope == s).toList();
    }
}
