# `floor`-family and `gethash` secondary values do not cross a function boundary

Found 2026-07-30 while making the REPL echo multiple values (`.kb/multiple-values.md`
"The REPL echo is a consumer"), by diffing our REPL against SBCL 2.2.9 on the host.
**Pre-existing**, as old as the syntactic multiple-value tier (todo-057).

The two-value built-ins are lowered SYNTACTICALLY: `lowerMvProducer` recognizes
`(floor a b)` / `(gethash k h)` etc. only when the form is written LITERALLY in the
consumer's producer position, and derives the second value from the temps
(`(- a (* q b))`, the gensym-sentinel `eq` test). Nothing is published to the
`%mv-spill` channel, so as soon as the producer is one call away the second value
is gone:

```console
$ sbcl --noinform                        $ rontolisp
* (defun g () (floor 10 3))              > (defun g () (floor 10 3))
G                                        G
* (g)                                    > (g)
3                                        3            <- SBCL also echoes 1
1
* (multiple-value-bind (q r) (g)         > (multiple-value-bind (q r) (g)
    (list q r))                              (list q r))
(3 1)                                    (3 NIL)
```

The same holds for `gethash`'s present-p, `array-displacement`'s offset, and any
`(values ...)`-free two-value built-in: a user function that merely RETURNS one of
them is single-valued to its caller. `parse-integer` is the exception -- its
expansion writes the spill (todo-061), which is exactly the shape the others lack.

## Scope

- Make the ordinary-context expansion of the two-value built-ins publish their
  secondary value to `%mv-spill`, the way `parse-integer` and `values-list`
  already do: `expandFloorFamilyDivisor` becomes
  `(let ((q (floor (/ a b)))) (setq %mv-spill (list (- a (* q b)))) q)`, and
  `gethash` / `array-displacement` grow the equivalent. Then the syntactic
  recognition in `lowerMvProducer` becomes a pure optimization (no spill
  round-trip when the producer IS literal), not the only route.
- All four backends -- the expansions are shared -- EXCEPT scalar `--no-gc`,
  which has no reference globals and no lists (`expandValuesPrimary` already
  documents that carve-out; keep it and say why in `.kb/multiple-values.md`).
- The one-argument `(floor x)` form is a plain built-in call today, so it needs
  the same treatment or `(defun g () (floor 7.5))` stays half-fixed.

## The cost that has to be measured first, and the reason this is not obviously worth doing

Every `gethash` and every `floor` in ordinary context would allocate a one-element
spill list and write a global, on the hottest paths there are (`gethash` is the
inner loop of every hash-table-driven library, `floor` of every division-based
loop). That is a real runtime cost paid by all programs to make a REPL echo and
a rarely-used cross-function `multiple-value-bind` correct. Before landing:

- benchmark `gethash`/`floor` loops on the interpreter, JVM and wasm-GC;
- consider narrowing to a compile-time decision -- publish the spill only when
  the program contains any multiple-value operator at all
  (`injectMvSpillGlobal` already scans for exactly that, so the information is
  available), which makes programs that never consume values pay nothing.

## Non-goals

- A runtime multiple-value representation (see `.todo/213`): that would subsume
  this, and if it is ever built this item disappears with it.
- The other CL built-ins with secondary values that we return single-valued
  (`read-from-string`, `macroexpand-1`, `intern`, ...) -- tracked in
  `.todo/214`.

## Verification

- `LispEvaluatorTest`: `(defun g () (floor 10 3))` then
  `(multiple-value-bind (q r) (g) ...)` -> `(3 1)`, and
  `evalValuesAtTopLevelYieldsEveryValue` gains the `(g)` echo case.
- The same program in `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`
  and a new `ci-spec.yaml` case (native E2E re-run: the four-backend rule).
- `multiple-values-core`'s existing expectations must stay byte-identical --
  the literal-producer route must not change what it emits.
- A `--no-gc` program using `(floor a b)` must still compile (the carve-out).
- Re-diff against SBCL with the transcript in `.kb/multiple-values.md`.
