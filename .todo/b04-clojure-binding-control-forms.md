# b04: Clojure binding and control forms -- destructuring, threading, multi-arity

Difficulty: High

## Premise (measured, 2026-09-30)

Destructuring, `->`/`->>`, `defmulti`/`defmethod`, `declare`, multi-arity
`defn`/`fn` and named `fn` are absent (`.kb/clojure-frontend.md`, "Deviations").
Measured on the interpreter (Clojure CLI 1.12 as oracle):

- `(let [[a b] [1 2]] a)` is refused (`let needs a plain name`) here, `1` there.
  The same refusal covers map destructuring, `:keys`/`:as`, and `loop`/`fn`
  parameter destructuring.
- `(-> 5 inc inc)` / `(->> 5 inc inc)` are `unknown name` here, `7` there; the
  same for `doto`, `cond->`, `as->`.
- `(defn f ([x] x) ([x y] (+ x y)))` is refused (`takes its bindings in a
  vector`) here, works there. `(fn myfn [x] x)` is refused the same way (the
  lowering only skips a `LispString` name, but a name reads as a symbol).
- `(declare f)` is `unknown name` here; `(defn g [] (f 1))` before `(defn f ...)`
  relies on it there.
- `(def x 1)` inside a body mutates the global at run time, unreviewed; `defn`
  inside a body works only in statement position (both per the `.kb`).
- `doseq`/`dotimes`/`for`/`list*` comprehensions are `unknown name` here.
- `(cond (= 1 2) :one :fallback)` answers `:fallback` here (odd trailing arm as
  default) and SIGNALS there (`cond requires an even number of forms`) -- the
  spec's `if-when-cond-do-and-or` case pins our side.

## Shape

- Destructuring first (it unlocks idiomatic `let`/`loop`/`fn`): sequential
  binding + pattern walk, with a named refusal for what stays out.
- Threading macros as lowering (pure AST rewrite, no backend work).
- Multi-arity `defn`/`fn` as a dispatch `defun` (arity-count dispatch like
  `case-lambda` in `.kb/scheme-frontend.md`) or an explicit refusal by name;
  named `fn` as a `labels` self-binding; `declare` as a forward declaration in
  the pre-scan.
- `def` in a body: decide implement (local? warn?) vs refuse; document the
  `defn`-in-body statement-position rule or lift it.
- `cond`: decide strict (even-only, matching Clojure) vs lenient (current);
  the change flips a pinned spec case either way.

## Tests

- `clojure-spec.yaml` cases per form as each lands; `ClojureLoweringTest` pins
  the lowering shapes; `ClojureSpecE2eTest` on all four backends.
