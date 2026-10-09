# f04. Clojure: a return type hint on a `defn` parameter vector

Difficulty: Low

`(defn f ^long [x] (inc x))` (and `defn-`) is refused at lowering with a message missing the
function's name: `the parameter vector of takes its bindings in a vector: |%with-meta|` -- the
reader wraps the vector in reader metadata. The oracle reads the hint as the arity's return
tag. Measured 2026-10-09: the first stop of instaparse 1.5.0, verbatim, past `hash` (e88):
`auto_flatten_seq.clj:233` (`(defn- hash-cat ^long [...] ...)`) and `:247`.

## Plan

1. Measure on clj 1.12.6 a hint on each arity's vector of a multi-arity `defn`, on `fn`, and
   on a `letfn` entry; a hint names no conversion the program can observe here.
2. Strip the metadata layer where a parameter vector is read (`defn`, `defn-`, `fn`,
   `letfn`, a protocol method) and fix the message's missing name.
