# Maven repository resolver (`am.ik.maven`)

**Invariant: `am.ik.maven` answers what Maven 3.9's resolver answers for a dependency's POM --
the descriptor (effective model) and the collected graph -- measured against Maven itself;
what needs `maven-metadata.xml` or an unsupported `settings.xml` feature is refused by name,
never approximated.** Language-independent: imports `am.ik.artifact` and nothing else of
ours (`PackageCycleTest`). No consumer yet: `deps.edn` `:mvn/version` (tools.deps
newest-wins) and Java libraries for CL programs (Maven nearest-wins) select over its graph.

## API
- `MavenResolver.builder()`: the local repository is never guessed -- `localRepository(..)`,
  else `MavenSettings.localRepository()`, else `IllegalStateException`
  (`defaultLocalRepository()` is `~/.m2/repository` for a caller that wants it). Defaults:
  `RemoteRepository.CENTRAL` (`https://repo1.maven.org/maven2/`, clj's id and URL) then
  `CLOJARS`; `HttpDownloader`; `MavenSettings.none()`; the JVM's system properties.
  `MavenSettings.readUserSettings()` is the caller's explicit call (the `ArtifactCache` rule:
  a test never picks up the developer's settings).
- `descriptor(Artifact)`, `collect(deps, managed)` (every version seen; selection is the
  caller's), `artifact(Artifact)` (local path). Public methods hold the instance lock.

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
selection runs before management; from depth 2 only `compile`/`runtime` are kept (Maven keeps
`system` and unknown scopes, which no repository class path uses; tools.deps drops them as
here) and optional ones dropped; exclusions accumulate down the path. The requested
management applies from depth 2 (exclusions at every depth), first entry wins; a POM's own
management only shaped its descriptor (Maven 3's classic manager). A relocation is
selected and managed again under its target (a version-only relocation keeps its version).
The same `g:a:ext:classifier` (any version) on the path is a cycle node, not expanded --
Maven shares the ancestor's children instead, which always lose. `war`/`ear`/`rar`/`par`
are not descended into. Children are pooled per (artifact, exclusions), as Maven's data pool.

## Repositories (`RepositoryAccess`, `MavenSettings`)
- A file in the local repository is used as it is, whoever put it there: no `.sha1` checked,
  no `_remote.repositories` read or written (Maven treats an untracked file as installed).
- Else each remote repository in order: a 404 moves on; a copy is taken only when its
  `.sha1` matches (Maven's checksum-file parse: first non-blank line, after the last space in
  `ALG(file)= hex`, else before the first space), then written with its `.sha1`, each by an
  atomic rename. A missing `.sha1`, a mismatch or a failure moves on too, and is reported
  when no repository yields the file. `HttpDownloader` throws `HttpStatusException` (status;
  `isNotFound()` is 404 only, as Maven) -- `DistClient`'s messages are unchanged.
- `file:` repositories are read in place. URLs are the layout path percent-encoded
  (Resolver's `new URI(null, null, path, null)`); a path leaving the repository's base or
  the local root is never fetched.
- `settings.xml`: `localRepository` (`${...}` and `${env.X}` expanded) and `offline` honored.
  A mirror (Maven's `mirrorOf` matching) or active proxy (per protocol, https falling back
  to an http proxy, `nonProxyHosts`) covering a repository about to be contacted is refused
  by name. Credentials are never sent; a 401/403 names the `<server>` entry. The global
  `$MAVEN_HOME/conf/settings.xml` is not read.
- Refused by name: SNAPSHOT (timestamped too), version ranges, `LATEST`/`RELEASE` -- at
  `descriptor`, `artifact`, a parent, an import, a collected node. Measured on a developer
  `~/.m2`: `org.bouncycastle:bcpkix-jdk18on:1.81` depends on `bcutil-jdk18on:[1.81,1.82)`.
- POM-declared `<repositories>` are never consulted (`MavenBoundaryTest`).
- Browser: no substitution of its own; a download reaches `Target_HttpDownloader`'s
  refusal. Native image: plain Java, no reflection or resources -- a `native-image` build of
  a probe over the library answered every oracle case byte-identically to the JVM
  (2026-10-08, GraalVM 25.0.4, no configuration).

## The oracle
`src/test/resources/am/ik/maven/oracle/MavenOracle.java` (run by hand on a Maven 3.9
distribution's `lib/`; usage in its javadoc) wrote each case file beside it: the request on
the first line, Maven's output after. `MavenOracleParityTest` replays them over the fixture
`src/test/resources/am/ik/maven/repo` (POMs only; `MavenTestRepository.remote` adds the
`.sha1`s), allowing only the differences its javadoc lists. Measured 2026-10-08 against Maven
3.9.16 (resolver 1.9.27), java.version 25.0.4 on Linux amd64, beyond the fixture: all 1549
release POMs of a developer `~/.m2/repository` gave identical descriptors (119,110 lines:
6,639 dependencies, 110,922 managed), and one collect rooted at its 911 jars an identical
114,312-line graph and the same 100 missing-POM warnings (`bcutil-jdk18on` excluded, both
refuse its range).

## Tests
`MavenOracleParityTest`, `MavenRepositoryTest`, `MavenSettingsTest`, `MavenBoundaryTest`,
`XmlParserTest`, `ArtifactTest`, `HttpDownloaderTest.theStatusTellsNotFoundFromAFailure`. No
automated test reaches the network (`.kb/dists.md`).
