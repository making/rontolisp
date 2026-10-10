# f27. Clojure: a deftype implementing `java.lang.CharSequence`

Difficulty: Medium

instaparse 1.5.0 (verbatim, interpreter, 2026-10-10) stops at `gll.clj:51:4:
java.lang.CharSequence is not supported yet as an interface of deftype` -- its `Segment`
deftype (`length`, `subSequence`, `charAt`, `toString` over a string slice). Measured against
clj 1.12.6 on the interpreter:

| program | oracle | here |
|---|---|---|
| `(deftype Seg [s offset count] CharSequence (length [_] count) (subSequence [_ a b] (Seg. s (+ offset a) (- b a))) (charAt [_ i] (.charAt s (+ offset i))) (toString [_] (subs s offset (+ offset count))))`, `(def g (Seg. "hello world" 6 5))`, `(prn (.length g) (.charAt g 1) (str g) (str (.subSequence g 1 3)) (re-find #"o.l" g))` | `5 \o "world" "or" "orl"` | the lowering refusal above |

## What decides the design

- `.kb/clojure-frontend.md`, "the `clojure.lang` interfaces a `reify`/`deftype`/`defrecord`
  body implements" (one arm family per interface group) and "Java faces".
- Which verbs read a CharSequence in the oracle (`.length`/`.charAt`/`.subSequence` calls,
  `str`, `re-find`/`re-matcher`/`re-seq`, `count`?, `seq`?, `subs`?) and what each answers for
  a deftype -- measure before choosing which verbs grow an arm.

## Plan

1. Measure the verbs above on clj 1.12.6 over a CharSequence deftype.
2. Accept `CharSequence` in a deftype/reify body; route the measured verbs through its rows
   behind an arm a program without one sheds; pin on all four backends; re-probe instaparse.
