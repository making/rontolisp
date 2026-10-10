# f36. Clojure: instaparse 1.5.0's stops past its `CharSequence` Segment

Difficulty: High

instaparse 1.5.0 (verbatim, interpreter, 2026-10-10) now stops at `gll.clj:183:4: No such
multimethod: clojure.core/print-method`. Each later stop, found by removing the previous one
by hand in a copy of its sources:

| site | form | here |
|---|---|---|
| `gll.clj:183`, `core.clj:163` | `(defmethod clojure.core/print-method Failure [x writer] ...)` | `No such multimethod: clojure.core/print-method` |
| `gll.clj:197` | `(def failure-type (type (Failure. nil nil)))` | `unknown name: type` |
| `gll.clj:942` | `(delay ...)` / `(force d)` | `delay is not supported yet: lazy memo cells need a design` |
| `viz.clj:19` | `(try (ns-resolve (find-ns 'rhizome.dot) 'escapable-characters) (catch Exception e nil))` | `unknown name: ns-resolve` |
| `viz.clj:89` | `(try (require 'rhizome.viz) (catch Exception e ...))` inside a `defn` | `Could not locate rhizome/viz.clj` while lowering (the oracle requires at run time) |
| `macros.clj:4` | `&env` in a `defmacro` body | `unknown name: &env` |

Past those, `gll.clj:746` `re-match-at-front` calls `.lookingAt` and `.group` on a
`re-matcher` matcher, which no value-method row maps (`ClojureValueMethods` lists both as
unsupported). The rows were not measured past `macros.clj:4`.

## What decides the design

- `.kb/clojure-frontend.md`: "Dispatch" (multimethods: is `print-method` a multimethod the
  printer consults), "Laziness" (`delay`), "Namespaces and project files" (a `require` in a
  function body), "Macros" (`&env`), "Regex" (matcher methods).
- Each stop is its own decision; measure each verb on clj 1.12.6 before choosing.

## Plan

1. `print-method` as an extensible multimethod the printer consults for a record/deftype
   (`pr`, `prn`, `pr-str`, `print`), behind an arm a program defining no method sheds.
2. `type` (`(or (:type (meta x)) (class x))`).
3. `delay`/`force`/`realized?` over a memo cell.
4. `ns-resolve`/`find-ns` of a namespace the program never loads (nil), a `require` in a
   function body of an absent namespace refused at run time, `&env`.
5. `.lookingAt`/`.group`/`.matches`/`.start`/`.end` of a `re-matcher` matcher.
6. Re-probe instaparse verbatim after each; pin each on all four backends.
