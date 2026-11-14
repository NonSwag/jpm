package dev.jpm.cli;

import dev.jpm.Project;
import dev.jpm.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.toolchain.Foojay;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK and GraalVM management, shared by {@code jpm java ...} and the separate {@code jvm} command.
 * {@code jvm} never changes anything persistent outside {@code ~/.jpm/jdks}: no PATH, JAVA_HOME or
 * shell profile edits.
 */
final class JavaCommands {
    private final Path cwd;
    private final String self;

    JavaCommands(Path cwd, boolean asJvm) {
        this.cwd = cwd;
        this.self = asJvm ? "jvm" : "jpm java";
    }

    static final String JVM_HELP = """
            jvm — install JDKs and GraalVM and run commands with them, without changing your setup.

            Usage: jvm <command>
              exec <java> [--] <command...>   Run a command with that JDK as JAVA_HOME and first on PATH
              env <java>                      Print shell exports for that JDK (eval "$(jvm env 21)")
              install <java...>               Install JDKs into ~/.jpm/jdks
              remove <java...>                Remove JDKs that jpm installed
              list [--available [vendor]]     List installed JDKs, or what can be installed
              home [java]                     Print the home directory of a JDK

            <java> is a version or vendor-version: 21, 21.0.5, temurin-17, zulu-21, graalvm-21, oracle-graalvm-21.
            Missing JDKs are installed automatically (set JPM_NO_INSTALL to turn that off).
            """;

    int run(List<String> args) {
        String sub = args.isEmpty() ? "list" : args.remove(0);
        return switch (sub) {
            case "list", "ls" -> list(args);
            case "install", "add" -> install(args);
            case "remove", "rm", "uninstall" -> remove(args);
            case "exec", "run" -> exec(args);
            case "env" -> env(args);
            case "home", "which" -> home(args);
            case "use" -> use(args);
            case "help", "-h", "--help" -> {
                System.out.print(JVM_HELP);
                yield 0;
            }
            default -> throw new JpmException("Unknown command `" + self + " " + sub + "`. Run `" + self + " help`.");
        };
    }

    private int list(List<String> args) {
        if (args.remove("--available")) {
            String dist = args.isEmpty() ? JavaSpec.DEFAULT_DISTRIBUTION : JavaSpec.parse(args.get(0)).installDistribution();
            List<String> versions = Foojay.available(dist);
            System.out.println(Log.bold(dist + " for " + Foojay.os() + "/" + Foojay.arch()));
            for (String v : versions) System.out.println("  " + v);
            return 0;
        }
        List<Jdk> all = Jdk.discover();
        if (all.isEmpty()) {
            Log.info("No JDKs found. Install one with `" + self + " install 21`.");
            return 0;
        }
        Jdk current = null;
        try {
            Project p = Project.find(cwd);
            current = Toolchain.find(JavaSpec.parse(p.modules.get(0).manifest.java));
        } catch (JpmException ignored) {
        }
        for (Jdk j : all) {
            String mark = j.equals(current) ? "*" : " ";
            String tags = (j.graal() ? " native-image" : "") + (j.managed() ? " (jpm)" : "");
            System.out.println(mark + " " + String.format("%-34s %s", j.id() + tags, j.home()));
        }
        if (current != null) Log.info(Log.dim("* used by this project"));
        return 0;
    }

    private int install(List<String> args) {
        if (args.isEmpty()) throw new JpmException("Usage: " + self + " install <java>, e.g. 21, temurin-17 or graalvm-21");
        for (String a : args) {
            JavaSpec spec = JavaSpec.parse(a);
            if (spec.version() == null) throw new JpmException("Say which version to install, e.g. " + spec.installDistribution() + "-21");
            Jdk have = Toolchain.find(spec);
            if (have != null && !spec.exact()) {
                Log.step("Installed", spec + " is already available: " + have.id() + " (" + have.home() + ")");
                continue;
            }
            Toolchain.install(spec);
        }
        return 0;
    }

    private int remove(List<String> args) {
        if (args.isEmpty()) throw new JpmException("Usage: " + self + " remove <java>");
        for (String a : args) {
            List<Jdk> removed = Toolchain.remove(JavaSpec.parse(a));
            if (removed.isEmpty()) {
                throw new JpmException("No JDK installed by jpm matches " + a + ". jpm only removes JDKs in " + Jdk.managedRoot());
            }
            for (Jdk j : removed) Log.step("Removed", j.id());
        }
        return 0;
    }

    private int home(List<String> args) {
        Jdk j;
        if (args.isEmpty()) {
            JavaSpec spec = JavaSpec.any();
            try {
                spec = JavaSpec.parse(Project.find(cwd).modules.get(0).manifest.java);
            } catch (JpmException ignored) {
            }
            j = Toolchain.require(spec);
        } else {
            j = Toolchain.require(JavaSpec.parse(args.get(0)));
        }
        System.out.println(j.home());
        return 0;
    }

    /** Environment that puts a JDK first: JAVA_HOME and PATH. */
    static Map<String, String> envFor(Jdk j) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", j.home().toString());
        String path = System.getenv("PATH");
        String pathKey = "PATH";
        for (String k : System.getenv().keySet()) if (k.equalsIgnoreCase("PATH")) pathKey = k;
        env.put(pathKey, j.home().resolve("bin") + (path == null || path.isEmpty() ? "" : File.pathSeparator + path));
        if (j.graal()) env.put("GRAALVM_HOME", j.home().toString());
        return env;
    }

    private int exec(List<String> args) {
        if (args.isEmpty()) throw new JpmException("Usage: " + self + " exec <java> [--] <command...>, e.g. jvm exec 21 -- java -jar app.jar");
        JavaSpec spec = JavaSpec.parse(args.remove(0));
        if (!args.isEmpty() && args.get(0).equals("--")) args.remove(0);
        if (args.isEmpty()) throw new JpmException("No command given. Example: " + self + " exec 21 -- java -version");
        Jdk j = Toolchain.require(spec);
        List<String> cmd = new ArrayList<>(args);
        // Resolve JDK tools from the chosen JDK even if the OS would search PATH before our environment applies.
        Path tool = j.bin(cmd.get(0));
        if (!cmd.get(0).contains("/") && !cmd.get(0).contains("\\") && Files.exists(tool)) cmd.set(0, tool.toString());
        return Exec.inherit(cwd, cmd, envFor(j));
    }

    private int env(List<String> args) {
        if (args.size() != 1) throw new JpmException("Usage: " + self + " env <java>");
        Jdk j = Toolchain.require(JavaSpec.parse(args.get(0)));
        boolean win = System.getProperty("os.name").toLowerCase().contains("win") && System.getenv("SHELL") == null;
        for (Map.Entry<String, String> e : envFor(j).entrySet()) {
            if (win) System.out.println("set \"" + e.getKey() + "=" + e.getValue() + "\"");
            else System.out.println("export " + e.getKey() + "=" + shellQuote(e.getValue()));
        }
        return 0;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** {@code jpm java use 21}: records the JDK in the manifest (and installs it). */
    private int use(List<String> args) {
        if (self.equals("jvm")) throw new JpmException("jvm never changes your projects; use `jpm java use` inside a project");
        if (args.size() != 1) throw new JpmException("Usage: jpm java use <java>");
        JavaSpec spec = JavaSpec.parse(args.get(0));
        if (spec.version() == null) throw new JpmException("Say which version, e.g. jpm java use 21");
        Project p = Project.find(cwd);
        Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
        Jdk j = Toolchain.require(spec);
        String value = args.get(0).trim();
        String src = Fs.read(m.manifest.file);
        Fs.write(m.manifest.file, JsoncEdit.set(src, List.of(), "java", Jsonc.quote(value)));
        Log.step("Using", value + " (" + j.id() + ") in " + p.dir.relativize(m.manifest.file).toString().replace('\\', '/'));
        return 0;
    }
}
