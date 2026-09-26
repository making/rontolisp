# Deviations

- **Tail calls are proper on WebAssembly and in the interpreter, and on the JVM only where
  they become a loop.** Compiled to WebAssembly (`-o prog.wasm`, `--component`) and run by
  the interpreter (`rontolisp prog.scm`, the REPL), every call in tail position runs in
  constant stack. On the JVM that holds for a named `let` or `do`, a procedure calling
  itself in tail position, and procedures that call each other in tail position
  (`even?`/`odd?`, a state machine, an evaluator's `eval`/`apply`) when they are the
  top-level procedures of a file, the internal definitions of one body or the `lambda`
  bindings of one `letrec`; a tail call through a procedure value (an argument, a
  variable, `apply`) uses stack there: a procedure calling itself through an argument
  overflows about 16,000 calls deep compiled to the JVM, on its 16 MiB stack
  (`-Drontolisp.stack` raises it).
- **`call/cc` is escape-only.** A continuation can be called while its `call/cc` is still
  running, once. There is no re-entry, so no generators or coroutines through it, and
  `dynamic-wind` runs its `before` exactly once.
- `call-with-values` is a direct binding when both arguments are written as `lambda`
  expressions; any other shape goes through a list.
- **Exceptions are caught after the unwinding.** A `guard` runs its clauses after its body
  has been left, so when none is taken the object is raised again from the `guard`: a
  handler outside it cannot resume a `raise-continuable` of the body. The handler of a
  `with-exception-handler` runs where `raise`, `raise-continuable` and `error` stand, but
  for an error a built-in procedure signals it runs after the `thunk` has been left. On
  WebAssembly a string index out of range is not checked (`string-ref` past the end answers
  a character). An uncaught `error` ends the program with its
  message and irritants, an uncaught `raise` with its object.
- Calling a parameter object with an argument is an error; Gauche sets its value. `eval`
  refuses `parameterize` by name.
- A `case-lambda` called with a number of arguments no clause accepts raises an error
  object; `eval` refuses `case-lambda` by name.
- A port is textual or binary, never both: `binary-port?` of a string port is `#f` (a
  Gauche port is both). The standard ports are textual, so `read-u8`, `write-u8` and the
  other binary procedures need a bytevector or binary file port argument. `char-ready?` and
  `u8-ready?` always answer `#t`.
- `with-input-from-file` and `with-output-to-file` close the file however the thunk is
  left, an escape or a raised object included (Gauche leaves it open). A file that cannot
  be opened for output raises an error `file-error?` answers `#t` for, as R7RS says.
- A record prints in Common Lisp's `#S(...)` syntax. `equal?` compares records by
  identity. A `define-record-type` in a body makes one type where it is written, not a
  new one each time the body runs (Gauche does the latter).
- `write` prints `'x` as `(quote x)`, and the unspecified value as `#!unspecific`. It is
  one object, true in a test.
- `exit` runs the `after` thunks of the `dynamic-wind`s it is inside, then ends the
  process with its status; only `emergency-exit` ends the process where it stands.
- There are no complex numbers: `(sqrt -4)`, `(log -1)` and `(asin 2)` end the program
  with an error naming the procedure.
- Error messages spell Common Lisp names (`CAR`).
- `utf8->string` decodes a byte that begins no valid UTF-8 sequence to the character
  with that byte's code instead of signalling an error.
- A library found in a file is looked up beside the file the program started from, with
  no search path. A `define-library` written in a file is seen by that file alone: a file
  `load`ed beside it finds the library only as a `.sld` file. A name an exported macro's
  template uses that its library neither defines nor imports means what it means where the
  macro is used. A record type a library defines prints its names with the library's name in
  front of them. Importing one name from two libraries is not refused; the later import
  wins.
- A `cond-expand` is decided when the program is read, against the features every
  backend shares; `(features)` names no operating system or processor. One no clause of
  which holds, with no `else`, is an error. Inside `eval`, `(library ...)` holds for the
  standard libraries only.

## Not yet

The other libraries. Importing one is refused by name when the file is read.
