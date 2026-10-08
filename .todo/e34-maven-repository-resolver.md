# e34. Maven repository resolver (coordinates -> jars + dependency graph)

Difficulty: High

Needed by `deps.edn` `:mvn/version` coordinates (`e39`) and by Java libraries for CL programs
(`e35`). Nothing in the tree reads a POM or a Maven repository; `--emit-pom` only writes one
(`.kb/jvm-export.md`). Builds on `am.ik.artifact` (`.kb/dists.md`).

## Scope

- Repositories: Maven Central and Clojars as defaults, extra ones by URL (`:mvn/repos`).
  Local repository: `~/.m2/repository` layout, shared with `clj`/`mvn` (an artifact either
  tool already fetched is not downloaded again); override path (`:mvn/local-repo`).
- Artifacts: `.jar` + `.pom`, `.sha1` verified, classifiers.
- POM model: parent chain, `${...}` property interpolation (project.*, properties, parent),
  `dependencyManagement` (including `import` scope BOMs), scopes (compile/runtime kept;
  test/provided/system dropped), `optional`, `exclusions`.
- Graph, not policy: answer the dependency graph with every version seen; selection is the
  caller's (Maven nearest-wins for `e35`, tools.deps newest-wins for `e39`).
- Refuse by name what is not done: SNAPSHOT/`maven-metadata.xml`, version ranges, auth
  (`settings.xml`), mirrors/proxies -- each a clear message, never a silent wrong pick.
- XML: the JDK's `java.xml` (no external dependency); check the native-image CLI and the web
  profile (`Target_*` substitution, browser refusal) accept it.

## Plan

1. Read `.kb/architecture.md`, `.kb/dists.md`, `.kb/jvm-export.md`. Decide the package
   (a language-independent `am.ik.*` library fits: it needs no rontolisp type).
2. Unit tests over a fixture repository on disk (file: URLs or a stub `Downloader`); no
   automated test hits the network (`.kb/dists.md` convention).
3. A new `.kb` file for the resolver (maven-resolver.md) + README index row.
