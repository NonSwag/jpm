package dev.jpm.build;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom commands from the manifest's {@code scripts}, run with {@code jpm run <name>}, and hooks around
 * built-in steps.
 *
 * <p>A {@code .java} script runs directly on the source launcher (JBang-style) with the project's runtime
 * classpath and build information in {@code JPM_*} environment variables. A {@code .sh} script runs with
 * {@code sh}; anything else is a shell command line. Shell scripts are not guaranteed to work on every
 * platform.
 */
public final class Scripts {
    private Scripts() {}

    public static int run(Builder b, Project.Module m, String name, List<String> args, Env.Mode mode) {
        String value = m.manifest.scripts.get(name);
        if (value == null) {
            throw new JpmException("No script named \"" + name + "\" in " + m.manifest.file
                    + (m.manifest.scripts.isEmpty() ? "" : ". Scripts: " + String.join(", ", m.manifest.scripts.keySet())));
        }
        return exec(b, m, name, value, args, mode, new LinkedHashSet<>());
    }

    private static int exec(Builder b, Project.Module m, String name, String value, List<String> args, Env.Mode mode, Set<String> stack) {
        if (!stack.add(name)) throw new JpmException("Script \"" + name + "\" calls itself");
        // A script may name another script (useful for hooks).
        String alias = m.manifest.scripts.get(value);
        if (alias != null && !value.equals(name)) return exec(b, m, value, alias, args, mode, stack);

        Map<String, String> env = new LinkedHashMap<>(Env.forChild(b.project.dir, mode));
        env.putAll(info(b, m));
        List<String> cmd = new ArrayList<>();
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path file = m.dir.resolve(value);
        if (value.endsWith(".java") && Files.exists(file)) {
            Jdk jdk = b.jdk(m.manifest);
            if (jdk.major() < 11) throw new JpmException("Java scripts need JDK 11 or newer");
            cmd.add(jdk.java());
            String cp = env.get("JPM_CLASSPATH");
            if (cp != null && !cp.isEmpty()) {
                cmd.add("-cp");
                cmd.add(cp);
            }
            cmd.add(file.toString());
        } else if (value.endsWith(".sh") && Files.exists(file)) {
            cmd.add("sh");
            cmd.add(file.toString());
        } else if (win) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(value);
        } else {
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(value + " \"$@\"");
            cmd.add(name);
        }
        cmd.addAll(args);
        Log.debug("script " + name + ": " + String.join(" ", cmd));
        return Exec.inherit(m.dir, cmd, env);
    }

    /** Build information passed to scripts and extensions. */
    public static Map<String, String> info(Builder b, Project.Module m) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JPM_PROJECT_DIR", b.project.dir.toString());
        env.put("JPM_MODULE_DIR", m.dir.toString());
        env.put("JPM_NAME", m.name());
        env.put("JPM_VERSION", m.version());
        env.put("JPM_MANIFEST", m.manifest.file.toString());
        Path classes = Builder.classesDir(m);
        env.put("JPM_CLASSES_DIR", classes.toString());
        Path jar = b.jarPath(m);
        if (Files.exists(jar)) env.put("JPM_JAR", jar.toString());
        try {
            List<Path> cp = new ArrayList<>();
            if (Files.isDirectory(classes)) cp.add(classes);
            for (Path p : b.classpathNoBuild(m)) cp.add(p);
            env.put("JPM_CLASSPATH", Compiler.join(Compiler.distinct(cp)));
        } catch (JpmException e) {
            Log.debug("no classpath for scripts: " + e.getMessage());
        }
        try {
            Jdk jdk = b.jdk(m.manifest);
            env.put("JPM_JAVA_HOME", jdk.home().toString());
        } catch (JpmException ignored) {
        }
        return env;
    }

    /** Runs the hooks for a step: {@code pre<step>} / {@code post<step>} and their {@code :<profile>} variants. */
    public static void hooks(Builder b, Project.Module m, Manifest manifest, String phase, String step, String profile, Env.Mode mode) {
        List<String> names = new ArrayList<>();
        names.add(phase + step);
        if (profile != null) names.add(phase + step + ":" + profile);
        for (String h : names) {
            String target = manifest.hooks.get(h);
            if (target == null) continue;
            Log.step("Hook", h + " -> " + target);
            int code;
            if (manifest.scripts.containsKey(target)) code = exec(b, m, target, manifest.scripts.get(target), List.of(), mode, new LinkedHashSet<>());
            else code = exec(b, m, h, target, List.of(), mode, new LinkedHashSet<>());
            if (code != 0) throw new JpmException("Hook " + h + " failed with exit code " + code, code);
        }
    }
}
