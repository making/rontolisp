# rontolisp:starlark-call

`(rontolisp:starlark-call function argument*)`

Calls a function value of the experimental [Starlark](../../starlark/index.md) front end --
a `def`, a `lambda`, a built-in function or a method bound to its receiver -- with
positional arguments, and returns its value. Arguments and value cross as they are
([Mixing with Common Lisp](../../starlark/common-lisp.md)). A Common Lisp function is
called as `apply` calls it.

```lisp
(rontolisp:starlark-call #'+ 1 2)   ; => 3
```

A global of a Starlark file is the variable of its name:

```console
$ cat rules.star
def greet(name, greeting = "Hello"):
    return greeting + ", " + name
$ cat main.lisp
(load "rules.star")
(write-line (rontolisp:starlark-call |greet| "Lisp"))
$ rontolisp main.lisp
Hello, Lisp
```

The arguments are bound as a Starlark call binds them: a parameter with a default may be
left out, `*args` takes the surplus, and a call that does not fit is an error with
Starlark's message. When the call ends in an error some handler takes, the lists and dicts
its loops were running over can be changed again, and under `--starlark-standard spec` the
functions it was running can be called again.

## Backend support

Works on every backend and in every WASM mode: the Starlark run-time library is written in
rontolisp itself and is compiled into the program when used.
