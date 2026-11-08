# Clojure: `iterate` calls its function one element ahead, and `take-while` over an infinite seq never returns

Difficulty: High

Found by the j07 probe round in rewrite-clj 1.3.59, whose zipper navigation is built on

```clojure
;; rewrite_clj/zip/whitespace.cljc:40 (skip), rewrite_clj/zip/findz.cljc:37 (find); also
;; removez.cljc:13,24 and paredit.cljc:55,58,69,293,407,417,535
(->> (iterate f zloc)
     (take-while identity)
     (take-while (complement zraw/end?))
     (drop-while p?)
     (first))
```

Two separate defects make it fail here: `z/of-string` throws `not a function` (iterate called
`zraw/right` on the `nil` past the last sibling, and `clojure.zip/right` of `nil` destructures
`nil`), and `z/find-value` never returns (the `take-while identity` over an infinite
`(iterate z/next ...)` walks forever).

## Repro 1: `iterate` runs `f` before the element is read (2026-11-08, develop `a3c68a63f`)

```clojure
(def calls (atom 0))
(defn f [x] (swap! calls inc) (if (nil? x) (throw (ex-info "called on nil" {})) nil))
(prn (try (second (iterate f 1)) (catch Exception e :threw)) @calls)
(reset! calls 0)
(prn (try (doall (take-while identity (iterate f 1))) (catch Exception e :threw)) @calls)
```

- oracle: `nil 1` then `(1) 1`
- interpreter and JVM class: `:threw 2` then `:threw 2`

Cause: `rontolisp::%clojure-iterate` (`src/main/resources/am/ik/rontolisp/eval/clojure.lisp`,
~line 5579) is `(make-lazy (lambda () (cons x (%clojure-iterate f (funcall f x)))))` -- realizing
the cell holding X computes `(f x)`, the NEXT element, eagerly. The oracle's `Iterate` computes
`(f x)` only when the next cell's `first` is read (`clojure.lang.Iterate`: `next()` makes
`new Iterate(f, null, UNREALIZED_SEED)`, `first()` runs `f`). Same suspicion for `%clojure-range-from`
(harmless, `+` cannot throw) and `cycle`/`repeatedly` -- measure each against the oracle with a
side-effecting function.

## Repro 2: `take-while` is strict

```clojure
(prn (first (take-while identity (iterate inc 0))))
(prn (first (take-while pos? (iterate inc 1))))
```

- oracle: `0` then `1`
- interpreter and JVM class: never returns (killed after 20 s)

`(first (take-while #(< % 3) (iterate inc 0)))` returns, so `take-while` walks the input until the
predicate fails, then answers a strict list -- correct output whenever the predicate fails at
some point, a hang when it never does. KB "Laziness" lists `take(-while)` under "Stepping ...
so an infinite input answers", which holds only for that case. `filter`, `drop-while` and `map`
over the same infinite input answer lazily (measured). Lowering:
`ClojureFilterLowering.takeWhileForm` (called from `ClojureLowering`'s `"take-while"` case,
~line 5498); the value form is `takeWhileValue`.

## Direction

- `iterate`: one wrapper per element whose thunk computes the element when the cell is realized
  from its predecessor's tail, i.e. carry the previous value and the function, not the computed
  next value. Mind KB "Laziness" b93 (realization chains are forced in a loop; a deep `iterate`
  must not grow the stack) and the measured `(first (filter #(> % 100000) (iterate inc 0)))` case.
- `take-while`: follow the lazy-or-strict rule (`%clojure-lazy-or-strict`): lazy when the input
  is lazy. Check `take`, `take-nth`, `partition-by`, `split-with` (`[(take-while ...) (drop-while
  ...)] in one walk`, `clojure.lisp` ~line 9607) for the same strictness.
- Cost: measure a strict 200,000-element `take-while` on the interpreter before and after (KB
  "Laziness" has the `for` numbers as the precedent).

## Done when

- Both repros print the oracle's lines on all four backends (ci-spec cases; the call counter is
  the observable).
- rewrite-clj's probe prints the oracle's lines without the `skip`/`find` loop hunks of j07's
  `rewrite-clj/patches.diff`.
- KB "Laziness" states which verbs step lazily over an infinite input.
