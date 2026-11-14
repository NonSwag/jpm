# JPM — Java Package Manager

A single CLI for Java in the spirit of Bun, Cargo and Go: dependencies, builds, running, tests,
patches and build-time defines, driven by one `project.jsonc`. The command is `jpm`.

This covers **roadmap steps 1 and 2** of the spec: the build tool itself (manifest, lockfile, resolution
from Git and Maven Central, building, running, defines, env files, patches, workspaces, tests, scripts,
hooks, extensions) and JDK/GraalVM management with native builds (`jpm java`, `jvm`,
`build --native`). Importing Maven and Gradle projects is step 3.

## Install

```sh
curl -fsSL https://github.com/NonSwag/jpm/releases/latest/download/install.sh | sh      # Linux, macOS
irm https://github.com/NonSwag/jpm/releases/latest/download/install.ps1 | iex           # Windows
```

The scripts download the native binary for your platform, check it against the release's
`SHA256SUMS`, and put `jpm` and `jvm` into `~/.jpm/bin`. They change nothing else; add that folder to
your PATH yourself. Releases are built by CI for Linux (x64, arm64), macOS (arm64, x64) and Windows
(x64) when a `v*` tag is pushed; the tag must match the version in `project.jsonc`.

## Getting started

```sh
# or from source:
./bootstrap.sh            # compiles jpm with javac, then jpm builds itself into dist/
export PATH="$PWD/bin:$PATH"

mkdir hello && cd hello
jpm init                  # project.jsonc, src/, test/, .gitignore
jpm add 'com.google.guava:guava@[33.0,34.0)'
jpm add --dev org.projectlombok:lombok@1.18.34
jpm start                 # build and run main
jpm test                  # JUnit 5 is added automatically
jpm build --release       # dist/hello-0.1.0.jar, runs postbuild:release hooks
```

jpm has no dependencies of its own and compiles to a native binary with GraalVM
(`jpm build --native`; jpm installs GraalVM if needed). It builds and tests itself: `bin/jpm test`.
On Windows use `bin\jpm.cmd` for the jar build.

CI (`.github/workflows/ci.yml`) runs the tests and builds and smoke-tests the native binary on Linux
(x64, arm64), macOS (arm64, x64) and Windows (x64); the binaries are uploaded as workflow artifacts.

## Commands

| Command | |
|---|---|
| `init [name]` | Create a project |
| `add <dep>[@version]` | Add dependencies (`--dev`, `--test`). Without a version: latest release (Maven) or `^latest-tag` (Git) |
| `remove <dep>` | Remove dependencies |
| `install [--frozen]` | Resolve and write `project.lock`; `--frozen` fails if the lock is stale (CI) |
| `update [dep…] [--latest]` | Re-resolve within ranges; `--latest` rewrites the manifest past them |
| `outdated`, `why <dep>` | Inspect the graph |
| `build`, `start`, `test`, `clean` | Build into `dist/`, run main with production env, run tests, delete output |
| `dev [entry]` | Run main with development env, restarting when sources, assets, manifest or env files change |
| `run <script> [args]` | Run a script from `scripts` (no shorthand) |
| `patch <dep>`, `patch --commit <dep>` | Patch a dependency |
| `java list [--available [vendor]]` | Installed JDKs (`*` marks this project's), or what can be installed |
| `java install <java>`, `java remove <java>` | Install into / remove from `~/.jpm/jdks` |
| `java use <java>` | Set `"java"` in the manifest, installing it if needed |
| `build --native` | Also build a native executable of `main` with GraalVM into `dist/` |
| `start --trace` | Run under the native-image tracing agent, recording configuration for native builds |
| `<anything else>` | Runs extension `jpm-<name>` from `~/.jpm/bin` or `PATH`; install with `jpm add -g <dep>` |

Dependency arguments: `group:artifact@1.2.3`, `group:artifact:1.2.3`, `'group:artifact@[1.0,2.0)'`,
`github:user/repo@^1.2`, `github:user/repo#main`, `github:user/repo/module@a1b2c3d`.

## Manifest

```jsonc
{
  "name": "my-app",
  "version": "1.3.0",
  "java": "graalvm-21",   // installed automatically if missing
  "target": "17",         // bytecode target (--release), defaults to java
  "main": "com.example.App",          // or { "cli": "...", "server": "..." } → jpm start server
  "runtime": {
    "com.google.guava:guava": "[33.0,34.0)",
    "github:user/repo/api": "^1.2"
  },
  "dev":  { "org.projectlombok:lombok": "1.18.34" },
  "test": { "org.junit.jupiter:junit-jupiter": "5.11.0" },
  "define": {
    "API_URL": { "type": "string", "env": "API_URL" },
    "RETRIES": { "type": "int", "value": 3 },
    "DEBUG":   { "type": "boolean", "env": "DEBUG", "default": false },
    // values for defines declared by a dependency:
    "github:user/repo/api": { "API_KEY": "..." }
  },
  "scripts": { "format": "scripts/format.sh", "upload-mappings": "scripts/UploadMappings.java" },
  "hooks":   { "postbuild:release": "upload-mappings" },
  "patches": { "com.example:old-lib": "patches/old-lib.patch" }
}
```

Other fields: `group`, `visibility` (`public`/`internal`, workspaces), `workspace` (module list, on a
workspace root), `repositories` (extra Maven repositories), `defineClass` (name of the generated class),
`javacArgs`. Unknown fields are errors, and every error names the file and line.

Layout: `src/`, `test/` (`test/resources/` on the test classpath), `assets/` (copied into the jar as is),
`scripts/`, `patches/`, `.cache/` (build cache, not committed), `dist/` (jars), `.changeset/` (reserved).

## How it works

**Resolution.** Maven and Git packages live in one graph and conflicts are settled the same way for
both: every hard constraint must hold — anything declared in a manifest, and any range anywhere — and
among the versions that satisfy them the highest wins. Plain versions inside POMs are soft. Locked
versions are kept while they still satisfy the constraints, so editing one dependency does not move
others. POMs are read with parent inheritance, property interpolation, `dependencyManagement`, imported
BOMs, exclusions, optional/provided/test scopes and relocation.

**Git dependencies** go through the system `git` (so SSH keys and credential helpers work), into bare
mirrors under `~/.jpm/git`. Versions are npm-style ranges matched against tags, exact tags, commits or
`#branch`. A tag must match the version in that commit's manifest; untagged commits are versioned like
`1.3.0+a1b2c3d`. Git packages are built from source with the consuming project's resolved versions
and cached in `~/.jpm/builds`, keyed by commit, patch, define values, classpath and JDK. A repository
without a `project.jsonc` fails with a clear error until step 3.

**Lockfile.** `project.lock` is sorted JSONC with one line per package: exact version, tag and commit
for Git, a SHA-256 for Maven jars (verified on every install), and its edges. It is reused as long as
the declared dependencies are unchanged.

**Workspaces.** A root `project.jsonc` with `"workspace": ["api", "impl"]`. Modules depend on each
other with `"workspace:impl": "*"`, share one version (lockstep) and one lockfile. Outside projects can
depend only on `public` modules; an `internal` module pulled in by a public one goes on the runtime
classpath but never the compile classpath, and always comes from the same commit as the module that
pulls it in. Prefixed tags (`api/v1.2.0`) version a module on its own.

**Builds** call the selected JDK's `javac`. Per module, a hash of the classpath, define values, JDK and
flags decides whether to reuse output. Per file, changed sources are recompiled; when a class's public
signature changes, every source that refers to it is recompiled too, and a change to a compile-time
constant rebuilds the module (javac inlines constants without leaving a reference). Dev dependencies are
the annotation processor path. Jars are reproducible.

**Defines** generate `BuildConfig` (in the main class's package, or `defineClass`) with `NAME`,
`VERSION` and one typed constant per define. Values come from the consumer (for libraries), the env
variable, the literal value, then the default. Libraries may declare defines without a value; a missing
or mistyped value fails with the library, variable, type and the exact snippet to add.

**Env files** load like Bun: `.env`, then `.env.development` (`dev`, `test`, `run`) or
`.env.production` (`build`, `start`), then `.env.local`, which overrides the other files. Variables
already set in the process environment win over all files, so CI secrets are never shadowed.

**Patches.** `jpm patch <dep>` unpacks the Git checkout or the Maven sources jar into
`.cache/patch/<name>` as a Git repo; `jpm patch --commit <dep>` writes the diff to `patches/` and
records it in the manifest. Git dependencies are patched before they are built. For Maven jars only the
changed classes are recompiled and swapped in. If a patch no longer applies, the build fails.

**Tests** run on the JUnit Platform console launcher matching the resolved platform version; any
platform engine works. `jpm test com.example.FooTest`, `jpm test 'com.example.*'` or
`jpm test 'com.example.FooTest#bar'` select tests.

**Scripts** run with `jpm run <name>`. `.java` scripts run on the source launcher with the runtime
classpath; `.sh` scripts with `sh`; anything else as a shell command. Scripts and extensions get
`JPM_PROJECT_DIR`, `JPM_MODULE_DIR`, `JPM_NAME`, `JPM_VERSION`, `JPM_MANIFEST`, `JPM_CLASSES_DIR`,
`JPM_CLASSPATH`, `JPM_JAR` (after a build) and `JPM_JAVA_HOME`; extensions also get `JPM_CONFIG`.
Hooks are `pre`/`post` + `build`/`test`/`start`, optionally with `:<profile>` (`build --release` is
profile `release`).

## JDKs and GraalVM

`"java"` names the JDK: `21`, `21.0.5`, or vendor-version such as `temurin-17`, `zulu-21`, `corretto-21`,
`graalvm-21` (GraalVM Community), `oracle-graalvm-21`, `mandrel-21`. A plain version accepts any vendor
and installs Temurin if none is present. jpm uses an installed JDK when one matches (`JAVA_HOME` first)
and otherwise downloads one through the [Foojay Disco API](https://api.foojay.io), verifies its SHA-256
and unpacks it into `~/.jpm/jdks`. It never edits PATH, JAVA_HOME or shell profiles. Set
`JPM_NO_INSTALL` to turn automatic installs off.

**`jvm`** is a separate command (link or copy the native `jpm` binary as `jvm`, or use `bin/jvm`) for
using JDKs without changing anything persistent:

```sh
jvm exec 21 -- java -jar app.jar      # run with JDK 21 as JAVA_HOME and first on PATH
eval "$(jvm env graalvm-21)"          # this shell only
jvm install temurin-17 · jvm list · jvm remove temurin-17 · jvm home 21
```

**Native builds.** `jpm build --native` builds the jar, then runs native-image from a GraalVM of the
project's Java version (any installed GraalVM-based JDK, else GraalVM Community, installed) and writes
`dist/<name>`. Extra flags go in `"nativeArgs"`. `jpm start --trace` runs the app under the tracing
agent and merges what it records into `assets/META-INF/native-image/<group>/<name>/`, which ships in the
jar and is picked up by native-image automatically.

## Environment variables

`JPM_HOME` (default `~/.jpm`), `JPM_OFFLINE` / `--offline`, `JPM_NO_INSTALL`, `JPM_FOOJAY_URL` (mirror), `JPM_VERBOSE` / `-v`, `JPM_JVM_ARGS` (for
`start`/`dev`), `JPM_MAVEN_CENTRAL` (mirror), `JPM_GIT_BASE_GITHUB` (and `_GITLAB`, `_CODEBERG`) to
redirect a Git host, e.g. to a mirror or `file:///…` for tests.

## Not yet done

- **Step 3:** importing Maven and Gradle builds.
- Jar-in-jar isolation with generated launcher and class loaders. Packaging is an open question in
  the spec; `build` produces plain jars for now.
- Running the tracing agent automatically during tests (an open question); for now `start --trace`
  records what one run of the app does.
- Hot reload is restart-based; in-process reload (JetBrains Runtime or similar) is an open question.
- Changesets: `.changeset/` is reserved only. The IntelliJ plugin is a separate project.
- The resolver is jpm's own rather than an embedded Coursier or Maven Resolver, to keep jpm free of
  dependencies for native-image. It does not evaluate POM profiles or Gradle module metadata, and skips
  dependencies with classifiers.
