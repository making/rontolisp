# pathnamep

`(pathnamep object)`

Always nil: rontolisp has no pathname type (paths are plain strings), so nothing is a pathname. Exists so portable type dispatches over `pathname` compile and take their other branches.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(pathnamep "/tmp/data.json") ; => nil
```
