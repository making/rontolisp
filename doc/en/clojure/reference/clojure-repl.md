# clojure.repl

A var's documentation and a throwable's stack trace. Require `clojure.repl` to use it; it is
Clojure source written for rontolisp from the documented behavior of Clojure's namespace, and
runs the same on every backend.

| Var | Behavior |
|---|---|
| `doc` | `(doc name)`: prints the var's name, its argument lists, `Macro` for a macro, and its docstring, as its definition gave them; for a special form its name, `Special Form` and its clojure.org page |
| `pst` | `(pst)`, `(pst e-or-depth)`, `(pst e depth)`: prints the throwable's simple class name, message and `ex-data` to `*err*`, then each cause after `Caused by:`; without a throwable, the root cause of `*e` |
| `root-cause` | `(root-cause t)`: the innermost cause of `t`, following its causes; `t` when it has none |
| `demunge` | `(demunge s)`: the Clojure spelling of a function's class name, as a stack trace element names it |
| `stack-element-str` | `(stack-element-str el)`: a host `StackTraceElement` as text, a Clojure function by its demunged name (interpreter and JVM) |

```clojure
(require '[clojure.repl :refer [doc]])
(defn add-one "Adds one." [x] (inc x))
(doc add-one)
```

```
-------------------------
user/add-one
([x])
  Adds one.
```

```clojure
(require '[clojure.repl :as r])
(r/demunge "my_app.core$valid_QMARK_") ; => "my-app.core/valid?"
```

## Not built in

Each of these is refused by name when the program is lowered:

- `dir`, `dir-fn`, `apropos`, `find-doc`: a namespace's vars are known only while the program
  is lowered.
- `source`, `source-fn`: a definition's text is not kept at run time.
- `set-break-handler!`, `thread-stopper`: there is no signal handler, and the JDK no longer
  supports `Thread.stop`.

## Differences

- `doc` of a special form prints its name, `Special Form` and the clojure.org link, without
  Clojure's forms and text. A `clojure.core` var's metadata holds only `:name` and `:ns`, so
  `doc` prints its name alone.
- `doc` of a name that is no var, a namespace's included, is the lowering's
  `Unable to resolve var`, where Clojure prints the namespace's docstring or nothing; `doc` of
  a keyword (a spec) is refused.
- A throwable carries no stack frames here, so `pst` prints none.
