# l57. servlet-httpserver: a repository of its own

Difficulty: Medium

On hold (user, 2026-11-23: "APIが安定するまでl57は保留"): not before servlet-httpserver's API is
stable.

Split from l07 (2026-11-23). The user decided the move (k96); it needs the user's hand:
creating the GitHub repository and letting the Vault role that holds the Maven Central
credentials accept the new repository's workflow (`.kb/session-workflow.md`, "The deploy
job"). Ask before starting.

What moves and what is left behind:

- The directory `servlet-httpserver/`, with its history (`git subtree split` or
  `git filter-repo --path servlet-httpserver/`), its `<scm>`, `<url>` and the `<developers>`
  of a repository of its own; coordinates and package stay (`am.ik.servlet`,
  `am.ik.servlet.httpserver`).
- `.github/workflows/servlet-httpserver.yaml` (the test and the SNAPSHOT deploy), there.
- `.kb/servlet-httpserver.md`: what is the container's goes with it as its own notes; what
  rontolisp needs of it ("What the rontolisp side needs", the sizes, the examples) stays.
- What reads the directory from this repository, each to read the published sources jar or
  a checkout of the new repository instead: `src/test/resources/java-http-server-spec.yaml`
  (`sourcePath: servlet-httpserver/src/main/java`), `examples/examples.yaml` and
  `ExamplesE2eTest` / `ExampleProject` (a module of this repository by its artifact id),
  `servlet-httpserver-keyvalue/pom.xml`, `ci.yaml`, the `servlet-httpserver/` prefix of
  `PathCitationTest`, the docs' README links (`doc/*/java/servlet.md`).
- `servlet-httpserver-keyvalue/` stays (it depends on `rontolisp-java-api`).

The legs that read the module as source need a SNAPSHOT of the new repository published
first, or a pinned commit of it; decide which with the user.
