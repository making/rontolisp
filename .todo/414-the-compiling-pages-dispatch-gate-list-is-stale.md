# 414. The compiling pages' dispatch-gate list is stale

Difficulty: Low

Found while doing `.todo/404`. `doc/en/compiling/jvm.md` (~L57) and
`doc/en/compiling/wasm.md` (~L193), and their `doc/ja` twins, still say the
dispatch gate switches off wholesale on

> any use of `eval`, `read`, `read-from-string`, a runtime `load`, `intern`,
> `find-symbol`, `make-symbol`, `symbol-function`, `fdefinition`, `fboundp` or
> `uiop:symbol-call`

That has not been true since 2026-08-08. `.kb/optimize-dead-code-elimination.md`
("The symbol BUILDERS no longer bail"): `intern`, `find-symbol`, `make-symbol`,
`symbol-function`, `fdefinition`, `fboundp` and `uiop:symbol-call` are
gate-NEUTRAL -- a symbol a builder produces is built from a string, and every
string the program holds is a compile-time constant the widened probes read. A
clack program shipping every defun dispatchable was the symptom that got it
fixed; the docs describe the world before the fix.

Two consequences on the same pages:

- **The `(intern name :keyword)` exemption sentence is now moot.** It reads as
  the one carve-out from a rule that no longer exists (todo-260's exemption was
  retired by the same 2026-08-08 split -- "an `(intern NAME :keyword)` is as
  gate-neutral as any other intern"). Deleting it is not enough on its own:
  something has to say what DOES hold the gate open, which is `eval` / `read` /
  `read-from-string` / a runtime `load` / `--dynamic`, plus the carve-out the
  fix left behind -- **a name assembled out of COMPUTED pieces does not
  resolve**, and that failure is an ordinary undefined-function error with
  `--dynamic` as the way back. A reader who only learns "the gate is rarely
  off" and not that carve-out is worse off than before.
- **`wasm.md`'s worked example output is suspect.**
  ```
  rontolisp -Drontolisp.debug.dispatchgate=true app.lisp -o app.wasm --optimize
  # => [dispatch-gate] every function stays dispatchable because of: INTERN
  ```
  `INTERN` can no longer be the blamed operator. RE-RUN the flag and paste what
  it actually prints rather than editing the line to a guess -- if no shape in
  the page's example program blames anything any more, the example needs a
  program that does (`eval` / `read`).

## Scope

Documentation only -- the code and `.kb` are already right, and this item must
not "fix" the behavior to match the prose. Verify against the code before
writing (`Jvm/WasmLispCompiler`'s gate, `Ctx.spelledLiterals`), not against
this file: it is a report, not a spec.

`doc/en` and `doc/ja` in the SAME commit, same file set, byte-identical code
fences. Run `./mvnw -f docs-tool/pom.xml test` afterwards.

## Check for the same claim elsewhere

The list was pasted around. At minimum grep the doc tree and `README.md` for
`find-symbol`, `fdefinition` and `dispatch` before calling this done -- the
`--optimize` / `--dynamic` reference pages and any tree-shaking guide are the
likely other copies.
