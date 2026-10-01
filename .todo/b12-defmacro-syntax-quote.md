# b12 — defmacro + syntax-quote/unquote/gensym

Difficulty: Medium (compile-time expander table + hygiene, but no per-backend work)

Status: open. The book's Ch.8 backbone; the frontend currently refuses every
piece by name (`syntax-quote ... backquote needs a design`,
`var is not supported yet`, `with-meta ...`, `ClojureLowering:559-580`).

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip

## Gap (measured 2026-10-01)

- `defmacro` x16+: `macros.clj` (`unless`, `bad-unless`, `evil-bench`, `bench`),
  `macros/chain_1..5.clj` (progressive `chain` fix), `macros/bench_1.clj`,
  `import_static.clj` (`import-static` generating `def`+`defmacro`), `utils.clj`
  (`re-test`, `show-publics`, `?.`, `defdemo`, `format-for-book`), `eager.clj`
  comment harness.
- Syntax-quote `` ` `` + `~`/`~@` + gensym `x#` in every macro body above
  (e.g. `bench`: `` `(let [start# (System/nanoTime) result# ~expr] ...) ``).
- `macroexpand-1` used implicitly by the book's chain_1→5 narrative.

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(defmacro my-unless [c t] (list 'if c nil t))
(macroexpand-1 '(my-unless true 1)) ; => (if true nil 1)
(my-unless false 42)                ; => 42
```

Syntax-quote qualifies symbols to the current ns, unquotes with `~`, splices
with `~@`, and `x#` auto-gensyms per expansion. The book's `chain_5` is the
correct shape (`~@more`, not `~more`).

## Scope

- `defmacro` (incl. multi-arity + `&` rest + docstring + `declare` pre-scan, same
  as `defn`), stored as a compile-time expander in `ClojureLowering` (whole-file
  pre-scan like `defn`, session-aware like `declare`).
- Syntax-quote lowering: `` `form `` → `quote`-with-unquote-splicing over the
  mangled (`c%`) namespace, `~`/`~@` honored at the right depth, `x#` gensym per
  expansion (reuse `gensym` machinery, `.kb/gensym-macroexpand.md`).
- `macroexpand-1`/`macroexpand` + `gensym` as lowered builtins (needed by the
  chain narrative + tests).
- `'` quote already works; `var`/`#'` stays refused except where a macro body
  needs it (document the boundary).

Out: `eval` of macro-generated code at runtime, `&env`/`&form`, reader
conditional inside syntax-quote, `definline`.

## Design constraints

- Clojure macros expand at Clojure-lower time (datum → datum), then lower to
  core forms — never beside `LispMacroExpander`, never a second pipeline
  (`.kb/architecture.md`: `CompileFrontend.expand` is the pipeline).
- Hygiene is `gensym`-only (`#` suffix); syntax-quote qualification is the
  `c%` prefix, documented as the deviation (no real namespaces).
- Four-backend parity by construction (expansion happens before backends);
  interpreter `eval` of a macro call expands the same way.
- `.kb/adding-primitives.md` "Adding a Macro" applies only if new CL-side
  macros are added; the Clojure side needs no per-backend compiler change.

## Acceptance

- Port `chain_1..5` + `unless` + `bench` into `clojure-spec.yaml`
  (expansion answers + runtime answers), green on all four backends.
- `ClojureLoweringTest` pins: `~`/`~@` depth errors, `x#` freshness across two
  expansions, `macroexpand-1` shape, multi-arity macro dispatch.
- Docs: `doc/en+ja/clojure/{semantics,deviations,reference}.md` + lowering-table
  rows in `.kb/clojure-frontend.md` (qualification = `c%` deviation).
