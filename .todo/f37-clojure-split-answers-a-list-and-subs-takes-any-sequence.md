# f37. Clojure: `clojure.string/split` answers a list, `subs` takes any sequence

Difficulty: Low

Measured 2026-10-10 against clj 1.12.6, on the interpreter:

| program | oracle | here |
|---|---|---|
| `(clojure.string/split "a,b" #",")` | `["a" "b"]` | `("a" "b")` |
| `(conj (clojure.string/split "a,b" #",") "c")` | `["a" "b" "c"]` | `("c" "a" "b")` |
| `(clojure.string/split-lines "a\nb")` | `["a" "b"]` | `("a" "b")` |
| `(subs [1 2 3] 1)` | `ClassCastException` | `[2 3]` |
| `(subs '(1 2 3) 1)` | `ClassCastException` | `(2 3)` |
| `(deftype D [x])` then `(subs (D. 1) 0)` | `ClassCastException` | the deftype's list |

`split`/`split-lines` (`ClojureStringLowering.splitForm`/`splitLinesForm`, and the pattern
arm `%clojure-re-split`) build a strict list; `.split` of a String shares them (the oracle's
answers a `String[]`). `subs` lowers to `subseq` (the refusal family's alias of
`%clojure-subs`), which takes any sequence.

## Plan

1. Answer a vector from `split`/`split-lines` (check what `.split` should answer) and update
   any spec expectation that pinned the list.
2. Refuse `subs` of a non-string with the oracle's `ClassCastException`, weighing what a
   check costs a program that reads no condition's class (where `subs` is `subseq`).
3. Pin on all four backends.
