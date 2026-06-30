# Dynamic (Late Binding)

By default the JVM and WASM compilers resolve every call and variable reference statically and reject anything they cannot find at compile time (`Cannot compile: cube`). A top-level `(load "lib.lisp")` with a literal path is handled for you: its forms are spliced into the program at compile time (a [compile-time include](#compile-time-include)), so the functions it defines compile natively and need no special handling. But a `load` whose path is computed at runtime, or one nested inside another form, runs only at runtime -- the compiler cannot see what it defines, so a static call to one of those functions still fails to compile.

The `--dynamic` flag relaxes this: a call or reference that cannot be resolved statically is deferred to the runtime `eval` environment (late binding) instead of failing. This lets a program you tested in the interpreter -- one that loads code by a computed path, or otherwise defines functions only at runtime -- compile unchanged, without rewriting `(cube 3)` into `(eval '(cube 3))`.

```bash
echo '(defun cube (n) (* n n n))' > lib.lisp
echo '(load (concatenate (quote string) "li" "b.lisp")) (print (cube 3))' > prog.lisp
rontolisp prog.lisp -o Prog.class --dynamic   # the computed-path load is invisible to the compiler; (cube 3) resolves at runtime
rontolisp prog.lisp -o prog.wasm  --dynamic
```

A call `(f a b)` compiles to `_apply(_eval('(function f), null), (list a b))`: the operator is resolved against the runtime function namespace while the arguments are compiled normally, so locals of the enclosing compiled function stay visible (e.g. `(defun caller (n) (cube n))` works). A bare reference `x` compiles to `_eval('x, null)`, which resolves the variable namespace only. Because the fallback uses the embedded `eval` runtime, `--dynamic` always emits it (as if the program used `eval`), and an unknown symbol that is never defined at runtime errors when it is reached rather than at compile time. Functions resolved this way run on the runtime `eval` interpreter, so they are subject to the [Compiled `eval` limitations](../guides/eval-limitations.md) above.

## Compile-time include

You usually do not need `--dynamic` just to split a program across files. A **top-level** `(load "lib.lisp")` whose path is a string literal is treated as a *compile-time include*: the compiler reads `lib.lisp` and splices its forms into the program before compiling (recursively, with a guard against circular loads). The definitions are compiled natively -- no `--dynamic`, no `eval` runtime, and no slowdown -- exactly as if the files had been concatenated.

```bash
echo '(defun cube (n) (* n n n))' > lib.lisp
echo '(load "lib.lisp") (print (cube 3))' > prog.lisp
rontolisp prog.lisp -o Prog.class   # (load "lib.lisp") is inlined; cube compiles natively
rontolisp prog.lisp -o prog.wasm
```

Only a top-level `load` with a literal path is inlined; a `load` whose path is computed, or one nested inside another form, stays a runtime call and needs `--dynamic` (above) for the compiler to accept calls into the loaded code. The interpreter is unaffected -- it always loads at runtime -- and the include resolves paths the same way the runtime `load` does (relative to the current working directory).
