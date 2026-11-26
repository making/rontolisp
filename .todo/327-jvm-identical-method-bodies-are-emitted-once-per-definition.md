# JVM: identical method bodies are emitted once per definition

Difficulty: Medium

The WASM duplicate-body fold (`am.ik.wasm.WasmBodyFolder`, the tail of
`WasmTreeShaker.shakeWithRemap`) has a measured JVM twin that is NOT implemented yet.
The zlib probe compiled to a JVM class at `--optimize` (2026-08-11, post-shake,
post-CP-dedup):

| | |
| --- | ---: |
| methods with a `Code` attribute | 353 |
| duplicates (identical descriptor + `Code` bytes) | 48 |
| redundant bytes | **8,331 (5.2% of the 161,382 code bytes)** |
| class file | 202,708 B |

The shape is the same as the WASM side's: `defstruct`/`define-condition` accessor twins
(`CODE-N-BITS`/`HDT-COUNTS`/`CRC32-LOW`/... share one body five ways), the generated
`%setf-` writers, and a few `_lambda_*` pairs. Comparing raw `Code` attribute bytes is
sound because both methods index the SAME constant pool.

## Why it is not just the WASM pass transplanted

WASM references functions by INDEX, so the folder only splices `call` immediates. JVM
methods are reachable BY NAME:

- call sites are `invokestatic` over a `Methodref` (name + descriptor), so redirecting a
  site means pointing it at the survivor's `Methodref` (adding one to the pool if the
  survivor was never directly called);
- `JvmClassShaker` keeps extra roots by name -- the dispatch/eval targets and the
  reflective `_apply` edge (`java:` interop looks it up with `getDeclaredMethod`); a
  folded-away method whose NAME something still resolves would break at run time, so the
  survivor set needs the registry/dispatch story spelled out, not assumed;
- the natural shape is: redirect the call sites, then let the existing `JvmClassShaker`
  reachability drop the orphaned method and compact the pool -- the fold itself never
  deletes.

The fixpoint iteration carries over (folding twins can make their callers identical),
and so does the identity answer: a JVM function VALUE is a closure object holding the
dispatch id, `eq` is reference equality, so `(eq #'f #'g)` stays NIL -- already pinned
four-backend by the `identical-function-bodies-keep-distinct-identity` ci-spec case.

## Deliverable

A measured reduction on the zlib JVM class with `ChipzE2eTest` (and the corpus
`JvmClassShakerCorpusTest` output-equality run) green, a pin that a class with N
identical bodies keeps one, and the `.kb/optimize-dead-code-elimination.md` JVM section
updated from "measured, not implemented" to the landed mechanics.
