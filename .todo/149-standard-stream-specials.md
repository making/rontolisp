# Default I/O streams must resolve through *standard-output* / *standard-input*

> **Status 2026-07-28 (todo-195): the `*standard-output*` OUTPUT side is DONE**
> on the interpreter, the JVM and both wasm-GC backends, exactly per the
> proposal below -- mechanics and the bind-activated special rule in
> `.kb/standard-output-redirect.md`, pinned by the
> `s-sql-enablement-language-group` ci-spec case.
>
> **Status 2026-07-31: the explicit-`nil` OUTPUT designator is DONE too**
> (`.todo/200`'s half). An omitted stream argument and an explicit `nil` are
> now the same designator on all four backends, so a renderer forwarding its
> own optional (`(defun emit (x &optional stream) (princ x stream))`) reaches
> the current `*standard-output*` instead of raw stdout. It did NOT need the
> runtime write helpers to grow a null-handle resolution as proposed below:
> the shared, backend-free AST rewrite `compiler/StreamDesignators` turns a
> non-literal stream expression into `(or <expr> *standard-output*)` and an
> omitted/literal-nil one into the bare read, behind the same
> `defaultStreamArg` gate -- so a program that never binds the variable is
> still byte-identical. Consequence, retired in the same pass: a synonym
> stream over `*standard-output*` IS the nil designator now
> (`expandMakeSynonymStream` answers nil), so it forwards PER OPERATION like
> CL's; `.kb/read-load-streams.md` keeps the lite construct-once expansion only
> for OTHER symbols. See `.kb/standard-output-redirect.md`.
>
> **Status 2026-07-31: the INPUT side is DONE too.** `*standard-input*` now
> exists (a `cl` variable seeded to the `t` designator), binding it redirects
> `read-line`/`read-char`/`read`/`peek-char`/`listen` -- including inside
> called functions and through an explicit nil argument -- on all four
> backends, and `make-synonym-stream` over it is the nil designator like its
> output twin. Mechanics, the runtime widenings it needed, the
> `--component` dispatcher change and the two known limits:
> `.kb/standard-output-redirect.md` ("The INPUT mirror"). One pre-existing
> cross-backend bug fell out of it: `(read-line nil)` used to TRAP on both
> wasm backends (`ref.cast (ref i31)` on a null ref) while the interpreter and
> the JVM read stdin.
>
> Still open here: `*error-output*` only.

## Still open (2026-07-31)

**`*error-output*`.** The variable exists (seeded to `t`) and an explicit
`(format *error-output* ...)` already follows a rebinding, but nothing DEFAULTS
to it: `warn` and the condition reporters write to standard output, where CL
sends them to `*error-output*` -- and `t` means the process standard OUTPUT
here, so even unbound it never reaches stderr. Decide first whether
`*error-output*`'s seeded designator should stay `t` (today's stdout) or become
a stderr designator, because that is an observable output change for every
program that uses it.

## Original problem statement (HISTORICAL -- everything below is done except `*error-output*`)

`*standard-output*` / `*error-output*` exist as global variables bound to
the designator `t` (`Environment.java:248`, registered in
`PackageRegistry.java:118`), but the stream-arg-less print family ignores
them: `print`/`prin1`/`princ`/`terpri` pass a `null` stream to `emitTo`
(`Environment.java:2526-2547`), which writes to hardcoded stdout. So the
standard CL idiom

```lisp
(let ((*standard-output* s))   ; s = a string/file stream
  (print x))                    ; expected: goes to s
```

does NOT redirect, on any backend — even though the shallow-binding special
machinery that would make it work exists everywhere except `--no-gc`. The
same applies on the input side (`read-line`/`read-char`/`read` default
stream vs `*standard-input*`, which may also need to be defined as a
variable). This breaks a very common idiom in real CL libraries (output
capture in test frameworks, logging redirection, `with-output-to-string`
over code that prints), so it is a direct blocker for loading community
libraries verbatim — higher leverage than any single new built-in.

## Proposal

When the print family (and `format` with destination `t`, `write-char`,
`write-string`, `write-line`, `terpri`, `fresh-line`) is called without a
stream argument, resolve the effective stream by READING the current value
of the `*standard-output*` cell at call time; `t` keeps meaning the real
stdout, a stream handle routes through the existing stream dispatch (the
2-arg forms already accept handles). Symmetrically `*error-output*` for the
error stream and `*standard-input*` for `read-line`/`read-char`/`read`.
Because specials are shallow-bound globals on all backends, `let`-rebinding
then redirects everywhere with no new binding machinery.

- `--no-gc` rejects specials already; there the default stays hardcoded
  stdout (documented, consistent with its existing "no specials" error).
- Follow the repo implementation order: interpreter (`emitTo` null path
  reads the global cell) -> JVM -> WASM (the print compilers emit a global
  read + the already-present stream-dispatch runtime for the default case)
  -> ci-spec case -> docs.

## Verification

- ci-spec case: rebind `*standard-output*` to a string stream around a
  `print`, assert the captured string and that output outside the `let` still
  reaches stdout — identical on interpreter/JVM/wasm-GC/component.
- A `with-output-to-string`-style capture of a defun that `print`s (the
  library-idiom acceptance test).
- Check the unwinding caveat documented in
  `.kb/dynamic-special-variables.md` (compile-path `return` does not
  restore) still holds and is referenced from the doc page.
