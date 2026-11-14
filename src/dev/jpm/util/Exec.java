package dev.jpm.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs external processes. */
public final class Exec {
    private Exec() {}

    public record Result(int exit, String out, String err) {
        public boolean ok() { return exit == 0; }
    }

    /** Runs and captures output. */
    public static Result capture(Path dir, List<String> cmd) {
        return capture(dir, cmd, Map.of());
    }

    public static Result capture(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd) + (dir != null ? "  (in " + dir + ")" : ""));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t = new Thread(() -> copy(p.getErrorStream(), err));
            t.start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            t.join();
            return new Result(code, new String(out, StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }

    /** Runs with inherited stdio and returns the exit code. */
    public static int inherit(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            return p.waitFor();
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /** Starts a process with inherited stdio without waiting. */
    public static Process start(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        }
    }

    public static Result checked(Path dir, List<String> cmd) {
        Result r = capture(dir, cmd);
        if (!r.ok()) {
            throw new JpmException(String.join(" ", cmd) + " failed (exit " + r.exit + "):\n" + r.err.strip());
        }
        return r;
    }

    public static List<String> cmd(String... parts) {
        return new ArrayList<>(List.of(parts));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
        }
    }

    /** Finds an executable on PATH. */
    public static Path which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String ext : win ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""}) {
                Path p = Path.of(dir, name + ext);
                if (java.nio.file.Files.isExecutable(p) && !java.nio.file.Files.isDirectory(p)) return p;
            }
        }
        return null;
    }
}
