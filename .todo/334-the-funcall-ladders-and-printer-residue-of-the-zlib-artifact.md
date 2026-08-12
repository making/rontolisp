# The funcall ladders and printer residue of the zlib artifact

Difficulty: High

`size-report/programs/zlib/zlib.lisp` at `--optimize=size` is **77,444 B** after todo
333's slot-carried case fold plus the unreferenced-labels drop (code 70,842 in 258
functions). The format dispatch is settled end to end -- caller literal -> `make-dstate`
-> the `data-format` slot -> the state machine's `ecase` arms -- and the zlib half
(`%MAKE-ZLIB-HEADER`, the adler32 runtime, four state closures) is gone. Same recipe as
todos 331-333:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -Drontolisp.wasm.debug-func-sizes=true -jar $JAR \
  size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size 2>sizes.txt
```

By owner: chipz itself ~31,800 B (110 entries), lambdas 10,397 (49), emitted runtime
`FUNC_*` ~12,100, funcall dispatch ladders 5,475 (5), spliced Lisp runtime ~5,100,
`_toplevel_chunk` 4,214, CLOS `%setf-` writers 1,216 (21).

## 1. The funcall dispatch ladders: 5,475 B in 5 ladders

`_dispatch_1` 1,914, `_dispatch_2` 1,371, `_dispatch_5` 841, `_dispatch_3` 815,
`_dispatch_4` 534. The question of todos 331-333 stands: whether every listed target is
really reachable as a first-class value (`-Drontolisp.debug.dispatchgate=true` lists the
funcIds that materialize). Most of the remaining 49 lambdas are the inflate state
machine's per-state closures -- all genuinely stored into `(inflate-state-state state)`
and funcalled, so the ladders they need are real; what may not be real is every ARITY
ladder listing every closure.

## 2. Printer/reader residue in a program that writes only octets: ~4.7 KB

Unchanged from todo 332's lever 4: `FUNC_PRINC_VAL` 1,739, `FUNC_CHARVEC_TO_STR` 653,
`FUNC_READ_CHAR` 649, `FUNC_PRINT_F64_NO_NL` 379, the i32/i64 printers, the write
helpers. The program's only I/O is `read-sequence` in and `write-sequence` out; ask what
still holds the VALUE printer and why `read-char` is alive in a byte-only reader.

## 3. Bignum + ratio runtime

Reachable only as the overflow fallback of generic arithmetic; chipz declares
`(unsigned-byte 32)` throughout, so type-directed narrowing is available in principle --
and it changes what an undeclared overflow does, so it is a semantics decision (todo 331
measured the floor: one `(logand (+ a b) 255)` defun already pays 1,392 B of it).

## 4. The CLOS accessor tail

21 `%setf-` writers (1,216 B), `%NO-APPLICABLE-METHOD`, the per-accessor baked error
strings in the data section -- plus the adler32 accessor residue todo 333 exposed
(`CHIPZ::ADLER32` 68, `%setf-CHIPZ::ADLER32` 70, `%ADLER32--m0` 36 survive with no
adler32 runtime left to serve). Todo 332's narrowing deliberately EXCLUDES accessor and
writer generics (their call sites are synthesized during Pass 2), so this tail needs its
own attribution story. Todo-317's rule (no generated literal may spell a defun name
exactly) still bounds what the strings can shrink to.

## Watch

- Todo 332's narrowing declines async programs wholesale -- the serve/fetch components
  are the biggest CLOS-heavy artifacts left, and extending it there means attributing
  the async lowering's synthesized closures. A second consumer for the same analysis,
  worth more total bytes than this artifact.
- Todo 333's slot flow has its own standing-down cliffs worth re-checking against real
  libraries: a runtime `read` anywhere stands the whole slot tracking down, and a
  computed-class `make-instance` widens every slot -- if a measured program loses a fold
  to one of these, the cliff is the lever (`.kb/library-defun-pruning.md`).
- A program is verified only on all four backends; zlib needs `-W exceptions=y` on both
  wasm runs; gunzip a fixture and compare byte for byte.
- Measure the row, not the probe: todos 329-333 all delivered from a different mechanism
  than their first probe suggested (333's biggest single lever turned out to be the
  labels expansion, not the case pruner alone).
- `size-report/measure.sh` re-measures the row; prose travels in
  `size-report/notes/wasm-flags.md`, never `results/`.
