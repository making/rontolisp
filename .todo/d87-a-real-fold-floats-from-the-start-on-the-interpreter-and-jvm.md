# d87. A real `+ - * /` fold floats from the start on the interpreter and the JVM

Difficulty: Medium

A real fold with a float anywhere converts EVERY argument to a double first on the
interpreter (`Environment`'s `+ - * /`: `hasDouble(args)` then one double loop) and on the
JVM, so an exact prefix rounds step by step. SBCL folds pairwise from the first argument, so
the exact prefix stays exact until it meets the float. The WASM backends fold pairwise
through variables (their `_rat_*` helpers), so the backends disagree with each other.
Measured 2026-10-07 (SBCL 2.2.9, `*read-default-float-format*` `double-float`), `rf-id` the
identity function:

| call | SBCL | interpreter | JVM | P1, component |
|---|---|---|---|---|
| `(+ 1/10 1/5 0.0)` | `0.3` | `0.30000000000000004` | `0.30000000000000004` | `0.30000000000000004` |
| `(+ (rf-id 1/10) (rf-id 1/5) (rf-id 0.0))` | `0.3` | `0.30000000000000004` | `0.30000000000000004` | `0.3` |
| `(- (rf-id 1/10) (rf-id -1/5) (rf-id 0.0))` | `0.3` | `0.30000000000000004` | `0.30000000000000004` | `0.3` |
| `(* (rf-id 1/10) (rf-id 3) (rf-id 1.0))` | `0.3` | `0.30000000000000004` | `0.30000000000000004` | `0.3` |

With a complex argument the fold is already pairwise on all four backends (the complex
`+ - /` became pairwise on the interpreter with SBCL's division dispatch;
`.kb/jvm-complex.md`, "Complex division is SBCL's dispatch").

Plan: fold pairwise on every backend, literals included (the WASM literal row still floats
first), and pin it with SBCL's rows in a shared fixture. The compiled f64 paths
(`isDefinitelyDouble`, the unboxed double fold, int fusion) are raw BECAUSE they convert
first, so a pairwise exact prefix there means folding the exact operands ahead of the first
float generically; find every such path before changing the emitters.
