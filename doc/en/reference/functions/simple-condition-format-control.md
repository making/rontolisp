# simple-condition-format-control

`(simple-condition-format-control condition)`

The `:format-control` slot of a condition instance (nil when the condition has no such slot). With [`simple-condition-format-arguments`](simple-condition-format-arguments.md) it lets a `:report` function re-render a wrapped condition's message.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(simple-condition-format-control
 (make-condition 'simple-error :format-control "boom ~a")) ; => "boom ~a"
```
