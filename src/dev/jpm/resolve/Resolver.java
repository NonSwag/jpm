package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.git.GitRepo;
import dev.jpm.maven.MavenRepo;
import dev.jpm.maven.Pom;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.MavenRange;
import dev.jpm.version.NpmRange;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Resolves the dependencies of a project into a {@link Graph}.
 *
 * <p>Git and Maven packages live in one graph and conflicts are settled the same way for both: every
 * hard constraint (a version declared in a manifest, or a range anywhere) must hold, and among the
 * versions that satisfy them the highest one wins. Plain versions in POMs are soft requirements.
 * Locked versions are kept when they still satisfy the constraints, so re-resolving after a manifest
 * edit does not move unrelated packages.
 */
public final class Resolver {
    /** JUnit added to tests when no test framework is declared. */
    public static final String JUNIT_JUPITER = "org.junit.jupiter:junit-jupiter";
    public static final String JUNIT_VERSION = "5.11.4";

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,40}");

    private record Request(String spec, boolean hard, boolean direct, String from) {}

    private record Selected(String version, String commit, String tag) {}

    private record EdgeReq(String target, Graph.Kind kind, String spec, boolean runtimeOnly, Set<String> exclusions) {}

    private record Expanded(List<EdgeReq> edges, boolean hasJar, Boolean isPublic) {}

    /** A top-level requirement: a dependency declared by {@code from}. */
    public record Root(String from, DepKey key, String spec) {}

    private final Project project;
    private final List<Root> roots;
    private final MavenRepo repo;
    private final Pom.Loader poms;
    private final Graph previous;
    private final Set<String> unlocked;
    private final boolean unlockAll;
    private final Map<String, Graph.Kind> kinds = new HashMap<>();
    private final Map<String, Expanded> expandMemo = new HashMap<>();
    private final Map<String, Map<String, String>> tagMemo = new HashMap<>();

    public Resolver(Project project, MavenRepo repo, Graph previous, Set<String> unlocked, boolean unlockAll) {
        this.project = project;
        this.roots = new ArrayList<>();
        for (Project.Module m : project.modules) {
            for (Manifest.Dependency d : effectiveDeps(m)) {
                if (d.key().isWorkspace()) continue;
                validateSpec(d);
                roots.add(new Root(m.id(), d.key(), d.spec()));
            }
        }
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.unlocked = unlocked;
        this.unlockAll = unlockAll;
    }

    /** Resolves explicit roots without a project (tools, a dependency's own build dependencies). */
    public Resolver(List<Root> roots, MavenRepo repo, Graph previous) {
        this.project = null;
        this.roots = roots;
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.unlocked = Set.of();
        this.unlockAll = false;
    }

    // ------------------------------------------------------------------ inputs

    /** Effective dependencies of a module, including implicit ones (JUnit for tests). */
    public static List<Manifest.Dependency> effectiveDeps(Project.Module m) {
        List<Manifest.Dependency> deps = new ArrayList<>(m.manifest.dependencies);
        boolean hasTests = Files.isDirectory(m.testDir());
        boolean hasFramework = deps.stream().anyMatch(d -> d.scope() == Manifest.Scope.TEST && d.key().isMaven()
                && (d.key().group().startsWith("org.junit") || d.key().group().equals("junit")
                || d.key().group().equals("org.testng") || d.key().group().startsWith("io.kotest")
                || d.key().group().startsWith("net.jqwik")));
        if (hasTests && !hasFramework) {
            deps.add(new Manifest.Dependency(DepKey.parse(JUNIT_JUPITER), JUNIT_VERSION, Manifest.Scope.TEST, 0));
        }
        return deps;
    }

    /** A hash of everything that feeds resolution; the lockfile is reused while it matches. */
    public static String inputs(Project project) {
        Hash h = new Hash().add("v1");
        for (String r : project.root.repositories) h.add("repo " + r);
        for (Project.Module m : project.modules) {
            h.add("module " + m.name());
            for (Manifest.Dependency d : effectiveDeps(m)) h.add(d.scope().key + " " + d.key() + " " + d.spec());
        }
        return h.hex().substring(0, 32);
    }

    /** Root edges of a module for one scope. Workspace siblings map to their LOCAL packages. */
    public static List<Graph.Edge> rootEdges(Project.Module m, Manifest.Scope scope) {
        List<Graph.Edge> out = new ArrayList<>();
        for (Manifest.Dependency d : effectiveDeps(m)) {
            if (d.scope() != scope) continue;
            out.add(new Graph.Edge(idOf(d.key()), false, Set.of()));
        }
        return out;
    }

    public static String idOf(DepKey k) {
        return k.toString();
    }

    // ------------------------------------------------------------------ resolution

    public Graph resolve() {
        Map<String, Selected> selected = new HashMap<>();
        Map<String, List<Request>> requests = null;
        Map<String, Set<String>> visitedFrom = null;
        for (int iteration = 0; iteration < 40; iteration++) {
            requests = new TreeMap<>();
            visitedFrom = new LinkedHashMap<>();
            Map<String, Selected> provisional = new HashMap<>(selected);
            Map<String, List<Set<String>>> seen = new HashMap<>();
            Deque<Object[]> queue = new ArrayDeque<>();

            for (Root r : roots) {
                String id = idOf(r.key());
                kinds.put(id, r.key().isGit() ? Graph.Kind.GIT : Graph.Kind.MAVEN);
                requests.computeIfAbsent(id, k -> new ArrayList<>()).add(new Request(r.spec(), true, true, r.from()));
                queue.add(new Object[]{id, new LinkedHashSet<String>()});
            }
            while (!queue.isEmpty()) {
                Object[] item = queue.poll();
                String id = (String) item[0];
                @SuppressWarnings("unchecked")
                Set<String> ex = (Set<String>) item[1];
                List<Set<String>> prior = seen.computeIfAbsent(id, k -> new ArrayList<>());
                boolean covered = false;
                for (Set<String> s : prior) if (ex.containsAll(s)) { covered = true; break; }
                if (covered) continue;
                prior.add(ex);
                visitedFrom.computeIfAbsent(id, k -> new LinkedHashSet<>());
                Selected sel = provisional.get(id);
                if (sel == null) {
                    sel = choose(id, requests.get(id));
                    provisional.put(id, sel);
                }
                Expanded exp = expand(id, sel);
                for (EdgeReq e : exp.edges) {
                    if (Graph.excluded(e.target, ex)) continue;
                    kinds.putIfAbsent(e.target, e.kind);
                    boolean hard = e.kind == Graph.Kind.GIT || MavenRange.isRange(e.spec);
                    requests.computeIfAbsent(e.target, k -> new ArrayList<>()).add(new Request(e.spec, hard, false, id));
                    visitedFrom.computeIfAbsent(e.target, k -> new LinkedHashSet<>()).add(id);
                    Set<String> next = new LinkedHashSet<>(ex);
                    next.addAll(e.exclusions);
                    queue.add(new Object[]{e.target, next});
                }
            }
            Map<String, Selected> next = new HashMap<>();
            for (Map.Entry<String, List<Request>> e : requests.entrySet()) {
                Selected cur = provisional.get(e.getKey());
                Selected s = choose(e.getKey(), e.getValue());
                next.put(e.getKey(), s);
                Log.debug("select " + e.getKey() + " -> " + s.version + (Objects.equals(cur, s) ? "" : " (was " + (cur == null ? "-" : cur.version) + ")"));
            }
            if (next.equals(selected)) break;
            selected = next;
            if (iteration == 39) Log.warn("dependency resolution did not settle; using the last result");
        }

        Graph g = new Graph();
        for (String id : requests.keySet()) {
            Selected s = selected.get(id);
            Graph.Pkg p = new Graph.Pkg(id, kinds.get(id));
            p.version = s.version;
            p.commit = s.commit;
            p.tag = s.tag;
            Expanded exp = expand(id, s);
            p.hasJar = exp.hasJar;
            for (EdgeReq e : exp.edges) {
                if (!requests.containsKey(e.target)) continue;
                p.deps.put(e.target, new Graph.Edge(e.target, e.runtimeOnly, e.exclusions));
            }
            g.put(p);
        }
        checkVisibility(g, requests);
        if (project != null) addLocal(g, project);
        return g;
    }

    private void checkVisibility(Graph g, Map<String, List<Request>> requests) {
        for (Graph.Pkg p : g.pkgs.values()) {
            if (p.kind != Graph.Kind.GIT) continue;
            Expanded exp = expand(p.id, new Selected(p.version, p.commit, p.tag));
            if (Boolean.TRUE.equals(exp.isPublic)) continue;
            DepKey key = DepKey.parse(p.id);
            for (Request r : requests.get(p.id)) {
                boolean sameRepo = r.from.contains(":") && !r.from.startsWith("workspace:")
                        && DepKey.parse(r.from).isGit() && DepKey.parse(r.from).repoKey().equals(key.repoKey());
                if (!sameRepo) {
                    throw new JpmException(p.id + " is an internal module and cannot be used from outside "
                            + key.repoKey() + " (required by " + r.from + "). Depend on one of its public modules.");
                }
            }
        }
    }

    /** Adds LOCAL packages for the project's own modules so classpaths can be walked through them. */
    public static void addLocal(Graph g, Project project) {
        for (Project.Module m : project.modules) {
            Graph.Pkg p = new Graph.Pkg(m.id(), Graph.Kind.LOCAL);
            p.version = m.version();
            p.dir = m.dir;
            for (Manifest.Dependency d : m.manifest.deps(Manifest.Scope.RUNTIME)) {
                String id = idOf(d.key());
                p.deps.put(id, new Graph.Edge(id, false, Set.of()));
            }
            g.put(p);
        }
    }

    private static void validateSpec(Manifest.Dependency d) {
        try {
            if (d.key().isMaven()) MavenRange.parse(d.spec());
        } catch (IllegalArgumentException e) {
            throw new JpmException(d.key() + " (" + Manifest.FILE + " line " + d.line() + "): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ choosing versions

    private boolean locked(String id) {
        return previous != null && !unlockAll && !unlocked.contains(id) && previous.get(id) != null;
    }

    private Selected choose(String id, List<Request> reqs) {
        if (reqs == null || reqs.isEmpty()) throw new IllegalStateException("no requests for " + id);
        return kinds.get(id) == Graph.Kind.GIT ? chooseGit(id, reqs) : chooseMaven(id, reqs);
    }

    private Selected chooseMaven(String id, List<Request> reqs) {
        List<VersionSpec> hard = new ArrayList<>();
        List<Version> soft = new ArrayList<>();
        for (Request r : reqs) {
            if (r.spec == null || r.spec.isBlank()) continue;
            if (r.hard) {
                try {
                    hard.add(MavenRange.parse(r.spec));
                } catch (IllegalArgumentException e) {
                    throw new JpmException(id + " (required by " + r.from + "): " + e.getMessage());
                }
            } else {
                soft.add(Version.of(r.spec));
            }
        }
        if (hard.isEmpty() && soft.isEmpty()) {
            throw new JpmException(id + " is required by " + reqs.get(0).from + " without a version");
        }
        Version pref = locked(id) ? Version.of(previous.get(id).version) : null;
        Version maxSoft = null;
        for (Version v : soft) if (satisfies(v, hard) && (maxSoft == null || v.compareTo(maxSoft) > 0)) maxSoft = v;
        if (pref != null && satisfies(pref, hard) && (maxSoft == null || pref.compareTo(maxSoft) >= 0)
                && (!hard.isEmpty() || soft.contains(pref))) {
            return new Selected(pref.toString(), null, null);
        }
        if (maxSoft != null) return new Selected(maxSoft.toString(), null, null);
        boolean allExact = hard.stream().allMatch(VersionSpec::isExact);
        if (allExact) {
            String first = hard.get(0).text().replaceAll("[\\[\\]]", "").trim();
            Version v = Version.of(first);
            if (satisfies(v, hard)) return new Selected(first, null, null);
        } else {
            int c = id.indexOf(':');
            List<Version> all = repo.versions(id.substring(0, c), id.substring(c + 1));
            Optional<Version> best = new AllOf(hard).best(all);
            if (best.isPresent()) return new Selected(best.get().toString(), null, null);
        }
        throw conflict(id, reqs);
    }

    private static boolean satisfies(Version v, List<VersionSpec> specs) {
        for (VersionSpec s : specs) if (!s.matches(v)) return false;
        return true;
    }

    private static JpmException conflict(String id, List<Request> reqs) {
        StringBuilder sb = new StringBuilder("No version of " + id + " satisfies every requirement:");
        for (Request r : reqs) sb.append("\n  ").append(r.spec).append("  required by ").append(r.from);
        return new JpmException(sb.toString());
    }

    /** Intersection of several specs. */
    private record AllOf(List<VersionSpec> specs) implements VersionSpec {
        public boolean matches(Version v) { return satisfies(v, specs); }
        public String text() { return String.join(" ", specs.stream().map(VersionSpec::text).toList()); }
        public boolean isExact() { return false; }
    }

    private Selected chooseGit(String id, List<Request> reqs) {
        DepKey key = DepKey.parse(id);
        GitRepo git = GitRepo.of(key);
        List<Selected> pins = new ArrayList<>();
        List<Request> pinReqs = new ArrayList<>();
        List<NpmRange> ranges = new ArrayList<>();
        // "@<commit>" ties a module to the commit of a sibling from the same repository. It only decides
        // when nothing else asks for the module; otherwise the sibling follows the other requests.
        boolean onlySiblingPins = reqs.stream().allMatch(r -> r.spec.trim().startsWith("@"));
        for (Request r : reqs) {
            String s = r.spec.trim();
            if (s.startsWith("@") && !onlySiblingPins) continue;
            if (s.startsWith("@")) {
                pins.add(commitSelection(key, s.substring(1), null));
                pinReqs.add(r);
            } else if (s.startsWith("#")) {
                String commit = locked(id) && previous.get(id).tag == null && previous.get(id).commit != null && r.direct
                        ? previous.get(id).commit : git.branchHead(s.substring(1));
                pins.add(commitSelection(key, commit, null));
                pinReqs.add(r);
            } else if (HEX.matcher(s).matches() && !NpmRange.looksLikeRange(s)) {
                String full = git.resolveCommit(s);
                if (full == null) throw new JpmException(id + ": commit " + s + " not found");
                pins.add(commitSelection(key, full, null));
                pinReqs.add(r);
            } else if (NpmRange.looksLikeRange(s)) {
                try {
                    ranges.add(NpmRange.parse(s));
                } catch (IllegalArgumentException e) {
                    throw new JpmException(id + " (required by " + r.from + "): " + e.getMessage());
                }
            } else {
                // an exact tag name
                Map<String, String> tags = tags(key);
                String commit = tags.get(s);
                if (commit == null) throw new JpmException(id + ": tag \"" + s + "\" not found in " + key.repoKey());
                pins.add(tagSelection(key, s, commit));
                pinReqs.add(r);
            }
        }
        if (!pins.isEmpty()) {
            // A pin in the project's own manifest wins; otherwise the highest pinned version does.
            Selected chosen = null;
            for (int i = 0; i < pins.size(); i++) {
                if (pinReqs.get(i).direct) { chosen = pins.get(i); break; }
            }
            if (chosen == null) {
                for (Selected s : pins) {
                    if (chosen == null || Version.of(s.version).compareTo(Version.of(chosen.version)) > 0) chosen = s;
                }
            }
            for (Selected s : pins) {
                if (!s.commit.equals(chosen.commit)) {
                    Log.warn(id + " is pinned to several commits; using " + chosen.commit.substring(0, 7) + " (" + chosen.version + ")");
                    break;
                }
            }
            for (NpmRange r : ranges) {
                if (!r.matches(Version.of(stripBuild(chosen.version)))) {
                    throw conflict(id, reqs);
                }
            }
            return chosen;
        }
        Map<String, String> tags = tags(key);
        Map<Version, String> byVersion = new LinkedHashMap<>();
        for (String tag : tags.keySet()) {
            String v = tagVersion(key, tag, tags.keySet());
            if (v != null) byVersion.putIfAbsent(Version.of(v), tag);
        }
        if (locked(id)) {
            Graph.Pkg p = previous.get(id);
            if (p.tag != null && tags.containsKey(p.tag) && tags.get(p.tag).equals(p.commit)) {
                Version v = Version.of(p.version);
                boolean ok = true;
                for (NpmRange r : ranges) ok &= r.matches(v);
                if (ok) return new Selected(p.version, p.commit, p.tag);
            }
        }
        Version best = null;
        for (Version v : byVersion.keySet()) {
            boolean ok = true;
            for (NpmRange r : ranges) {
                if (!r.matches(v) || (v.isPreRelease() && !r.text().contains("-"))) { ok = false; break; }
            }
            if (ok && (best == null || v.compareTo(best) > 0)) best = v;
        }
        if (best == null) {
            StringBuilder sb = new StringBuilder("No tag of " + key.repoKey() + " matches every requirement for " + id + ":");
            for (Request r : reqs) sb.append("\n  ").append(r.spec).append("  required by ").append(r.from);
            List<String> avail = byVersion.keySet().stream().sorted().map(Version::toString).toList();
            sb.append("\nAvailable versions: ").append(avail.isEmpty() ? "(no version tags)" : String.join(", ", avail));
            throw new JpmException(sb.toString());
        }
        String tag = byVersion.get(best);
        return tagSelection(key, tag, tags.get(tag));
    }

    private static String stripBuild(String v) {
        int p = v.indexOf('+');
        return p < 0 ? v : v.substring(0, p);
    }

    private Map<String, String> tags(DepKey key) {
        return tagMemo.computeIfAbsent(key.repoKey().toString(), k -> GitRepo.of(key).tags());
    }

    /**
     * Version a tag stands for, for this module. Prefixed tags ({@code api/v1.2.0}) are used for a module
     * when the repository has any; otherwise plain tags ({@code v1.2.0}) version the whole workspace.
     */
    static String tagVersion(DepKey key, String tag, Collection<String> allTags) {
        String module = key.module();
        boolean prefixed = module != null && allTags.stream().anyMatch(t -> t.startsWith(module + "/"));
        String v;
        if (prefixed) {
            if (!tag.startsWith(module + "/")) return null;
            v = tag.substring(module.length() + 1);
        } else {
            if (tag.contains("/")) return null;
            v = tag;
        }
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,2}(-[0-9A-Za-z.-]+)?") ? v : null;
    }

    private Selected tagSelection(DepKey key, String tag, String commit) {
        GitModule mod = GitModule.locate(key, commit);
        String declared = mod.version();
        String fromTag = tagVersion(key, tag, tags(key).keySet());
        if (fromTag != null && !Version.of(fromTag).equals(Version.of(declared))) {
            throw new JpmException(key + ": tag " + tag + " points to a commit whose " + Manifest.FILE
                    + " says version " + declared + ". Tags must match the manifest version.");
        }
        return new Selected(fromTag != null ? fromTag : declared, commit, tag);
    }

    private Selected commitSelection(DepKey key, String commit, String tag) {
        // A commit that carries a version tag for this module is that version.
        Map<String, String> tags = tags(key);
        for (Map.Entry<String, String> t : tags.entrySet()) {
            if (t.getValue().equals(commit) && tagVersion(key, t.getKey(), tags.keySet()) != null) {
                return tagSelection(key, t.getKey(), commit);
            }
        }
        GitModule mod = GitModule.locate(key, commit);
        return new Selected(mod.version() + "+" + commit.substring(0, 7), commit, tag);
    }

    // ------------------------------------------------------------------ expanding

    private Expanded expand(String id, Selected sel) {
        String memoKey = id + "@" + sel.version + "@" + sel.commit;
        Expanded e = expandMemo.get(memoKey);
        if (e != null) return e;
        e = kinds.get(id) == Graph.Kind.GIT ? expandGit(id, sel) : expandMaven(id, sel.version);
        expandMemo.put(memoKey, e);
        return e;
    }

    private Expanded expandMaven(String id, String version) {
        int c = id.indexOf(':');
        Pom pom = poms.load(id.substring(0, c), id.substring(c + 1), version);
        List<EdgeReq> edges = new ArrayList<>();
        if (pom.relocation != null) {
            String[] r = pom.relocation.split(":");
            if (!(r[0] + ":" + r[1]).equals(id)) {
                edges.add(new EdgeReq(r[0] + ":" + r[1], Graph.Kind.MAVEN, r[2], false, Set.of()));
                return new Expanded(edges, false, null);
            }
        }
        for (Pom.Dep d : pom.dependencies) {
            String scope = d.scope() == null ? "compile" : d.scope();
            if (!scope.equals("compile") && !scope.equals("runtime")) continue;
            if (d.optional()) continue;
            if (d.classifier() != null && !d.classifier().isEmpty()) continue;
            if (d.type() != null && !d.type().equals("jar") && !d.type().equals("bundle")) continue;
            if (d.group() == null || d.artifact() == null || d.group().contains("${") || d.artifact().contains("${")) continue;
            if (d.version() == null || d.version().contains("${")) {
                Log.warn(id + ":" + version + " depends on " + d.key() + " without a usable version; skipping it");
                continue;
            }
            edges.add(new EdgeReq(d.key(), Graph.Kind.MAVEN, d.version(), scope.equals("runtime"), d.exclusions()));
        }
        return new Expanded(edges, !"pom".equals(pom.packaging), null);
    }

    private Expanded expandGit(String id, Selected sel) {
        DepKey key = DepKey.parse(id);
        GitModule mod = GitModule.locate(key, sel.commit);
        List<EdgeReq> edges = new ArrayList<>();
        for (Manifest.Dependency d : mod.manifest().deps(Manifest.Scope.RUNTIME)) {
            if (d.key().isWorkspace()) {
                String sib = d.key().artifact();
                if (!mod.siblings().containsKey(sib)) {
                    throw new JpmException(id + ": depends on workspace:" + sib + ", which is not a module of " + key.repoKey());
                }
                GitModule sm = GitModule.locate(key.withModule(sib), sel.commit);
                // The implementation of a public API is used at runtime only, never compiled against.
                boolean runtimeOnly = !sm.isPublic();
                String target = idOf(key.withModule(sib));
                edges.add(new EdgeReq(target, Graph.Kind.GIT, "@" + sel.commit, runtimeOnly, Set.of()));
            } else {
                edges.add(new EdgeReq(idOf(d.key()), d.key().isGit() ? Graph.Kind.GIT : Graph.Kind.MAVEN, d.spec(), false, Set.of()));
            }
        }
        return new Expanded(edges, true, mod.isPublic());
    }
}
