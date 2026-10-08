# e39. deps.edn `:mvn/version` and `:git/url` coordinates

Difficulty: High

Depends on `e37` (graph, source path), `e36` (git fetcher), `e35` (Java jars at run time);
the Maven resolver is `am.ik.maven` (`.kb/maven-resolver.md`).

## Gaps

1. `:mvn/version`: each lib's `MavenResolver.descriptor` is its dependency list (keep
   `compile`/`runtime`, drop optional -- what tools.deps reads from the same Maven descriptor
   reader), merged into `e37`'s graph under tools.deps' newest-wins selection; jars from
   `MavenResolver.artifact`. Repositories from `:mvn/repos`, defaults Central + Clojars
   (`RemoteRepository.CENTRAL`/`CLOJARS` carry clj's ids and URLs); `:mvn/local-repo` is the
   builder's local repository.
2. `:git/url` + `:git/sha` (+ `:git/tag`, `:deps/root`, inferred `io.github.*` URLs):
   fetch through `e36`; transitive deps from the checkout's `deps.edn`. A `pom.xml` or
   `project.clj` manifest (`:deps/manifest`): read or refuse by name; decide.
3. What a jar contributes: `.clj`/`.cljc` sources to the source path; AOT `.class` files of
   Clojure namespaces are ignored (the source is what lowers); a source-less Clojure jar is
   refused by name when a namespace is required from it. Java classes go to `e35`'s class
   path (interpreter, JVM); on wasm a required Java class keeps today's call-time refusal.
4. `data_readers.clj`/`.cljc` at a jar root: honored or refused; decide with `e40`.
5. Offline and reproducible: a resolved graph is cached per `deps.edn` content (the
   `.cpcache` idea) so a second compile needs no network; the browser refuses by name.

## Plan

1. Read `.kb/clojure-frontend.md`, `.kb/dists.md`, `.kb/maven-resolver.md`, the `e36` kb
   file.
2. Fixture repositories on disk; the network check stays manual (`.kb/dists.md`
   convention).
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja`.
