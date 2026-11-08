# Clojure: a multi-arity `defmethod` is refused at lowering

Difficulty: Low

Found by the j07 probe round: core.match 1.1.1 stops loading at `clojure/core/match.clj:208`
(and `clojure/core/match/array.clj:27` has the same shape):

```clojure
(defmethod subvec-inline ::vector
  ([_ ocr start] `(subvec ~ocr ~start))
  ([_ ocr start end] `(subvec ~ocr ~start ~end)))
```

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defmulti f (fn [t & _] t))
(defmethod f :a ([_ x] [:one x]) ([_ x y] [:two x y]))
(prn (f :a 1) (f :a 1 2))
```

- oracle (clj 1.12.6.1673): `[:one 1] [:two 1 2]`
- interpreter and JVM class: `error: f.clj:2:1: a parameter vector takes its bindings in a
  vector: ((|%vector| _ |x|) (|%vector| :|one| |x|))`

`defmethod`'s tail is `fn`'s tail in the oracle (`(defmethod name dv & fn-tail)` expands to
`(. name addMethod dv (fn fn-tail))`), so every `fn` shape is legal: one or more arities, a name
before them (`(defmethod f :a named-fn [x] ...)`, the name visible in the body for recursion),
`&` rest arities.

## Where

`ClojureDispatchLowering` builds the method lambda from a single parameter vector
(`(defmethod name dispatch-value [params...] body...)`, see its javadoc near line 1327); the
refusal text comes from `ClojureLowerUtil` line ~256. The lowering should route the tail
through the same path `fn` takes (multi-arity dispatch, the optional name), not a second
parser.

## Done when

- The repro prints the oracle's line on the interpreter, the JVM, P1 and the component, plus a
  named method calling itself and a `& rest` arity beside a fixed one -- one `ci-spec.yaml` case
  (`.kb/test-execution.md`; extend an existing defmethod case if one fits).
- core.match loads past `match.clj:208` without the patch (j07's `core-match/patches.diff`, first
  hunk).
- `.kb/clojure-frontend.md`'s `defmethod` row says the tail is `fn`'s.
