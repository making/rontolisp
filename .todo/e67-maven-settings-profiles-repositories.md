# e67. Maven resolver: settings.xml profiles' repositories

Difficulty: Low

`MavenSettings` reads no `<profiles>` / `<activeProfiles>` (`.kb/maven-resolver.md`,
"Repositories"). `mvn` adds the `<repositories>` of every active settings profile
(`<activeProfiles>`, `<activation>`) to a project's, so a corporate `settings.xml` that names its
repository manager in a profile instead of a mirror resolves under `mvn` and not under
`--java-dep`.

- `--java-dep`: Central plus the active profiles' repositories, in Maven's order
  (`DefaultMaven` / `MavenRepositorySystem` injection; profile activation as
  `ProfileActivator` already does for POMs, with settings' own activation fields).
- `deps.edn`: measure first whether `clj` adds them. MIMA's `StandaloneRuntimeSupport` builds
  the context's remote repositories from active settings profiles, but tools.deps resolves
  against its own `remote-repos` list; one `clj -Spath` with a profile-only repository answers
  it.
- Tests: `MavenSettingsTest` (parsing, activation), `MavenRepositoryTest` (a profile repository
  searched after Central).
