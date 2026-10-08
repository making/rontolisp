# e51. `--java-dep` resolves from Maven Central only

Difficulty: Low

`cli/JavaClassPath` resolves `--java-dep` coordinates against `RemoteRepository.CENTRAL` alone
(`.kb/java-interop.md`, "The program's Java class path"). A library published only to Clojars,
a company repository or a `file:` repository cannot be named; the workaround is downloading the
jar and passing it as `--java-classpath`, which loses its transitive dependencies and the pom
entry.

## Scope

- A repeatable `--java-repository [ID=]URL` (Maven's project `<repositories>`), searched after
  Central in the order given; `MavenResolver.builder().repositories(...)` already takes the list.
  Decide whether Central stays first or the flag replaces it.
- An id names the `settings.xml` `<server>` (credentials are `e48`'s) and the mirror match
  (`mirrorOf` by id is `e48`'s too); until `e48` lands, a covered repository is refused by name
  as Central is.
- Tests over a `file:` repository (`JavaClassPathCliTest.fixtureResolver` builds one); docs in
  the interop guide's "Java libraries", `doc/en` + `doc/ja`.
