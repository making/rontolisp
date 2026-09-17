# wasm: every list/vector key of an `eq`/`eql` table lands in bucket 0

Difficulty: High

Predates `.todo/835`, surfaced while measuring it (2026-09-17). `.kb/hash-tables.md`: on
wasm "an eql/eq aggregate key hashes to the shared bucket 0", because a wasm GC reference
has no identity hash. A table keyed by n conses or vectors is therefore a linked list:
`gethash` is O(n), a fill is O(n^2). `.todo/835`'s first cycle detector used such a table
and writing a 50,000-element list took 62 s.

## To do

1. Measure: fill + lookup of an `eq` table with 10^3, 10^4, 10^5 cons keys, on all four
   backends. Record the numbers in `.kb/hash-tables.md`.
2. Design an identity hash for wasm GC aggregates that survives the representation (e.g.
   a lazily assigned hash field in the struct header, or a side counter), weighing the
   per-object size cost against `.kb/wasm-size` budgets -- a cost paid by every cons is
   likely not worth it; one paid only by keyed objects may be.
3. If no design is worth its size, write the measurement and the rejected designs into the
   kb and close as such.
