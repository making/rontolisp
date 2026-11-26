# Standards

`--scheme-standard` picks what every Scheme file of the program is read against: the
entry file, a file it loads (at run time or inlined by `-o`), and the REPL.

| value | meaning |
|---|---|
| `rontolisp` (default) | This implementation's own dialect: R7RS plus the [*Structure and Interpretation of Computer Programs* (SICP)-compatibility](sicp.md) and R5RS names, visible to a file with no `(import ...)` and to the REPL. |
| `r7rs` | R7RS-small, strictly, within the subset this front end implements. |

Under `r7rs`:

- A program must begin with `(import ...)`.
- The SICP-compatibility and R5RS names are never visible, not even to `eval`.
- Redefining or `set!`-ing an imported name in a file is an error. The REPL may redefine one.
- `eval` requires its environment argument.

A valid R7RS program prints the same under both values.

```console
$ rontolisp --scheme-standard r7rs sicp.scm
error: sicp.scm:1:1: an R7RS program begins with an import declaration
```
