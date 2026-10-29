# Clojure: a symbol carries no metadata (reader `^` and `with-meta`)

Difficulty: High

`(meta '^:foo x)` is `nil` and a macro never sees the metadata on a symbol argument
(`doc/en/clojure/deviations.md`: "a symbol carries none (`with-meta` answers the symbol)"):

```clojure
(defmacro m [n] (pr-str (meta n)))
(println (m ^:foo x))       ;; clj, cljw 1.15.4: {:foo true}   rontolisp: nil
```

`^:private` on a `def` name works only because the `def` lowering reads it specially. A
library macro that reads `(meta name)` itself (`:private`, `:doc`, `:tag`, `:arglists`,
custom markers for `defn`-like macros) loses it. cljw found the same class of bug in malli's
transitive deps (its `docs/works/ladder.md`, rung 16).

Symbols are interned Lisp symbols, so metadata cannot sit on them. Design a symbol
carrying metadata (a wrapper the reader produces only when `^` is present, equal and
hashed as its bare symbol) on all four backends; `ClojureMacroTime` must pass it to
expanders and strip it where a name is consumed.
