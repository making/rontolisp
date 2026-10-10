# f29. then/then*/catch/finally of a non-future signal a simple-error, not the promised type-error

Difficulty: Low

`doc/{en,ja}/guides/async.md` ("A non-future first argument to any of the four is a `type-error`") and
`.kb/async-await.md` promise a `type-error`. The four prelude defuns (`LispPreludeLibrary.SOURCES`)
signal `(error "rontolisp:THEN expects a future as its first argument")`, a `simple-error`, on all
four backends: `(handler-case (rontolisp:then 42 #'identity) (error (c) (type-of c)))` is
`SIMPLE-ERROR` on the interpreter, the JVM, Preview 1 and the component (measured 2026-10-10).

## Plan

1. Pin the datum, expected type and report on all four backends (ci-spec case, and the
   `AsyncEvalTest` / `JvmAsyncCompilerTest` / `WasmLispCompilerIntegrationTest` triple).
2. Signal the operator's type-error expecting `(SATISFIES RONTOLISP:FUTUREP)`, the way the stream
   verbs expect `(SATISFIES RONTOLISP:STREAMP)` (`.kb/error-handling.md`, "A wrong-type argument
   names its operator", the asynchronous stream verbs' bullet): `%operand-type-error` in the prelude
   source. A row named bare `CATCH` would also name CL's `catch` forms, whose helper calls report
   under the innermost named operator; keep the rows keyed so `catch` the special form does not
   match.
