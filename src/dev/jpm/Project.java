package dev.jpm;

import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A project on disk: either a single module, or a workspace root listing several modules. Used for the
 * project being built and for Git checkouts alike.
 */
public final class Project {

    /** One buildable module. */
    public static final class Module {
        public final Project project;
        public final Path dir;
        public final Manifest manifest;

        Module(Project project, Path dir, Manifest manifest) {
            this.project = project;
            this.dir = dir;
            this.manifest = manifest;
        }

        public String name() {
            return manifest.name;
        }

        /** Lockstep by default: a module without its own version uses the workspace version. */
        public String version() {
            if (manifest.version != null) return manifest.version;
            if (project.root.version != null) return project.root.version;
            return "0.0.0";
        }

        public String id() {
            return "workspace:" + name();
        }

        /** The first source folder (the only one unless the manifest lists {@code "sources"}). */
        public Path srcDir() { return srcDirs().get(0); }
        /** The first resource folder (the only one unless the manifest lists {@code "resources"}). */
        public Path assetsDir() { return assetsDirs().get(0); }
        public List<Path> srcDirs() { return manifest.sourceDirs(dir); }
        public List<Path> assetsDirs() { return manifest.resourceDirs(dir); }
        public Path testDir() { return manifest.layout.tests(dir); }
        public Path testResourcesDir() { return manifest.layout.testResources(dir); }

        public boolean isPublic() {
            return "public".equals(manifest.visibility);
        }

        /** Directory name relative to the workspace root ("" for a single-module project). */
        public String relDir() {
            return project.dir.relativize(dir).toString().replace('\\', '/');
        }

        public Path cacheDir() {
            return project.dir.resolve(".cache").resolve("build").resolve(name());
        }

        @Override
        public String toString() {
            return name();
        }
    }

    public final Path dir;
    public final Manifest root;
    public final List<Module> modules;

    private Project(Path dir, Manifest root, List<Module> modules) {
        this.dir = dir;
        this.root = root;
        this.modules = modules;
    }

    /** Loads the project whose root is exactly {@code dir}. */
    public static Project load(Path dir) {
        dir = dir.toAbsolutePath().normalize();
        Manifest root = Manifest.load(dir);
        List<Module> modules = new ArrayList<>();
        Project p = new Project(dir, root, modules);
        if (root.isWorkspaceRoot()) {
            for (String rel : root.workspace) {
                Path md = dir.resolve(rel).normalize();
                if (!md.startsWith(dir)) throw new JpmException(root.file + ": workspace entry \"" + rel + "\" is outside the workspace");
                if (!Files.exists(md.resolve(Manifest.FILE))) {
                    throw new JpmException(root.file + ": workspace module \"" + rel + "\" has no " + Manifest.FILE);
                }
                Manifest m = Manifest.load(md);
                if (m.isWorkspaceRoot()) throw new JpmException(m.file + ": nested workspaces are not supported");
                modules.add(new Module(p, md, m));
            }
            if (!root.dependencies.isEmpty() || !root.mains.isEmpty()) {
                throw new JpmException(root.file + ": a workspace root lists modules only; move dependencies and main into a module");
            }
        } else {
            modules.add(new Module(p, dir, root));
        }
        Set<String> names = new LinkedHashSet<>();
        for (Module m : modules) {
            if (!names.add(m.name())) throw new JpmException("Two workspace modules are named \"" + m.name() + "\"");
        }
        for (Module m : modules) {
            for (Manifest.Dependency d : m.manifest.dependencies) {
                if (d.key().isWorkspace() && p.module(d.key().artifact()) == null) {
                    throw new JpmException(m.manifest.file + ":" + d.line() + ": no workspace module named \"" + d.key().artifact() + "\"");
                }
            }
        }
        return p;
    }

    /**
     * Finds the project for a working directory: the nearest {@code project.jsonc}, or the workspace
     * root that lists it.
     */
    public static Project find(Path cwd) {
        Path d = cwd.toAbsolutePath().normalize();
        Path found = null;
        for (Path p = d; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(Manifest.FILE))) { found = p; break; }
        }
        if (found == null) {
            throw new JpmException("No " + Manifest.FILE + " found in " + d + " or any parent directory. Run `jpm init` to create one.");
        }
        for (Path p = found.getParent(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(Manifest.FILE))) {
                Manifest m = Manifest.load(p);
                if (m.isWorkspaceRoot()) {
                    for (String rel : m.workspace) {
                        if (p.resolve(rel).normalize().equals(found)) return load(p);
                    }
                }
                break;
            }
        }
        return load(found);
    }

    public Module module(String nameOrDir) {
        for (Module m : modules) if (m.name().equals(nameOrDir)) return m;
        for (Module m : modules) if (m.relDir().equals(nameOrDir) || m.dir.getFileName().toString().equals(nameOrDir)) return m;
        return null;
    }

    /** The module containing the path, or the only module. */
    public Module moduleAt(Path cwd) {
        Path c = cwd.toAbsolutePath().normalize();
        Module best = null;
        for (Module m : modules) {
            if (c.startsWith(m.dir) && (best == null || m.dir.getNameCount() > best.dir.getNameCount())) best = m;
        }
        if (best != null && (best.dir != dir || modules.size() == 1)) return best;
        return modules.size() == 1 ? modules.get(0) : null;
    }

    /** The root manifest as a module, for scripts and hooks declared on a workspace root. */
    public Module rootModule() {
        if (!root.isWorkspaceRoot()) return modules.get(0);
        return new Module(this, dir, root);
    }

    public Path lockFile() {
        return dir.resolve(Manifest.LOCK);
    }

    /** Workspace modules in build order. */
    public List<Module> buildOrder() {
        Map<String, Module> byName = new LinkedHashMap<>();
        for (Module m : modules) byName.put(m.name(), m);
        List<Module> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (Module m : modules) visit(m, byName, done, new LinkedHashSet<>(), out);
        return out;
    }

    private void visit(Module m, Map<String, Module> byName, Set<String> done, Set<String> stack, List<Module> out) {
        if (done.contains(m.name())) return;
        if (!stack.add(m.name())) throw new JpmException("Cycle between workspace modules: " + String.join(" -> ", stack) + " -> " + m.name());
        for (Manifest.Dependency d : m.manifest.dependencies) {
            if (d.key().isWorkspace()) visit(module(d.key().artifact()), byName, done, stack, out);
        }
        stack.remove(m.name());
        done.add(m.name());
        out.add(m);
    }
}
