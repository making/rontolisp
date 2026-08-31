# A sized string specifier measures the fill pointer, not the dimension

Difficulty: Medium

`(typep x '(string n))` -- and every sibling spelling, `(simple-string n)`,
`(base-string n)`, `(vector character n)`, `(simple-array character (n))` --
compares `(length x)` against `n`. `length` on a fill-pointered character
vector is the FILL POINTER; CL's sized specifier means the array DIMENSION.
Measured 2026-08-31, all four backends agreeing with each other and
disagreeing with SBCL 2.2.9:

```lisp
(defvar *cv* (make-array 4 :element-type 'character :fill-pointer 0))
(typep *cv* '(string 0))                ; here T   SBCL NIL   (dimension is 4)
(typep *cv* '(string 4))                ; here NIL SBCL T
```

The array ARM already reads the right thing -- `(vector t 4)` over a
fill-pointered general array answers `T` here and in SBCL, because it goes
through `array-dimension`. Only the STRING arm is wrong, and it is wrong
because it cannot use those functions: `array-dimension` / `array-rank` refuse
a string on the compile paths (`.todo/464`, `.kb/declarations-type-checks.md`,
"The array type lattice" -- the string arm "sizes itself with `length`, since
the array-info functions do not take a string on the compile paths").

So the item is that refusal, not the comparison. Either:

- teach `array-dimension` / `array-rank` to take a string on the compile
  backends (an immutable runtime string answers its own length, a character
  vector its `dims[0]`, a string view its own span) -- the same shape
  `%simple-array-p` took in `.todo/610`, which had to become a new TOTAL
  internal predicate for exactly this reason; or
- give the string arm its own internal `%string-dimension` and leave the public
  array-info surface alone.

Measure both against the size floors `.todo/610` recorded (the array-free
program must stay byte-identical, and the `(typep v 'simple-vector)` /
computed-`typep` floors are the rows to compare), then take the cheaper one.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case,
with SBCL 2.2.9's answers on the same program. The existing
`simple-type-name-typep-simplicity` case is the natural place to extend --
its `(typep *tps-cv* 'string)` row is next door.
