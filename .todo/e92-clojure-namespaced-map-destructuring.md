# e92. Clojure: namespaced map destructuring (`:ns/keys`, `::keys`)

Difficulty: Low

`(let [{:a/keys [b]} {:a/b 2}] b)` is refused while lowering: `a map pattern binding needs
a plain name, not :|a/keys|` (measured 2026-10-09). Clojure 1.9+ reads `:ns/keys [b]` as
`:keys [ns/b]`, `:ns/syms` alike, `::keys` and `::alias/keys` against the current namespace,
and takes keywords in a `:keys` vector (`{:keys [:a :b/c]}`). `clojure.main/ex-str`, whose
oracle destructures `{:clojure.error/keys [...]}`, is written with plain keys instead.

## Plan

1. Measure on clj 1.12.6: `:ns/keys`, `:ns/syms`, `::keys`, `::alias/keys`, keyword entries,
   `:or` defaults by the short name.
2. `ClojureBindingLowering.bindKeys` and its mirror `ClojureLoopLowering.collectKeyNames`.
3. clojure-spec case on all four backends; the destructuring reference pages.
