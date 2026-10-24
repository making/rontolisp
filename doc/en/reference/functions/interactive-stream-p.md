# interactive-stream-p

`(interactive-stream-p stream)`

Whether the stream is attached to a terminal. A process standard stream answers what the host reports: `*error-output*` is true when standard error is a terminal, and `t` (the default `*standard-output*`, `*standard-input*` and `*terminal-io*`) when standard input and standard output both are. A synonym stream answers for the stream its variable holds. Every other stream -- a string, a file, a socket, a composite -- answers nil. The argument is a stream, not a stream designator -- anything else is a `type-error`.

| Backend | How it asks |
|---------|-------------|
| Interpreter, JVM | `isatty` through FFM. Standard input and output count only while the program uses the process's own, so a captured run answers nil. Without native access (`java Prog` without `--enable-native-access=ALL-UNNAMED`; the executable jar and every compiled jar enable it) the answer is nil, and no warning is printed. |
| WASM (Preview 1, native executable) | `fd_fdstat_get`: a character device is a terminal. |
| WASM component | `wasi:cli`'s `get-terminal-stdin`, `get-terminal-stdout` and `get-terminal-stderr`. |
| `--no-wasi`, served component, browser playground | Always nil: there is no terminal to ask about. |

A program that colors its output on a terminal and writes plain text to a pipe or file:

```lisp
(if (interactive-stream-p *error-output*)
    (format *error-output* "~c[31merror~c[0m~%" (code-char 27) (code-char 27))
    (format *error-output* "error~%"))
```

```lisp
(with-output-to-string (s) (princ (interactive-stream-p s) s)) ; => "NIL"
```
