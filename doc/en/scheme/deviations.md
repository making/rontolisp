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
- A loop -- a named `let`, a procedure whose only calls to itself are tail calls -- that
  exits through a call answers that call's first value only: `(let loop ((l l)) (if (null?
  l) (f) (loop (cdr l))))` answers one value however many `f` returns. A loop that exits
  through `(values ...)` answers them all.
- **Exceptions are caught after the unwinding.** A `guard` runs its clauses after its body
  has been left, so when none is taken the object is raised again from the `guard`: a
  handler outside it cannot resume a `raise-continuable` of the body. The handler of a
  `with-exception-handler` runs where `raise`, `raise-continuable` and `error` stand, but
  for an error a built-in procedure signals it runs after the `thunk` has been left. On
  WebAssembly an error the machine traps on (`car` of a non-pair, an index out of range)
  ends the program instead of being raised. An uncaught `error` ends the program with its
  message and irritants, an uncaught `raise` with its object.
- Calling a parameter object with an argument is an error; Gauche sets its value. `eval`
  refuses `parameterize` by name.
- A `case-lambda` called with a number of arguments no clause accepts raises an error
  object; `eval` refuses `case-lambda` by name.
- A port is textual or binary, never both: `binary-port?` of a string port is `#f` (a
  Gauche port is both). The standard ports are textual, so `read-u8`, `write-u8` and the
  other binary procedures need a bytevector port argument. `char-ready?` and `u8-ready?`
  always answer `#t`.
- A record prints in Common Lisp's `#S(...)` syntax. `equal?` compares records by
  identity.
- `write` prints `'x` as `(quote x)`, and the unspecified value as `#!unspecific`. It is
  one object, true in a test.
- `exit` runs the `after` thunks of the `dynamic-wind`s it is inside, then ends the
  process with its status; only `emergency-exit` ends the process where it stands.
- There are no complex numbers: `(sqrt -4)`, `(log -1)` and `(asin 2)` end the program
  with an error naming the procedure.
- Error messages spell Common Lisp names (`CAR`).
- `utf8->string` decodes a byte that begins no valid UTF-8 sequence to the character
  with that byte's code instead of signalling an error.

## Not yet

`define-library`, file ports (`(scheme file)`), the other libraries, `|...|` identifiers,
reading `+inf.0` / `+nan.0`. The syntactic ones are refused by name when the file is
read.
