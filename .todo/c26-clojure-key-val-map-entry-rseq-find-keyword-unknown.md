# c26. Clojure `key`, `val`, `map-entry?`, `rseq` and `find-keyword`

Difficulty: Low

All five are unknown names (2026-10-03, exec jar built from the c24 work):
`(key (first {:a 1}))`, `(val ...)`, `(map-entry? ...)`, `(rseq [1 2 3])` and
`(find-keyword "a")` each answer `error: unknown name: ...`. `clj` 1.12.6 answers
`:a`, `1`, `true` (`false` for `[1 2]`: only a real map entry is one), `(3 2 1)` (`nil`
for `[]`) and `:a` (nil when the keyword was never interned).

Add them as Clojure built-ins (`.kb/adding-primitives.md`), in call position and as values.
A map entry is a plain 2-vector here (`first` of a map, `find`), so `key`/`val` are
`first`/`second` of a 2-vector and signal otherwise; `map-entry?` cannot tell an entry from
a 2-vector, which `clojure-spec.yaml` must state as a deviation (the oracle's `false` for
`[1 2]`) unless an entry gets its own representation. Pin on all four backends against the
oracle; add `doc/{en,ja}/clojure/reference` pages with catalog entries.
