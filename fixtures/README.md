Builds used by CI to test `jpm import`. They are not part of jpm's own sources or tests.

- `gradle-demo`: a two-project Gradle build (Kotlin DSL) with a version catalog, a Java toolchain, the
  `application` plugin, a project dependency, a platform (BOM) and Lombok.
- `bundle-demo`: a workspace with a public API, an internal implementation (with a dependency only it
  uses) and an app packaged as a jar-in-jar bundle, to check class loader isolation.
