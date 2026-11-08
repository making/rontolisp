# Clojure: at macro time a protocol extended to `clojure.lang.ISeq` takes the `Object` row for a list read from the source

Difficulty: Medium

Found by the j07 probe round: core.match 1.1.1 dispatches every pattern through

```clojure
(defmulti emit-pattern (fn [pattern] (syntax-tag pattern)))      ; match.clj:1742
(extend-protocol ISyntaxTag                                      ; match.clj:1748
  clojure.lang.IPersistentVector (syntax-tag [_] ::vector)
  clojure.lang.ISeq              (syntax-tag [_] ::seq)
  clojure.lang.IPersistentMap    (syntax-tag [_] ::map)
  clojure.lang.Symbol            (syntax-tag [_] ::symbol)
  Object                         (syntax-tag [_] :default)
  nil                            (syntax-tag [_] :default))
```

Inside the `match` macro a list pattern from the user's source (`(n :guard even?)`,
`(:or :x :y)`) answers `:default`, so it becomes a literal pattern and the expansion holds
`(= ocr-1 (n :guard even?))`: `unknown name: n`. The same call at run time
(`(syntax-tag '(n :guard even?))`) answers `::seq`, and so does `(macroexpand-1 '(match ...))`
at run time.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defprotocol T (tg [x]))
(defmulti emit (fn [x] (tg x)))
(defmethod emit :seq [x] `(quote ~x))
(defmethod emit :default [x] x)
(defmacro m [v] (emit v))
(extend-protocol T
  clojure.lang.ISeq (tg [_] :seq)
  Object (tg [_] :default))
(prn (m (a b)))
```

- oracle: `(a b)`
- interpreter and JVM class: `error: f.clj:9:6: unknown name: a` (the macro saw `:default`)

Observed while narrowing (a `(binding [*out* *err*] (prn (tg v)))` in the macro body): with the
library split over two files, the macro body ran twice for one call site -- first `:default`,
then `:seq` -- and the program used the second when the first did not fail. So there are two
macro-time evaluations, and the first one does not see the `ISeq` row (or does not see the value
as a seq). Also seen: `(defmacro m [v] (mapv tg v))` over `[(n :g e) n 1 nil]` answered the
right tags in a one-file program -- the row was found there, so the miss depends on when the
`extend-protocol` ran relative to the expansion (here it is BELOW the `defmacro` and the
`defmulti`, above the use).

## Direction

Find the first evaluation (the pre-scan expanding macros? the `ClojureMacroEvaluator` image
built before the `extend-protocol` form ran?) and make it see the program's state at the call
site, or not run at all. A protocol extension to an interface reaching every value implementing
it is KB "Collection interfaces" (protocol extensions to an interface); check the macro-time
image takes that path for the reader's list values (`LocatedCons`, a symbol carrying metadata,
`.kb/source-positions.md`).

## Done when

- The repro prints `(a b)` on all four backends (ci-spec case), also with the `extend-protocol`
  above the `defmacro`, and with the protocol, multimethod and macro in a required namespace.
- The macro body runs once per call site (or the KB says why twice and which result is used).
- core.match's `emit-pattern` works without the patch (j07 `core-match/patches.diff`, the
  `emit-pattern` dispatch hunk) -- the guard, `:or` and `:seq` probe lines.
