# Clojure: a `defn` inside a top-level `let` is undefined when a macro calls it

Difficulty: Medium

Found by the j07 probe round: core.match 1.1.1's `match` macro calls `regroup-keywords`
(`clojure/core/match.clj:1883`), which is defined inside a top-level `let` that closes over a
sentinel:

```clojure
(let [void (Object.)
      void? #(identical? void %)
      infix-keyword? #(#{:when :as :guard} %)]
  (defn regroup-keywords [pattern] ...))
```

Every `match` expansion stops with `in macro 'match': The function
c%clojure.core.match/regroup-keywords is undefined`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(let [k 10]
  (defn g [x] (+ x k)))
(defmacro mm [x] (g x))
(prn (g 1) (mm 2))
```

- oracle: `11 12`
- interpreter and JVM class: `error: f.clj:4:12: in macro 'mm': The function c%g is undefined`
  (the run-time call `(g 1)` alone works)

## Analysis

The macro-time evaluator (`.kb/clojure-frontend.md`, "Macros": `eval` and `resolve` while the
program lowers) learns the definitions a macro body may call from the top-level `defn`/`def`
forms it sees; a `defn` nested in a `let` (or `letfn`, `binding`, `when`, ...) at top level is a
var definition all the same in the oracle -- the var is interned when the form compiles and its
root set when the `let` runs, before the next top-level form. The macro-time image has to run
that top-level form (the `let` with its bindings, `(Object.)` included) to get the closure, not
just register a name.

The neighbours of the same shape, each called from a macro body defined after it (measured the
same day; oracle answers in brackets):

| top-level form | here |
|---|---|
| `(when true (defn g [x] (+ x 1)))` | `The function c%g is undefined` [3] |
| `(binding [*print-length* 3] (defn g ...))` | same [3] |
| `(letfn [(h [x] (* 2 x))] (defn g [x] (h x)))` | same [4] |
| `(let [k 1] (defmacro mm [x] (+ x k)))` (a macro closing over a `let`) | `The variable c%k is unbound` [3] |
| `(do (defn g ...))` | works |

So only `do` is treated as top level member by member; any other wrapper hides the definition
from the macro-time image.

## Done when

- The repro and the neighbours above print the oracle's lines on all four backends (one
  ci-spec case; the macro-time half is what differs, so the case must call the function from a
  macro).
- core.match loads past `regroup-keywords` without the patch (j07's `core-match/patches.diff`,
  the `void` hunk).
- KB "Macros" says which top-level forms the macro-time image runs.
