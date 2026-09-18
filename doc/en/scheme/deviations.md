# Deviations

- **Tail calls are proper only where they become a loop**: a named `let` or `do`, and a
  procedure calling itself in tail position. Mutual and higher-order tail calls use
  stack: a pair of procedures calling each other overflows the JVM's default stack
  between 2,000 and 5,000 calls deep, and the interpreter and WebAssembly between 10,000
  and 100,000.
- **`call/cc` is escape-only.** A continuation can be called while its `call/cc` is still
  running, once. There is no re-entry, so no generators or coroutines through it, and
  `dynamic-wind` runs its `before` exactly once.
- `call-with-values` is a direct binding when both arguments are written as `lambda`
  expressions; any other shape goes through a list.
- A first-class `values` -- `(apply values '(1 2))`, `values` reached through a variable,
  `values` inside `eval` -- answers its first value only on the compiled backends; the
  interpreter answers them all. Written as a call, `(values 1 2)`, it answers them all
  everywhere.
- An uncaught `error` ends the program with its message and irritants. There is no
  `guard` to catch it.
- A record prints in Common Lisp's `#S(...)` syntax. `equal?` compares records by
  identity.
- `write` prints `'x` as `(quote x)`, and the unspecified value as `#!unspecific`. It is
  one object, true in a test.
- `exit` runs the `after` thunks of the `dynamic-wind`s it is inside, then ends the
  process with its status; only `emergency-exit` ends the process where it stands.
- There are no complex numbers: `(sqrt -4)`, `(log -1)` and `(asin 2)` end the program
  with an error naming the procedure.
- Error messages spell Common Lisp names (`CAR`).

## Not yet

`define-library`, `guard` / `raise`,
`parameterize`, `case-lambda`, bytevectors, ports other than the current
output and input ports (string ports, and a port argument to `read` / `write` /
`display`), `(scheme char)` and the other libraries, `|...|` identifiers,
reading `+inf.0` / `+nan.0`. The syntactic ones are refused by name when the file is
read.
