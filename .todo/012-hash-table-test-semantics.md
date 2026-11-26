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

## What `.todo/567` handed over (2026-08-29)

567 was filed as two defects with one symptom, and the second of them -- the hash capping
DEPTH but not WORK, so a key with SHARED substructure cost the exponentially many
root-to-leaf paths through it -- is FIXED on all four backends by a node budget beside the
depth cap (`.kb/hash-tables.md`). What that leaves for this item, and what it changes about
it:

- **The cliff is survivable, so this is no longer a liveness bug.** A program that wrote
  `:test 'eq` precisely BECAUSE its keys are large linked objects used to get the most
  expensive possible behaviour -- 61 ms for one `gethash` on an EMPTY table keyed by a node
  two links down a parent-linked chain, and no return at all at three. It is now bounded.
  What remains is the WRONG ANSWER: two distinct-but-equal aggregates are still ONE key in
  a table the caller asked to compare by identity, which for sibling scene-graph nodes,
  ORM entities or parse-tree nodes with equal slots is a silent collision.
- **The hash does NOT have to narrow, only the comparison does.** `eql` implies `equal`
  implies the same structural hash, so the existing `_hash` stays a SOUND placement
  function for an `eql` table -- it merely over-collides, which the bucket scan then
  decides correctly. That removes the part of the WASM design this item was stuck on:
  wasm-GC has no address-of, so an identity hash of a struct ref is not expressible, and
  it turns out none is needed. What each backend needs is the test FLAG plus an `eql`
  bucket comparison.
- **The blast radius is the default test, and it has to be measured before this lands.**
  `(make-hash-table)` is `eql` by default, so narrowing `eql` changes every table in every
  bundled Lisp library (`src/main/resources/*.lisp`) and in every example that relies on a
  list or a runtime-built string key matching structurally under the default test. The
  ci-spec case `cyclic-hash-key` is one already in the tree: its 200-element list key is
  looked up through a DISTINCT list in a default table and expects `:DEEP`. Audit that set
  first; the change is mechanical, the fallout is not.
- On WASM the header count's fold flag becomes two bits (`entries * 4 + test`), and every
  count read and write has to agree about that in one program-wide answer -- the same
  invariant the fold bit already has.

## Also update when this lands

The WIDENING half is done (`.todo/543`, 2026-08-28): a table already knows
whether it is an `equalp` one, on all four backends, and both the printed
`:TEST` and `hash-table-test` already report `EQUALP` for it. What is left for
this item is the same two sites answering `EQL` once an `eql` table stops
matching structurally -- `LispHashTable.equalpTest()` / `_hashEqp` / the WASM
header count's fold bit each become a three-way answer, and the ci-spec case
`hash-table-print-syntax` grows an `eql` row. `LispMacroExpander.expandHashTableTest`
is the remaining constant-`EQUAL` path, still taken by a program that can build
no folding table.

The machinery to reuse: `LispMacroExpander.programMakesEqualpHashTable` is the
shared source scan that gates the fold, and the flag it sets is exactly where an
`eql` flag would go (a second reserved map key on the JVM, a second header bit on
WASM).

## Definition of done

`(let ((h (make-hash-table)))           ; eql by default
   (setf (gethash (list 1) h) 'a)
   (gethash (list 1) h))`  => nil  (distinct cons, eql miss) on all backends,
while an `equal` table hits. Add cross-backend tests + a `src/test/resources/ci-spec.yaml` case.
