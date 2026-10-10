# f30. The async predicates and stream verbs are no function values on the compiled backends

Difficulty: Medium

`(funcall #'rontolisp:streamp s)`, `#'rontolisp:futurep`, `#'rontolisp:stream-read` and
`#'rontolisp:stream-close` answer on the interpreter. On the JVM, Preview 1 and the component each
signals `The function RONTOLISP:STREAMP is undefined` (and so on), measured 2026-10-10.
`#'rontolisp:read-all` and `#'rontolisp:then` are prelude defuns and work everywhere.

## Plan

1. Pin the four as function values (`funcall`, `apply`, `mapcar`) on all four backends.
2. Give them `BuiltinFunctionWrappers` rows (`.kb/adding-primitives.md`, step 5), the wrapper calling
   the same helpers, so a non-stream is the operator's type-error there too (`.kb/async-await.md`,
   "A value that is no stream"). `stream-write` and `make-stream` on the interpreter and the JVM only.
