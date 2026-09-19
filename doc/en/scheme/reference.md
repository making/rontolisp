# Reference

One page per name the Scheme front end provides: every procedure, constant and syntactic
keyword, grouped by the library that exports it. **Each name in a table links to its own
page**, which gives the signature, the behavior, every deviation from R7RS and a checked
example.

| Page | Contents |
|---|---|
| [Syntax](reference/syntax.md) | The syntactic keywords of `(scheme base)`, and `import` |
| [(scheme base)](reference/library-base.md) | Numbers, pairs and lists, symbols, characters, strings, vectors, control, exceptions, ports, input and output |
| [(scheme write)](reference/library-write.md) | `display`, `write` and their variants |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme char)](reference/library-char.md) | Character classes, case mappings, `char-ci=?` and `string-ci=?` and their orderings |
| [(scheme inexact)](reference/library-inexact.md) | Transcendental functions, `finite?`, `infinite?`, `nan?` |
| [(scheme cxr)](reference/library-cxr.md) | The three- and four-deep `car`/`cdr` compositions |
| [(scheme lazy)](reference/library-lazy.md) | Promises |
| [(scheme case-lambda)](reference/library-case-lambda.md) | `case-lambda` |
| [(scheme process-context)](reference/library-process-context.md) | `exit`, `emergency-exit` |
| [(scheme eval)](reference/library-eval.md) | `eval`, `environment` |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |
| [(scheme file)](reference/library-file.md) | File ports, `file-exists?`, `delete-file` |
| [(scheme r5rs)](reference/library-r5rs.md) | R5RS names outside the other libraries, visible with no `import` |
| [SICP Names](reference/library-sicp.md) | The *[Structure and Interpretation of Computer Programs](sicp.md)* (SICP) / MIT Scheme names, visible with no `import` |

A file that opens with `(import ...)` sees only the libraries it names; a file with none,
and the REPL, see every name listed here -- except under
[`--scheme-standard r7rs`](standards.md), which hides the R5RS and SICP names.
