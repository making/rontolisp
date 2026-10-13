# load-file

`(load-file path)`

The file the literal `path` names read and lowered where the call stands, like
[`load-string`](load-string.md) -- resolved like a `load` path, answering the last form's
value. Anything computed refuses at run time (no compiler runs there). As a value one
argument (which refuses).

```console
clojure> (load-file "helper.clj")
41
```
