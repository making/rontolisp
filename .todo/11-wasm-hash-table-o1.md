# WASM hash tables: real O(1) table (currently O(n) alist)

**Status:** not done. HARD / deferred.

The WASM backend represents a hash table as a `TYPE_CELL` box holding an
association list of `(key . value)` pairs, scanned with the `_equal` runtime
(`WasmHashTableCompiler`). Correct and small, but every `gethash`/`puthash`/
`remhash` is O(n). The interpreter and JVM use real hash maps (O(1)); the
original motivation for hash tables was avoiding O(n) alist lookups, so the WASM
side does not yet fully deliver that.

## What to implement

A linear-probing (open-addressing) table in WASM linear memory, or a wasm-GC
array of buckets:

- Hashing must agree with the structural (`equal`) key comparison already used:
  hash a value by walking conses and combining i31 ints / interned string
  offsets / char codes. Equal keys (e.g. two `(0 1 2)` lists, or two equal
  strings) must hash equal.
- Store (keyptr, value) slots; grow/rehash when the load factor is exceeded.
- Keep the same observable behavior as the alist version (and the interpreter/
  JVM): same `gethash`/default semantics, `hash-table-count`, `maphash` over all
  live entries, `remhash`, `clrhash`. `maphash` order need not match other
  backends (already documented as unspecified).
- Decide the representation: a dedicated struct/type vs. linear-memory region.
  Note the index-stability constraints on `TYPE_*`/`FUNC_*` and the `--component`
  blobs (see CLAUDE.md and `src/wasm-component/README.md`) if adding GC types.

## Definition of done

`examples/maze-rl.lisp` and the hash-table tests still produce identical output
on all backends, with WASM lookups no longer linear. Re-run the per-backend
tests and the native `CiSpecE2eTest`.
