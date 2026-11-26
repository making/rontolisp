# Maven repository resolver (`am.ik.maven`)

**Invariant: `am.ik.maven` answers what Maven 3.9's resolver answers for a dependency's POM --
the descriptor (effective model), the collected graph, the resolved (nearest-wins) graph and
its runtime class path, versions resolved through `maven-metadata.xml` (ranges, SNAPSHOT,
`LATEST`/`RELEASE`) -- measured against Maven itself; an unsupported `settings.xml` feature is
refused by name, never approximated.**
Language-independent: imports `am.ik.artifact` and nothing else of ours (`PackageCycleTest`).
Consumers: `cli/JavaClassPath` (`--java-dep`, `.kb/java-interop.md` "The program's Java class
path"); `eval/ClojureDepsRepositories` (`deps.edn` `:mvn/version`, `.kb/clojure-frontend.md`
"deps.edn"), which reads descriptors and jars one at a time and selects with clj's
newest-wins itself -- never `collect`/`resolve` -- and builds its resolver as `clj` does:
`:mvn/local-repo` else `~/.m2/repository`, `settings.xml` without its `localRepository` and
`offline` (measured: `clj` reads neither; it does read the global file, below).

## API
- The local repository is never guessed: `localRepository(..)`, else
  `MavenSettings.localRepository()`, else `IllegalStateException`. Defaults are clj's
  (`CENTRAL` then `CLOJARS`). `MavenSettings.readGlobalAndUser()` is the caller's explicit
  call (the `ArtifactCache` rule: a test never picks up the developer's settings).
- The resolver instance is Maven's session: a repository is asked for a file or metadata at
  most once per resolver, whatever the policy.
- `descriptor`/`artifact`/`version` refuse a range by name (Maven would look the range up as
  a literal path). An unknown update-policy spelling is an `IllegalArgumentException`, where
  Maven warns and uses `never`. `Builder.updatePolicy` is Maven's SESSION policy (`mvn -U` =
  `always`): when set it replaces every repository's own.
- `projectDependencies(byte[] pom)` (a jar's `META-INF/maven/**/pom.xml`) and
  `project(Path pom, ..)` (a `:pom` project): built as `clj` answers, STRICT ("Validation
  levels"); an invalid POM is a `MavenResolutionException` (no descriptor to fall back to).
  `project` has no project directory: `${basedir}/x` stays literal, `${project.basedir}/x`
  is `x` because the caller passes `project.basedir=.` (`.kb/clojure-frontend.md`,
  "deps.edn"). A parent is read beside the file first (Maven's `readParentLocally`):
  `relativePath` absent -> `../pom.xml`, empty -> none; the range test uses `GenericVersion`
  where Maven uses `DefaultArtifactVersion`. A repository parent's own relatedSource is
  never looked at.

## Effective model (`ModelBuilder`, `ProfileActivator`, `Interpolator`)
Maven's `DefaultModelBuilder` at a validation level ("Validation levels"), no project
directory. Traps measured against Maven:
- Profile activation is interpolated against that POM's OWN raw properties, then user, then
  system: a property only the parent defines leaves `${...}` and the profile inactive.
  `property` activation reads user then system properties, never the POM's. `jdk` uses
  Maven's three-token range compare (`(,25]` excludes 25.0.4).
- User properties are `{packaging: <the requested POM's packaging>}`, for its parents and
  imports too (3.9), shadowing a POM property.
- The super POM is a text block in `ModelBuilder` (no resource for the native image).
- Not inherited: relocation (in a parent or active profile it does not apply, measured) and
  modelVersion (`oracle/superpom.txt`).
- A parent's packaging must be `pom` (ERROR at every level, measured).
- `build` mergers (`BuildMerger`) are a port of the 3.9.16 mergers read from source. Not
  modelled: extensions, plugin dependencies, reporting.
- Interpolation: unresolved stays literal; a cycle invalidates, an unused property cycle
  included. With the super POM `${project.build.directory}` is `${project.basedir}/target`,
  `basedir` unresolved without a project directory (`oracle/superpom.txt`).
- Imports are cached by id AND packaging: Maven's session cache keys by id, so a BOM whose
  profiles test `packaging` answers its first importer's choice; with the packaging a
  descriptor does not depend on what was resolved before it (Maven without a session cache
  agrees).
- Management fills exclusions only when the dependency has none; optional never.
- A parent version range resolves to its highest; resolved to another spelling (`[1.0]`
  included) while the child's raw version is absent or `${project.version}`-like ->
  `Version must be a constant`, FATAL at the next parent read. An IMPORT's version is never
  a range in Maven 3.9: a range import is the fatal `Non-resolvable import POM`
  (`oracle/parent-ranges.txt`).
- Invalid POM, or a POM in no repository -> no dependencies + a warning (Maven's default
  ignore policy). A parent or import in no repository, and a transfer failure, are fatal.

**XML: hand-written (`XmlParser`), not `java.xml`.** Maven's reader is MXParser, which accepts
what a conforming parser must reject: the 248 XHTML named references (`XhtmlEntities`), a
DOCTYPE whose entities never resolve, any root element name, whitespace before the
declaration. Also: no external entity can ever be fetched, no JAXP factory lookup or xerces
in a native image. `PomSchema` checks the 4.0.0 tree as `MavenXpp3Reader` reads it, strict and
lenient (the lenient `nextTag` steps over one text event; `oracle/lenient.txt`). The reader's
unbalanced skip of an unknown element inside a list is not reproduced. `XmlElement` keeps
whether it was `<a/>` (an `Xpp3Dom` value is `null` then, `""` for `<a></a>`).

## Validation levels (`ModelValidator`, `PomSchema`, `ModelProblems`)
`DefaultModelValidator` 3.9.16 ported check by check. Who builds at which: a descriptor and
every import, minimal; `project` and `projectDependencies`, STRICT = 3.0 (a bare
`DefaultModelBuildingRequest`'s, the level `clj`'s answers fit); a parent beside the file at
the request's level, a repository parent at 2.0 at most (measured: duplicate profile ids
refuse a local parent and pass in a repository one).
- Strict-only failure is `Malformed POM <where>: ..` -- an ERROR when the request names the POM
  file, else a WARNING. `clj` answers as a request naming none: its `pom.xml` and a jar's pom
  only warn and a parent beside the file errs (measured, `clj` 1.12.6: `<foo/>` in the
  project accepted, in its `../pom.xml` refused). Neither reader -> fatal `Non-parseable POM`.
- A fatal problem stops the build at the next POM read; an error rejects the model; warnings
  travel only inside a refusal, marked `[WARNING]` (as `clj` prints them). A POM missing
  `modelVersion` reports it twice, raw and effective (measured).
- A `modelVersion` segment that is no number throws `NumberFormatException` out of Maven's
  builder (`clj` crashes); here a fatal problem naming it.
- `checkPluginVersions` warns in Maven's `HashMap` order.
- `PomModel.Ancillary` keeps what only validation reads; the super POM's `central`
  repositories are in, as they collapse a POM's same-id repositories (measured).
- An import's problems join every importer's, also when its contribution is cached.

## Collected graph (`DependencyCollector`)
Maven Resolver's depth-first collector under Maven's session, conflict resolver off:
- A range gives one node per version the metadata lists in it, ascending, each carrying the
  constraint (`[1.0]` prints `[1.0,1.0]`); a union's parts in written order where Maven's is
  hash order, so the oracle and `MavenTestRepository.render` sort them.
- From depth 2 `test`/`provided`/optional are dropped; `system` and unknown scopes are KEPT,
  as Maven keeps them (a kept one can win a conflict, and an unknown scope below a `runtime`
  parent derives `runtime` and reaches the class path). No POM is read for a `system` node.
- The requested management applies from depth 2 (exclusions at every depth), first entry
  wins; a POM's own management only shaped its descriptor (Maven 3's classic manager).
- The same `g:a:ext:classifier` on the path is a cycle node, not expanded -- Maven shares the
  ancestor's children instead, which always lose. `war`/`ear`/`rar`/`par` are not descended
  into. Children are pooled per (artifact, exclusions), as Maven's data pool.

## Resolved graph (`ConflictResolver`, `GenericVersion`)
A PORT of Maven Resolver 1.9's `ConflictMarker` + `ConflictIdSorter` + `ConflictResolver`
(verbosity none) with Maven's selectors and `JavaScopeDeriver` -- not a re-derivation: the
traversal orders, the hash-ordered collections (`Key`'s hash formula, `HashSet` of conflict
ids) and the order-dependent loser removal are Maven's, so ties break as Maven breaks them. A
pooled child list is ONE mutable list under every node that reached it and a cycle node
shares its ancestor's list, so removing a loser from a shared list removes it under every
parent. `NearestVersionSelector`'s ranges ported whole: a range excluding the current winner
backtracks, so `app -> mid -> lib:[1.0,1.5]` plus a direct `lib:2.0` selects 1.5
(`oracle/resolve-ranges*.txt`). Maven reports the collection's warnings with a conflict
failure; the parity test re-collects for them.
- `runtimeClassPath()`: maven-core's `RepositoryUtils.toArtifacts` preorder; `javadoc` type is
  on it (Maven's session says so), `bundle` and other unknown types are not.

## Repositories (`RepositoryAccess`, `MavenSettings`, `UpdatePolicy`, `TrackingFile`)
- A file in the local repository is used as it is: no `.sha1` checked, no
  `_remote.repositories` (Maven treats an untracked file as installed).
- A remote copy is taken only when its `.sha1` matches (Maven's checksum-file parse), written
  by atomic rename. A missing `.sha1`, mismatch or failure moves on, reported only when no
  repository yields the file. `HttpStatusException.isNotFound()` is 404 only, as Maven.
- A 404 is recorded as Maven records it (`FILE.lastUpdated`) and not asked again until the
  update policy says so (Maven CLI's `cacheNotFound`; `clj`'s session does not cache). This
  is what makes a second `deps.edn` run with a missing POM network-free. A transfer failure
  is not recorded (Maven records one but retries anyway).
- Metadata: `maven-metadata-local.xml` first, then each remote's copy cached as
  `maven-metadata-<id>.xml` with Maven's record in `resolver-status.properties`; a file
  without a record is asked again; a 404 deletes the cached copy; a transfer failure keeps the
  cached copy. The records are Maven's format, so `mvn`, `clj` and this resolver honor each
  other's.
- Version resolution: "favor local" -- a `maven-metadata-local.xml` modified within the
  policy spares the remote ones any request, so a snapshot installed today shadows a newer
  deployed build. The jar is looked up only where the metadata named it; a timestamped file is
  copied to its `-SNAPSHOT` name (Maven's snapshot normalization). An unresolvable
  `RELEASE`/`LATEST` gives Maven's first exception.
- Range resolution: equal versions keep Maven's `HashMap` order; never "favor local". Known
  difference: Maven fetches a range-chosen version's jar only from the repository whose
  metadata listed it; `artifact` searches them all.
- **Per-repository policies** (`RemoteRepository.releases()` / `snapshots()`; `deps.edn`'s
  `:releases` / `:snapshots`). `RepositoryRoute.effective` is
  `DefaultRemoteRepositoryManager.getPolicy`. Consulted exactly where Maven consults it
  (measured against Maven 3.9.16 / resolver 1.9.27, `oracle/policy-*.txt`):
  - artifact files: the artifact's own kind; a disabled repository is skipped before the
    offline check.
  - metadata by the nature asked: `RELEASE` -> release, a `-SNAPSHOT`'s -> snapshot,
    `LATEST` and a range -> either. A repository not enabled for the nature is neither asked
    NOR read: a copy it cached earlier is ignored.
  - a range: each REMOTE repository's list keeps a version only when it serves its kind, by
    `ArtifactUtils.isSnapshot` (not `Artifact.isSnapshot`'s pattern); the local list is
    unfiltered.
  - a mirror serves what the repositories it covers serve, policies merged.
  Defaults stay clj's: `CENTRAL` serves snapshots; `--java-dep` declares Central with
  snapshots disabled, as Maven's super POM does (`JavaClassPath.repositories`).
  `deps.edn`: an INTEGER `:update` runs as `never` (measured, clj 1.12.6.1673: no metadata
  request two days after the record, where `:daily` asks) -- not the minutes the reference
  promises; `ClojureDepsEdn.repositoryPolicy` does the same.
- URLs are the layout path percent-encoded; a path leaving the repository's base or the local
  root is never fetched.
- `settings.xml`: `localRepository` and `offline` honored. `readGlobalAndUser()`: the global
  `conf/settings.xml` of `maven.home` else `$MAVEN_HOME` (MIMA's lookup; measured: `clj` with
  `MAVEN_HOME` naming a blocked `*` mirror fails `Blocked mirror for repositories`), merged as
  `MavenSettingsMerger` (3.9.16 bytecode): the user's `offline` alone.
- Routing (`RepositoryRoute.of`): the ROUTE's id/URL (the mirror's) is what is contacted,
  cached and named in messages; the proxy is chosen by the route's URL, the `<server>` by its
  id. A `blocked` mirror or non-`default` mirror layout fails per repository without a request.
- Transport, as resolver-transport-http 1.9.27 is configured (`preemptiveAuth` false,
  ISO-8859-1 credentials, `maxRedirects` 5): server credentials answer a `401` Basic challenge
  for that host:port only, then go preemptively; non-numeric timeouts are refused at parse as
  Maven fails. Proxy credentials are sent preemptively. An https URL through a proxy WITH
  credentials is tunnelled by `ProxyTunnel`: the JDK client drops Basic on `CONNECT` by
  `jdk.http.auth.tunneling.disabledSchemes=Basic`, read once into a static -- a library cannot
  flip it. A 401/403 names the `<server>` (or its absence), a 407 the proxy entry.
- Passwords (`SettingsPasswords`, plexus-sec-dispatcher/plexus-cipher 2.0): fixtures in
  `MavenSettingsTest` are `mvn --encrypt-master-password` / `--encrypt-password` output of
  3.9.16. Undecryptable: kept as written (as `DefaultSettingsDecrypter`) and the reason joins
  the 401 message.
- Profile repositories go AHEAD of the builder's list: measured `mvn -X` printed
  `[profile repositories, project repositories, central]`. Active profiles are injected each
  ahead of what is there (defined pa, pb, pc -> `[pc.., pb.., pa..]` whatever
  `<activeProfiles>` order); a settings profile's `activeByDefault` is NOT retired by an
  activated one (POM-only rule). A repository without `url`/`id` is refused even in an inactive
  profile (Maven's wording); an unreadable URL only for an active one. `deps.edn` does NOT
  honor them: `clj` 1.12.6 with a profile-only repository fails `Could not find artifact ...
  in central`; `ClojureDepsRepositories` builds its `MavenSettings` without profiles.
- POM-declared `<repositories>` are never consulted (`MavenBoundaryTest`).
- Browser: no substitution of its own; a download reaches `Target_HttpDownloader`'s refusal.
  Native image: plain Java, no reflection or resources; a probe answered every oracle case
  byte-identically to the JVM with no configuration (GraalVM 25.0.4, measured before the
  metadata support).

## The oracle
`src/test/resources/am/ik/maven/oracle/MavenOracle.java` (run by hand on a Maven 3.9
distribution's `lib/`; usage in its javadoc) wrote each case file beside it: the request on
the first line, Maven's output after. `MavenOracleParityTest` replays them over the fixture
`src/test/resources/am/ik/maven/repo` (`MavenTestRepository.remote` adds the `.sha1`s), its
local repository seeded from `.../maven/local`, allowing only the differences its javadoc
lists. Also checked against Maven 3.9.16 over Central (ranges in bcpkix, jdt.core,
core.runtime; `RELEASE`/`LATEST`) and over every release POM of a developer `~/.m2`:
identical.

## Tests
`MavenOracleParityTest`, `MavenProjectTest` (each case measured through `clj -Srepro -Spath`
1.12.6), `MavenRepositoryTest` (metadata caching, update policy, not-found records, snapshots,
ranges, mirrors, per-repository policies), `ClojureDepsFetchCliTest` (`:releases` /
`:snapshots`, measured against `clj`), `MavenSettingsTest`, `MavenSettingsTransportTest`,
`HttpDownloaderAccessTest` (challenge, redirect scoping, headers, proxy, the `CONNECT`
tunnel), `MavenBoundaryTest`, `XmlParserTest`, `ArtifactTest`,
`HttpDownloaderTest.theStatusTellsNotFoundFromAFailure`, `JavaClassPathCliTest`. No
automated test reaches the network (`.kb/dists.md`).
