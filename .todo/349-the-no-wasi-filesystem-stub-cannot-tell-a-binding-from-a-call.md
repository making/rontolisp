# The `--no-wasi` filesystem stub cannot tell a BINDING named `open` from a call to `open`

Difficulty: Medium

`compiler/NoWasiFilesystemStubs` rewrites every `(open path opts...)` to a
call-time error stub, because a `--no-wasi` module has no filesystem. It finds
those calls with a plain AST walk, and a plain AST walk cannot tell

```lisp
(let ((open nil)) ...)      ; a BINDING whose spec happens to read (open nil)
(defun f (open) ...)        ; a PARAMETER named open
```

from the call it rewrites -- `open` is an ordinary variable name as well as a CL
function. Both become `(progn nil (error "open requires WASI; ..."))` in a
binding position, and the backend then rejects the malformed binding.

**Found the hard way** (2026-08-13): `http-server.lisp`'s UTF-8 chunk-boundary
scan was written `(let ((i start) (open nil)) ...)`, which took EVERY `--no-wasi`
reactor build down with `setq requires an even number of arguments` -- four tests
red on `develop` (`RontoLispCliTest` x2, `WasmLispCompilerIntegrationTest`,
`WasmTreeShakerCorpusTest`). The variable is renamed `split` there now and the
walk no longer descends into a list's TAIL as if the tail were a form (which is
what turned `(setq open t)` into a one-argument `setq`), so the loud failure is
gone -- but the binding case is untouched and will bite the next program that
binds the name.

**Not a silent miscompile**, which is why this is its own item and not a blocker:
the residue is a malformed binding the backend refuses, so nobody ships a wrong
module. It is a confusing compile error naming neither `open` nor the pass.

## The fix

Teach the walk the non-evaluated positions, which `compiler/ShadowedBuiltins`
already carries for exactly the same reason (a user generic named like a
built-in): `defun`/`defmacro`/`lambda` lambda lists, `flet`/`labels` definitions,
`let`/`let*`/`do`/`do*`/`handler-bind`/`restart-bind` binding lists,
`dolist`/`dotimes`/`with-*` specs, `case`/`handler-case` clause heads. The
knowledge should be SHARED rather than copied a second time -- that walker is
private and threaded with its own rewrite state, so extracting the position map
(a "which argument indices of this operator are not forms" table) is the piece of
work here.

Gate: `NoWasiFilesystemStubsTest` grows the two cases it deliberately does not
assert today (`(let ((open nil)) ...)`, `(defun f (open) ...)`), and
`http-server.lisp`'s scan can go back to reading `open` if that is the better
name.
