# `row-major-aref` on a string answers three different things, and its place cannot take an immutable one

Difficulty: Low

Found 2026-08-30 while closing `.todo/581`, sweeping the indexed- and bulk-write family
for source-literal corruption. `row-major-aref` is the one place head of the family that
is not wired for a string; `aref` / `char` / `schar` / `elt` are all correct on all four
(`.kb/string-write-runtime.md`).

Measured 2026-08-30, all four backends:

## 1. The READ answers three different things

```lisp
(row-major-aref "abc" 0)
```

| backend | answer |
|---|---|
| interpreter | `Unhandled condition: ROW-MAJOR-AREF expects an array, got "abc"` |
| JVM | `#\a` |
| WASM Preview 1 / component | `wasm trap: cast failure` |

The JVM one is the CL-correct answer (a string IS a rank-1 array, and
`(aref "abc" 0)` is `#\a` on all four). So the interpreter is missing the string arm and
WASM is missing it in the trapping way. Same shape as `.todo/464`, which lists the five
array-INFO functions with the identical JVM/WASM split; `row-major-aref` is simply not
on its list.

## 2. The WRITE takes a mutable character vector and nothing else

```lisp
(let ((a (make-string 3 :initial-element #\a))) (setf (row-major-aref a 0) #\Z) a)
;; "Zaa" on all four -- correct
```

```lisp
(defun r () "abc")
(let ((a (r))) (setf (row-major-aref a 0) #\Z) a)
```

| backend | answer |
|---|---|
| interpreter | `Unhandled condition: %ROW-MAJOR-ASET on a string literal requires a variable string place` |
| JVM | `ClassCastException: java.lang.String cannot be cast to java.util.ArrayList` |
| WASM Preview 1 / component | `wasm trap: cast failure` |

The interpreter's message is deliberate (`.todo/581`: `%aset`/`%row-major-aset` carry no
rebind hook, so like `%schar-set` as a first-class value they refuse a source constant
rather than rewrite the program text). The compile paths do not refuse -- they crash,
and they crash on any IMMUTABLE string, a `copy-seq` result as much as a literal,
because their `%row-major-aset` knows only the general-array and packed representations.

## Do

Route the string case through the arms that already answer it, rather than adding a
fourth spelling of the string test:

- **The place**: `LispMacroExpander.expandSetf` routes `aref` / `svref` / `elt` /
  `char` / `schar` through `%schar-set`, which owns the whole rule -- the mutable
  character-vector write, the immutable rebuild, and the rebind of the place
  (`.kb/string-write-runtime.md`). `row-major-aref` on a rank-1 target IS that place, so
  add it to that list. That also turns the interpreter's refusal above into a REBIND,
  which is the better answer and what the other four spellings already give; move
  `LispEvaluatorTest.aRowMajorWriteThroughAStringLiteralIsAnError` with the change.
- **The read**: give the interpreter's `ROW-MAJOR-AREF` the string arm it is missing
  (`Environment`, beside `AREF`'s) and both WASM sites the one the JVM already has.

Failing tests on all four first, then a `ci-spec.yaml` case beside
`string-literal-bulk-write-cross-backend`.

## Related

- `.kb/string-write-runtime.md` -- the place-head list this one is missing from, and the
  measurement above.
- `.todo/464` -- the five array-INFO functions with the same JVM/WASM split on a string.
- `.todo/559` -- why an immutable string cannot be written at all on the compile paths;
  the crash in part 2 is that gap surfacing through a place with no functional branch.
