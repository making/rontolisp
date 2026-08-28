## What is measured

[`programs/`](../programs) holds the benchmarks. Each is one file of portable
ANSI Common Lisp -- no library, no implementation-specific form, no declaration
-- that defines `bench` and ends with the same four-line footer: read the clock,
call `bench`, read the clock, print `result=<answer> ms=<elapsed>`.

| Benchmark | What it exercises |
| --- | --- |
| [`fib`](../programs/fib.lisp) | 18 million calls of a two-line recursive function: call overhead and small-integer arithmetic, nothing else |
| [`mandelbrot`](../programs/mandelbrot.lisp) | A 400x400 grid at 200 iterations: `double-float` arithmetic in a tight loop, no arrays |
| [`matmul`](../programs/matmul.lisp) | 200x200 `double-float` matrix multiply: the same arithmetic, plus two-dimensional `aref` on every operand |
| [`sieve`](../programs/sieve.lisp) | Eratosthenes to 2,000,000, three times: element access on a general vector |
| [`sort`](../programs/sort.lisp) | `sort` over a 400,000-element vector: a built-in sequence function and the 8 million predicate calls it makes |
| [`hash`](../programs/hash.lisp) | 1,600,000 `gethash` writes then as many reads: table growth and lookup |
| [`string`](../programs/string.lisp) | 30,000 rounds of building an 880-character string through `with-output-to-string` and `search`ing it |
| [`clos`](../programs/clos.lisp) | 5 million calls to a two-method generic function: dispatch, with method bodies small enough to disappear |
| [`bignum`](../programs/bignum.lisp) | 48 runs of 3000!, which passes 32,000 bits: arbitrary-precision multiplication |
| [`list`](../programs/list.lisp) | 240 rounds of cons / `mapcar` / `reverse` over a 20,000-element list: the allocation-heavy one, where the garbage collector shows up |

The sizes are chosen so SBCL lands between 90 ms and 460 ms on each: large
enough that process noise does not dominate, small enough that the slowest
implementation in the table still finishes.

**Every benchmark's answer is the same integer on every implementation**, and
the harness refuses a run that prints a different one. That rules out the
classic way a cross-implementation benchmark goes wrong -- one implementation
being fast because it did less -- and it is why nothing here calls `random`
(every implementation seeds and steps its own differently) or prints a float.

## Reading the numbers

**The run-time number comes from inside the program**, between two
`get-internal-real-time` calls around `bench`, so it holds no process startup,
no image load and no compilation. `internal-time-units-per-second` is 1000 on
rontolisp and ABCL and a million on SBCL and ECL, so the footer normalises to
milliseconds rather than reporting the raw count.

**Startup is its own row**, measured as the wall clock of a program that
computes nothing. It is the number that decides whether an implementation suits
a short-lived process, and it is not part of any other row.

**Every implementation runs a compiled artifact.** Loading a benchmark as
source would measure ECL's bytecode interpreter (`fib` in 3,030 ms rather than
the 397 ms its C-compiled output takes) and ABCL's evaluator (10,181 ms rather
than 1,606 ms) -- modes nobody deploys. So the harness compiles first and
times the load of the result:
`.fasl` on SBCL, `.fas` on ECL, `.abcl` on ABCL, a `.class` or a `.wasm` on
rontolisp. The rontolisp interpreter is the deliberate exception, because
interpreting the source is what that mode IS.

**Best of N, not the mean.** A slow run is contention or a garbage collection
that a faster run shows was avoidable; the fastest run is the one the machine is
actually capable of. Under CI, treat a change under ~10% as noise.

**`timeout` is a result.** A cell that cannot finish inside the per-run budget
is reported as `timeout` rather than dropped, and it is not retried. It means
what it says: on that implementation the benchmark is slower than the budget, by
an unknown factor.

**No declarations anywhere.** Every benchmark is written the way ordinary
portable Common Lisp is written, with no `declare` and no `optimize`. An
implementation that infers types from the code is rewarded for it here; one that
needs to be told will look worse than a tuned benchmark would make it look. That
is the comparison this report intends -- what the same portable source costs --
and not the one where each implementation gets its own hand-tuned variant.

## The comparison

SBCL compiles to native code ahead of the run and is the reference column
because it is the fastest free Common Lisp, not because it is the most
comparable: it and rontolisp target different machines.

ECL compiles through C: `compile-file` shells out to the system C compiler,
which costs it about twenty times SBCL's build column and buys it a run column
in the same range as SBCL's. ABCL's build column is the slowest of the six
anyway, and for an unrelated reason -- most of it is the JVM starting up, the
same seconds its `startup` row reports.

ABCL and rontolisp's JVM backend are the pair that share a machine: both emit
JVM bytecode, both run under the same `java`, both pay the same JIT warm-up and
the same startup. Differences between those two columns are differences in code
generation and runtime representation, with the platform held fixed.

rontolisp's wasm column runs under wasmtime, and its interpreter column walks
the AST. Neither has a counterpart among the other three; they are in the table
because they are what rontolisp also is.
