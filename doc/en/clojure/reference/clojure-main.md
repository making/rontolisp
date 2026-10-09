# clojure.main

The error reports of Clojure's REPL and script runner, and the helpers they share.
`clojure.main` is loaded before the program, as in Clojure, so `clojure.main/demunge` works
without a `require`; it is Clojure source written for rontolisp from the documented behavior
of Clojure's namespace, and runs the same on every backend.

| Var | Behavior |
|---|---|
| `ex-triage` | `(ex-triage data)`: the phase, class, cause, symbol, source, path, line and column of an error, as `:clojure.error/` keys, from the data `Throwable->map` answers |
| `ex-str` | `(ex-str triage)`: the report of an error: a line naming its phase and location, then its cause |
| `err->msg` | `(err->msg e)`: `ex-str` of `ex-triage` of `(Throwable->map e)` |
| `repl-caught` | `(repl-caught e)`: prints `(err->msg e)` to `*err*` |
| `repl-exception` | `(repl-exception e)`: the root cause of `e` |
| `root-cause`, `demunge`, `stack-element-str` | As [clojure.repl](clojure-repl.md)'s |
| `repl-prompt` | `(repl-prompt)`: prints the current namespace and `=> ` |
| `repl-requires` | The libspecs Clojure's REPL requires when it starts |
| `with-read-known` | `(with-read-known & body)`: `body` with `*read-eval*` true where it is `:unknown` |

```clojure
(require '[clojure.main :as m])
(print (m/ex-str (m/ex-triage {:via [{:type 'java.lang.ArithmeticException :message "Divide by zero"}]
                               :trace '[[user$eval1$fn__2 invoke "NO_SOURCE_FILE" 7]]})))
```

```
Execution error (ArithmeticException) at user/eval1$fn (REPL:7).
Divide by zero
```

## Not built in

No compiler runs at run time, so `repl`, `main` and `load-script` are refused by name when the
program is lowered, and so are the parts of the REPL: `repl-read`, `renumbering-read`,
`skip-whitespace`, `skip-if-eol`, `with-bindings` and `report-error`.

## Differences

- A throwable carries no stack frames here, so the triage of one names no symbol, source or
  line: `err->msg` and `repl-caught` report it at `(REPL:1)`.
- `:clojure.error/path` is the source path as the data gives it, where Clojure's makes a path
  below the working directory relative.
- `ex-str` of spec problems is refused: `clojure.spec.alpha` is not built in.
