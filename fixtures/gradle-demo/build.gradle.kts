subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(17) }
    }
    repositories { mavenCentral() }
}
