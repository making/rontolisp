# Identical function bodies are emitted once per definition

Difficulty: Medium

**6,639 B of the zlib `--optimize=size` artifact (4.8%) is function bodies that are
byte-for-byte identical to another body in the same module**, and every one of the
duplicate groups also shares its declared type index, so nothing but the emitter's
one-body-per-definition rule keeps the copies apart.

## Measured

zlib `--optimize=size`, 137,430 B, 362 function bodies:

| | |
| --- | ---: |
| duplicate groups (>= 2 identical bodies) | 28 |
| redundant bytes | **6,639 (4.8%)** |
| of those, groups whose members share a declared type | 6,639 (all of them) |

The largest group is 1,023 B x 3 (`_lambda_549`, `_lambda_592`, `_lambda_594`); the
rest is a long tail of the 282-303 B accessor lambdas a `defstruct`/`define-condition`
generates, two or three at a time. chipz declares five conditions and several structs,
so the tail is the shape, not an accident of one definition.

Reproduce with `-Drontolisp.wasm.debug-func-sizes=1` for the names, and hash the code
entries of the emitted module for the groups.

## Where the sharing has to happen

The bodies are already final when the module is assembled, so this is a POST-PASS over
the encoded module rather than a change to any expression compiler -- the same layer
`am.ik.wasm.WasmTreeShaker` works at, and it already owns the hard part: it renumbers
every function index in every body, export, element and start section
(`shakeWithRemap` even reports the mapping). Folding duplicates is the same rewrite
with a different survivor set, so the natural home is next to it in `am.ik.wasm`,
language-independent like the rest of that package.

Two things to check before assuming it is a pure win:

- **The dispatch ladders map funcId -> a direct call.** Two lambdas that fold still have
  distinct funcIds and distinct closure structs; only the code they jump to is shared.
  That is fine for the ladder (its `br_table` arms would just call the same index), but
  `.kb/optimize-dead-code-elimination.md`'s reachability accounting has to be re-read
  with folding in mind.
- **`eq` on two function VALUES.** Nothing observes function identity through the code
  index today (a closure's identity is its struct), but confirm it before folding, and
  pin whatever the answer is.

The JVM backend has the same shape (one method per definition) and `am.ik.jvm` already
deduplicates the constant pool; whether the same post-pass is worth having there is a
separate measurement, not an assumption.

## Deliverable

A measured reduction in the `zlib` rows of `size-report/results/wasm-flags.md` with the
row's check still gunzipping byte for byte, a pin that a module with N identical bodies
emits one, `./mvnw test` + native `CiSpecE2eTest` green, and the identity question above
answered in `.kb` rather than left to the next visitor.
