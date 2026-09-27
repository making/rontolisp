# The JVM `handler-case` landing inlines the condition synthesis

Difficulty: Medium

Found 2026-09-27 while measuring a49's stack-map cost. Every `handler-case` / `ignore-errors`
landing (`JvmHandlerCaseCompiler.compile` -> `emitTakeCondition` -> `emitSynthesizeCondition`)
emits the raw-failure classification inline: the host-text overrides, the quote framing, and one
condition construction per raw-failure class (`emitClassifiedConstruction` over
`LispMacroExpander.reportingConditionForm`). The `%hb-guard` pad had the same body and was outlined
into the shared `_hbGuard` for exactly this reason (`.kb/hot-path-method-size.md`); the landing
never was.

Measured on develop at `d7fbe3e9f` (default `--optimize`):

| program | method | code | StackMapTable |
|---|---|---|---|
| `(defun g (x) (ignore-errors (f x)))` | `G` | 949 B | 211 B |
| `(defun h (x) (handler-case (f x) (type-error () :t)))` | `H` | 667 B | 211 B |

`f` is `(car x)`, 12 B. The ci-spec corpus as one class (`--optimize=off`, 7.9 MB) holds 334
landings that synthesize; `examples/net/hello-clack.lisp` (953,796 B) holds 7.

Plan: a shared `(Throwable)Object` method (`_hcSynth`, built on first use like
`guardLandingPad`, recorded in `ConditionChannel`) that answers the synthesized instance; the
landing calls it when `_condTake` answers null, and `_hbGuard`'s own synthesis calls it too. The
landing's clause dispatch stays inline (it compiles the program's own clause types). Watch: the
landing is per method, so the call must not disturb `Ctx.spillOperandStack` or the frames the
augmenter writes; `JvmLibraryMethodSizeTest` and the `compileAndRunHandlerCaseIn*` block
(`JvmLispCompilerTest`, which must LOAD and RUN the class) are the guards. Measure the two rows
above, the corpus class and a handler-heavy library program before and after, and record them in
`.kb/error-handling.md` ("A built-in error carries its CONDITION CLASS", the JVM bullet).
