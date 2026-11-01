# compile

`(compile name definition)`

Coerces a literal `(lambda ...)` definition to a function, evaluated in the null lexical environment. With `name` nil, the function itself is returned; with a symbol `name`, the function is also installed as the global definition of that name and the name is returned, as in Common Lisp. rontolisp's `compile` does not generate native code -- it exists so definition-time code construction idioms load: in particular, a no-argument definition whose body defines methods over class metaobjects (the `(funcall (compile nil `(lambda () ,code)))` idiom of object mappers, where `code` closes over values computed from the class) is executed as definition-time method construction.

In a compiled program (JVM / WASM output), that method-construction idiom is expanded at compile time and spliced into the program, and the run-time re-execution of the same call is a no-op. A definition that the program's own top-level definitions compile -- a library building a function from a lambda expression while it defines a class or a method, such as contextl's layered accessors -- is compiled ahead: the compiler runs those definitions when it compiles the program, and each definition they hand `compile` becomes compiled code in the program, with every object the definition holds (a generic function, a quoted list) taken from the run-time call. Any other run-time `compile` call evaluates the definition through the program's [`eval`](eval.md) runtime, since a compiled program carries no compiler (see [Compiled `eval` limitations](../../guides/eval-limitations.md)).

```lisp
(list (funcall (compile nil '(lambda (x) (* x x))) 7)
      (compile 'my-inc '(lambda (x) (+ x 1)))
      (my-inc 41)) ; => (49 MY-INC 42)
```
