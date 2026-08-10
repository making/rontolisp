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

## Deliverable

A measured reduction in the `zlib` rows of `size-report/results/wasm-flags.md`,
with each step's win recorded here or in the matching `.kb` file, and no change
in what the row checks (it must still gunzip the check stream to exactly the
octets it was made from, on all four backends). Every mechanism this touches is
shared, so the acceptance criterion is the usual one: `./mvnw test`, the native
`CiSpecE2eTest`, and byte-identical output for programs that do not use the
mechanism being gated.
