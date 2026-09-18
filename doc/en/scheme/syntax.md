# Syntax

- **Reader** (case-sensitive): `#t` `#f` `#true` `#false`, integers, decimals, rationals,
  `#x` `#b` `#o` `#d`, `#\a` `#\space` `#\newline` `#\x41`, strings with
  `\n \t \" \\ \xHH;`, `#( )` vectors, dotted pairs, `'` `` ` `` `,` `,@`, `;`, `#;`,
  `#| |#`, `#!fold-case` / `#!no-fold-case` (per file, folding identifiers and character
  names -- not strings, not the character itself -- until the counterpart directive or
  end of file).
- **Special forms**: `define` (both forms, internal definitions as `letrec*`),
  `define-values`,
  `lambda`, `if`, `cond` (`else`, `=>`), `case`, `and`, `or`, `when`, `unless`, `let`,
  `let*`, `letrec`, `letrec*`, named `let`, `do`, `begin`, `set!`, `quote`, `quasiquote`,
  `let-values`, `let*-values`, `define-record-type` (top level only), `delay`,
  `delay-force`, `define-syntax` / `let-syntax` / `letrec-syntax` (below), and
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))` with `only` / `except` /
  `prefix` / `rename`.

See [Libraries](libraries.md) for what each of those nine libraries exports, and
[SICP Compatibility](sicp.md) for the names visible with no `import` at all.

## Macros

`define-syntax`, `let-syntax` and `letrec-syntax` with `syntax-rules` transformers:
literals, `_`, `...` (nested, followed by more patterns, in a dotted tail, in a vector),
a custom ellipsis `(syntax-rules ::: (literal ...) rule ...)`, the `(... ...)` escape,
and `syntax-error`. A syntax definition may stand at the top level or at the head of a
body; a macro may expand into definitions, `begin` included.

Macros are hygienic: a variable the template binds (`tmp` below) captures nothing the
user wrote, and a name the template uses freely (`if` below) means what it meant where
the macro was defined, whatever the use site binds.

```scheme
(define-syntax swap!
  (syntax-rules ()
    ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
(define tmp 1)
(define other 2)
(swap! tmp other)
(display (list tmp other)) (newline)
(define-syntax my-or
  (syntax-rules ()
    ((_) #f)
    ((_ e) e)
    ((_ e r ...) (let ((t e)) (if t t (my-or r ...))))))
(define t 5)
(display (let ((if list)) (my-or #f t))) (newline)
```

```
(2 1)
5
```

The limits: only `syntax-rules` transformers; a macro is visible in the file that
defines it (a `(load ...)`ed file does not see the loader's macros, nor the other way
round); names a template introduces into a `define-record-type` are not renamed; `eval`
knows no macro and refuses `define-syntax`.
