# Two more `elt`-per-element list walks, one on each side of the fence

Difficulty: Medium

Found 2026-08-31 while doing `.todo/593` (`search`/`mismatch` over lists were
quadratic on the three compile paths; fixed there with a cons cursor in the
prelude source). 593's brief asked whether the defect was a family. It is. The
PRELUDE is clean -- `search` and `mismatch` were the only two entries left with
the shape, and `count-if-not` already had the cursor -- but two more sites index
a list with `(elt seq i)` (or its Java twin) inside a loop, and each is quadratic
on exactly the half of the world the other one is fine on.

Both were measured under the machine-exclusive lock on an Apple M4 Max, 100-200
timed iterations after an equal warm-up, ms per call. Haystack elements are
`(mod i 7)`.

## 1. `make-array :initial-contents <list>` -- quadratic on all THREE compile paths

`LispMacroExpander.lowerInitialContentsMakeArray` builds

```lisp
(do ((__mk_i 0 (+ __mk_i 1))) ((>= __mk_i __mk_n) __mk_arr)
  (%aset __mk_arr __mk_i (elt __mk_c __mk_i)))
```

and its own comment says why -- "the contents can be ANY sequence (cl-ppcre
passes a string), so the fill indexes it with elt instead of walking it as a
list". That is right for a string or a vector and O(n^2) for a LIST. The
interpreter never sees it (native `make-array`).

| n | 250 | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: | ---: |
| WASM p1 `(make-array n :initial-contents <n-element list>)` | 0.035 | 0.14 | 0.535 | 2.13 | **8.29** |
| ratio to the previous column | -- | 4.0x | 3.8x | 4.0x | 3.9x |
| JVM `.class`, same call | 0.07 | 0.23 | 0.835 | 3.13 | **12.2** |

For scale at n = 4000: the same call with a VECTOR argument is 0.12 ms (WASM) /
0.39 (JVM), `(coerce <list> 'vector)` is 0.055 / 0.025, and the INTERPRETER's
native `make-array` answers the list in **0.045 ms**. So a compiled program is
184x (WASM) to 271x (JVM) slower than the interpreter on this call -- the same
inversion 593 was written about.

**The fix** is the cursor pair that already lives in that file:
`LispMacroExpander.readElement` (`(if (consp c) (car c) (elt seq i))`) and
`advanceCursor` (`(if (consp c) (cdr c) c)`), written for `map-into` a few
hundred lines away. Add a cursor `do` binding beside `__mk_i` and read through
it. Two things to get right:

- **Do not add a `(null cell)` stop.** `(elt <proper list> i)` past the end is
  NIL on all four backends and `(elt <dotted list> i)` past the tail SIGNALS;
  both must stay. The cursor falling back to the original `elt` call whenever it
  is not a cons reproduces both, which is what 593 did -- read the "must stay a
  strict subset" reasoning in `.kb/seq-coerce-runtime.md` before choosing.
- **The rank >= 2 lowering has the same shape and is harder.**
  `buildNestedInitialContentsFillLevel` binds each row with
  `(elt <the level's sequence> idx)` inside a `dotimes` and stores leaves with
  `%row-major-aset`; a nested LIST of lists is therefore quadratic at every
  level. Fixing rank 1 alone is a legitimate first commit -- say so in the
  message -- but the nested one is the same defect.

Watch the size: 593's cursor cost +634 B of `--optimize=size` wasm per prelude
body because it ADDS a branch in front of the `elt` loop rather than replacing
it. `make-array :initial-contents` is emitted PER SITE, not through a shared
callee, so the same +N lands at every site. Measure before/after with
`size-report/` and a one-site probe; if the per-site growth is what decides it,
the honest alternative is a shared `%fill-from-sequence` helper (the
`.kb/sequence-op-runtimes.md` shape) rather than a wider inline body.

## 2. The INTERPRETER's native `replace` with a LIST source

The exact mirror. `.kb/sequence-op-runtimes.md` records todo-413 fixing this on
the compile paths -- "a LIST source is walked with a cursor, not indexed with
`elt` ... 1,570 -> ~450 bytes, and O(n^2) -> O(n)". The interpreter's native
`replace` (`Environment.createGlobal`, the `LispNames.REPLACE` entry) still calls
`sequenceRef(source, start2 + k)` in its element loop, and `sequenceRef`'s list
arm walks from the head every time.

| n | 500 | 1000 | 2000 | 4000 |
| --- | ---: | ---: | ---: | ---: |
| interpreter `(replace <n-element array> <n-element list>)` | 0.20 | 0.67 | 3.03 | **26.5** |
| JVM `.class`, same call | 0.01 | 0.02 | 0.05 | 0.06 |
| WASM p1, same call | 0.01 | 0.02 | 0.03 | 0.05 |

**440x slower than the compiled program at n = 4000**, and worse than quadratic
past n = 2000 (allocation pressure on top of the walk). A list DESTINATION is
fine everywhere (0.01-0.11 ms at n = 4000): that arm already walks a cursor, six
lines above the one that does not.

**The fix** is local: hoist a `LispVal cur` cursor for a `LispCons`/`LispNil`
source and advance it once per element, exactly as the list-destination arm below
it does, leaving `sequenceRef` for every other representation. All three target
arms (`LispArray`, `LispIntVector`, list) read the source the same way, so one
cursor serves all three.

**Keep the error.** `sequenceRef` SIGNALS `sequence-ref: index N out of range`
when the list runs out, and `.kb/sequence-op-runtimes.md` records that as the one
surviving three-way disagreement with the compile paths (which truncate). A
cursor that stops silently would erase it and take the disagreement with it,
which is a behavior change hiding inside a performance fix -- signal from the
cursor instead.

## What is NOT in scope

- The prelude is clean. `(elt ...)` appears in `LispPreludeLibrary.SOURCES` only
  in `count-if-not` (already cursor-guarded), `search` and `mismatch` (593).
- `src/main/resources/**.lisp` has three `elt` uses, none in a loop
  (`uiop-utility.lisp:404`, `usocket.lisp:138-139`).
- `Environment.sequenceRef` is called in a loop from ONE place, the `replace`
  entry above.

## The reproduction

```lisp
(defun mk (n) (let ((out nil)) (dotimes (i n) (setq out (cons (mod i 7) out))) (nreverse out)))
(defvar *k* 0)
(defmacro timed (label n &body body)
  `(progn
     (dotimes (i ,n) ,@body)
     (let ((start (get-internal-real-time)))
       (dotimes (i ,n) ,@body)
       (format t "~a ~a~%" ,label (round (* 1000 (- (get-internal-real-time) start))
                                         internal-time-units-per-second)))))
(defvar *l2000* (mk 2000))
(defvar *l4000* (mk 4000))
(defvar *d4000* (make-array 4000))
(timed "mkarr-2000" 200 (setq *k* (aref (make-array 2000 :initial-contents *l2000*) 0)))
(timed "mkarr-4000" 200 (setq *k* (aref (make-array 4000 :initial-contents *l4000*) 0)))
(timed "replace-list-src-4000" 100 (setq *k* (aref (replace *d4000* *l4000*) 0)))
(format t "sink ~a~%" *k*)
```

Run it on the interpreter, then `-o q.wasm` under `wasmtime`, then
`-o Q.class --class-name Q`. Doubling n should quadruple the `mkarr` rows on the
two compiled backends and the `replace` row on the interpreter, and leave the
other halves flat. Take every timing under the machine-exclusive lock.

## Where the numbers go

`.kb/seq-coerce-runtime.md` holds the `search`/`mismatch` family and the
declining-arm design; `.kb/sequence-op-runtimes.md` holds `replace`/`fill`/
`map-into` and already has the compile-path half of item 2 -- the interpreter
half belongs beside it. `make-array :initial-contents` has no `.kb` file of its
own; `.kb/seq-conversion-runtime.md` is the nearest home if a shared helper is
what lands.
