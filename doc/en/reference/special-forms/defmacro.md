# defmacro

`(defmacro name (params... [&rest|&body rest]) body...)`

Defines a user macro named `name` and returns the name symbol. A macro call receives its argument forms **unevaluated**: the `body` runs at expansion time with the parameters bound to the raw forms, and the form it returns (the expansion) is evaluated in place of the call. The lambda list supports required parameters and one trailing `&rest`/`&body` parameter that collects the remaining forms as a list; `&optional`/`&key` are not supported. A standard operator (`when`, `setf`, ...) cannot be redefined, and a macro has no function value (`#'name` is an error).

Macro bodies usually build the expansion with the backquote template syntax, which is also available anywhere else in a program:

- `` `form `` quotes `form` except where a comma unquotes it
- `,expr` inserts the value of `expr`
- `,@expr` splices the value of `expr` (a list) into the surrounding list

Nested backquote is not supported. Use [`gensym`](../functions/gensym.md) to generate capture-safe temporaries in macro bodies, and [`macroexpand-1`](../functions/macroexpand-1.md)/[`macroexpand`](../functions/macroexpand.md) to inspect an expansion.

The interpreter expands macro calls at evaluation time (so `defmacro` also works in the REPL and via `load`/`eval`). On the compilation path the CLI fully expands every macro call **before** the JVM/WASM compilers run and removes the definitions, so compiled output contains only ordinary forms; consequently the runtime `eval`/`read` of a compiled program does not know `defmacro` or the backquote character, and a macro must be defined before its first use.

```lisp
(defmacro my-unless (test &body body)
  `(if ,test nil (progn ,@body)))
(my-unless (> 1 3) 'a 'b) ; => b
```

```lisp
(defmacro swap! (a b)
  `(let ((__tmp ,a))
     (setq ,a ,b)
     (setq ,b __tmp)))
(setq x 1)
(setq y 2)
(swap! x y)
(list x y) ; => (2 1)
```
