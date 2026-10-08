# e75. deps.edn: the POMs tools.deps reads are validated at Maven's STRICT level

Difficulty: Medium

tools.deps builds a `pom.xml` project's model and a jar's own `pom.xml` with a bare
`DefaultModelBuildingRequest`, whose validation level is `VALIDATION_LEVEL_STRICT` (Maven
3.0, value 30; `clojure.tools.deps.extensions.pom/read-model`). `MavenResolver.project` and
`projectDependencies` validate at the MINIMAL level, as a repository descriptor is, so a POM
the oracle refuses is read here (`.kb/maven-resolver.md`, "API").

Measured on `clj` 1.12.6 (2026-10-08): a `pom.xml` project with `<resource/>` fails
`'build.resources.resource.directory' is missing.` (and warns `'build.plugins.plugin.version'
for ... is missing.`); one without `modelVersion` reports it twice (raw and effective).

## What it needs

- `DefaultModelValidator` (maven-model-builder 3.9.16) at level 30: `validateRawModel`'s
  level-2.0 branch (modelVersion, ids, raw dependencies and plugins, profile ids and
  activation expressions, repositories) and `validateEffectiveModel`'s (modules, version
  characters and expressions, plugins, resources, repositories, distribution management),
  each check's severity at that level (`errOn30`, `errOn31`).
- The strict reader for the file POM: `readModel` reads strictly, and a POM that only the
  lenient reader reads is an ERROR for a file (`Malformed POM ...`), a WARNING for the
  jar's (`UrlModelSource`); a repository parent is read at level 2.0.
- A level parameter through `ModelBuilder.build`, so the descriptor path stays minimal.
- Oracle fixtures: `clj -Srepro -Spath` over projects each tripping one strict check.
