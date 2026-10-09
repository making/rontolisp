# e88. Clojure: `hash` and the collection hash verbs

Difficulty: High

`hash`, `mix-collection-hash`, `hash-ordered-coll`, `hash-unordered-coll` and `hash-combine`
are unknown names, and a body's `IHashEq` (`hasheq`) is stored but read by no verb
(`.kb/clojure-frontend.md` "Collection interfaces", the `MARKER` family). instaparse 1.5.0
hashes its `AutoFlattenSeq` and compares by hash (`(== hashcode (hash other))`), and
data.priority-map 1.2.0 answers `hasheq` through `hash-unordered-coll` (measured 2026-10-08,
past e87's `compile-if`).

## What decides the design

- The values: the oracle's Murmur3 (`clojure.lang.Murmur3`, `Util.hasheq`) for numbers,
  strings, keywords, symbols, characters and the collections, so a printed hash and a hash
  kept across runs agree with the oracle on all four backends; a type's `hasheq` row, then its
  `hashCode` override, then identity.
- 32-bit arithmetic on every backend (`unchecked-*-int` exists; `.kb/clojure-frontend.md`).

## Plan

1. Measure on clj 1.12.6 `hash` of each kind (`1`, `1.0`, `1N`, `"a"`, `:a`, `'a`, `\a`, `[]`,
   `{}`, `#{}`, a record, a type with and without `hasheq`) and the four helpers.
2. The verbs over one runtime worker each, `hash` reading a type's `IHashEq` row; a
   clojure-spec case on all four backends.
3. Re-probe instaparse and data.priority-map.
