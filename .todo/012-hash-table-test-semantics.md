# Hash tables: honor :test (eql vs equal) instead of always equal

Difficulty: High

**Status:** not done. HARD-ish / deferred.

All three backends currently compare hash-table keys structurally (as if by
`equal`) regardless of the `:test` argument, which is accepted but ignored. This
is a documented deviation: an `eql` table also matches structurally-equal
aggregate keys (lists, distinct-but-equal strings). For atoms (numbers, symbols,
characters) `eql` and the current behavior coincide, so the common cases are
fine; only `eql`/`eq` tables keyed by aggregates differ from Common Lisp.

## What to implement

Store the table's test and branch the key comparison on it, consistently across
backends:

- Interpreter (`LispHashTable`): it already keeps an `equalTest` flag but keys
  the map by `key.print()` unconditionally. For an `eql` table, key by identity
  for conses (and value for atoms) instead of by printed form.
- JVM (`JvmHashRuntimeBuilder`): the map keys on `_lispToString` (always
  structural). For `eql`, key on the object itself (Long/String value-equal,
  Object[] cons identity). Needs the test stored on/with the table (e.g. a
  wrapper object or a sentinel entry) and the get/put/rem helpers to branch.
- WASM (`WasmHashTableCompiler`): a real open-chaining bucketed table -- the
  `TYPE_CELL` box holds a `(count . buckets)` header cons and `_equal` compares
  within a bucket. So `eql` needs BOTH an `_eql` bucket comparison AND an
  `eql`-consistent `_hash` variant (the `_hash` <-> `_equal` invariant in
  `.kb/hash-tables.md`), plus a test flag carried in the header.

Keep `equal` the default-friendly behavior; only narrow `eql`/`eq` tables.

## Also update when this lands

A table prints as `#<HASH-TABLE :TEST EQUAL :COUNT n>` on all four backends, and
the `EQUAL` there is a CONSTANT for exactly the reason above: it is the test
lookup actually implements, and WASM does not store the requested one. Once the
test is stored and honored, the printer must report the table's real test
(`.kb/hash-tables.md` "Printing" -- the three print sites plus the ci-spec case
`hash-table-print-syntax`), and `hash-table-test` must stop being folded to a
constant (`LispMacroExpander.expandHashTableTest`).

## Definition of done

`(let ((h (make-hash-table)))           ; eql by default
   (setf (gethash (list 1) h) 'a)
   (gethash (list 1) h))`  => nil  (distinct cons, eql miss) on all backends,
while an `equal` table hits. Add cross-backend tests + a `src/test/resources/ci-spec.yaml` case.
