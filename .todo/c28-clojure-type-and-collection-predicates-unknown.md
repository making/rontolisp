# c28. Clojure type and collection predicates are unknown names

Difficulty: Medium

`(println (NAME 1))` answers `error: unknown name: NAME` for each of these (2026-10-03, exec jar
built from the c26 work; `NAME` is in `ClojureCoreNames`, so the oracle's `clj` 1.12.6 knows all of them):

- collections and types: `seq?` `sequential?` `map?` `set?` `list?` `record?` `sorted?` `seqable?`
  `associative?` `counted?` `indexed?` `reversible?` `ifn?` `chunked-seq?`
- numbers: `number?` `integer?` `int?` `double?` `float?` `decimal?` `ratio?` `rational?` `nat-int?`
  `pos-int?` `neg-int?` `infinite?`
- names: `keyword?` `ident?` `simple-ident?` `qualified-ident?` `simple-keyword?` `qualified-keyword?`
  `simple-symbol?` `qualified-symbol?`
- other: `char?` `bytes?` `any?` `not-any?` `not-every?` `distinct?` `identical?` `inst?` `uuid?` `uri?`
  `var?` `volatile?` `delay?` `future?` `realized?` `bound?` `class?` `extends?` `special-symbol?`

Some of these have no meaning without a feature the front end refuses by name (`future?`, `delay?`,
`volatile?`, `realized?` over a delay): those answer the same refusal as the verb they belong to.

Add the rest as Clojure built-ins in call position and as values (`.kb/adding-primitives.md`), pinned on all
four backends against the oracle. The representation decides the answers (`.kb/clojure-frontend.md`
"Lowering"): a keyword is `(:C%KEYWORD s)`, a set `(:C%SET table)`, a map an `equal` hash table, a vector a
non-string CL vector, a list a cons headed by no wrapper tag, a lazy seq its own wrapper; `seq?` is true of a
list and of a lazy seq, false of a vector, map and `nil`. Add `doc/{en,ja}/clojure/reference` pages with
catalog entries (a predicates table page may group them).
