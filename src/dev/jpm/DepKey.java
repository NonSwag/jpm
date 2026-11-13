package dev.jpm;

import dev.jpm.util.JpmException;

import java.util.Locale;

/**
 * A dependency name as written in the manifest.
 *
 * <ul>
 *   <li>{@code group:artifact} — Maven</li>
 *   <li>{@code github:user/repo} or {@code github:user/repo/module} — Git (also {@code gitlab:}, {@code codeberg:})</li>
 *   <li>{@code workspace:module} — a sibling module in the same workspace</li>
 * </ul>
 */
public record DepKey(Kind kind, String host, String group, String artifact, String module) implements Comparable<DepKey> {

    public enum Kind { MAVEN, GIT, WORKSPACE }

    public static DepKey parse(String s) {
        s = s.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) throw new JpmException("Invalid dependency \"" + s + "\": expected group:artifact or github:user/repo");
        String prefix = s.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = s.substring(colon + 1);
        switch (prefix) {
            case "github", "gitlab", "codeberg" -> {
                String[] parts = rest.split("/");
                if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    throw new JpmException("Invalid Git dependency \"" + s + "\": expected " + prefix + ":user/repo or " + prefix + ":user/repo/module");
                }
                String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
                return new DepKey(Kind.GIT, prefix, parts[0], repo, parts.length == 3 ? parts[2] : null);
            }
            case "workspace" -> {
                if (rest.isEmpty() || rest.contains(":")) throw new JpmException("Invalid workspace dependency \"" + s + "\"");
                return new DepKey(Kind.WORKSPACE, null, null, rest, null);
            }
            default -> {
                String[] parts = s.split(":");
                if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    throw new JpmException("Invalid Maven dependency \"" + s + "\": expected group:artifact (put the version on the right-hand side)");
                }
                return new DepKey(Kind.MAVEN, null, parts[0], parts[1], null);
            }
        }
    }

    public static DepKey maven(String group, String artifact) {
        return new DepKey(Kind.MAVEN, null, group, artifact, null);
    }

    public boolean isGit() { return kind == Kind.GIT; }
    public boolean isMaven() { return kind == Kind.MAVEN; }
    public boolean isWorkspace() { return kind == Kind.WORKSPACE; }

    /** The repository part of a Git key without the module, e.g. {@code github:user/repo}. */
    public DepKey repoKey() {
        return new DepKey(kind, host, group, artifact, null);
    }

    public DepKey withModule(String m) {
        return new DepKey(kind, host, group, artifact, m);
    }

    public String gitUrl() {
        String base = switch (host) {
            case "github" -> "https://github.com/";
            case "gitlab" -> "https://gitlab.com/";
            case "codeberg" -> "https://codeberg.org/";
            default -> throw new IllegalStateException(host);
        };
        // Allow tests and mirrors to redirect hosts: JPM_GIT_BASE_GITHUB=file:///path/
        String override = System.getenv("JPM_GIT_BASE_" + host.toUpperCase(Locale.ROOT));
        if (override != null && !override.isBlank()) base = override.endsWith("/") ? override : override + "/";
        return base + group + "/" + artifact + (base.startsWith("http") ? ".git" : "");
    }

    /** Maven-style coordinates under which a Git dependency is cached and resolved. */
    public String mavenGroup() {
        return switch (kind) {
            case MAVEN -> group;
            case GIT -> "git." + host + "." + group.toLowerCase(Locale.ROOT) + "." + artifact.toLowerCase(Locale.ROOT);
            case WORKSPACE -> "workspace";
        };
    }

    public String mavenArtifact() {
        return switch (kind) {
            case MAVEN -> artifact;
            case GIT -> module != null ? module : artifact;
            case WORKSPACE -> artifact;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case MAVEN -> group + ":" + artifact;
            case GIT -> host + ":" + group + "/" + artifact + (module != null ? "/" + module : "");
            case WORKSPACE -> "workspace:" + artifact;
        };
    }

    @Override
    public int compareTo(DepKey o) {
        return toString().compareTo(o.toString());
    }
}
