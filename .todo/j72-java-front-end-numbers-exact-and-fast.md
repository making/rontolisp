# j72. Java front end: the numbers, exact and fast

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**First target (j68): all of this item** -- the primitive types and their operators are
the language.

After j68. Measured by the spike (`.todo/artefacts/j68-java-front-end-spike/`, `bench.sh`,
2026-11-17; the compiled class / a precompiled wasm, best of three):

| program | seconds |
|---|---|
| `WrapInt`: 30,000,000 `h = 31 * h + i % 7` | 0.11 / 0.08 |
| `WrapLong`: 10,000,000 steps of `g = g * 6364136223846793005L + 1442695040888963407L` | 1.16 / 2.43 |
| `Fib`: `fib(35)` | 0.09 / 0.08 |
| `Sieve`: an `int[5000001]` sieve, then 20 walks of a 2^18-leaf tree by virtual calls | 0.33 / 0.17 |

- **`long` leaves the fixnum range before it wraps**: `(* g c)` is a bignum, then
  `logand`. A 64-bit wrapping add, subtract and multiply as a primitive of each backend
  (the JVM's is `ladd`/`lmul` itself, wasm's `i64.add`/`i64.mul`), named for what it does,
  not for Java (`.kb/adding-primitives.md`, `.kb/integer-bitwise-fast-paths.md`).
  Target: `WrapLong` under `WrapInt`'s time per step on both.
- **`int` is already there**: the unconditional mask is compiled raw by both backends,
  and `Fib` and `WrapInt` are at the start-up cost of javac's class (0.07 s). Keep it so:
  a lowering change that wraps the mask in a `let` or a test loses 4x (the artefact
  README). Declarations are not the lever -- `(declare (type fixnum ..))` and `the` on the
  same loop measured no gain on the JVM and 0.06 -> 0.29 s on wasm; that slowdown is a
  defect of its own to look at (`.kb/declarations-type-checks.md`).
- Exactness the spike leaves out: a double division by zero is `Infinity`/`NaN` (the
  spike's `/` signals), `NaN` comparisons, `-0.0`, `(int)` of a double saturating, `float`
  as single precision (the spike reads it as `double`), `short`/`byte` narrowing,
  `Integer.MIN_VALUE / -1`, shifts of a `long` by an `int`, `Math` functions' exact
  results, `strictfp`-free rounding as the JDK's.
- Arrays: `int[]` is `(signed-byte 32)`, `double[]` `double-float`; add `long[]`,
  `char[]`, `byte[]`, `boolean[]`, the store check, `ArrayIndexOutOfBoundsException`'s
  message.
- `Sieve` is 0.33 s on the JVM against 0.17 s on wasm and the spike did not separate its
  halves: find whether the `int[]` loop or the virtual call (a `cond` over structure
  predicates) owns the difference; for the call, measure a generic function and a
  per-class method table against the `cond` before choosing.

Each number above is rewritten in `.kb/java-frontend.md` with its date when it moves.

## State after j68 (2026-11-17, `.kb/java-frontend.md`, "Measured")

The front end's lowering measures as the spike's on Linux x86-64 (`Fib` 0.19 / 0.18 s, `WrapInt`
0.21 / 0.11, `Sieve` 1.22 / 0.61, `WrapLong` 3.46 / 7.46). Already exact: `(int)`/`(long)` of a
double (toward zero, the type's ends, `%java-double-to-int`), a double division by zero and
`0.0 / 0.0` (the host's own `Infinity`/`NaN`, four backends), `Math.sqrt` of a negative,
`Integer.MIN_VALUE / -1`, shift counts, `Double`'s text. `float`, `byte`, `short` are refused.
`long[]`, `char[]`, `boolean[]` are untyped vectors.

After j69 (2026-11-17): a `float` literal and the `float` type are still refused here; a record
with a `double` component has no `hashCode` (`Double.hashCode` is `%java-hash-code`, which
refuses a float); `Double.compare` is exact (`%java-double-compare`); `new long[n][m]` leaves are
untyped vectors.
