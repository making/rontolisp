# 404. `uiop:symbol-call` has no compiler case

Difficulty: Low

Found by the dexador spike (`.todo/396`). `uiop:symbol-call` is a built-in on
the interpreter side only; both compile paths refuse it:

```
error: while compiling defun DEXADOR:REQUEST: Cannot compile: UIOP/PACKAGE:SYMBOL-CALL
```

dexador's public entry point dispatches to its backend with it:

```lisp
(ecase *dexador-backend*
  (:usocket (apply #'uiop:symbol-call '#:dexador.backend.usocket '#:request uri args))
  (:winhttp (apply #'uiop:symbol-call '#:dexador.backend.winhttp '#:request uri args)))
```

which is the idiom's whole point: name a function in a package that may not
exist yet. The spike replaced it with a direct call to compile at all.

## The work

The two arguments are almost always LITERAL designators, as they are above, so
the compile paths can resolve at compile time and emit an ordinary call --
the same shape `AsdfSystems` already relies on when it reads `symbol-call` in
a `:perform` body as data (`.kb/asdf.md`). Concretely:

- `Jvm/WasmExprCompiler.compileCons`: a `UIOP_SYMBOL_CALL` case that, when the
  package and name arguments are literal designators (`'#:foo`, `:foo`,
  `"FOO"`, a quoted symbol -- `JvmLispCompiler` already normalizes the
  `:member` keyword spelling), rewrites to the direct call and lets the
  ordinary undefined-function warning fire if the target is not in the program.
- A NON-literal designator has no compile-time answer. Route it through the
  dynamic-call path if the program is `--dynamic`, otherwise emit the
  named-form compile error we already emit -- but say WHICH argument was not
  literal, so the message tells the user what to change.
- Note that dexador's form is behind an `ecase` over a target that will never
  be `:winhttp` here: the compile path must not require the winhttp package to
  exist for the `:usocket` arm to compile. Resolving each arm independently and
  leaving the unreachable one as a call-time error is the behavior to pin.
- `uiop:find-symbol*` and `uiop:symbol-call`'s siblings are the same shape;
  sweep them rather than fixing one.
