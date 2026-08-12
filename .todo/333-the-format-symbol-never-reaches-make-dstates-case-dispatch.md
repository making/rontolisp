# The format symbol never reaches make-dstate's case dispatch

Difficulty: High

`size-report/programs/zlib/zlib.lisp` at `--optimize=size` is **83,269 B** after todo
332's dispatcher-branch narrowing (code 76,634 in 266 functions). The `%DECOMPRESS`
method family is settled -- what survives is the selectable slice (dispatcher 323 B, the
default method, `--m5` + `%DECOMPRESS/NULL-VECTOR`) -- and the remaining inventory is
below. Same recipe as todos 331/332:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -Drontolisp.wasm.debug-func-sizes=true -jar $JAR \
  size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size 2>sizes.txt
```

By owner: chipz itself 33,784 B (93 functions), lambdas 12,444 (53), emitted runtime
`FUNC_*` 12,163 (74), funcall dispatch ladders 5,563 (5, down from 7), spliced Lisp
runtime ~5,100, `_toplevel_chunk` 4,214, CLOS `%setf-` writers 1,216 (21).

## 1. The `'chipz:gzip` literal still anchors the zlib-format half

Todo 332's lever 1 removed the dead METHODS; the format-keyed dispatch inside
`make-dstate` (2,307 B) is a `case`/`ecase` over the format SYMBOL, and nothing
propagates the literal `'chipz:gzip` into it -- so `%MAKE-ZLIB-HEADER` (1,761), the
adler32 family (645 B in 6 functions) and the zlib arms of the state machine stay. This
is `DeadTypeBranchPruner` territory one step further: that pass prunes `typecase` clauses
by argument SHAPE; a `case` clause over a literal-symbol join needs the VALUE, not the
shape. The narrowing analysis of todo 332 (`GenericDispatchNarrowing`) already computes
per-parameter joins over visible call sites -- a `case`-clause pruner could read the same
join at SYMBOL granularity (the join must carry the symbol's identity, not just
`Shape.SYMBOL`). The same soundness rules apply verbatim; bzip2 is already absent, so the
measurable slice is the zlib half (~2.5-3 KB) plus whatever the state machine's zlib arms
hold.

## 2. The funcall dispatch ladders: 5,563 B in 5 ladders

`_dispatch_1` 1,994, `_dispatch_2` 1,375, `_dispatch_5` 841, `_dispatch_3` 819. Two
ladders disappeared with the narrowed methods; the question of todos 331/332 stands for
the rest -- whether every listed target is really reachable as a first-class value. 76
funcIds still materialize as values (`-Drontolisp.debug.dispatchgate=true`), most of them
lambdas (12,444 B in 53) -- the inflate state machine's per-state closures.

## 3. Printer/reader residue in a program that writes only octets: ~4.7 KB

Unchanged from todo 332's lever 4: `FUNC_PRINC_VAL` 1,739, `FUNC_CHARVEC_TO_STR` 653,
`FUNC_READ_CHAR` 649, `FUNC_PRINT_F64_NO_NL` 379, the i32/i64 printers, the write
helpers. The program's only I/O is `read-sequence` in and `write-sequence` out; ask what
still holds the VALUE printer and why `read-char` is alive in a byte-only reader.

## 4. Bignum + ratio runtime

Reachable only as the overflow fallback of generic arithmetic; chipz declares
`(unsigned-byte 32)` throughout, so type-directed narrowing is available in principle --
and it changes what an undeclared overflow does, so it is a semantics decision (todo
331 measured the floor: one `(logand (+ a b) 255)` defun already pays 1,392 B of it).

## 5. The CLOS accessor tail

21 `%setf-` writers (1,216 B), `%NO-APPLICABLE-METHOD` (788), the per-accessor baked
error strings in the data section. Todo 332's narrowing deliberately EXCLUDES accessor
and writer generics (their call sites are synthesized during Pass 2 -- setf expansion,
the ambiguous slot-value fallback), so this tail needs its own attribution story before
any of it can move. Todo-317's rule (no generated literal may spell a defun name exactly)
still bounds what the strings can shrink to.

## Watch

- Todo 332's narrowing declines async programs wholesale -- the serve/fetch components
  are the biggest CLOS-heavy artifacts left, and extending it there means attributing
  the async lowering's synthesized closures. That is a second consumer for the same
  analysis, worth more total bytes than this artifact.
- A program is verified only on all four backends; zlib needs `-W exceptions=y` on both
  wasm runs; gunzip a fixture and compare byte for byte.
- Measure the row, not the probe: todos 329, 331 and 332 all delivered from a different
  mechanism than their first probe suggested (332's biggest single win was the
  `%DECOMPRESS/NULL-STREAM`/`STREAM-STREAM` helper family, not the stubs).
- `size-report/measure.sh` re-measures the row; prose travels in
  `size-report/notes/wasm-flags.md`, never `results/`.
