# clojure.stacktrace

Printing of throwables and their causes. Require `clojure.stacktrace` to use it; it is Clojure
source written for rontolisp from the documented behavior of Clojure's namespace, and runs the
same on every backend.

| Var | Behavior |
|---|---|
| `root-cause` | `(root-cause tr)`: the innermost cause of `tr`, following its causes; `tr` when it has none |
| `print-throwable` | `(print-throwable tr)`: `tr`'s class and message, then its `ex-data` on a line of its own |
| `print-stack-trace` | `(print-stack-trace tr)`, `(print-stack-trace tr n)`: `print-throwable`, then the stack frames (the first `n`) |
| `print-cause-trace` | `(print-cause-trace tr)`, `(print-cause-trace tr n)`: `print-stack-trace` of `tr` and of each cause after `Caused by:` |
| `print-trace-element` | `(print-trace-element e)`: one stack trace element, a Clojure function as `namespace/name` |
| `e` | `(e)`: a short stack trace of the root cause of `*e` |

```clojure
(require '[clojure.stacktrace :as st])
(def failure (Exception. "outer" (ex-info "boom" {:x 1})))
(ex-message (st/root-cause failure)) ; => "boom"
```

```clojure
(require '[clojure.stacktrace :as st])
(st/print-throwable (ex-info "boom" {:x 1}))
```

```
clojure.lang.ExceptionInfo: boom
{:x 1}
```

## Differences

- A throwable carries no stack frames here, so `print-stack-trace`, `print-cause-trace` and
  `e` print ` at [empty stack trace]` where Clojure prints the frames of the code that built
  it.
