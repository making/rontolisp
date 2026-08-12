# The decompress generic keeps every method a literal call cannot select

Difficulty: High

`size-report/programs/zlib/zlib.lisp` at `--optimize=size` is **96,834 B** after todo
331's shared `_seq_len` (code 90,413 in 282 functions, data 5,444, everything else
~1,000). The next inventory says the artifact is still ~45% machinery and dead variants,
and the single biggest item is no longer a per-site expansion: it is the `decompress`
GENERIC carrying every method for a call whose arguments are literals.

The High rating: every lever below is either a whole-program soundness argument (1, 2) or
a semantics decision (4, 5), and the four-backend rule sits on top of all of them.

## How this inventory was taken (same recipe as todo 331)

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -Drontolisp.wasm.debug-func-sizes=true -jar $JAR \
  size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size 2>sizes.txt
wasm-tools objdump zlib.wasm
```

By owner: chipz itself 46,975 B (127 functions), lambdas 12,462 (53), emitted runtime
`FUNC_*` 12,319 (76), funcall dispatch ladders 6,799 (7), spliced Lisp runtime 5,134 (5),
`_toplevel_chunk` 4,214, CLOS `%setf-` writers 1,216 (21).

## 1. The `%DECOMPRESS` method family: 11,998 B for a call that reaches one method

The program's only entry is `(chipz:decompress nil 'chipz:gzip <ub8-vector>)` -- three
literal/known-shape arguments -- and `decompress` is a defgeneric. The artifact carries
**18 `%DECOMPRESS` variants totalling 11,998 B**, of which that call can select exactly
one, `%DECOMPRESS/NULL-VECTOR` (988 B): `/NULL-STREAM` 1,882, `--m7` 1,725,
`/STREAM-STREAM` 1,704, `--m5` 1,309, `--m8` 875, `/STREAM-VECTOR` 799, `--m3` 635,
`--m4` 517, `--m6` 459, `/VECTOR-VECTOR` 443, `%DECOMPRESS-FROM-PATHNAME` 342, plus the
small `--m0..m9` stubs -- and the `CHIPZ:DECOMPRESS` dispatcher itself is another 2,838 B
of arms that keep them reachable.

The 'chipz:gzip literal also anchors the whole zlib-format half: `%MAKE-ZLIB-HEADER`
1,761 B, the adler32 family ~790 (`UPDATE-ADLER32` 374, `COPY-ADLER32`, the
`CMF`/`FDICT`/`ADLER32` accessor triples), kept because nothing propagates the format
symbol into `make-dstate`'s dispatch. Ballpark for the lever as a whole: **~15 KB, 15%+
of the module.**

This is `DeadTypeBranchPruner`'s blind spot, one layer up: that pass prunes a `typecase`
clause no call site's argument SHAPE can satisfy (`.kb/optimize-dead-code-elimination.md`,
todo-311), but a defgeneric's dispatch is not a `typecase` -- it is the generated
dispatcher's arm list (`.kb/clos.md`). The analogous move is a method-set narrowing: join
the argument shapes over every call site of the generic (same `ArgumentShapes.maySatisfy`
machinery, same escape rules -- a `#'decompress` taken as a value, `anyNameResolvable`,
multiple definitions all mean "keep everything"), and let the dispatcher list only the
methods some call can select; the shakers then drop the rest by the reachability they
already have. Check FIRST whether the name-as-value escape already fires here (the
wrapper catalog and `dispatchableFuncIds` are the usual suspects, todo-317's generated-
literal rule): if `decompress`'s name reaches a dispatch ladder, that edge -- lever 2 --
has to be settled first or the join is moot.

## 2. The funcall dispatch ladders: 6,799 B, unchanged since todo 331

`_dispatch_1` 1,994 B, `_dispatch_2` 1,375, `_dispatch_5` 841, `_dispatch_3` 819;
arities 1,2,3,4,5,7,9 emitted. The open question is the same one todo 331 carried:
whether every target LISTED in a ladder is really reachable as a first-class value, or
whether the designator resolution of todos 315/323/328 leaves entries nothing can
select. Each dead entry costs its arm AND everything the arm's call edge keeps alive --
which is exactly what may be pinning lever 1.

## 3. The other per-site ladders, still per-site

Todo 331's probe table (net B per additional site, measured there): `position` 451,
`assoc` 433, `reverse` 419, `elt` 406, `(setf (aref ...))` 375, `member` 335, `eql` 228,
`aref`/`svref` 204. The `_seq_len` move (`.kb/length-runtime.md`) is the template --
count the SURVIVING copies in this artifact before pricing any row (declared packed
sites are already fused; chipz declares nearly everything, which is why `length` was the
outlier at 66 copies and the rest may not repeat that).

## 4. Printer/reader residue in a program that writes only octets: 4,795 B

`FUNC_PRINC_VAL` 1,739, `FUNC_CHARVEC_TO_STR` 653, `FUNC_READ_CHAR` 649,
`FUNC_PRINT_F64_NO_NL` 379, the i32/i64 printers, the write helpers. The program's only
I/O is `read-sequence` on octets in and `write-sequence` on octets out; these are
reachable through chipz's `error` sites (post-todo-324, a signal carries slot values,
not rendered prose -- so ask what still holds the VALUE printer, and why `read-char` is
alive in a byte-only reader).

## 5. Bignum + ratio runtime: 4,609 B in 44 functions

Reachable only as the overflow fallback of generic arithmetic. chipz declares
`(unsigned-byte 32)` throughout, so type-directed narrowing is available in principle --
and it changes what an undeclared overflow does, so it is a semantics decision, not a
size one (todo 331 measured the floor: one `(logand (+ a b) 255)` defun already pays
1,392 B of it).

## 6. The CLOS accessor tail

21 `%setf-` writers (1,216 B) plus the reader/`--m0` stubs, `%NO-APPLICABLE-METHOD`
(788 B), and the per-accessor baked error strings in the 5,444-B data section. Todo
331's framing stands: whether the `on <arg>` half of a no-applicable-method message is
worth its per-accessor string is one decision for every backend -- and todo-317's rule
(no generated literal may spell a defun name exactly) bounds what the strings can
shrink to.

## Watch

- Levers 1 and 2 interact: settle the name-as-value question once and both read from it.
  Do not build the method-set join while a dispatch ladder still lists the generic.
- A program is verified only on all four backends; zlib needs `-W exceptions=y` on both
  wasm runs; gunzip a fixture and compare byte for byte (`size-report/measure.sh`'s
  8,192-byte input ceiling bounds the timing fixture at ~1.3 MB decompressed).
- Measure the row, not the probe: todos 329 and 331 both delivered from a different
  mechanism than their first probe suggested.
- `size-report/measure.sh` re-measures the row; prose travels in
  `size-report/notes/wasm-flags.md`, never `results/`.
