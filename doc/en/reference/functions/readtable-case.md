# readtable-case

`(readtable-case readtable)`

Lite stub: always returns `:upcase` -- the reader is not readtable-driven and always upcases unescaped symbol names, which is exactly the standard readtable's `:upcase` mode. The argument is evaluated but ignored (the `*readtable*` variable exists but is seeded to `nil`). Exists so library code that branches on the readtable case, like s-sql's `from-sql-name`, takes the standard-mode branch.

```lisp
(readtable-case *readtable*) ; => :UPCASE
```
