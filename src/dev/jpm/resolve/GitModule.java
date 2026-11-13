package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.git.GitRepo;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A module of a Git repository at a given commit, read without checking it out. */
public record GitModule(DepKey key, String commit, Manifest root, Manifest manifest, String subdir,
                        Map<String, String> siblings) {

    /** The version the module's manifest declares (lockstep: the workspace version). */
    public String version() {
        if (manifest.version != null) return manifest.version;
        if (root.version != null) return root.version;
        throw new JpmException(key + " at " + commit.substring(0, 7) + ": " + Manifest.FILE + " has no \"version\"");
    }

    public boolean isPublic() {
        return "public".equals(manifest.visibility);
    }

    public static GitModule locate(DepKey key, String commit) {
        GitRepo repo = GitRepo.of(key);
        repo.ensureCommit(commit);
        String rootText = repo.show(commit, Manifest.FILE);
        if (rootText == null) {
            throw new JpmException(key.repoKey() + " has no " + Manifest.FILE + " at its root (commit " + commit.substring(0, 7)
                    + "). Only repositories with a jpm manifest can be used as dependencies for now;"
                    + " importing Maven and Gradle builds is planned.");
        }
        Manifest root = Manifest.parse(Path.of(key.repoKey() + "@" + commit.substring(0, 7), Manifest.FILE), rootText);
        Map<String, String> siblings = new LinkedHashMap<>(); // module name -> subdir
        Map<String, Manifest> manifests = new LinkedHashMap<>();
        if (root.isWorkspaceRoot()) {
            for (String rel : root.workspace) {
                String text = repo.show(commit, rel + "/" + Manifest.FILE);
                if (text == null) throw new JpmException(key.repoKey() + ": workspace module \"" + rel + "\" has no " + Manifest.FILE);
                Manifest m = Manifest.parse(Path.of(key.repoKey() + "@" + commit.substring(0, 7), rel, Manifest.FILE), text);
                siblings.put(m.name, rel);
                manifests.put(m.name, m);
            }
        }
        if (key.module() == null) {
            if (root.isWorkspaceRoot()) {
                throw new JpmException(key + " is a workspace; depend on one of its modules instead: "
                        + String.join(", ", manifests.keySet().stream().map(n -> key.withModule(n).toString()).toList()));
            }
            return new GitModule(key, commit, root, root, "", siblings);
        }
        if (!root.isWorkspaceRoot()) {
            if (key.module().equals(root.name)) return new GitModule(key, commit, root, root, "", siblings);
            throw new JpmException(key + ": " + key.repoKey() + " is not a workspace, so it has no module \"" + key.module() + "\"");
        }
        String name = key.module();
        Manifest m = manifests.get(name);
        if (m == null) {
            throw new JpmException(key + ": no module \"" + key.module() + "\" in " + key.repoKey() + " (modules: " + String.join(", ", manifests.keySet()) + ")");
        }
        return new GitModule(key, commit, root, m, siblings.get(name), siblings);
    }

    /** The source directory of the module after checkout. */
    public Path checkout() {
        Path dir = GitRepo.of(key).checkout(commit);
        return subdir.isEmpty() ? dir : dir.resolve(subdir);
    }
}
