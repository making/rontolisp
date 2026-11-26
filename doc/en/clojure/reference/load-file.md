# load-file

`(load-file path)`

The file the literal `path` names read and lowered where the call stands, like
[`load-string`](load-string.md), answering the last form's value: a path of the file
system, relative to the working directory, in whatever namespace the call stands, as
Clojure opens it. A namespace the file switches to is the load's own. A computed path
loads when the call runs on the interpreter, which keeps the program's lowering; a compiled
program carries none, so there it refuses at run time. As a value one argument.

```console
clojure> (load-file "helper.clj")
41
```
