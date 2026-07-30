# 216 - `format` with a RUNTIME control string only honors the simple directives

Split out of `.todo/193` (the aside noticed in the same logs), and it turned out
to be both narrower and more general than the note there said.

`(format nil <literal> args...)` is a compile-time expansion over the literal
(`LispMacroExpander.expandFormat`) and supports the full directive set. When the
control string is a **runtime value**, the fallback renderer handles only the
simple directives -- `~A`, `~S`, `~D`, `~%`, `~~` -- and leaves everything else
LITERAL while still consuming the argument. Measured on the native binary
(2026-07-30, all in one program, same arguments):

```lisp
(let ((c "A=~A S=~S D=~D ~5,'0D% ~{~A~^,~} ~@[cond=~A~] ~~ end"))
  (format nil c 1 "s" 42 7 (list 1 2) "c"))
;; => A=1 S="s" D=42 ~5,'0D% ~{7~^,~} ~@[cond=(1 2)~] ~ end

(format nil "A=~A S=~S D=~D ~5,'0D% ~{~A~^,~} ~@[cond=~A~] ~~ end"
        1 "s" 42 7 (list 1 2) "c")
;; => A=1 S="s" D=42 00007% 1,2 cond=c ~ end
```

Note the args also SHIFT (`~{7~^,~}` got the padding directive's argument), so
the tail of a mixed control string is not merely unrendered but wrong.

`(apply #'format nil c (list ...))` renders correctly -- so the full renderer is
reachable at runtime; the direct runtime-control call site is the one that is not
wired to it.

## How it shows up

cl-postgres' `get-warning` signals with a `format-control` slot, and the
condition report renders it with the runtime path:

```
WARNING: PostgreSQL warning: relation "notes" already exists, skipping~@[
NIL~]
```

`~A` was substituted; `~@[~%~A~]` was not, and its nil argument printed as
`NIL`. Interpreter and JVM alike.

## Fix direction

Route the runtime-control `format` at the same renderer `apply #'format` already
reaches, rather than growing the fallback directive by directive. Watch the
compile backends: `expandFormat`'s literal path must keep emitting exactly what
it emits today (it is what every ci-spec `format` case pins), and the change has
to hold on all four backends plus a ci-spec case for the runtime-control shape.
