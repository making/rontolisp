# i72. A typed signal renders its report at the signal on the interpreter and the JVM

Difficulty: Medium

Measured 2026-11-05 against SBCL 2.2.9, where `c` has a slot with no `:initform` and a lambda `:report` reading it through its
reader, `(+ 1 (c-v x))`:

| Form | SBCL | Interpreter | JVM | Preview 1, component |
| --- | --- | --- | --- | --- |
| `(handler-case (error 'c) (error (x) (type-of x)))` | `c` | `unbound-slot` | `unbound-slot` | `c` |
| `(handler-case (error (id 'c)) (error (x) (type-of x)))` | `c` | `unbound-slot` | `unbound-slot` | `c` |
| `(handler-case (error 'c :v 1) (error (x) (type-of x)))` | `c` | `c` | `c` | `c` |

wasm-GC never compiles the report half of a message (`.kb/error-handling.md`, "Signal messages are lazy on wasm-GC"). The other two
evaluate it as an argument of `%error-cond` / `%signal-cond`, so the report runs before the condition is raised: what it signals
replaces the condition, and its side effects happen whether or not anything prints it.

## Plan

Render where the text is read. On the JVM the reader is `RuntimeException.getMessage` (`JvmErrorCompiler.compileThrowRuntimeException`),
which embedders read too, so the choice is a throwable that renders on demand, or the entry handler rendering the instance through
`%condition-report-str` as `WasmUncaughtReportCompiler` does and the message operand keeping only the fallback
(`LispMacroExpander.conditionReportFallback`). Interpreter: `LispEvalException`'s message, `Environment`'s `%error-cond`. A guard
`handler-case` around the rendering at each site was considered and rejected: it adds a try range per typed signal to hot methods
(`.kb/hot-path-method-size.md`), and the report still runs for a condition nothing prints.
