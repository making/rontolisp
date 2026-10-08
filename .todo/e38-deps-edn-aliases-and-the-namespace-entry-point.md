# e38. deps.edn aliases and running a project from a namespace (`-M -m`, `-X`)

Difficulty: Medium

A `deps.edn` project is started as `clj -M:alias -m my.app args` or `clj -X:alias`, not by
naming a file. rontolisp has neither: the CLI takes a source file, there is no way to name a
namespace as the entry point or to call its `-main`, and aliases are not read. Depends on
`e37`.

## Gaps

1. Alias keys: `:extra-paths`, `:extra-deps`, `:replace-paths`, `:replace-deps`,
   `:override-deps`, `:default-deps`, `:main-opts`, `:exec-fn`, `:exec-args`, `:ns-default`;
   `:jvm-opts` ignored or refused (decide). Alias selection on the CLI (proposed: `-A`/`-M`/`-X`
   spelled as `clj` does; settle in step 1 against `CliOptions`).
2. Entry by namespace: load `my.app` from the source path and call `-main` with the
   arguments (`*command-line-args*` already reads argv). Works for `run`, the compile
   outputs (`-o app.jar|.wasm`, `--native`) and the four backends.
3. `-X`: call `:exec-fn` with `:exec-args` merged with `key value` arguments read as EDN.
4. Tests: `rontolisp test` for a Clojure project, the `:test` alias convention
   (`:extra-paths ["test"]`) and `clojure.test/run-tests` over the test namespaces
   (`.kb/clojure-frontend.md`, "clojure.test").

## Where e37 left it (`.kb/clojure-frontend.md`, "deps.edn")

- `:aliases` are read and validated (`ClojureDepsEdn.DepsMap.aliases`, raw values) from the
  root map (its `:test` and `:deps` aliases), the user-level map and the project's, merged.
  Nothing applies them: the oracle's `merge-alias-maps` rules and `tools.deps/tool`
  (`:replace-*`) are the next step, and `flattenPaths` must put `:extra-paths` ahead of
  `:paths` like `flatten-paths`.
- `ClojureDepsGraph.expand` takes no `:override-deps`/`:default-deps` yet: `choose-coord`
  (override, else the coordinate, else the default) belongs where a node's coordinate is
  read, and a `nil` coordinate is the oracle's `Bad coordinate` until then. The alias
  arguments' lib names need the same canonicalization as `:deps` (`ClojureDepsEdn.libMap`).

## Plan

1. Read `.kb/clojure-frontend.md`, `.kb/source-language.md`, the `RontoLispCli` subcommands.
2. Measure `clj` 1.12.6 for each alias rule (merge order across several aliases).
3. Four-backend tests (`.kb/running-backends.md`); `doc/en` + `doc/ja`.
