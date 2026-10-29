# Clojure: a `defn`'s function knows its class name

Difficulty: Medium

`(.getClass f)` of a fn answers the class the oracle compiles it to only for a core function
(`clojure.core$int_QMARK_`, through the shared-value table); a `defn`'s function answers the
anonymous `clojure.lang.AFunction$fn`, where the oracle's is `ns$name`. So spec.alpha's
`fn-sym` cannot name a bare predicate a program defined: `(s/explain-str my-pred 2)` prints
`failed: :clojure.spec.alpha/unknown`, the oracle `failed: my-pred`
(`.kb/clojure-frontend.md`, "clojure.spec.alpha (g90)", and the `.getClass` bullet under
"Java interop").

Approach: register each `defn`'s canonical function object (one per `defun`, every backend)
with its munged `ns$name` beside the definition, as a statement an arms family strips from a
program that reads no fn's class (`ClojureArms`, the statement shape; its producer the
`.getClass` fn arm, `%clojure-fn-class-name`). Decide whether `class` should answer the same
per-function class (the oracle's), which moves dispatch and the class rows off the one
`:function` kind, or keep the `class`/`.getClass` split; measure the corpus programs that
dispatch on the class of a fn first.
