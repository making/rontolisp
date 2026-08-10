# The zlib artifact is mostly code a gzip decompress never reaches

Difficulty: High

## Symptom

`size-report/programs/zlib/zlib.lisp` is now the same program as the
cross-language rows it sits next to in `size-report/results/wasm-flags.md`, and
the comparison is no longer flattering: the other implementations of the same
program are 16 KB - 89 KB, this one is **425,815 bytes** at `--optimize=size`.
The gap is not the inflate algorithm. Almost none of those bytes are reached by
`(chipz:decompress nil 'chipz:gzip <octets>)`.

## Where the bytes are (measured 2026-08-10, `--optimize=size`, wasmtime 47)

Section split of `zlib_size.wasm`: code **387,532** (91%), data **36,027** (8%),
everything else ~1,400. 574 functions.

The fixed runtime floor was measured directly, by compiling the smallest
program that turns each mechanism on:

| program | bytes |
| --- | ---: |
| `(print (+ 1 2))` | 423 |
| `+ (catch 'tag (throw 'tag 1))` | 5,159 |
| `+ (unwind-protect ...)` | 4,818 |
| `+ (apply #'+ (list 1 2))` | 27,589 |
| `+ (ignore-errors ...)` | 88,638 |
| `+ (handler-case ... (error (e) ...))` | 89,138 |
| `apply` AND `handler-case` together | **97,959** |

So the floor decomposes as: EH MODE is cheap (~5 KB), the **condition system is
~84 KB**, the embedded `eval` runtime `apply` turns on is **~27 KB**, and the two
overlap to 98 KB. Subtracting that floor from the artifact:

| | funcs | code | data | total |
| --- | ---: | ---: | ---: | ---: |
| floor (`apply` + `handler-case`) | 204 | 72,291 | 24,644 | 97,959 |
| zlib | 574 | 387,532 | 36,027 | 425,815 |
| **chipz's own share** | **370** | **315,241** | **11,383** | **327,856** |

Two function bodies alone are 44,878 and 29,447 bytes -- 19% of the whole
artifact in two functions. Both are past the floor's 204 functions, so both are
compiled from chipz's source; WHICH functions they are is not currently
knowable from the artifact (no name section, no per-function size dump).

## What is provably dead in there

`strings` on the module finds the entire **bzip2** decompressor: the ~60-slot
`CHIPZ:BZIP2-STATE` struct, its CRC table, the MTF/BWT machinery,
`CHIPZ::%BZIP2-DECOMPRESS`, `BZIP2-RANDOMIZED-BLOCKS-UNIMPLEMENTED`. A gzip
decompress cannot reach any of it. The zlib-format path is equally unreachable.

`LibraryDefunPruner` cannot drop it and is not at fault: its reachability rule
is "any occurrence of the name in a kept form" (`.kb/library-defun-pruning.md`),
and chipz's own dispatch table names every format
(`:DEFLATE :ZLIB :GZIP :BZIP2` appear as one blob in the data section), so
`%bzip2-decompress` is textually referenced from a form that is kept. Dropping
it needs the compiler to know the FORMAT ARGUMENT is the constant
`'chipz:gzip`, which no pass currently does.

Also visible in the data section, and worth its own measurement before anyone
optimizes it:

- every function name is interned TWICE, once as `CHIPZ::FOO` and once as
  `CHIPZ:FOO`;
- one `"No applicable method: <NAME> on "` string literal per CLOS accessor,
  and chipz defines dozens;
- the `format` renderer, pulled in by the condition reports rather than by the
  program (measured on the old program shape: dropping the summary line saved
  only 2,008 bytes, so the renderer is not the program's).

## The work, in the order the numbers rank it

1. **Find out what the two big bodies are.** Nothing today maps a wasm function
   index back to the Lisp definition that produced it. A `-Drontolisp.debug.*`
   per-function size dump (the wasm twin of `rontolisp.debug.dump-program`) is
   the prerequisite for every decision below, and is useful far beyond this row.
2. **The condition system, ~84 KB, is the single biggest fixed cost.** A
   program whose only handler is `(handler-case ... (error (e) ...))` pays for
   the whole condition class tree, the restart machinery
   (`MUFFLE-WARNING/ABORT/CONTINUE/USE-VALUE/STORE-VALUE` are all in the data
   section), `print-object` dispatch and the report renderer. Measure what a
   program actually reaches and gate the rest the way the stderr branch and the
   file-metadata helpers are gated. Compare against `catch`/`throw`'s 5 KB:
   that is what the mechanism costs when the class system is not involved.
3. **The `eval` runtime, ~27 KB, is turned on by one `apply` call.** chipz calls
   `apply` on a function it names literally. If `apply`'s argument is a `#'`
   constant, it is a plain call and should not turn the runtime on at all;
   check how many of the 27 KB survive that narrowing, and whether the registry
   (which also holds every name twice) can be narrowed to the functions an
   `apply`/`funcall` can actually reach.
4. **Constant-folding the format argument** so the pruner can see that the
   bzip2 and zlib branches are dead. This is the one item that needs a real new
   pass rather than a gate, and it is worth stating as a general capability --
   a library whose entry point dispatches on a keyword the caller passes as a
   constant is a common shape, not a chipz peculiarity.
5. The two data-section items (double interning, per-accessor message strings)
   are small next to the above; measure before spending time on them.

## Results (2026-08-10)

Items 1-3 delivered; items 4-5 (and the rest of item 2's ~84 KB floor) split into
`.todo/316` with the measurements below. `zlib --optimize=size`:
**425,815 -> 411,948 bytes (-3.3%)**, check stream byte-identical.

1. **Done.** `-Drontolisp.wasm.debug-func-sizes` (`.kb/wasm-function-body-size.md`)
   dumps every shipped function's post-shake size with its Lisp name, joined through
   the shaker's remap (`WasmTreeShaker.shakeWithRemap`). The two big bodies are
   `CHIPZ::%MAKE-BZIP2-STATE` (44,878 -- the ~60-slot BOA constructor, so it dies with
   the bzip2 tree, item 4) and a `labels` state-machine lambda (29,447).
2. **Partial.** The `MUFFLE-WARNING/ABORT/...` strings were a FALSE POSITIVE, not part
   of the floor: `usesRestartSystemForm`'s spine-walking scan read chipz's `tagbody`
   tag `CONTINUE` (bzip2.lisp:290) as a restart call. Operator-position scan
   (`.kb/error-handling.md`) -> -6,923 B on this row. The remaining ~84 KB floor
   (format renderer via `%format-condition` is the largest piece) is decomposed with
   gate sites in `.todo/316`.
3. **Done.** `_apply` + the SPREAD dispatcher are now a tier below the interpreter
   (`LispMacroExpander.needsApplyRuntime`, `.kb/eval-runtime.md`): a literal-target
   apply forces nothing (`(print (apply #'+ (list 1 2)))` at `--optimize=size`:
   27,589 -> 18,829, and the remainder is the `#'+` WRAPPER's own transitive closure,
   not runtime -- chipz's `(apply #'decompress ...)` direct call has no such cost); a
   computed-designator apply costs 22,461 (no `_eval`/`_store`). zlib's LAST eval
   trigger was then `boundp` in chipz's define-constant idiom;
   `foldBoundpDefineConstantIdiom` discharges the 18 guards by proof and the eval
   runtime is out of the artifact (-6,774 B beyond item 2's win). The JVM deliberately
   keeps its wider gate: a trivial class already carries the runtime through the
   wrapper-body self-check, so narrowing there changes nothing (recorded with a
   re-evaluation trigger in `.kb/eval-runtime.md`).
4. -> `.todo/316` item 1 (the ranked prize: ~90-120 KB of provably-dead bzip2).
5. -> `.todo/316` item 3.

## Deliverable

A measured reduction in the `zlib` rows of `size-report/results/wasm-flags.md`,
with each step's win recorded here or in the matching `.kb` file, and no change
in what the row checks (it must still gunzip the check stream to exactly the
octets it was made from, on all four backends). Every mechanism this touches is
shared, so the acceptance criterion is the usual one: `./mvnw test`, the native
`CiSpecE2eTest`, and byte-identical output for programs that do not use the
mechanism being gated.
