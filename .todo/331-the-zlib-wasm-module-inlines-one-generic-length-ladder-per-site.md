# The zlib wasm module inlines one generic `length` ladder per site

Difficulty: High

`size-report/programs/zlib/zlib.lisp` at `--optimize=size` is **105,393 B** (`83083cd3`,
the post-todo-329 row). Asked whether that number can still move, an inventory of the
artifact says yes: roughly a quarter of it is machinery rather than inflate code, and the
single biggest item -- ~14 KB, ~13.6% of the module -- is one shared dispatch that is
still written out at every call site.

Lever 1 alone is a well-precedented mechanical change (five `.kb` files already describe
the same move for other operators). The High rating is the breadth: six levers, a hot
decompression loop that must not regress, and the four-backend rule on top.

## How the inventory was taken (reuse these, do not re-derive)

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -Drontolisp.wasm.debug-func-sizes=true -jar $JAR \
  size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size 2>sizes.txt
# one "[func-size] <bytes> <final index> <name>" line per surviving function, largest first
wasm-tools objdump zlib.wasm    # section split
wasm-tools print  zlib.wasm     # ~2.05 module bytes per wat line, calibrated on five functions
```

Section split: code **98,973** (281 functions), data **5,444** (11 segments), everything
else 976. By owner:

| Owner | Bytes | Functions |
| --- | ---: | ---: |
| chipz itself | 49,739 | 106 |
| lambdas (`_lambda_*`) | 12,454 | 53 |
| emitted runtime (`FUNC_*`) | 12,009 | 75 |
| spliced Lisp runtime (`%SUBSEQ-RUNTIME`, `%SEQ-*`, `%REPLACE-RUNTIME-ARRAY`, ...) | 10,430 | 29 |
| funcall dispatch ladders (`_dispatch_1..9`) | 6,799 | 7 |
| `_toplevel_chunk_565` | 6,050 | 1 |
| `cl` operator defuns (`LENGTH`, `EQL`, `-`, `/`, ...) | ~1,100 | 10 |

The eval runtime is NOT in this module -- todo 315's apply tier already keeps it out, and
todo 329 confirmed the `boundp` arm never held it open either. What is left is the list
below.

## 1. The generic `length` dispatch is inlined, ~66 times (~14 KB)

`WasmLengthCompiler.compile` writes the whole sequence-type ladder -- packed-float arm,
packed-int arms, string arm, hash-table/array box arm, cons walk -- straight into the
caller, and only short-circuits when `DeclaredArrayTypes` already fixes the argument's
type. `LENGTH` also exists in the module as a **314 B function** (emitted for the
first-class wrapper), so the callee this wants is already there and already paid for.

Measured in the artifact: **66 non-overlapping ladder regions, 7,011 wat lines, ~14,372 B
(13.6% of the module)**, spread over the toplevel chunk (22 signature hits),
`CHIPZ::CONSTRUCT-HUFFMAN-DECODE-TABLE` (20), `%REPLACE-RUNTIME-ARRAY` (14),
`%SUBSEQ-RUNTIME` (10), `%SEQ-INT-VECTOR` (8), `%SEQ-TO-LIST` (8) and 18 more. Note the
last four: the *spliced runtime helpers* inline it too, so sharing it shrinks them as
well.

This is the move `.kb/wasm-shared-coercion.md` (`_as_f64`), `.kb/subseq-runtime.md`,
`.kb/seq-conversion-runtime.md` and `.kb/string-write-runtime.md` already made for four
other operators; the open question each of them answered is whether the shared callee
belongs at the DEFAULT level or only at `--optimize=size`. Answer it here by measurement,
not by analogy: zlib's inflate loop calls `length` inside the window copy, so time the
decompression as well as measure the bytes.

## 2. Every other operator whose ladder is still per-site

Measured net cost of one ADDITIONAL call site, over a `(list <i> x)` baseline that pins
the site cost itself. **Bodies must differ per site or `WasmBodyFolder` folds all of them
into one and every operator reads ~27 B** -- that is what a first pass at this table
measured, and it was wrong:

| Inlined per site | net B/site | | Already shared | net B/site |
| --- | ---: | --- | --- | ---: |
| `position` | 451 | | `subseq` | 12 |
| `assoc` | 433 | | `equal` | 16 |
| `reverse` | 419 | | `char` | 18 |
| `elt` | 406 | | `concatenate` | 20 |
| `(setf (aref ...))` | 375 | | `+` / `logand` / `ash` / `mod` | 8 |
| `member` | 335 | | `funcall` | 10 |
| `length` | 309 | | `format` / `princ-to-string` | 6 / 2 |
| `eql` | 228 | | | |
| `aref` / `svref` | 204 | | | |
| `floor` | 111 | | | |
| `mapcar` | 110 | | | |
| `nth` | 82 | | | |

chipz's own sources hold 35 `aref`, 25 `length`, 7 `(setf (aref ...))`, 2 `svref`, 2
`member` -- but a declared packed-array site is already fused to a raw `array.get`, so
COUNT the surviving ladders in the artifact before pricing any row of this table against
the zlib number.

## 3. The funcall dispatch ladders: 6,799 B

`_dispatch_1` alone is 1,994 B. Arities 1,2,3,4,5,7,9 are emitted; 6 and 8 are not, so the
set is already narrowed to arities the program reaches (this is the WASM side of the
question todo 330 asks about the JVM's closed `0..MAX` range). Unanswered: whether every
target LISTED in a ladder is really reachable as a first-class value, or whether the
designator resolution of todos 315/323/328 leaves entries behind that nothing can select.

## 4. A literal format argument that nothing propagates: ~2.8 KB

The program calls `(chipz:decompress nil 'chipz:gzip ...)`, a literal symbol, and chipz
dispatches on it at runtime -- so the whole zlib-format half stays: `%MAKE-ZLIB-HEADER`
1,761 B, the adler32 family ~575 B, the `CMF`/`FDICT`/`ADLER32` accessors. The same shape
keeps `%DECOMPRESS/STREAM-STREAM` (1,704 B), `/STREAM-VECTOR` (799 B),
`/VECTOR-VECTOR` (443 B) and `%DECOMPRESS-FROM-PATHNAME` (342 B) alive for a call that can
only ever take the null-vector arm. A constant propagated across a defun boundary is a
much bigger change than the rest of this file -- price it before starting it.

## 5. The CLOS accessor family and its baked strings: 6,059 B + ~1.4 KB of data

chipz spells `gzip-header` / `zlib-header` as classes, so each of the 18 slots costs a
reader, a `%setf-` writer, a dispatcher and TWO baked error strings
(`"CHIPZ::FLAGS on "`, `"%setf-CHIPZ::FLAGS on "`), plus `%NO-APPLICABLE-METHOD` (788 B)
and the class metadata table in data segment 6 (~3.7 KB, every chipz condition class
included). Whether the `on <arg>` half of a no-applicable-method message is worth its
per-accessor string is a decision to make once, for every backend.

## 6. Two smaller residues

- **Bignum + ratio runtime: 4,546 B in 42 functions.** Reachable only as the overflow
  fallback of the generic arithmetic. Note the floor: `(defun g (a b) (logand (+ a b) 255))`
  already pays 1,392 B in 17 of them. chipz declares `(unsigned-byte 32)` throughout, so a
  type-directed narrowing is available in principle -- and changes what an undeclared
  overflow does, so it is a semantics decision, not a size one.
- **Printer/reader residue in a program that writes only octets: ~3.9 KB.**
  `FUNC_PRINC_VAL` 1,739, `FUNC_CHARVEC_TO_STR` 653, `FUNC_READ_CHAR` 649,
  `FUNC_PRINT_F64_NO_NL` 379, the i32/i64 printers 323, `FUNC_OPEN` 123. Reachable through
  chipz's `error` calls; the question is whether all of it is.

## Watch

- Measure the row, not a probe. The per-site table above is a probe and says so; todo 329
  ended up delivering its win from a different mechanism than its own probe predicted.
- A program is verified only on all four backends. zlib needs `-W exceptions=y` on both
  wasm runs; gunzip a fixture and compare byte for byte. Check whether the JVM inlines the
  same ladders before calling lever 1 a wasm-only change.
- `size-report/measure.sh` re-measures the row; the prose that travels with it is
  `size-report/notes/wasm-flags.md`, never `results/`.
