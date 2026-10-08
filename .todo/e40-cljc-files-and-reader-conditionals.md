# e40. `.cljc` files and reader conditionals (`#?`, `#?@`)

Difficulty: Medium

Most Clojars libraries ship `.cljc`. Today `ClojureSourcePath.resourceOf` maps a namespace to
`.clj` only, and `ClojureReader.readDispatch` refuses `#?` as `unsupported reader form #?`.
Independent of `e37`; needed by any real dependency.

## Gaps

1. Reader: `#?(...)` and splicing `#?@(...)` in source files; `#?` outside a `.cljc` is the
   oracle's refusal (`Conditional read not allowed`). Unknown features skipped, `:default`.
2. Platform features: which keys the front end answers. `:clj` maximizes library reach, but
   its branches are Java interop that wasm refuses at call time; a `:cljs` branch is often
   the portable one. Measure on a few real libraries and decide (a rontolisp key first,
   then `:clj`, is one candidate). Record the decision in `.kb`.
3. Lookup order: `.clj` before `.cljc` for one namespace (measure the oracle, `RT.load`).
4. `read-string`/`read` with `{:read-cond :allow|:preserve :features #{...}}`
   (`.kb/clojure-frontend.md`, "Reading" lists `#?` as a refusal).
5. ns forms in `.cljc`: `:require-macros`, `:include-macros` inside `#?(:cljs ...)` only;
   confirm they never reach the lowering on the chosen feature.

## Plan

1. Read `.kb/clojure-frontend.md` ("Reading", "Namespaces and project files").
2. Oracle-diffed lines in `clojure-spec.yaml`; four backends.
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja` syntax/deviations pages.
