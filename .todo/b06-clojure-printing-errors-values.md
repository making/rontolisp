# b06: Clojure printing, error positions and builtin value positions

Difficulty: Low

## Premise (measured, 2026-09-30)

Printing (`pr`/`prn`, the `println` separator) and error positions are the two
open deviations that touch every program (`.kb/clojure-frontend.md`,
"Deviations"), plus a set of small builtin-signature gaps the spec flushed out.
Measured on the interpreter (Clojure CLI 1.12 as oracle):

- `(println "x" "y")` prints `xy` here, `x y` there (parts concatenated with NO
  separator); `(print "a" "b")` likewise `ab` vs `a b`. Pinned by the spec's
  `str-print-println-and-newline` case.
- `pr`/`prn`/`print-method`/`pprint` are `unknown name` here; collections print
  in CL notation (`#(A B)` for `[:a :b]`, `(1 2)` for `'(1 2)`) either way.
- `(str nil)` is `"NIL"` here, `""` there; `(str [1 2])` is `"#(1 2)"` here,
  `"[1 2]"` there (waits on the seq/vector print decision).
- Errors carry no source position: the reader's `LispReadException`s ARE
  positioned (`file:line:column`), but the lowering's errors (`unknown name`,
  `recur outside loop`, `takes its bindings in a vector`, ...) are not -- the
  plumbing exists (`.kb/source-positions.md`), the spike never threads it.
- Builtins in value position: `(map inc '(1 2))` is `unknown name: inc` here
  (`builtinValue` lacks `inc`/`dec`/`str`/`map`/`filter`/`reduce`/`concat`),
  `(2 3)` there. `(= a b c)` lowers to 3-argument `EQUAL`, which signals arity
  here and compares a chain there; `(not= a b c)` likewise. `(apply f args)`
  accepts any arity through CL `apply` (the `.kb` still says 2-arity only --
  stale since it always forwarded).
- `boolean?`, `false?`, `true?` wait on [[b00]]; keyword printing waits on
  [[b01]].

## Shape

- `println`/`print` with the Clojure space separator (or a documented decision
  to keep concatenating); `pr`/`prn` as `prin1` arms; `str` of `nil` as `""`.
- Thread the reader's line/column into the lowering's errors (positions on
  `unknown name` and arity errors first).
- `builtinValue`: synthesize correct function values for `inc`/`dec`
  (`(lambda (x) (+ x 1))`), `str`, and the seq verbs -- or refuse them by name
  naming the gap; variadic `=`/`not=` as a chain; fix the stale `apply` doc
  line in `.kb/clojure-frontend.md` (one sentence, mirrored nowhere -- `.kb`
  is not translated).

## Tests

- `clojure-spec.yaml`: separator/`pr`/`str`-of-nil cases and a value-position
  case (`(map inc ...)`); `ClojureLoweringTest` pins positions once threaded;
  `ClojureSpecE2eTest` on all four backends.
