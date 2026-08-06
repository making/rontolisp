# Shake the wasm string table and type section under --optimize

Difficulty: High

After the case-fold segment split and the literal-print specialization (2026-08-06),
`(print "Hello World!")` at `--optimize` is 1,886 bytes:

```
578  type section   (60 entries, copied verbatim by the shaker)
909  data section   (871-byte string blob + 3 seed cells + program literal)
282  code           (4 functions: _start, top-level chunk, _write_str, _str_build)
~120 rest
```

The two remaining verbatim-copied sections are now 79% of the module.

## Data: string provenance + relocation

Of the 871-byte string blob, ~676 bytes are the package alias table (the
`FIND-PACKAGE` builtin wrapper's `expandRuntimeFindPackage` alist — every spelling in
`PackageResolver.runtimePackageTable()`) and ~56 bytes are sequence keywords — all
interned by WRAPPER bodies that Pass 2a compiles and the shaker then deletes, leaving
their strings behind. `StringTable` records no provenance: an offset is baked as an
indistinguishable `i32.const` the moment `addString` returns.

Sketch: record, per emitted function, which `StringEntry`s its body references and
the byte ranges of the `i32.const (offset|length)` immediates (the emitters all go
through a handful of helpers — `compileStringLiteral`, the runtime builders' `st.*`
uses — so the capture points are enumerable). Post-shake, rebuild the blob with only
entries referenced by surviving functions (plus registry/intern blobs, which carry
offsets in DATA, not code) and rewrite the recorded immediates, the same way the
shaker already rewrites `call` immediates. Beware: registry/intern/reader blobs
reference string offsets from DATA; either keep every entry such a blob cites or
relocate the blob words too. Keep `--optimize` output deterministic.

## Types: renumber type immediates

The shaker's decoder already walks every instruction; extending it to collect used
type indices (function-section entries, GC-op immediates, blocktypes, locals'
ref-type declarations, globals, import typeidx, plus type-to-type edges inside the
type section: struct field refs and the rec group) and renumber would drop most of
the 60 entries for small programs. The rec group must be kept or dropped atomically.
`.kb/optimize-dead-code-elimination.md` documents today's "types stay stable"
invariant — retire that sentence in the same pass.

## Component wrapper (separate, larger)

`--component` hello is 8,930 bytes: shaken core 1,893 + P1 adapter module 3,624 +
~3.2 KB of component types/imports/aliases/canonical functions. The adapter carries
all nine WASI shims however few the core still imports; the component type/import
surface declares the full fixed WASI surface. Shaking the adapter against the
core's post-shake import set (its exports are bound BY NAME, so dropping unneeded
exports composes exactly like the core shake) and emitting only the needed component
imports would roughly halve the component floor. Check `WasmComponentBuilder` /
`adapter.wat` and `.kb/wasi-component.md` first.
