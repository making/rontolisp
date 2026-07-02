# Promise error callback (onRejected / catch equivalent)

`rontolisp:then` takes a success callback only. There is no way for a program to
*handle* a failed promise on the interpreter/JVM (the failure signals from
`rontolisp:await`, and rontolisp has no condition system to catch it); on WASM a failed
fetch resolves to `nil`, which is at least branchable but indistinguishable from a nil
value.

Deliberately deferred (user decision, 2026-07-03) because the semantics cannot be made
uniform yet:

- WASM has no failure representation. `_promise_await`'s kind-0 path *does* see the
  adapter errno, so a "rejected" kind (e.g. kind 3, base = error-message string) is
  implementable there -- but `error` on WASM is a bare `unreachable` trap with no
  message, so signaling on an unhandled rejection has nowhere to carry the message.
- The natural surfaces would be `(rontolisp:then p on-value on-error)` (on-error
  receives an error-message string, its return value settles the chain) and/or a
  `rontolisp:catch`-like operator -- note `catch` itself collides with the CL
  special operator name.
- Revisit together with a future condition system (handler-case) or with the
  WASI 0.3 fetch upgrade (`.todo/02-upgrade-fetch-to-wasi-http-0.3.md`), whichever
  lands first. The promise struct/kind representation (see `.kb/fetch-http.md`) leaves
  room for a rejected state on every backend.
