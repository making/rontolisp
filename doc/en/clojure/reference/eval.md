# eval

`(eval form)`

Evaluates a form given as data. In a macro body, or in a function the body calls, the form
lowers in the namespace of the expansion, without the call site's locals, and evaluates
over the definitions above the call site. As the program runs, the interpreter, which keeps
the program's lowering, lowers the form in the namespace `*ns*` holds and evaluates it:
what it defines the program's later `eval`s, `resolve`s and REPL inputs name, a value no
literal spells (a function, an atom) stands in the form as itself, and a form that does not
lower throws a `clojure.lang.Compiler$CompilerException` with the lowering's words. A JVM
class does the same: it carries its program and the compiler, lowers the program again at
its first run-time `eval`, and compiles each form into a class linked against itself, so a
`.class` output has the compiler beside it and a `.jar` inside it. A wasm module carries no
lowering, so there `eval` at run time throws an `UnsupportedOperationException`. Also names
a function value.

```clojure
(defmacro compile-if [test then else] (if (eval test) then else))
(def limit 3)
(println (compile-if (> limit 2) :big :small))
(println (compile-if (resolve 'clojure.core/inc) :has :lacks))
```

```
:big
:has
```
