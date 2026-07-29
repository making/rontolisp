# Default I/O streams must resolve through *standard-output* / *standard-input*

> **Status 2026-07-28 (todo-195): the `*standard-output*` OUTPUT side is DONE**
> on the interpreter, the JVM and both wasm-GC backends, exactly per the
> proposal below -- mechanics and the bind-activated special rule in
> `.kb/standard-output-redirect.md`, pinned by the
> `s-sql-enablement-language-group` ci-spec case. Still open here:
> `*error-output*` (same machinery, not wired), the whole INPUT side
> (`*standard-input*` for `read-line`/`read-char`/`read`), and -- added
> 2026-07-29 by `.todo/200` -- **an explicit `nil` stream ARGUMENT must mean
> `*standard-output*`** (CL's stream designator), which today reaches the raw
> stdout path on every backend. That last one is what a per-operation
> `make-synonym-stream` needs: with it, a synonym stream over
> `*standard-output*` IS the nil designator, and the lite construct-once
> expansion in `.kb/read-load-streams.md` can be retired. It needs the runtime
> write helpers (`_writeStr`/`_writeLine`/`_freshLine`,
> `_write_stream_str`/`_write_line`/`_fresh_line_stream`) to resolve a null
> handle through the `*standard-output*` global when the redirect is active --
> gated exactly like `defaultStreamArg`, so a program that never binds it stays
> byte-identical.

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
