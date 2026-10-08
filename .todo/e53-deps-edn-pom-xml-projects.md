# e53. deps.edn: a pom.xml project (`:deps/manifest :pom`) is not read

Difficulty: Medium

A `:local/root` directory or a git commit holding a `pom.xml` and no `deps.edn` is tools.deps'
`:pom` manifest; here it contributes nothing and is named when a lookup misses
(`.kb/clojure-frontend.md`, "deps.edn"). The oracle reads it (`clojure.tools.deps.extensions.pom`):

- `coord-deps :pom`: the effective model's compile/runtime dependencies, optional ones kept,
  classifier as written (`model-dep->data`, like a jar's own pom, which is read already:
  `MavenResolver.projectDependencies`).
- `coord-paths :pom`: `build.sourceDirectory` (default `src/main/java`), `src/main/clojure`,
  each `build.resources` directory (default `src/main/resources`), and the
  `build-helper-maven-plugin` `add-source`/`add-resource` directories -- read off the FIRST
  plugin of `build.plugins` only (tools.deps' `(first plugins)`), each made canonical against
  the root, absent ones included. Measured on `clj` 1.12.6 (2026-10-08): a pom without a
  `build` gives `src/main/java`, `src/main/clojure`, `src/main/resources`.

## What it needs

- `am.ik.maven` reads no `build` section (`PomReader` skips it): `sourceDirectory`,
  `resources`, `plugins` with inheritance from the parent chain, interpolation, and the super
  POM's defaults against the project directory (`${project.basedir}`).
- A file model's parent at `relativePath` (default `../pom.xml`) before the repositories, as
  Maven's `FileModelSource` resolves it: a module of a git monorepo names its parent that way.
- Oracle fixtures: `pom.xml` projects with and without `build`, resources, a build-helper
  plugin first and not first, a relativePath parent; `clj -Srepro -Spath`.
