package dev.jpm.cli;

import dev.jpm.toolchain.Foojay;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * {@code jpm upgrade}: replaces the jpm binary with the latest release (or a given version), checked against
 * the release's SHA256SUMS like the install script does, then refreshes the java shims.
 *
 * <p>Releases come from {@code https://github.com/NonSwag/jpm/releases}; {@code JPM_REPO} changes the
 * repository and {@code JPM_INSTALL_BASE} replaces the download location (a mirror, or file:// for tests).
 */
final class Upgrade {
    private Upgrade() {}

    static String repo() {
        String r = System.getenv("JPM_REPO");
        return r == null || r.isBlank() ? "NonSwag/jpm" : r;
    }

    /** Where release files live: the latest release, or the release tagged with the version. */
    static String base(String version) {
        String b = System.getenv("JPM_INSTALL_BASE");
        if (b != null && !b.isBlank()) return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return version == null
                ? "https://github.com/" + repo() + "/releases/latest/download"
                : "https://github.com/" + repo() + "/releases/download/" + version;
    }

    /** Release asset for this platform, e.g. jpm-linux-x64 or jpm-windows-x64.exe. */
    static String asset() {
        String os = Foojay.os();
        if (os.equals("alpine_linux")) os = "linux";
        String arch = Foojay.arch().equals("aarch64") ? "arm64" : Foojay.arch();
        return "jpm-" + os + "-" + arch + (os.equals("windows") ? ".exe" : "");
    }

    /** The checksum for an asset from a SHA256SUMS file, or null. */
    static String checksum(String sums, String asset) {
        for (String line : sums.split("\r?\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2 && (p[1].equals(asset) || p[1].equals("*" + asset))) return p[0].toLowerCase();
        }
        return null;
    }

    private static String fetch(String url) {
        if (url.startsWith("file:")) {
            Path p = Path.of(URI.create(url));
            if (!Files.isRegularFile(p)) throw new JpmException("Not found: " + url);
            return Fs.read(p);
        }
        return Http.getString(url);
    }

    private static String download(String url, Path target) {
        if (url.startsWith("file:")) {
            try {
                Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
                return dev.jpm.util.Hash.sha256(target);
            } catch (IOException e) {
                throw new JpmException("Could not read " + url + ": " + e.getMessage());
            }
        }
        return Http.download(url, target, "jpm");
    }

    static int run(List<String> args) {
        boolean check = args.remove("--check");
        boolean force = args.remove("--force");
        if (args.size() > 1) throw new JpmException("Usage: jpm upgrade [version] [--check] [--force]");
        String wanted = args.isEmpty() ? null : args.get(0).replaceFirst("^v", "");
        String current = Main.VERSION;

        String base = base(wanted);
        String latest;
        if (wanted != null) {
            latest = wanted;
        } else {
            try {
                latest = fetch(base + "/VERSION").strip();
            } catch (JpmException e) {
                throw new JpmException("Could not find the latest jpm release (" + e.getMessage() + ")");
            }
        }
        int cmp = Version.of(latest).compareTo(Version.of(current));
        if (check) {
            if (cmp > 0) Log.info("jpm " + latest + " is available (you have " + current + "). Run `jpm upgrade`.");
            else Log.info("jpm " + current + " is up to date" + (cmp < 0 ? " (newer than the latest release, " + latest + ")" : "") + ".");
            return 0;
        }
        if (cmp == 0 && !force) {
            Log.info("jpm " + current + " is already the " + (wanted == null ? "latest version" : "installed version") + ".");
            return 0;
        }
        if (cmp < 0 && wanted == null && !force) {
            Log.info("jpm " + current + " is newer than the latest release (" + latest + "); nothing to do.");
            return 0;
        }

        Path self = Shims.self();
        if (self == null) {
            throw new JpmException("`jpm upgrade` replaces the native jpm binary, but this is the jar build. Update the source"
                    + " (git pull) and run ./bootstrap.sh, or install the binary with the install script.");
        }
        try {
            self = self.toRealPath();
        } catch (IOException ignored) {
        }

        String asset = asset();
        Log.step("Upgrading", "jpm " + current + " -> " + latest + Log.dim(" (" + asset + ")"));
        String expected = checksum(fetch(base + "/SHA256SUMS"), asset);
        if (expected == null) throw new JpmException("The " + latest + " release has no " + asset + " for this platform");
        Path tmp = self.resolveSibling(self.getFileName() + ".new");
        String actual = download(base + "/" + asset, tmp);
        if (!expected.equals(actual)) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new JpmException("Checksum mismatch for " + asset + " (expected " + expected + ", got " + actual + "); nothing was changed");
        }
        tmp.toFile().setExecutable(true);
        replace(self, tmp);

        // Refresh the shims with the new binary (hard links on Windows still point at the old file).
        Exec.Result r = Exec.capture(null, List.of(self.toString(), "jvm", "setup"));
        if (!r.ok()) Log.warn("could not refresh the java shims: " + (r.err() + r.out()).strip());
        Exec.Result v = Exec.capture(null, List.of(self.toString(), "--version"));
        Log.step("Upgraded", (v.ok() ? v.out().strip() : "jpm " + latest) + Log.dim(" (" + self + ")"));
        return 0;
    }

    /** Puts the new binary in place of the running one. */
    private static void replace(Path self, Path fresh) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            if (win) {
                // A running .exe cannot be overwritten, but it can be renamed out of the way.
                Path old = self.resolveSibling(self.getFileName() + ".old");
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                    // still in use by an earlier upgrade; the next upgrade cleans it up
                }
                Files.move(self, old, StandardCopyOption.REPLACE_EXISTING);
                Files.move(fresh, self);
            } else {
                Files.move(fresh, self, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new JpmException("Could not replace " + self + ": " + e.getMessage()
                    + (Files.isWritable(self.getParent()) ? "" : " (no write access to " + self.getParent() + ")"));
        }
    }
}
