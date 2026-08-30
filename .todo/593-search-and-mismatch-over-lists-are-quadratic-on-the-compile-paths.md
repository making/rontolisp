# `search` / `mismatch` over LISTS are quadratic on the three compile paths

Difficulty: Medium

Found 2026-08-31 while doing `.todo/591` (`search` at 104 us on the interpreter).
591 fixed the INTERPRETER with a native declining arm (`eval/SequenceScanFast`,
`.kb/seq-coerce-runtime.md`), which materializes a list operand into a
`LispVal[]` once and so is O(n*m). **The three compile paths still run the prelude
`defun` unchanged, and that body indexes both operands with `(elt seq i)`.**

`(elt list i)` lowers to `(nth i list)`, an O(i) walk from the head. So on the
JVM and both WASM backends:

- `search` over two lists is **O(n^2 * m)**, not O(n*m)
- `mismatch` over two lists is **O(n^2)**, not O(n)

This is the same defect `.kb/sequence-op-runtimes.md` records fixing in
`replace`'s list SOURCE arm (an `elt` per element -> a `nthcdr`/`cdr` cursor,
1,570 -> ~450 bytes and O(n^2) -> O(n)), and the same one `count-if-not`'s prelude
entry already avoids -- read that entry's `lst`/`cell` cursor first, it is the
shape to copy.

## Measured

Apple M4 Max, under the machine-exclusive lock, after 591 landed. Haystack is
`(mod i 7)` over n elements, needle `'(3 5)` -- which never occurs, so every
outer position is attempted. 20 timed iterations after a 20-iteration warmup, ms
per call.

**The wasm-GC backend has no JIT, so its ladder is the clean proof: doubling n
QUADRUPLES the time.**

| n | 250 | 500 | 1000 | 2000 |
| --- | ---: | ---: | ---: | ---: |
| WASM preview 1, `(search '(3 5) <n-element list>)` | 0.05 | 0.20 | 0.70 | **2.65** |
| ratio to the previous column | -- | 4.0x | 3.5x | 3.8x |
| JVM `.class`, same call | 0.30 | 4.8 | 11.8 | **44.6** |

Against the interpreter's arm on the same 2000-element input (100 timed
iterations after a 100-iteration warmup, ms per call):

| form | interpreter arm | the same prelude `defun`, reached with `(funcall #'search ...)` | |
| --- | ---: | ---: | ---: |
| `(search '(3 5) <2000-element list>)` | **0.06** | 16.6 | 277x |
| `(mismatch <2000 elements> <itself>)` | **0.11** | 14.6 | 133x |
| `(mismatch <2000-char string> <itself>)` | **0.01** | -- | |

So a 2000-element list `search` is 0.06 ms on the interpreter and 2.65 ms (WASM)
/ 44.6 ms (JVM) on the compile paths -- the interpreter is now 44x to 740x
FASTER than the compiled program, which is the wrong way round and is entirely
the `nth` walk.

## Why it was NOT fixed in 591

591's brief was the interpreter's 104 us, and its arm is interpreter-only by
design (a cross-backend primitive would cost every wasm module bytes -- see
`.kb/seq-coerce-runtime.md`). At the sizes every current consumer uses the
quadratic is invisible: the benchmark row there is a 2-element needle in a
10-element list, 0.15 us on WASM and 0.45 on the JVM. No shipped `search` caller
passes a long list -- `uiop-utility.lisp`'s `frob-substrings`,
`cffi-rontolisp.lisp` and `examples/db/database-url.lisp` all pass STRINGS. It is
a latent complexity bug, not a current profile, and fixing it means editing the
prelude source, which moves all four backends at once and needs its own
verification pass.

## Where the fix goes

`eval/LispPreludeLibrary.SOURCES`, the `LispNames.SEARCH` and `LispNames.MISMATCH`
entries. **One edit moves all four backends at once** (the interpreter splices the
same source for the shapes its arm declines, and `CompileFrontend` splices it for
the compile paths), which is exactly why it was left as its own item rather than
folded into an interpreter-only change.

The shape, from `count-if-not`:

```lisp
(let* ((lst (listp sequence))
       (cell (if lst (nthcdr i sequence) nil)))
  ... (if lst (car cell) (elt sequence i)) ... (setq cell (cdr cell)))
```

`mismatch` takes it directly -- two cursors advancing together with the two
indices. `search` is harder and is the reason for the Medium: its inner loop
restarts at every outer position, so the haystack needs a cursor that advances one
`cdr` per OUTER step and is copied into a scratch cursor for the inner walk, and
the needle needs its own cursor re-seeded per outer step (or, since `start1`/`end1`
bound a window that never moves, materialized once with `nthcdr`).

## Things to get right

- **Both operands can be lists independently.** The dispatch is per operand, and
  the existing `(elt seq i)` must stay for a string or an array -- do not funnel
  through `(coerce seq 'list)`, which would allocate on the compile paths where
  the current code does not, and would change the answer for a non-sequence
  (`(search "ab" 5)` is NIL today, an oddity `.kb/seq-coerce-runtime.md` records).
- **`:start1`/`:end1`/`:start2`/`:end2` bound the walk**, and an out-of-range bound
  currently produces whatever the first `elt` call produces. The interpreter's arm
  DECLINES every out-of-range bound precisely so the prelude keeps owning that;
  if the rewrite changes it, `SequenceScanFast`'s decline rules and the
  `search-and-mismatch-across-representations` ci-spec case have to move with it,
  in the same commit (see that file's "must stay a strict subset" trigger). The
  `end2 99` and `start1 3 :end1 1` rows in that case exist to catch exactly this.
- **Size.** `search` is 15 bytes a site on wasm-GC because the prelude `defun` is
  already a shared callee (`.kb/sequence-op-runtimes.md`); a cursor branch grows
  that ONE body, not the sites. Measure `size-report` before/after anyway --
  `replace`'s equivalent change made its helper smaller, not bigger.
- Re-run the ci-spec case and
  `LispEvaluatorTest.theNativeSearchAndMismatchArm*` / the two per-backend
  `...AnswerTheSameAsTheInterpretersNativeArm` tests: they compare the arm and the
  defun on the same program, so a rewrite that drifts shows up there first.

## The reproduction

```lisp
(defun mk (n) (let ((out nil)) (dotimes (i n) (setq out (cons (mod i 7) out))) (nreverse out)))
(defvar *needle* '(3 5))          ; never occurs: 3 is always followed by 4
(defvar *hay* (mk 2000))
(defmacro timed (label n &body body)
  `(progn
     (dotimes (i ,n) ,@body)       ; warm up, then measure
     (let ((start (get-internal-real-time)))
       (dotimes (i ,n) ,@body)
       (format t "~a ~a~%" ,label (round (* 1000 (- (get-internal-real-time) start))
                                         internal-time-units-per-second)))))
(timed "search" 20 (search *needle* *hay*))
(timed "mismatch" 20 (mismatch *hay* *hay*))
```

Run it on the interpreter, then `-o q.wasm` under `wasmtime`, then
`-o Q.class --class-name Q`. Double `2000` and the two compiled numbers should
quadruple while the interpreter's stays flat. Take every timing under the
machine-exclusive lock. The JVM row above is JIT-noisy at 20 iterations (its
n=250 entry is under-measured); the wasm-GC ladder is the one to trust.
