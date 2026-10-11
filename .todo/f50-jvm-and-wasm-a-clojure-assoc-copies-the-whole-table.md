# f50. JVM and wasm: a Clojure `assoc` copies the whole table

Difficulty: High

Every Clojure map, set and record field table is an `equal` hash table no verb mutates:
`assoc`, `dissoc`, `merge` and a set's `conj`/`disj` copy it first
(`rontolisp::%copy-equal-table`, `.kb/clojure-frontend.md` "The copy a map verb takes"). The
interpreter's copy is native (the entries shared); the JVM and wasm compile the prelude loop,
a `maphash` and a store per entry, so a map grown one `assoc` at a time costs O(n^2) there.
Measured 2026-10-11 (load ~4), instaparse 1.5.0, whose GLL parse keeps its nodes in one map
(19,481 for `instaparse.abnf`'s load-time grammar) and a 7,220-entry message cache:

| | oracle (clj 1.12.6) | JVM class | ceiling: a probe mutating a table over 64 entries in place |
|---|---|---|---|
| `cfg/ebnf` of the ABNF grammar | 0.19 s | 36.5 s, 91% in the copy | 4.5 s |
| `instaparse.core` load + README parses | 2.5 s wall | 36.3 s run | -- |

## What decides the design

- The readers stay: the lowering inlines `gethash`/`hash-table-p`/`maphash`/`hash-table-count`
  on a map, and Java runtime code reads the JVM table's internals directly
  (`JavaBridgeTemplate`, `JvmJavaDirectSites`, `JvmHttpHandlerRuntimeBuilder`,
  `JvmOperandTypeRuntime`, `RontoHttpClack`). A table sharing structure with its copy has to
  remain what all of them read, with the sharing behind the `_hash*` helpers.
- Shapes considered, not built: a layered table (a frozen base the copy shares plus entries of
  its own, flattened past sqrt n: amortized O(sqrt n), two probes per lookup) and an int-keyed
  trie of immutable buckets that the generated `_equal` scans (O(log n), insertion order kept
  by a sequence number per pair). The order must survive: maps print in insertion order, the
  oracle's array-map order, since the copy keeps it.
- A Clojure map is shared read-only across Ring request threads: a copy may not write its
  source (no moving the source's storage into a shared base).
- wasm's table (a cell over `(count . buckets)`, alist chains that `puthash` mutates) needs
  its own answer; instaparse does not run there yet (`Segment`'s `StringBuilder` interop).

## Plan

1. Re-take the ceiling on the JVM and wasm with the in-place probe.
2. The JVM: a native `%copy-equal-table` sharing structure, `JvmHashRuntimeBuilderTest` and the
   readers above; the instaparse numbers against the table.
3. wasm, measured on its own.
