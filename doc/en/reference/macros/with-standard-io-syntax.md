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

Under `*print-readably*` the printer's `prin1` conversion ignores `*print-length*` and `*print-level*` and keeps `#:`. `princ` and `~A` bind it to `nil`, so a [`print-object`](../functions/print-object.md) method sees `nil` under them.

Deviations:

- Printing an object with no readable syntax under `*print-readably*` prints its `#<...>` form instead of signalling [`print-not-readable`](../functions/print-not-readable-object.md).
- A double prints without its `d0`: `*read-default-float-format*` is informational, because every float is the one double representation.
- `*read-base*` holds its value, but every reader reads integers in base 10.

```lisp
(let ((*print-base* 2))
  (with-standard-io-syntax (write-to-string 5))) ; => "5"
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
```
