# Java front end: the spike (2026-11-17)

`JavaSpike.java` reads one `.java` file -- its own lexer and parser, a class table, static
types, overload resolution -- and prints Common Lisp core forms as text. Nothing in `src/`
changed: the text goes through the existing pipeline.

```bash
./mvnw clean spring-javaformat:apply package -DskipTests     # the jar the scripts run
cd .todo/artefacts/j68-java-front-end-spike
./run4.sh Shapes     # lower, run on all four backends, diff against `java programs/Shapes.java`
./bench.sh Fib       # best of three: javac's class, the compiled class, a precompiled wasm
```

Needs a JDK 25 (`Compact.java` is a compact source file) and wasmtime.

| program | what it holds |
|---|---|
| `Basics.java` | recursion, `int` overflow, `long`, shifts, division, arrays, nested loops with `break`/`continue`, overloads by type, `char` arithmetic, `StringBuilder`, string concatenation of every primitive |
| `Shapes.java` | interfaces, an abstract class, three levels of `extends`, `super(..)`/`this(..)`/`super.m()`, a static field, `toString`, user exceptions, `try`/`catch`/`finally`, `ArrayList`, `HashMap`, lambdas capturing a local |
| `Compact.java` | top-level methods, instance `main`, `var`, `IO.println` |
| `Fib.java`, `WrapInt.java`, `WrapLong.java`, `Wrap.java`, `Sieve.java` | timing |

Measured 2026-11-17 (Apple silicon, JDK 25.0.3, wasmtime 50.0.1): the first three print what
the JDK prints on the interpreter, the JVM, WASM preview 1 and the component.

| program | javac's class | compiled class | precompiled wasm | `.class` / `.wasm` bytes |
|---|---|---|---|---|
| `Fib` (`fib(35)`) | 0.07 s | 0.09 | 0.08 | 26,339 / 8,420 |
| `WrapInt` (30,000,000 `h = 31 * h + i % 7`) | 0.07 | 0.11 | 0.08 | |
| `WrapLong` (10,000,000 steps of a 64-bit LCG) | 0.06 | 1.16 | 2.43 | |
| `Sieve` (to 5,000,000, then 20 walks of a 2^18-leaf tree by virtual calls) | 0.08 | 0.33 | 0.17 | 41,218 / 19,940 |

The first column is mostly the JVM's start.

**The wrap's shape is the speed.** `%i32` as the unconditional
`(- (logand (+ x 2147483648) 4294967295) 2147483648)` is what the table shows. The same
macro written to wrap only a result that left the range -- `(let ((v x)) (if (< lo v hi) v
(mask v)))` -- measured `Fib` 0.34 / 0.32, `WrapInt` 0.25 / 0.62, `Sieve` 0.61 / 0.29: the
`let` and the test take the expression out of what the backends' integer paths recognise
(`.kb/jvm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`). Declarations do not buy it back:
`fib` and the 30,000,000-step loop as plain Common Lisp are 0.06 / 0.09 and 0.06 / 0.06,
with `(declare (type fixnum ..))`, `the` and an `ftype` 0.07 / 0.09 and 0.07 / 0.29 -- the
declared loop is slower on wasm.
