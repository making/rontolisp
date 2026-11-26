# with-standard-io-syntax

`(with-standard-io-syntax form...)`

Binds the reader and printer control variables to the standard values of CLHS 23.1.2 and evaluates the body as a `progn`, so a body reads and prints independently of the caller's settings.

| Variable | Value |
| --- | --- |
| `*package*` | `cl-user` |
| `*print-array*`, `*print-escape*`, `*print-gensym*`, `*print-readably*`, `*read-eval*` | `t` |
| `*print-base*`, `*read-base*` | `10` |
| `*print-case*` | `:upcase` |
| `*print-circle*`, `*print-length*`, `*print-level*`, `*print-lines*`, `*print-miser-width*`, `*print-pretty*`, `*print-radix*`, `*print-right-margin*`, `*read-suppress*` | `nil` |
| `*print-pprint-dispatch*` | a fresh empty table |
| `*read-default-float-format*` | `single-float` |
| `*readtable*` | a fresh standard [readtable](../../guides/readtables.md) |

Under `*print-readably*` the printer's `prin1` conversion ignores `*print-length*` and `*print-level*` and keeps `#:`, and a value with no readable syntax, at any depth, signals [`print-not-readable`](../functions/print-not-readable-object.md): a function, a class or condition instance with no method of its own, a [`print-unreadable-object`](print-unreadable-object.md). `princ` and `~A` bind it to `nil`, so a [`print-object`](../functions/print-object.md) method sees `nil` under them and nothing signals. A condition's message is rendered the same way, so `(error "bad ~S" #'car)` signals the error it asks for. A hash table prints as `#.` of its construction while `*read-eval*` is true, and signals otherwise.

Deviations:

- A package and a standard stream print as the values they are here (`:CL-USER`, `T`), so they do not signal.
- A random state signals; SBCL writes a structure.
- A hash table holding entries prints as `#.(LET ((H (MAKE-HASH-TABLE ...))) (SETF (GETHASH ...) ...) ... H)`, which SBCL reads too. Only the interpreter's reader evaluates `#.`; the compiled readers refuse it.
- A double prints without its `d0`: `*read-default-float-format*` is informational, because every float is the one double representation.

```lisp
(let ((*print-base* 2))
  (with-standard-io-syntax (write-to-string 5))) ; => "5"
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
(handler-case (with-standard-io-syntax (prin1-to-string #'car))
  (print-not-readable () :refused)) ; => :REFUSED
```
