# b79. `binding` of syntax-quote-qualified `clojure.core/*out*` is rejected

Difficulty: Low

 Regression found 2026-10-02 closing b78: `ClojureSpecE2eTest` fails on all four
 legs at read time -- `binding clojure.core/*out* needs a ^:dynamic var: only
 dynamic vars rebind` (concatenated `spec.clj:1715`). Interaction of b76 and b77:

 - b76's `with-out-str` spec case defines the oracle's own macro, whose expansion
   binds `*out*` written inside a syntax-quote.
 - b77 qualifies such a spelling with the defining namespace, so the `binding`
   form reaches the lowering as `clojure.core/*out*`.
 - `ClojureStateLowering` (line ~669) only accepts the bare `*out*`/`*in*`
   spellings (plus `^:dynamic` vars) and refuses the qualified core spelling.

 Oracle (`clj` 1.12.6): `` `*out* `` reads `clojure.core/*out*`, and binding it
 works -- it is a dynamic var there.

## Acceptance

- `(binding [clojure.core/*out* s] ...)` (and `*in*`) rebinds like the bare
  spelling on all four backends; or syntax-quote keeps the two stream specials
  bare -- whichever matches the oracle (check `` `*out* `` and a
  `(binding [clojure.core/*out* ...])` probe on `clj` first).
- `ClojureSpecE2eTest` (all four legs, incl. the b76 `with-out-str` case) is
  green again; the chosen shape pinned in `clojure-spec.yaml` + a lowering test.
