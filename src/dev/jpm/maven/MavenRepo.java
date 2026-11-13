package dev.jpm.maven;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.Version;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads from Maven repositories into the shared cache {@code ~/.jpm/repository}. */
public final class MavenRepo {
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Duration METADATA_TTL = Duration.ofHours(24);

    private final List<String> repos;
    private final Path cache;
    private final boolean offline;
    private boolean refreshMetadata;
    private final HttpClient http;
    private final ConcurrentHashMap<String, List<Version>> versionsMemo = new ConcurrentHashMap<>();

    public MavenRepo(List<String> extra) {
        List<String> r = new ArrayList<>();
        String central = System.getenv("JPM_MAVEN_CENTRAL");
        r.add(central != null && !central.isBlank() ? slash(central) : CENTRAL);
        for (String e : extra) r.add(slash(e));
        this.repos = r;
        this.cache = Fs.home().resolve("repository");
        this.offline = Fs.offline();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static String slash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }

    public void setRefreshMetadata(boolean b) {
        this.refreshMetadata = b;
    }

    public Path cacheDir() {
        return cache;
    }

    public static String path(String group, String artifact, String version, String classifier, String ext) {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
    }

    public Path local(String group, String artifact, String version, String classifier, String ext) {
        return cache.resolve(path(group, artifact, version, classifier, ext));
    }

    /** Fetches a file into the cache, returning null if no repository has it. */
    public Path fetch(String group, String artifact, String version, String classifier, String ext) {
        Path local = local(group, artifact, version, classifier, ext);
        if (Files.exists(local)) return local;
        Path missing = local.resolveSibling(local.getFileName() + ".missing");
        if (Files.exists(missing) && !refreshMetadata) return null;
        String rel = path(group, artifact, version, classifier, ext);
        for (String repo : repos) {
            if (download(repo + rel, local)) return local;
        }
        if (!offline) Fs.write(missing, "");
        return null;
    }

    public Path require(String group, String artifact, String version, String classifier, String ext) {
        Path p = fetch(group, artifact, version, classifier, ext);
        if (p == null) {
            throw new JpmException("Could not find " + group + ":" + artifact + ":" + version
                    + (classifier != null ? ":" + classifier : "") + " (" + ext + ") in " + String.join(", ", repos));
        }
        return p;
    }

    /** All published versions of a module, from maven-metadata.xml. */
    public List<Version> versions(String group, String artifact) {
        return versionsMemo.computeIfAbsent(group + ":" + artifact, k -> loadVersions(group, artifact));
    }

    private List<Version> loadVersions(String group, String artifact) {
        List<Version> out = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            String repo = repos.get(i);
            Path local = cache.resolve(group.replace('.', '/') + "/" + artifact + "/maven-metadata-" + i + ".xml");
            boolean fresh = false;
            try {
                fresh = Files.exists(local) && !refreshMetadata
                        && Files.getLastModifiedTime(local).toInstant().plus(METADATA_TTL).isAfter(java.time.Instant.now());
            } catch (IOException ignored) {
            }
            if (!fresh && !offline) {
                download(repo + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml", local);
            }
            if (!Files.exists(local)) continue;
            Matcher m = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>").matcher(Fs.read(local));
            while (m.find()) {
                Version v = Version.of(m.group(1));
                if (!out.contains(v)) out.add(v);
            }
        }
        if (out.isEmpty()) {
            throw new JpmException("Could not find " + group + ":" + artifact + " in " + String.join(", ", repos)
                    + (offline ? " (offline)" : ""));
        }
        out.sort(null);
        return out;
    }

    private boolean download(String url, Path target) {
        if (offline) return false;
        Log.debug("GET " + url);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", "jpm")
                        .GET().build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() == 404 || resp.statusCode() == 403 || resp.statusCode() == 401) {
                    resp.body().close();
                    return false;
                }
                if (resp.statusCode() != 200) {
                    resp.body().close();
                    if (resp.statusCode() >= 500) continue;
                    return false;
                }
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
                try (InputStream in = resp.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (IOException e) {
                Log.debug("download failed: " + e);
                if (attempt == 2) throw new JpmException("Could not download " + url + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JpmException("Interrupted");
            }
        }
        return false;
    }
}
