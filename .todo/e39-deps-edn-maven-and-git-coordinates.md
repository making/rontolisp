# e39. deps.edn `:mvn/version` and `:git/url` coordinates

Difficulty: High

Depends on `e37` (graph, source path), `e34` (Maven resolver), `e36` (git fetcher), `e35`
(Java jars at run time).

## Gaps

1. `:mvn/version`: resolve through `e34`; transitive deps from the POM, merged into `e37`'s
   graph under tools.deps' newest-wins selection. Repositories from `:mvn/repos`, defaults
   Central + Clojars.
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

## Where e37 left it (`.kb/clojure-frontend.md`, "deps.edn")

- The selection is complete (`ClojureDepsGraph`, pinned against the oracle on Maven graphs
  through `ClojureDepsGraphTest.FakeRepository`); what is missing is the procurer:
  `ClojureDepsProcurer.children`/`contribution` answer nothing for a non-built-in Maven
  coordinate and for every git one (`Contribution.unread`), `compareGit` keeps the first
  commit, and `canonicalGit` skips the checks that need the repository (a tag's existence,
  a short sha with a tag). A `:local/root` jar's own `pom.xml` and a `:pom` manifest are
  unread the same way: gap 2's decision covers them too.
- A jar is already read in place (`SourceLoader.listArchive`), its AOT-only namespace
  refused by name (gap 3's refusal), so a fetched jar needs only its path as a root.
- `:mvn/repos` and `:mvn/local-repo` are parsed and merged (the root map holds Central and
  Clojars), not used.

## Plan

1. Read `.kb/clojure-frontend.md`, `.kb/dists.md`, the `e34`/`e36` kb files.
2. Fixture repositories on disk; the network check stays manual (`.kb/dists.md`
   convention).
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja`.
