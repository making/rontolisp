# e47. Maven resolver: maven-metadata.xml -- version ranges, SNAPSHOT, LATEST/RELEASE

Difficulty: Medium

`am.ik.maven` refuses every version that needs `maven-metadata.xml` (`.kb/maven-resolver.md`,
"Repositories"). Real graphs need it: on a developer `~/.m2`,
`org.bouncycastle:bcpkix-jdk18on:1.81` depends on `bcutil-jdk18on:[1.81,1.82)` (reached from
`git-commit-id-maven-plugin`), so any graph through BouncyCastle 1.81 fails today, where
Maven and `clj` resolve it.

## Scope

- `maven-metadata.xml` per repository, cached as Maven caches it
  (`maven-metadata-<repoId>.xml` in the local repository) under an update policy (decide;
  Maven's default is daily) -- and offline mode reading only the cache.
- Version ranges: Maven's version order (`GenericVersionScheme`/`ComparableVersion`) and range
  syntax (`[a,b)`, unions, `[a]`), resolved against the merged metadata of every repository,
  in the collector and for parents and imports. Range constraints in nearest-wins selection
  stay the caller's.
- SNAPSHOT: a locally installed `-SNAPSHOT` (`maven-metadata-local.xml`) and a remote
  timestamped one (`<snapshotVersions>`, the base-version directory); `LATEST`/`RELEASE`.
- Oracle: metadata files in the fixture repository and new case files
  (`src/test/resources/am/ik/maven/oracle/MavenOracle.java` with the checksum policy and
  metadata it needs).
