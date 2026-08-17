# 431. The WASM backends print floats with a lossy digit extraction

Difficulty: High

```lisp
(princ (list 1.21 3.14159 1.0e10 (/ 1.0 3.0) 0.1))
```

| | output |
| --- | --- |
| interpreter | `(1.21 3.14159 1.0E10 0.3333333333333333 0.1)` |
| JVM | same as the interpreter |
| WASM Preview 1 | `(1.209999 3.141589 10000000000.0 0.333333 0.1)` |
| WASM component | same |

Every float rendered on WASM is wrong unless its decimal expansion happens to
fit the fixed digit budget. `1.21` -- a literal the reader accepted verbatim --
prints as `1.209999`. `princ`, `print`, `princ-to-string`, `format ~A` and the
array/list printers all route through the same helper, so there is no spelling
that avoids it.

The interpreter and the JVM answer the SHORTEST decimal that round-trips
(Java's `Double.toString`). `WasmRuntimeBuilder.buildPrintF64Core` instead
extracts the integer part, writes `.`, then peels fractional digits one at a
time off the residual f64 -- so it accumulates the residual's own
representation error and stops at a fixed count. `NoGcWasmCompiler`'s `__ftoa`
is documented as the same algorithm hardened for linear memory, so `--no-gc`
inherits it. jzon's `schubfach.lisp` is already shimmed to
`write-string` of `princ-to-string` (`.kb/asdf.md`), i.e. "rontolisp's shape,
not schubfach's shortest round trip" -- which means JSON output on WASM carries
this too.

## Why it matters

Found by the cl-mustache spike (`.todo/425`): three mustache spec cases
("Basic / Triple Mustache / Ampersand Decimal Interpolation") fail on the two
WASM backends and nowhere else. But the spike is only the messenger --
**this is the one place in the language where WASM silently answers a different
value from the other three backends**, and it is not confined to printing: it
is the text any WASM program hands to a log, a template, an HTTP response or a
JSON body.

Difficulty is High because the fix is a real algorithm, not an arm: shortest
round-trip decimal (Grisu/Ryu/Schubfach class) hand-emitted as wasm bytes,
under the module-size pressure `.kb/format.md` already records for the float
digit printer (~3.8 KB of runtime it counts today). Expect the size report to
move; that is the trade, and the correctness side wins.

## Definition of done

`(princ x)` for any float produces byte-identical text on all four backends --
the shortest decimal that reads back as the same double -- including the
exponent spelling, the `--no-gc` `__ftoa` path and single vs double float
width. Pinned by a shared float-printing corpus run on all four
(`LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` + a `ci-spec.yaml` case) with the awkward
values in it -- `1.21`, `3.14159`, `0.1`, `(/ 1.0 3.0)`, `1.0e10`, `1d-300`,
`most-positive-double-float`, denormals, `-0.0`, infinities and NaN. Record the
algorithm and its size cost in `.kb/format.md`, and re-run `size-report` so the
new floor is on the record rather than in a commit message.

While in the area: the interpreter prints `1.0E10` where CL spells it `1.0e10`,
and prints a single-float with double-float precision (`(/ 1.0 3.0)` ->
`0.3333333333333333`, SBCL `0.33333334`). Both are the SAME decision -- what
text a float has -- so settle them in this pass rather than leaving a second
divergence behind the first.
