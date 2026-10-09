# clojure.java.shell

Running a process: its standard input, output, error output and exit code. Require
`clojure.java.shell` to use it; it is Clojure source written for rontolisp from the documented
behavior of Clojure's namespace, over the host's `ProcessBuilder`, so it runs on the
interpreter and the JVM. A WebAssembly target has no process to launch: there the `require` is
refused when the program is lowered.

| Var | Behavior |
|---|---|
| `sh` | `(sh & args)`: runs the command the leading strings name and answers `{:exit code :out text :err text}`. The options after them: `:in` a string, a File or a reader for its standard input; `:in-enc` the charset of `:in`'s text (UTF-8); `:out-enc` the charset of `:out` (UTF-8); `:dir` the directory it runs in; `:env` a map replacing its environment. `:err` is decoded in the platform's charset |
| `with-sh-dir` | `(with-sh-dir dir & forms)`: `forms` with `*sh-dir*` bound to `dir` |
| `with-sh-env` | `(with-sh-env env & forms)`: `forms` with `*sh-env*` bound to `env` |
| `*sh-dir*`, `*sh-env*` | The `:dir` and `:env` of a `sh` that gives none; `nil` for the current ones |

```console
clojure> (require '[clojure.java.shell :refer [sh]])
nil
clojure> (sh "echo" "hello")
{:exit 0, :out "hello\n", :err ""}
clojure> (sh "cat" :in "one\ntwo\n")
{:exit 0, :out "one\ntwo\n", :err ""}
clojure> (:out (sh "pwd" :dir "/tmp"))
"/tmp\n"
```

## Differences

- `:out-enc :bytes` is refused: there is no byte array.
- `:in` takes a string, a File or a reader, not a host `InputStream` or a byte array; `:env`
  takes a map, not a `String[]`.
