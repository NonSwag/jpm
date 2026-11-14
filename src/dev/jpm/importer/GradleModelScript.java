package dev.jpm.importer;

/** Gradle init script that reports the build model as JSON. Kept in code so native builds need no resources. */
final class GradleModelScript {
    private GradleModelScript() {}

    static final String TEXT = """
// Written by jpm: reports the Gradle build model as JSON for `jpm import`. It does not change the build.
import groovy.json.JsonOutput

gradle.projectsEvaluated {
    def out = []
    def root = gradle.rootProject
    root.allprojects.each { p ->
        def info = [
            path       : p.path,
            name       : p.name,
            dir        : root.projectDir.toPath().relativize(p.projectDir.toPath()).toString().replace('\\\\', '/'),
            group      : p.group?.toString(),
            version    : p.version?.toString(),
            java       : p.plugins.hasPlugin('java'),
            kotlin     : p.plugins.findPlugin('org.jetbrains.kotlin.jvm') != null,
            plugins    : p.plugins.collect { it.class.name },
            deps       : [:],
            resolved   : [:],
            repositories: [],
            sourceSets : [:],
        ]
        def javaExt = p.extensions.findByName('java')
        if (javaExt != null) {
            try { info.toolchain = javaExt.toolchain.languageVersion.getOrNull()?.asInt() } catch (ignored) {}
            try { info.target = javaExt.targetCompatibility?.majorVersion } catch (ignored) {}
            try {
                javaExt.sourceSets.each { ss ->
                    info.sourceSets[ss.name] = [
                        java     : ss.java.srcDirs.collect { root.projectDir.toPath().relativize(it.toPath()).toString().replace('\\\\', '/') },
                        resources: ss.resources.srcDirs.collect { root.projectDir.toPath().relativize(it.toPath()).toString().replace('\\\\', '/') },
                    ]
                }
            } catch (ignored) {}
        }
        def compileJava = p.tasks.findByName('compileJava')
        if (compileJava != null) {
            try { info.release = compileJava.options.release.getOrNull() } catch (ignored) {}
        }
        def app = p.extensions.findByName('application')
        if (app != null) {
            try { info.mainClass = app.mainClass.getOrNull() } catch (ignored) {
                try { info.mainClass = app.mainClassName } catch (ignored2) {}
            }
        }
        ['api', 'implementation', 'compileOnly', 'compileOnlyApi', 'runtimeOnly', 'annotationProcessor',
         'testImplementation', 'testCompileOnly', 'testRuntimeOnly', 'testAnnotationProcessor'].each { c ->
            def conf = p.configurations.findByName(c)
            if (conf == null) return
            info.deps[c] = conf.dependencies.collect { d ->
                if (d instanceof ProjectDependency) {
                    def path = null
                    try { path = d.path } catch (ignored) { path = d.dependencyProject.path }
                    return [project: path]
                }
                def category = null
                try {
                    def attr = d.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)
                    category = attr?.name
                } catch (ignored) {}
                [group: d.group, name: d.name, version: d.version, category: category]
            }
        }
        ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath', 'annotationProcessor'].each { c ->
            def conf = p.configurations.findByName(c)
            if (conf == null || !conf.canBeResolved) return
            try {
                conf.incoming.resolutionResult.allComponents.each { comp ->
                    def id = comp.id
                    if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        info.resolved["${id.group}:${id.module}"] = id.version
                    }
                }
            } catch (e) {
                info.resolveError = e.message
            }
        }
        p.repositories.each { r ->
            if (r instanceof MavenArtifactRepository) info.repositories << r.url.toString()
        }
        out << info
    }
    def file = new File(System.getProperty('jpm.model.output'))
    file.text = JsonOutput.toJson([gradleVersion: gradle.gradleVersion, projects: out])
}
""";
}
