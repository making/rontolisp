# get-environment-variables

`(get-environment-variables)`

Every environment variable as a fresh list of `(name . value)` pairs of strings, in the host's order, which no backend promises to share. A value may contain `=`; the pair splits at the first one. The same sources as [get-environment-variable](get-environment-variable.md).

```console
$ cat env.scm
(import (scheme base) (scheme write) (scheme process-context))
(write (assoc "GREETING" (get-environment-variables)))
$ GREETING=hello rontolisp env.scm
("GREETING" . "hello")
```

```scheme
(write (list? (get-environment-variables)))
```

```
#t
```
