# e64. am.ik.maven: per-repository policies (update, releases/snapshots enabled)

Difficulty: Low

`am.ik.maven` applies one update policy to every repository (`MavenResolver.Builder.updatePolicy`,
default daily) and lets every repository serve releases and snapshots (`.kb/maven-resolver.md`,
"Repositories"). Maven and tools.deps set both per repository:

- `deps.edn` `:mvn/repos {"x" {:url .. :releases {:enabled .. :update ..} :snapshots {..}}}`
  (tools.deps `repo-policy`: `:update` `:daily`/`:always`/`:never`/minutes, `:enabled`).
  `ClojureBasis` already drops a repository whose `:releases {:enabled false}`; `:update` and
  `:snapshots` are not read (`doc/*/clojure/deviations.md` names this).
- Maven's super-POM Central serves no snapshots, so `mvn` never asks Central for a SNAPSHOT's
  metadata or file; `--java-dep` does (a 404, but a request).

Shape: a policy per `RemoteRepository` (releases and snapshots each: enabled, update), consulted
where Maven consults it -- `DefaultMetadataResolver.getEnabledSourceRepositories` by metadata
nature, `DefaultVersionRangeResolver.filterVersionsByRepositoryType`, the artifact resolver's
`repo.getPolicy(artifact.isSnapshot())`. Oracle: `MavenOracle` with a repository whose snapshot
policy is disabled; a `deps.edn` case against `clj`.
