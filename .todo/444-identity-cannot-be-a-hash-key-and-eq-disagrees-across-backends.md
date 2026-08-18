# 444. Identity cannot be a hash key, and `eq` disagrees across backends

Difficulty: High

Deferred out of `.todo/438` (2026-08-18) with the measurement below. Not part of
the `.todo/436` family's current wave -- this is the follow-up that makes
`:test` real, plus the identity question underneath it. Nothing forces it today;
this file records what the gap IS, what it costs, when to act, and the design
that was already worked out so the next visitor does not redo it.

## Gap 1: `:test` is ignored; every table is `EQUAL`

`(hash-table-test (make-hash-table :test 'eq))` answers `EQUAL`, and lookup is
structural on every backend. `.todo/438` fixes HOW an `EQUAL` table hashes; it
deliberately does not make the four tests real.

**Where that bites**: a table whose keys are mutable AGGREGATES -- CLOS
instances, structs, conses -- and whose point is identity. Two distinct objects
with equal slots collapse into one entry. The canonical uses are memoization
keyed by object, visited-sets in a graph walk, and object -> property registries.

**Where it does NOT bite, measured (2026-08-18)**: every `:test 'eq` / `'eql`
site in the vendored corpus keys on a SYMBOL or an INTEGER, where `eq` and
`equal` agree here by construction --

| site | key |
| --- | --- |
| lisp-namespace, the namespace tables (x3) | symbol |
| cl-ppcre, the symbol sweeps (x2) | symbol |
| esrap, `seen` (x4) | symbol (rule name) |
| sxql, `*clause-priority*` | symbol |
| rove, `*package-suites*` | package = a keyword here |
| `examples/wit/keyvalue` | integer handle |
| alexandria's test | none (it tests `:size`) |

Across the whole Quicklisp cache the ratio is `equal` 119 : `eq` 53 : `equalp`
12 : `eql` 7, and the `eq` side is dominated by the same symbol-keyed shape. The
one consumer that genuinely needed identity keys was upstream ASDF, which is not
being hosted (`.kb/asdf.md`).

**Act when**: a library keys an `eq`/`eql` table on instances or conses. The
symptom is not an error -- it is two objects sharing one entry, so it reads as a
logic bug in the library.

## Gap 2: `eq` on instances disagrees between backends

Found while measuring the above, and undocumented. For two separately
constructed instances with equal slots:

```
interpreter:  (eq p q) => T     (eql p q) => T
JVM:          (eq p q) => NIL   (eql p q) => NIL
```

`Environment.isEq` falls through to `.equals()` for a non-cons, and
`LispInstance.equals` is structural; the JVM and WASM (`ref.eq`, see
`WasmEqGeneralCompiler`) use reference identity, which is what CL specifies.
Conses agree on all three (reference identity). `equal` on instances is
structural everywhere and that is deliberate (`.kb/instance-syntax.md`).

The interpreter is the wrong one. Fixing it is small, but it belongs HERE rather
than in a drive-by: what identity MEANS has to be settled before a table can key
on it, and settling it is most of this todo.

## The WASM design, already worked out

The reason `:test` was deferred is that identity hashing has no primitive in
WASM GC: `ref.eq` compares two references but nothing turns one into a stable
integer, deliberately, because the collector moves objects. The three families
are (a) an id field per object, (b) `ref.eq` linear scan, (c) structural hash +
`ref.eq` compare. (c) is wrong -- mutating a key after insertion moves its
bucket, which is exactly what an identity registry does.

The design that costs nothing until used:

- **i31 keys** (fixnums, characters) -- hash the value. `eq` is value comparison
  for them anyway. Free.
- **symbols and strings** -- hash the `StringTable` offset. Offsets are already
  deduplicated and `eq` already compares them (`WasmEqGeneralCompiler`). Free.
- **instances** -- carry an identity id, **as a hidden element of the existing
  `slots` array, NOT as a struct field**. `.kb/instance-syntax.md` forbids the
  field: `TYPE_INSTANCE` = `struct {const i32 layoutAddr, mut eqref slots}`, and
  `{i32, i32, eqref}` canonicalizes equal to `TYPE_VBLOCK` under `--simd`, after
  which `ref.test` can no longer tell an instance from a packed-array block.
- **conses and arrays** -- no id; one shared bucket plus a `ref.eq` scan. O(n),
  never an error. A cons as an identity key is close to meaningless anyway (the
  caller cannot reconstruct it), so this is a deliberate limit, not an oversight.
- **the gate**: emit the hidden id only when the program calls
  `make-hash-table` with a LITERAL `:test 'eq`/`'eql`. Every other program keeps
  today's layout and allocation sequence byte for byte, so `size-report` does
  not move. A computed `:test` falls back to the linear scan.

Interpreter and JVM are straightforward (`IdentityHashMap` /
`System.identityHashCode`), so the semantics stay identical on all four
backends and only the performance characteristic differs on WASM -- say so in
`.kb/hash-tables.md`, which is where the "always `EQUAL`" statement and the
printed `:TEST EQUAL` tag both have to change when this lands.
