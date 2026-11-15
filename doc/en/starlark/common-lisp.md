# Mixing with Common Lisp

The language is picked per file, so one program may hold Starlark files and Common Lisp
files. No value is converted between them: it is the same object on both sides.

| Starlark | Common Lisp |
|---|---|
| `None` | `:none` |
| `True`, `False` | `t`, `nil` |
| an int, a float, a string | an integer, a double float, a string |
| a list, a tuple, a dict, a range, a function | an opaque structure, to hand back to Starlark |
| a value of type `"unknown"`: true, equal to itself only, no dict key | any other value (a cons, a symbol, a hash table) |
| a built-in function | a function |

`nil` is `False`, so an empty Common Lisp list is no Starlark list.

## Common Lisp calls Starlark

`(load "rules.star")` runs the file. Each of its globals is the variable of that name,
written between vertical bars because a Starlark name keeps its case.
[`rontolisp:starlark-call`](../reference/functions/rontolisp-starlark-call.md) calls a
function value -- a `def`, a `lambda`, a built-in or a method -- with positional arguments,
bound as a Starlark call binds them. `apply` spreads a Common Lisp list over them.

```console
$ cat rules.star
def greet(name, greeting = "Hello"):
    return greeting + ", " + name
def longest(*words):
    return max(words, key = len)
$ cat main.lisp
(load "rules.star")
(write-line (rontolisp:starlark-call |greet| "Lisp"))
(write-line (rontolisp:starlark-call |greet| "Lisp" "Hi"))
(write-line (apply #'rontolisp:starlark-call |longest| '("a" "abc" "ab")))
$ rontolisp main.lisp
Hello, Lisp
Hi, Lisp
abc
```

Once `load` returns, the lists and dicts the file made are [frozen](index.md#modules). A
call that ends in an error some handler takes (`handler-case`) leaves nothing counted: the
lists its loops were running over can be changed again.

The `defun` some `def`s also lower to is the front end's own and may change; call the
value.

## Starlark calls Common Lisp

A `load` whose module is `@` and a package name binds functions of that package:
`load("@cl-user", "emit", note = "add-note")` binds `emit` to the function `emit` and
`note` to `add-note`. The string is read as a symbol is: upcased, or kept as written
between vertical bars (`"|twice|"`). The function must be defined when the statement runs.

Such a function is a built-in function value. It takes positional arguments as they are --
a named one is an error -- and its value comes back as it is. A `*` argument spreads a
Starlark list over its parameters, so `cl_list(*xs)` below makes a Common Lisp list.

```starlark
load("@cl", "gcd", upcase = "string-upcase", cl_list = "list")
print(gcd(12, 18), upcase("abc"), upcase, type(cl_list(*[1, 2])))
```

```
6 ABC <built-in function cl:string-upcase> unknown
```

A function that Common Lisp hands over as an argument is called the same way. A program
whose own functions Starlark calls defines them before it loads the file:

```console
$ cat rules.star
load("@cl-user", "emit")
emit("app", 2)
emit("lib", 0)
$ cat main.lisp
(defun emit (name deps) (format t "~a has ~a deps~%" name deps))
(load "rules.star")
$ rontolisp main.lisp
app has 2 deps
lib has 0 deps
```
