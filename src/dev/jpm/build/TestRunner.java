package dev.jpm.build;

import dev.jpm.DepKey;
import dev.jpm.Project;
import dev.jpm.toolchain.Jdk;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Resolver;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.Version;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs tests on the JUnit Platform. JUnit 5 is added automatically when no test framework is declared;
 * any other platform engine on the test classpath works too.
 */
public final class TestRunner {
    private TestRunner() {}

    /** Runs a module's tests. Returns the exit code (0 when no tests exist). */
    public static int run(Builder b, Project.Module m, List<String> filters) {
        if (!b.compileTests(m)) {
            Log.info(m.name() + ": no tests");
            return 0;
        }
        List<Path> cp = b.testRuntimeClasspath(m);
        String platform = platformVersion(b.graph, m);
        if (platform == null) {
            throw new JpmException(m.name() + ": the test classpath has no JUnit Platform engine. Add a test framework such as "
                    + Resolver.JUNIT_JUPITER + " to \"test\".");
        }
        List<Path> launcher = b.resolveTool(List.of(new Resolver.Root("jpm test runner",
                DepKey.maven("org.junit.platform", "junit-platform-console"), platform)), null, dev.jpm.resolve.Graph.Walk.RUNTIME);
        List<Path> full = new ArrayList<>(cp);
        full.addAll(launcher);
        Jdk jdk = b.jdk(m.manifest);
        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.java());
        cmd.add("-cp");
        cmd.add(Compiler.join(Compiler.distinct(full)));
        cmd.add("org.junit.platform.console.ConsoleLauncher");
        if (Version.of(platform).compareTo(Version.of("1.10")) >= 0) cmd.add("execute");
        cmd.add("--disable-banner");
        cmd.add("--details=tree");
        if (filters.isEmpty()) {
            cmd.add("--scan-class-path=" + Builder.testClassesDir(m));
        } else {
            for (String f : filters) {
                if (f.contains("#")) cmd.add("--select-method=" + f);
                else if (f.contains("*")) {
                    cmd.add("--scan-class-path=" + Builder.testClassesDir(m));
                    cmd.add("--include-classname=" + f.replace(".", "\\.").replace("*", ".*"));
                } else cmd.add("--select-class=" + f);
            }
        }
        Log.step("Testing", m.name());
        return Exec.inherit(m.dir, cmd, Env.forChild(b.project.dir, Env.Mode.DEVELOPMENT));
    }

    private static String platformVersion(Graph g, Project.Module m) {
        List<Graph.Edge> roots = new ArrayList<>(Resolver.rootEdges(m, dev.jpm.Manifest.Scope.TEST));
        roots.addAll(Resolver.rootEdges(m, dev.jpm.Manifest.Scope.RUNTIME));
        for (String id : g.closure(roots, dev.jpm.resolve.Graph.Walk.ALL)) {
            if (id.equals("org.junit.platform:junit-platform-engine")) return g.get(id).version;
        }
        return null;
    }
}
