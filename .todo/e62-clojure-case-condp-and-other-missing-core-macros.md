# e62. Clojure: `case`, `condp`, `if-some`/`when-some`, `while`, `with-redefs`, `locking`

Difficulty: Medium

Each is `unknown name: X` when the program lowers (measured 2026-10-08 on the interpreter at
the develop tip), and no page lists or refuses them, where the front end's rule is that a
missing form names its missing design (`doc/en/clojure/semantics.md`, "Not yet"). Library
code uses them constantly; `e43`'s probes will meet `case` first.

## Shape (clj 1.12.6; measure each before writing)

- `case`: test constants unevaluated (a symbol, a list of alternatives `(2 3)`, a vector or
  map constant, `nil`); a duplicate constant is refused when the form compiles
  (`Duplicate case test constant: X`); no match without a default throws
  `IllegalArgumentException` `No matching clause: X`. Measure the equality across number
  types (`(case 1N 1 :x)`, `(case 1.0 1 :x)`).
- `condp`: `(condp pred expr & clauses)`, the `:>>` clause, no match without a default the
  same `IllegalArgumentException`.
- `if-some` / `when-some`: the binding tested against nil only (`false` binds).
- `while`: the body while the test is truthy; answers nil.
- `with-redefs`: the var roots replaced for the body and restored in a finally. A `defn` is a
  directly called `defun` here (`.kb/clojure-frontend.md`, lowering table), so a replaced
  root never reaches a direct call site: decide between calls through the var in such a
  program and a refusal by name.
- `locking`: the body; on the interpreter and the JVM a monitor if threads can reach it.

## Plan

1. Measure each on clj 1.12.6 (answers and refusal texts).
2. Lower beside `cond` in `ClojureLowering`, or expand where the expansion is exact;
   clojure-spec cases on all four backends (`.kb/running-backends.md`).
3. `.kb/clojure-frontend.md` lowering table; `doc/en` + `doc/ja` reference pages.
