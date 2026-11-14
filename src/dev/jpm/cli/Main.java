package dev.jpm.cli;

import dev.jpm.util.JpmException;
import dev.jpm.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** {@code jpm} — the Java package manager and build tool. */
public final class Main {
    public static final String VERSION = "0.1.0";

    private Main() {}

    static final String HELP = """
            jpm %s - one command for Java: dependencies, builds, running, tests and patches.

            Usage: jpm <command> [options]

            Project
              init [name]                 Create a project in the current directory
              add <dep>[@version]         Add dependencies (--dev, --test; -g installs an extension)
              remove <dep>                Remove dependencies (-g removes an extension)
              install                     Resolve dependencies and write project.lock (--frozen in CI)
              update [dep...]             Re-resolve within ranges (--latest goes past them)
              outdated                    Show dependencies with newer versions
              why <dep>                   Show why a dependency is in the graph

            Build and run
              build                       Compile and package into dist/ (--release, --profile <name>)
              start [entry] [-- args]     Build, then run main with production env variables
              dev [entry] [-- args]       Run main with development env variables, restarting on changes
              test [filter...]            Run tests on the JUnit Platform
              run <script> [args]         Run a script from the manifest
              clean                       Delete build output

            Dependencies
              patch <dep>                 Unpack a dependency's source for editing
              patch --commit <dep>        Save your edits to patches/ and record them in the manifest

            Toolchain
              java list [--available]     List installed JDKs, or what can be installed
              java install <java>         Install a JDK or GraalVM (21, temurin-17, graalvm-21, ...)
              java remove <java>          Remove a JDK jpm installed
              java use <java>             Set "java" in the manifest (and install it)
              jvm exec <java> -- <cmd>    Run a command with a JDK, changing nothing persistent
              build --native              Also build a native executable with GraalVM
              start --trace               Run under the native-image tracing agent to record its config

            Any other command runs an extension: `jpm format` runs `jpm-format`.
            Global options: -v/--verbose, --offline
            """;

    public static void main(String[] argv) {
        int code;
        try {
            code = run(new ArrayList<>(Arrays.asList(argv)));
        } catch (JpmException e) {
            Log.error(e.getMessage());
            if (Log.verbose && e.getCause() != null) e.getCause().printStackTrace();
            code = e.exitCode();
        } catch (RuntimeException e) {
            Log.error("unexpected failure: " + e);
            if (Log.verbose) e.printStackTrace();
            else Log.info("Run with -v for details.");
            code = 70;
        }
        System.out.flush();
        System.exit(code);
    }

    /** True when started as {@code jvm} (a link or copy of the binary, or the jvm launcher script). */
    static boolean invokedAsJvm() {
        if ("jvm".equals(System.getenv("JPM_AS"))) return true;
        try {
            String cmd = ProcessHandle.current().info().command().orElse("");
            String name = java.nio.file.Path.of(cmd).getFileName().toString().toLowerCase();
            return name.equals("jvm") || name.equals("jvm.exe");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static int run(List<String> args) {
        if (invokedAsJvm()) {
            if (args.isEmpty() || args.get(0).equals("-h") || args.get(0).equals("--help")) {
                System.out.print(JavaCommands.JVM_HELP);
                return 0;
            }
            if (args.get(0).equals("--version")) {
                System.out.println("jvm (jpm " + VERSION + ")");
                return 0;
            }
            args.removeIf(a -> {
                if (a.equals("-v") || a.equals("--verbose")) { Log.verbose = true; return true; }
                return false;
            });
            return new Commands().java(args, true);
        }
        // global flags, before the command
        while (!args.isEmpty() && args.get(0).startsWith("-")) {
            String a = args.get(0);
            if (a.equals("-v") || a.equals("--verbose")) { Log.verbose = true; args.remove(0); }
            else if (a.equals("--offline")) { System.setProperty("jpm.offline", "true"); args.remove(0); }
            else if (a.equals("-h") || a.equals("--help")) { System.out.print(HELP.formatted(VERSION)); return 0; }
            else if (a.equals("--version")) { System.out.println("jpm " + VERSION); return 0; }
            else break;
        }
        args.removeIf(a -> {
            if (a.equals("-v") || a.equals("--verbose")) { Log.verbose = true; return true; }
            return false;
        });
        if (args.isEmpty()) {
            System.out.print(HELP.formatted(VERSION));
            return 0;
        }
        String cmd = args.remove(0);
        Commands c = new Commands();
        return switch (cmd) {
            case "help" -> { System.out.print(HELP.formatted(VERSION)); yield 0; }
            case "version" -> { System.out.println("jpm " + VERSION); yield 0; }
            case "init" -> c.init(args);
            case "add" -> c.add(args);
            case "remove", "rm" -> c.remove(args);
            case "install", "i" -> c.install(args);
            case "update" -> c.update(args);
            case "outdated" -> c.outdated(args);
            case "why" -> c.why(args);
            case "build" -> c.build(args);
            case "start" -> c.start(args, false);
            case "dev" -> c.start(args, true);
            case "test" -> c.test(args);
            case "run" -> c.runScript(args);
            case "clean" -> c.clean(args);
            case "patch" -> c.patch(args);
            case "java" -> c.java(args, false);
            case "jvm" -> c.java(args, true);
            default -> c.extension(cmd, args);
        };
    }
}
