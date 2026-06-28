# Dynamic (Late Binding)

By default the JVM and WASM compilers resolve every call and variable reference statically and reject anything they cannot find at compile time (`Cannot compile: cube`). That catches typos, but it also means a source that calls a function defined later by `load` must wrap the call in `eval` (`(eval '(cube 3))`) to compile.

The `--dynamic` flag relaxes this: a call or reference that cannot be resolved statically is deferred to the runtime `eval` environment (late binding) instead of failing. This lets a program you tested in the interpreter compile unchanged -- typically to run it faster -- without rewriting `(cube 3)` into `(eval '(cube 3))`.

```bash
echo '(load "lib.lisp") (print (cube 3))' > prog.lisp
rontolisp prog.lisp -o Prog.class --dynamic   # compiles; (cube 3) resolves at runtime
rontolisp prog.lisp -o prog.wasm  --dynamic
```

A call `(f a b)` compiles to `_apply(_eval('(function f), null), (list a b))`: the operator is resolved against the runtime function namespace while the arguments are compiled normally, so locals of the enclosing compiled function stay visible (e.g. `(defun caller (n) (cube n))` works). A bare reference `x` compiles to `_eval('x, null)`, which resolves the variable namespace only. Because the fallback uses the embedded `eval` runtime, `--dynamic` always emits it (as if the program used `eval`), and an unknown symbol that is never defined at runtime errors when it is reached rather than at compile time. Functions resolved this way run on the runtime `eval` interpreter, so they are subject to the [Compiled `eval` limitations](../guides/eval-limitations.md) above.
