# e37. deps.edn core: the whole file, `:local/root`, the dependency graph, the source path

Difficulty: High

Today `ClojureSourcePath.depsPaths` reads `:paths` and nothing else (`.kb/clojure-frontend.md`,
"Namespaces and project files"); `:deps`, `:aliases` and every coordinate are ignored
silently. This item builds the offline core; network coordinates are `e39`, aliases/CLI `e38`.

## Gaps

1. Parse the whole `deps.edn` (`:paths`, `:deps`, `:aliases`, `:mvn/repos`,
   `:mvn/local-repo`); an unknown key or coordinate type is refused by name, never ignored.
2. `:local/root` to a directory: its own `deps.edn` read recursively (`:paths` relative to
   it); to a jar: extracted through `e33`.
3. Graph and selection with tools.deps' rules: top-level deps win, otherwise newest version
   across the tree, `:exclusions`, cycle tolerance; deterministic order
   (`.kb/emitted-output-determinism.md`).
4. Source path: project `:paths`, then each selected dependency's roots in tools.deps'
   order, then the built-in namespaces (`Found.builtin` stays last, as now).
5. `clojure.*` namespaces from the source path: `ClojureNamespaceLowering` refuses every
   non-built-in `clojure.*` as `unknown namespace` before looking at the path, which blocks
   the org.clojure contrib libraries (`clojure.data.json`, `clojure.tools.cli`, ...). Look
   the path up first; keep the refusal for a name found nowhere.
6. Built-in coordinates: `org.clojure/clojure` (and its `spec.alpha`/`core.specs.alpha`)
   is the front end itself; `ring/ring-core`/`ring/ring-codec` map to the shipped namespaces
   (`.kb/clojure-frontend.md`, "Ring util namespaces"). Decide how a version differing from
   the shipped one is reported.
7. User-level `~/.clojure/deps.edn` (`CLJ_CONFIG`): merged or refused; decide.
8. Every route that reads Clojure source sees the same path: interpreter, compile path,
   session/REPL (`SourceSession`), playground (`SourceLanguage.clojureFiles`).

## Plan

1. Read `.kb/clojure-frontend.md` ("Where it sits", "Namespaces and project files", "Ring
   util namespaces"), `.kb/source-language.md`, `ClojureSourcePath`, `ClojureFiles`.
2. Measure each rule on the oracle (`clj` 1.12.6, `clj -Spath`) before pinning it.
3. Tests over fixture project trees; four backends for the loading behavior
   (`.kb/running-backends.md`).
4. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja` Clojure pages.
