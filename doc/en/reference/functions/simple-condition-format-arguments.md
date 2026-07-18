# simple-condition-format-arguments

`(simple-condition-format-arguments condition)`

The `:format-arguments` slot of a condition instance (nil when the condition has no such slot).

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(simple-condition-format-arguments
 (make-condition 'simple-error :format-control "boom ~a" :format-arguments '(1))) ; => (1)
```
