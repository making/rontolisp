# f38. The rontolisp: native built-ins are no function values on the compiled backends

Difficulty: Medium

Every `rontolisp:` row of `NativeCallShapes` but the async predicates and stream verbs has no
compiled function value: on the JVM, `(functionp #'rontolisp:NAME)` is `The function
RONTOLISP:NAME is undefined` for `destroy-thread join-thread json-parse json-stringify
mutex-acquire mutex-release tcp-accept tcp-local-address tcp-local-port tcp-peer-address
tcp-peer-port thread-alive-p threadp wait-for current-thread make-mutex version tcp-connect
tcp-set-timeout fetch make-thread tcp-listen tls-listen tls-listen-pem tls-connect tls-upgrade`
(measured 2026-10-11). The interpreter answers each. The user docs of `make-thread`,
`make-mutex` and `current-thread` state "no function value"; the others say nothing.

## Plan

1. Measure each name on Preview 1 and the component too: several are spliced library defuns
   there (`sockets.lisp` under `--component`, `wait.lisp`), or a compile error / call-time stub
   (Preview 1's sockets, `wait-for`), which decides what a value should do per backend.
2. Add the names to `BuiltinFunctionWrappers.NATIVE_VALUE_FUNCTIONS` (reference-gated, body =
   call position, as the async ones did) where the call position is a backend lowering; check
   each gate that scans for the operator's name sees the `#'`. Pin on all four backends.
3. Drop the "no function value" lines from the docs it makes false (`doc/en` + `doc/ja`).
