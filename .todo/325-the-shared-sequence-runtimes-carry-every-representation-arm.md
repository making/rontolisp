# The shared sequence runtimes carry every representation arm

Difficulty: Medium

`%REPLACE-RUNTIME` is **4,463 B of the zlib `--optimize=size` artifact (3.2%)**, the
second largest function in it. It is already shared -- one copy for the whole module,
which is what `.kb/sequence-op-runtimes.md` bought -- but that one copy is three
representation arms wide: a `%row-major-aset` copy loop over an `aref`/`elt` source
split, a list rewrite, and an immutable-string rebuild made of three `subseq`s and a
`concatenate`. chipz replaces into `(unsigned-byte 8)` vectors and nothing else.

The same shape holds for the rest of the shared sequence runtime in the same artifact:

| function | bytes |
| --- | ---: |
| `%REPLACE-RUNTIME` | 4,463 |
| `%SEQ-INT-VECTOR` | 2,136 |
| `%FILL-RUNTIME` | 1,697 |
| `%SEQ-TO-LIST` | 1,592 |
| `%SEQ-TO-STRING` | 1,515 |
| `%SUBSEQ-RUNTIME` | 1,114 |
| **total** | **12,517 (9.1%)** |

## What is actually being asked

Not "re-inline the dispatch" -- that was measured and is far worse
(`.kb/sequence-op-runtimes.md` records `CHIPZ::UPDATE-WINDOW` at 18 KB when it was
inline). The question is whether a helper can be emitted with the arms the PROGRAM can
reach, the way the module already drops whole functions it cannot reach:

- The helpers are injected from `LispMacroExpander`'s wrapper builders, so the arms are
  Lisp source at injection time and can be selected there.
- The evidence for selecting is the same evidence `WasmArrayCompiler.arrayKindOfExpr`
  already computes for a site, and the same `declare (type ...)` the compile paths now
  honour (`.kb/declarations-type-checks.md`).
- A gate that under-predicts must keep the wide arm, never emit a narrow one: a helper
  that answers wrongly for a representation the scan missed is a correctness bug, where
  a helper that stayed wide is only bytes.

The honest alternative, if a whole-program scan cannot be made sound: emit ONE helper per
arm-set actually requested (`%replace-runtime-vector` alongside `%replace-runtime`) and
route per site on the same evidence the site already has, exactly as `map-into` gets one
helper per source count today.

## Deliverable

Measured reductions in the `zlib` rows of `size-report/results/wasm-flags.md` with the
row's check still gunzipping byte for byte, a pin that a program which DOES replace into
a list/string still gets the right answer on all four backends, `./mvnw test` + native
`CiSpecE2eTest` green, and the soundness argument for the narrowing gate written into
`.kb/sequence-op-runtimes.md` next to the sharing argument it qualifies.
