# f18. Clojure: `clojure.lang.PersistentQueue`

Difficulty: Medium

medley 1.8.1 (verbatim, interpreter, 2026-10-10) stops at `core.cljc:194:3: unknown name:
clojure.lang.PersistentQueue` -- its `queue` is `clojure.lang.PersistentQueue/EMPTY` and
`queue?` an `instance?` of the class (`.kb/clojure-frontend.md`, "Reading" re-probe). No value
here is a queue.

## What decides the design

- A queue is a sequential, counted collection: `conj` adds at the rear, `peek`/`pop` read and
  drop the front, `seq` walks front to rear, `=` compares it as a sequential, it prints as the
  oracle's `#object[clojure.lang.PersistentQueue ...]` (measure), `class`/`instance?` answer
  its class.
- Every shared verb must reach it through an arm family (`.kb/clojure-frontend.md`, the byte
  array and transient families) so a program making none compiles as before.

## Plan

1. Measure on clj 1.12.6: `PersistentQueue/EMPTY`, `conj`/`into`/`peek`/`pop`/`seq`/`count`/
   `=`/`hash`/printing/`str`/`class`/`instance?`, `empty`, metadata.
2. The value, its family, the verbs' arms, on all four backends; re-probe medley.
