# load-file

`(load-file path)`

The file the literal `path` names read and lowered where the call stands, like
[`load-string`](load-string.md) -- resolved like a `load` path, answering the last form's
value. A computed path loads when the call runs on the interpreter, which keeps the
program's lowering; a compiled program carries none, so there it refuses at run time. As a
value one argument.

```console
clojure> (load-file "helper.clj")
41
```
