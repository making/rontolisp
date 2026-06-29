# 28 - JVM (and syntactic) `abs` miscompile on a float reaching it through a variable

## Symptom

On the **JVM** backend, `abs` traps when its argument is a float that is NOT a
literal (and whose argument expression contains no float literal either) -- e.g. a
bare float variable:

```lisp
(let ((x -0.5)) (abs x))   ; JVM: java.lang.ClassCastException:
                           ;   class java.lang.Double cannot be cast to
                           ;   class java.math.BigInteger  (at _abs)
```

The interpreter and both WASM backends (Preview 1 / component) compute it
correctly (`0.5`). Reproduce across backends:

```
java -jar ...-exec.jar abs.lisp                 # interpreter: 0.5  -- OK
java -jar ...-exec.jar abs.lisp -o A.class && java A   # JVM: ClassCastException
java -jar ...-exec.jar abs.lisp -o abs.wasm && wasmtime run -W gc abs.wasm  # 0.5 -- OK
```

## Root cause

Same shape as the (now-fixed) float `mod`/`rem` bug, see [[24-wasm-gc-float-mod-rem]].
`abs` selects its integer vs float path with the purely *syntactic*
`hasDoubleLiteral`/`containsDouble` heuristic (does the argument expression
textually contain a float literal?), not the runtime type:

- JVM `JvmAbsCompiler`: with no double literal it compiles the integer path, which
  casts the operand to `BigInteger`; a `Double` at runtime throws
  `ClassCastException`.
- WASM `WasmAbsCompiler`: its integer/ratio "else" path happens to be float-safe
  because it routes through `_rat_cmp(x,0)` and `_rat_sub(0,x)`, both of which
  dispatch on type at runtime (float -> f64). So WASM returns the right answer even
  on the integer path -- which is why only the JVM visibly traps.

So a float reaching `abs` through a variable is mishandled on the JVM. (A float
*literal* argument, or any argument expression that *contains* a float literal,
takes the float path and is fine on every backend -- that is why
`examples/rainbow.lisp` can use the built-in `abs`: its argument
`(- (mod (/ h 60.0) 2.0) 1.0)` contains float literals.)

## Fix direction

Make `abs` dispatch on the operand's runtime type instead of the syntactic
heuristic, the way `mod`/`rem` now do (see #24):

- **Interpreter**: already correct (works on a Double).
- **JVM** (`JvmAbsCompiler`): branch on `instanceof Double` at runtime (f64 abs) vs
  the integer/ratio path, rather than `hasDoubleLiteral`. The runtime `_abs`/`_cmp`
  helpers already exist for the other arithmetic ops; mirror their type dispatch.
- **WASM** (`WasmAbsCompiler`): already returns the correct value via the
  rat-helper else path, but for clarity/perf could test the type at runtime too
  (optional; no correctness bug today).

Per the bug-fix workflow: first add a failing cross-backend test (a float `abs`
through a `let`-bound variable, plus a `ci-spec.yaml` case so all four backends are
checked), then fix the JVM path, then confirm parity with the interpreter.

## Broader note

`hasDoubleLiteral` is used by several WASM/JVM arithmetic compilers as a
compile-time float-vs-int discriminator. `mod`/`rem` were migrated to runtime
dispatch in #24; `abs` is the next instance. Worth auditing the remaining callers
(comparisons, `+ - * /` already go through the rat runtime) for the same
float-through-a-variable hazard.
