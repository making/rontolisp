# Maven repository resolver (`am.ik.maven`)

**Invariant: `am.ik.maven` answers what Maven 3.9's resolver answers for a dependency's POM --
the descriptor (effective model), the collected graph, the resolved (nearest-wins) graph and
its runtime class path, versions resolved through `maven-metadata.xml` (ranges, SNAPSHOT,
`LATEST`/`RELEASE`) -- measured against Maven itself; an unsupported `settings.xml` feature is
refused by name, never approximated.**
Language-independent: imports `am.ik.artifact` and nothing else of ours (`PackageCycleTest`).
Consumers: `cli/JavaClassPath` (`--java-dep`, `.kb/java-interop.md` "The program's Java class
path"); `eval/ClojureDepsRepositories` (`deps.edn` `:mvn/version`, `.kb/clojure-frontend.md`
"deps.edn"), which reads descriptors and jars one at a time and selects with tools.deps'
newest-wins itself -- never `collect`/`resolve`; `mavenVersion` is `versions` (a range's highest)
or `version` (`RELEASE`/`LATEST`) -- and builds its resolver as `clj` does:
`:mvn/local-repo` else `~/.m2/repository`, `settings.xml` without its `localRepository` and
`offline` (measured: `clj` reads neither; it does read the global file, below).

## API
- `MavenResolver.builder()`: the local repository is never guessed -- `localRepository(..)`,
  else `MavenSettings.localRepository()`, else `IllegalStateException`
  (`defaultLocalRepository()` is `~/.m2/repository` for a caller that wants it). Defaults:
  `RemoteRepository.CENTRAL` (`https://repo1.maven.org/maven2/`, clj's id and URL) then
  `CLOJARS`; `HttpDownloader`; `MavenSettings.none()`; the JVM's system properties.
  `MavenSettings.readGlobalAndUser()` is the caller's explicit call (the `ArtifactCache` rule:
  a test never picks up the developer's settings).
- `descriptor(Artifact)`, `collect(deps, managed)` (every version seen; selection is the
  caller's), `resolve(deps, managed)` (Maven's selection, below), `artifact(Artifact)` (local
  path), `version(Artifact)` (Maven's `VersionResolver`), `versions(Artifact)` (Maven's
  `VersionRangeResolver`); `DependencyGraph.runtimeClassPath()` of a resolved graph. Public
  methods hold the instance lock, and the instance is Maven's session: a repository is asked
  for a file or metadata at most once per resolver, whatever the policy.
- `descriptor`/`artifact`/`version` refuse a range by name (it names no single artifact; Maven
  would look the range up as a literal path). `RepositoryPolicy.update` and
  `Builder.updatePolicy` take Maven's spellings (`daily` default, `always`, `never`,
  `interval:N`; anything else an `IllegalArgumentException`, where Maven warns and uses
  `never`). `Builder.updatePolicy` is Maven's SESSION policy (`mvn -U` = `always`): when set
  it replaces every repository's own; unset (default), each repository's policy for the kind
  asked applies ("Repositories", below).
- `Artifact.path()` puts a timestamped snapshot in its base version's directory
  (`1.0-SNAPSHOT/lib-1.0-20240101.123456-1.jar`); `baseVersion()`, `isSnapshot()`,
  `isVersionRange()` are `DefaultArtifact`'s.
- `projectDependencies(byte[] pom)`: a POM given as bytes (a jar's `META-INF/maven/**/pom.xml`)
  built like a repository POM (`ModelBuilder.effective(byte[])`, parents and imports from the
  repositories -- Maven's `UrlModelSource` has no relativePath parent either), its
  dependencies as the MODEL holds them: the classifier written (a `test-jar` type implies
  none here, `tests` in a descriptor), the type's extension. An invalid POM is a
  `MavenResolutionException` here (no descriptor to fall back to). What tools.deps'
  `coord-deps :jar` reads (`model-dep->data`).

## Effective model (`ModelBuilder`, `ProfileActivator`, `Interpolator`)
Maven's `DefaultModelBuilder` at validation level minimal, no project directory:
- Per POM of the parent chain: duplicate dependencies collapse (last declaration, first
  position); profile activation is interpolated against that POM's OWN raw properties, then
  user, then system properties -- a property only the parent defines leaves `${...}` and the
  profile inactive (measured). Conditions, all present ones ANDed: `jdk` (prefix, `!`,
  Maven's three-token range compare: `(,25]` excludes 25.0.4), `os` (family per Maven's
  `Os.isFamily`, `unix` by path separator; name/arch lowercased; version `regex:`),
  `property` (user then system properties, never the POM's; an empty value is absent),
  `file` (absolute paths only, `${basedir}` never). `activeByDefault` only when no profile
  of the same POM is active otherwise.
- User properties are `{packaging: <the requested POM's packaging>}`, for its parents and
  imports too (3.9): profiles and `${packaging}` see it, and it shadows a POM property.
- Inheritance by management key, the child's entry whole: the child's dependencies first,
  then each ancestor's. Not inherited: artifactId, packaging, name, profiles, modules,
  relocation (a relocation in a parent or an active profile does not apply, measured).
- Interpolation (`StringSearchInterpolator`'s semantics): `project.*`/`pom.*` reflection,
  user, model, system properties, `env.X` as system property `env.X`, unprefixed reflection;
  unresolved stays literal; a cycle (`project.`/`pom.` trimmed) invalidates, an unused
  property cycle included. Reflection covers groupId, artifactId, version, packaging, name,
  description, modelVersion, parent.{groupId,artifactId,version}; `${project.build.*}` is
  not modelled (Maven: the super POM's `${project.basedir}/target`, a path no coordinate
  uses; `MavenBoundaryTest`).
- `import`-scoped `pom` entries after interpolation: removed, own entries first, then each
  import in order, first entry of a key wins. Cached by id AND packaging: Maven's session
  cache keys by id, so a BOM whose profiles test `packaging` answers its first importer's
  choice; with the packaging a descriptor does not depend on what was resolved before it
  (Maven without a session cache agrees).
- Management fills version, scope, systemPath; exclusions only when the dependency has
  none; optional never. Then scope defaults to `compile` and the minimal validation runs.
- A parent's version goes through `versionRange` (`DefaultModelResolver.resolveModel(Parent)`):
  the highest version, none -> fatal `No versions matched the requested parent version range`,
  an open upper bound -> fatal `does not specify an upper bound`. Resolved to another spelling
  (any range, `[1.0]` included) while the child's raw version is absent or `${project.version}`
  / `${pom.version}` / `${project.parent.version}` / `${pom.parent.version}` -> `Version must
  be a constant`, FATAL: invalid at the next parent read, else at the end. An IMPORT's version
  is never a range in Maven 3.9 (`resolveModel(g, a, v)`, the literal path): a range import is
  the fatal `Non-resolvable import POM`, answered without the request. SNAPSHOT/`LATEST`/
  `RELEASE` parents and imports resolve through `read` (`oracle/parent-ranges.txt`).
- Invalid (parse error, failed validation, cycle) -> no dependencies + a warning, and a POM
  in no repository -> the same: Maven's default ignore-invalid/ignore-missing policy. A
  parent or import in no repository, and a transfer failure, are fatal.

**XML: hand-written (`XmlParser`), not `java.xml`.** Maven's reader is MXParser, which accepts
what a conforming parser must reject: the 248 XHTML named references (plexus-utils 3.6.1's
default map, `XhtmlEntities`), a DOCTYPE whose entities never resolve, any root element name,
whitespace before the declaration. Also: no external entity can ever be fetched, no JAXP
factory lookup or xerces in a native image, nothing for the web profile to substitute.
Measured 2026-10-08: no POM of a developer `~/.m2` (1552) uses an undeclared entity outside
CDATA, so the table is parity, not a hot path. `PomReader` refuses what the lenient reader
refuses in the sections it reads (a known field twice, text in a structure, an element in a
value); the others (`build`, `reporting`, ...) are skipped unvalidated, and the reader's
unbalanced skip of an unknown element inside a list is not reproduced.

## Collected graph (`DependencyCollector`)
Maven Resolver's depth-first collector under Maven's session, conflict resolver off:
the managed version goes through `versionRange` -- a plain version is itself, a range one node
per version the metadata lists in it, ascending, each carrying the range
(`DependencyNode.versionRange`, printed as Maven's constraint: `[1.0]` is `[1.0,1.0]`, `[1.*]`
`[1.min,1.max]`, a union's parts in written order where Maven's is hash order, so the oracle and
`MavenTestRepository.render` sort them); none -> `No versions available for X within specified
range`. A relocation inside a range ends that range's loop (Maven's `return`); a cycle node
continues it. The node's artifact keeps `LATEST`/`RELEASE`/`-SNAPSHOT` as written (Maven's
descriptor keeps the request's version); its POM is the resolved one's. Selection runs before
management; from depth 2 a `test`/`provided` one is dropped and so is an
optional one -- `system` and unknown scopes are KEPT, as Maven keeps them (until 2026-10-08
they were dropped and the parity test filtered Maven's answer; a kept one can win a conflict,
and an unknown scope below a `runtime` parent derives `runtime` and reaches the class path).
No POM is read for a `system` node (Maven's `isLackingDescriptor`: its file is its
`systemPath`; condition used here: the managed scope is `system`). Exclusions accumulate
down the path. The requested
management applies from depth 2 (exclusions at every depth), first entry wins; a POM's own
management only shaped its descriptor (Maven 3's classic manager). A relocation is
selected and managed again under its target (a version-only relocation keeps its version).
The same `g:a:ext:classifier` (any version) on the path is a cycle node, not expanded --
Maven shares the ancestor's children instead, which always lose. `war`/`ear`/`rar`/`par`
are not descended into. Children are pooled per (artifact, exclusions), as Maven's data pool.

## Resolved graph (`ConflictResolver`, `GenericVersion`)
A PORT of Maven Resolver 1.9's `ConflictMarker` + `ConflictIdSorter` + `ConflictResolver`
(verbosity none) with Maven's `NearestVersionSelector`, `JavaScopeSelector`,
`SimpleOptionalitySelector`, `JavaScopeDeriver` -- not a re-derivation: the traversal orders,
the hash-ordered collections (`Key`'s hash formula, `HashSet` of conflict ids) and the
order-dependent loser removal are Maven's, so ties break as Maven breaks them. The collected
records are rebuilt as Maven holds them: a pooled child list is ONE mutable list under every
node that reached it (identity of the record's list), a cycle node shares its ancestor's list
(the collector emits it childless), so removing a loser from a shared list removes it under
every parent. Sibling conflicts (two children of one list) compare by `GenericVersion`
(Resolver's `GenericVersionScheme`). `DependencyNode.premanagedOptional` is Maven's
`MANAGED_OPTIONAL` bit (`premanagedScope != null` is `MANAGED_SCOPE`); both stop the scope /
optional derivation. `NearestVersionSelector`'s ranges ported whole: each range met joins the
group's constraints, only a version all of them contain is a candidate, and a range excluding
the current winner backtracks to the nearest admitted candidate -- so `app -> mid -> lib:[1.0,1.5]`
plus a direct `lib:2.0` selects 1.5 (`oracle/resolve-ranges*.txt`). No admitted candidate ->
`Could not resolve version conflict among [paths]` (each node with its constraint). Maven
reports the collection's warnings with that failure; the parity test re-collects for them.
- `runtimeClassPath()`: maven-core's `RepositoryUtils.toArtifacts` preorder, each artifact
  once, kept when the scope is `compile`/`runtime` and the type constitutes a build path
  (`ArtifactTypes.addedToClassPath`: jar, test-jar, maven-plugin, ejb, ejb-client and --
  Maven's session says so -- `javadoc`; never pom, java-source, war/ear/rar/par or an unknown
  type such as `bundle`).
- Oracle modes `resolve` and `classpath` (the latter types requested roots through the
  session's registry, as maven-core types a project's dependencies; re-measuring every
  existing case after that change altered none). Measured 2026-10-08 against Maven 3.9.16
  over a developer `~/.m2` (905 release jars, each resolved on its own): identical trees
  (7,933 lines, 5,836 below a root), identical class paths (6,741 entries), and an identical
  collect after the scope change (115,348 lines).

## Repositories (`RepositoryAccess`, `MavenSettings`, `UpdatePolicy`, `TrackingFile`)
- A file in the local repository is used as it is, whoever put it there: no `.sha1` checked,
  no `_remote.repositories` read or written (Maven treats an untracked file as installed).
- Else each remote repository in order: a 404 moves on; a copy is taken only when its
  `.sha1` matches (Maven's checksum-file parse: first non-blank line, after the last space in
  `ALG(file)= hex`, else before the first space), then written with its `.sha1`, each by an
  atomic rename. A missing `.sha1`, a mismatch or a failure moves on too, and is reported
  when no repository yields the file. `HttpDownloader` throws `HttpStatusException` (status;
  `isNotFound()` is 404 only, as Maven) -- `DistClient`'s messages are unchanged.
- A 404 is recorded as Maven records it, `FILE.lastUpdated` (`<repo url>/.error=` empty,
  `.lastUpdated=<millis>`), and that repository is not asked again until the update policy says
  so (Maven CLI's `cacheNotFound`; `clj`'s session does not cache, it asks every run). A
  transfer failure is not recorded (Maven records one but, by default, retries anyway). The
  record goes once the file is fetched and no other repository's `.error` remains. This is what
  makes a second `deps.edn` run with a missing POM network-free.
- `maven-metadata.xml`: the local repository's `maven-metadata-local.xml` (what `mvn install`
  writes) first, then each remote repository's copy cached as `maven-metadata-<id>.xml` (+
  `.sha1`, verified like any file), with Maven's record in `resolver-status.properties`
  (`maven-metadata-<id>.xml.lastUpdated`, `.error=` empty for a 404). Fetched again when no
  record says otherwise or the policy has elapsed (`DefaultUpdateCheckManager.checkMetadata`:
  a file without a record is "unknown", asked again); a 404 deletes the cached copy and is
  remembered; a transfer failure keeps reading the cached copy. Offline (settings.xml), only
  cached copies are read. The records are Java `Properties` in Maven's format, so `mvn`, `clj`
  and this resolver honor each other's.
- Version resolution (`DefaultVersionResolver`): `RELEASE` / `LATEST` (else the release; a
  snapshot latest resolved further against the one repository that named it) from
  `g/a/maven-metadata*.xml`, a `-SNAPSHOT` from `g/a/V/maven-metadata*.xml`
  (`<snapshotVersions>` keyed `classifier:extension`, else the old `<snapshot>` timestamp and
  build number, else the version itself), the newest timestamp across repositories winning.
  "Favor local": a `maven-metadata-local.xml` modified within the policy spares the remote
  ones any request -- a snapshot installed today shadows a newer deployed build. The jar is
  then looked up only where the metadata named it (a local origin: the local file or nothing),
  and a timestamped file is copied to its `-SNAPSHOT` name (size or time differ), which
  `artifact` answers (Maven's snapshot normalization). An unresolvable `RELEASE`/`LATEST`:
  `Failed to resolve version for X: Could not find metadata g:a/maven-metadata.xml in local
  (...)`, Maven's first exception.
- Range resolution (`DefaultVersionRangeResolver`): every version any of those files lists
  (local first, first origin kept, a `HashMap` as Maven's so equal versions keep its order),
  filtered by the constraint, sorted by `GenericVersion`; `[1.0]` and a plain version need no
  metadata. Never "favor local". Known difference: Maven fetches a range-chosen version's jar
  only from the repository whose metadata listed it (`getRemoteRepositories`); `artifact`
  searches them all.
- Update policy: Maven's `DefaultUpdatePolicyAnalyzer` (`daily` = local midnight).
- **Per-repository policies** (`RemoteRepository.releases()` / `snapshots()`, each a
  `RepositoryPolicy(enabled, update)`, default enabled + `daily`; tools.deps' `:releases` /
  `:snapshots`). `RepositoryRoute.effective(releases, snapshots, override)` is
  `DefaultRemoteRepositoryManager.getPolicy`: policy1 = the snapshot policy when snapshots
  are wanted, policy2 = the release policy unless releases are wanted; a disabled one gives
  way to the other, two enabled ones update as often as the more frequent (`always` 0,
  `interval:N` N, `daily` 1440, `never` MAX), the session override replacing the update.
  Consulted exactly where Maven consults it (measured 2026-10-08 against Maven 3.9.16 /
  resolver 1.9.27 with a probe over a `file:` repository and a listener on the downloads,
  and the oracle with `--no-releases` / `--no-snapshots`, `oracle/policy-*.txt`):
  - artifact files (`DefaultArtifactResolver`): the artifact's own kind (`isSnapshot`, a
    timestamped build included); a disabled repository is skipped before the offline check.
  - metadata (`DefaultMetadataResolver.getEnabledSourceRepositories`) by the nature asked:
    `RELEASE` -> release policy, a `-SNAPSHOT`'s own `maven-metadata.xml` -> snapshot policy,
    `LATEST` and a range's `g/a/maven-metadata.xml` -> either (enabled when one is, the
    more frequent update). A repository not enabled for the nature is neither asked NOR
    read: a copy it cached earlier is ignored, and no exception names it.
  - a range (`DefaultVersionRangeResolver.filterVersionsByRepositoryType`): each REMOTE
    repository's list keeps a version only when that repository serves its kind, by
    `ArtifactUtils.isSnapshot` (case-blind `SNAPSHOT` suffix, `^(.*)-\d{8}\.\d{6}-\d+$`: not
    `Artifact.isSnapshot`'s pattern); the local repository's list is unfiltered.
  - a mirror serves what the repositories it covers serve (`mergeMirrors`: policies merged as
    above, the first dominant).
  Defaults stay clj's: `CENTRAL` serves snapshots (tools.deps' `standard-repos` set no
  policy); `--java-dep` declares Central with snapshots disabled, as Maven's super POM does,
  so a SNAPSHOT is never asked of it (`JavaClassPath.repositories`).
  tools.deps `repo-policy` (clj 1.12.6.1673, `:update` read from its source and measured):
  `:enabled` defaults true, `:update` `:daily`; an INTEGER `:update` is passed on as
  `(str update)`, the string `"5"`, which Maven reads as an unknown policy and runs as `never`
  (measured: no metadata request two days after the record, where `:daily` asks) -- not the
  minutes its docstring promises; `ClojureDepsEdn.repositoryPolicy` does the same.
- `file:` repositories are read in place. URLs are the layout path percent-encoded
  (Resolver's `new URI(null, null, path, null)`); a path leaving the repository's base or
  the local root is never fetched.
- `settings.xml` (`MavenSettings`; `${...}` and `${env.X}` expanded): `localRepository` and
  `offline` honored. `readGlobalAndUser()`: the global `conf/settings.xml` of `maven.home`
  (system property) else `$MAVEN_HOME` -- MIMA's lookup; measured 2026-10-08, `clj` 1.12.6
  with `MAVEN_HOME` naming a `conf/settings.xml` holding a blocked `*` mirror fails with
  `Blocked mirror for repositories: [central (...), clojars (...)]` -- merged under the
  user's as `MavenSettingsMerger` (3.9.16 bytecode): user's `localRepository` else global's,
  the user's `offline` alone, mirrors/proxies/servers user's first then each global id the
  user's lack.
- Routing (`RepositoryRoute.of`, `DefaultRemoteRepositoryManager.aggregateRepositories`):
  each repository through its mirror (`mirrorOf`, then `mirrorOfLayouts` against the
  `default` layout), the repositories one mirror covers merged into one route where the
  first stood, a later repository reusing an id dropped. The ROUTE's id/URL is what is
  contacted, cached (`maven-metadata-<mirror id>.xml`, tracking keys) and named in messages;
  the proxy is chosen for the route's URL, the `<server>` by the route's id. A `blocked`
  mirror or a non-`default` mirror layout is a per-repository failure without a request
  (Maven's `NoRepositoryConnectorException`, `Blocked mirror for repositories: [...]`); a
  mirror URL that is not http/https/file is refused by name when routes are first built.
- Transport (`am.ik.artifact.HttpAccess` from the route; `HttpDownloader`), as resolver-
  transport-http 1.9.27 is configured (its constants: `preemptiveAuth` false,
  `credentialEncoding` ISO-8859-1, `maxRedirects` 5): server credentials answer a `401`
  Basic challenge for the requested URL's host:port only, then go preemptively to that host
  (the auth cache); a challenge with no Basic scheme fails by name; a missing password is
  Apache's `user:null`. `httpHeaders` go with every request, redirects included;
  `connectTimeout` / `requestTimeout` (ms; Maven 3's `httpConfiguration/all/
  connectionTimeout`/`readTimeout` as fallback, non-numeric refused at parse as Maven
  fails) become the connect / idle timeouts; other `<configuration>` children are ignored, as
  the HTTP transport ignores them. Proxy credentials are sent preemptively. An https URL
  through a proxy WITH credentials is tunnelled by `ProxyTunnel` (own `CONNECT` + TLS +
  HTTP/1.1): the JDK client drops Basic on `CONNECT` by `jdk.http.auth.tunneling.
  disabledSchemes=Basic` (`conf/net.properties`), read once into a static -- a library
  cannot flip it. A 401/403 names the `<server>` (or its absence), a 407 the proxy entry.
- Passwords (`SettingsPasswords`, plexus-sec-dispatcher/plexus-cipher 2.0): `{...}` anywhere in
  the value (plexus' `ENCRYPTED_STRING_PATTERN`) is AES/CBC with key+IV = SHA-256(passphrase
  + 8-byte salt); the master from `settings.security` (system property) else
  `~/.m2/settings-security.xml`, `<relocation>` followed (a cycle refused), itself encrypted
  with `settings.security`. Fixtures in `MavenSettingsTest` are `mvn --encrypt-master-password`
  / `--encrypt-password` output of 3.9.16. Undecryptable: kept as written (Maven's
  `DefaultSettingsDecrypter` does the same) and the reason joins the 401 message.
- Not read: `settings.xml` `<profiles>` / `<activeProfiles>` (their `<repositories>`; e67).
- POM-declared `<repositories>` are never consulted (`MavenBoundaryTest`).
- Browser: no substitution of its own; a download reaches `Target_HttpDownloader`'s
  refusal (`get(String, HttpAccess)`, where every download converges). Native image: plain Java, no reflection or resources -- a `native-image` build of
  a probe over the library answered every oracle case byte-identically to the JVM
  (2026-10-08, GraalVM 25.0.4, no configuration; before the metadata support, which adds
  only `java.util.Properties` and `Calendar` -- not re-measured).

## The oracle
`src/test/resources/am/ik/maven/oracle/MavenOracle.java` (run by hand on a Maven 3.9
distribution's `lib/`; usage in its javadoc) wrote each case file beside it: the request on
the first line, Maven's output after. `MavenOracleParityTest` replays them over the fixture
`src/test/resources/am/ik/maven/repo` (POMs and `maven-metadata.xml` files;
`MavenTestRepository.remote` adds the `.sha1`s), its local repository seeded from
`.../maven/local` (what `mvn install` leaves: `maven-metadata-local.xml` + files; the oracle
copies the same, so both see them modified today), allowing only the differences its javadoc
lists. A node from a range prints ` range=<constraint>` after its dependency.
Measured 2026-10-08, Maven 3.9.16 against Central (the oracle with the HTTP transporter, a
fresh local repository each side): `resolve`/`classpath` of `org.bouncycastle:bcpkix-jdk18on:1.81`
(`bcutil-jdk18on:[1.81,1.82)` -> 1.81.1), `org.eclipse.jdt:org.eclipse.jdt.core:3.33.0`,
`org.eclipse.platform:org.eclipse.core.runtime:3.26.100` (ranges throughout), and
`collect junit:junit:RELEASE org.slf4j:slf4j-api:LATEST` identical; `collect` of
`org.eclipse.core.runtime:3.26.100` identical over 5,926 lines. Earlier, 2026-10-08 against Maven
3.9.16 (resolver 1.9.27), java.version 25.0.4 on Linux amd64, beyond the fixture: all 1549
release POMs of a developer `~/.m2/repository` gave identical descriptors (119,110 lines:
6,639 dependencies, 110,922 managed), and one collect rooted at its 911 jars an identical
114,312-line graph and the same 100 missing-POM warnings (`bcutil-jdk18on` excluded: ranges
were refused then).

## Tests
`MavenOracleParityTest`, `MavenRepositoryTest` (metadata caching, update policy, not-found
records, snapshots, `LATEST`/`RELEASE`, ranges across repositories, mirror routing, blocked
mirrors, the access a route carries, per-repository release/snapshot policies;
`oracle/policy-*.txt` are the same measured against Maven), `ClojureDepsFetchCliTest`
(`:releases` / `:snapshots` of a `deps.edn` repository, measured against `clj`), `MavenSettingsTest` (parsing, decryption, global merge),
`MavenSettingsTransportTest` (mirror behind an authenticating proxy over `HttpDownloader` and
a local `HttpServer`), `HttpDownloaderAccessTest` (challenge, redirect scoping, headers, http
proxy, the TLS tunnel through a `CONNECT` proxy with a keytool certificate), `MavenBoundaryTest`,
`XmlParserTest`, `ArtifactTest`, `HttpDownloaderTest.theStatusTellsNotFoundFromAFailure`,
`JavaClassPathCliTest` (the CLI over a `file:` fixture repository). No
automated test reaches the network (`.kb/dists.md`).
