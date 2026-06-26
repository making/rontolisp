# Hash tables: honor :test (eql vs equal) instead of always equal

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
- WASM (`WasmHashTableCompiler`): scans with `_equal`; add an `_eql`-based scan
  and store the test in the box (the `TYPE_CELL` holds only the alist today, so
  the representation must carry a test flag too).

Keep `equal` the default-friendly behavior; only narrow `eql`/`eq` tables.

## Definition of done

`(let ((h (make-hash-table)))           ; eql by default
   (setf (gethash (list 1) h) 'a)
   (gethash (list 1) h))`  => nil  (distinct cons, eql miss) on all backends,
while an `equal` table hits. Add cross-backend tests + a `ci-spec.yaml` case.
