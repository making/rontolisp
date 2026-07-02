# java:proxy

`(java:proxy "fully.qualified.Interface" callable)`

Creates a host instance of the given interface backed by a rontolisp callable.
Every interface method is dispatched to the callable as `(callable "method-name"
arg...)` — so the callable's **first argument is the name of the invoked method**
(a string) and the remaining arguments are the method's arguments. The callable's
return value is marshalled back to the method's return type (a `void` method
ignores it). This is how a rontolisp lambda becomes a Java listener or
comparator. For a single-method (SAM) interface the method name is always the
same, so it is conventionally ignored (the `method` parameter in the examples
below). Part of the JVM-only `java` interop
package — available on the interpreter and in JVM-compiled classes, not on the
WASM backend. See the [Java interop
guide](../../guides/java-interop.md).

```lisp
(java:call (java:proxy "java.util.function.Supplier" (lambda (method) 42)) "get")
; => 42
```

A `java.util.function.Supplier` is implemented by the lambda; calling its `get`
method runs the lambda and returns `42`.

## Functional interfaces

Any interface works, including the `java.util.function` family. The lambda's
first parameter receives the method name; the rest receive the method arguments,
so match the arity to the interface's single abstract method (SAM):

| Interface | SAM | Lambda shape |
|-----------|-----|--------------|
| `Supplier` | `get()` | `(lambda (method) ...)` |
| `Function` | `apply(x)` | `(lambda (method x) ...)` |
| `Consumer` | `accept(x)` | `(lambda (method x) ...)` |
| `Predicate` | `test(x)` | `(lambda (method x) ...)` |
| `BiFunction` | `apply(a, b)` | `(lambda (method a b) ...)` |
| `BinaryOperator` | `apply(a, b)` | `(lambda (method a b) ...)` |
| `Comparator` | `compare(a, b)` | `(lambda (method a b) ...)` |

```lisp
;; Function<Integer,Integer>: apply(x) -> x + 1
(java:call (java:proxy "java.util.function.Function" (lambda (method x) (+ x 1))) "apply" 41)
; => 42
```

```lisp
;; BiFunction<Integer,Integer,Integer>: apply(a, b) -> a * b
(java:call (java:proxy "java.util.function.BiFunction" (lambda (method a b) (* a b))) "apply" 6 7)
; => 42
```

```lisp
;; Predicate<Integer>: test(x) -> even?
(java:call (java:proxy "java.util.function.Predicate" (lambda (method x) (evenp x))) "test" 4)
; => t
```

The proxy also works when the JDK itself invokes the SAM method. For example
`HashMap.merge` calls the supplied `BiFunction` to combine the old and new value:

```lisp
(let ((m (java:new "java.util.HashMap"))
      (mul (java:proxy "java.util.function.BiFunction" (lambda (method a b) (* a b)))))
  (java:call m "put" "x" 10)
  (java:call m "merge" "x" 5 mul)
  (java:call m "get" "x"))
; => 50
```

## Default methods

A dynamic proxy routes **every** method call to the callable, including default
methods such as `BiFunction.andThen` or `Predicate.and`. Calling one dispatches
to the lambda as `(callable "andThen" ...)` rather than running the interface's
built-in default implementation, so combinators like `(f.andThen g)` are not
available — call the single abstract method (`apply`/`test`/`accept`/`get`/
`compare`) instead.
