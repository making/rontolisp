# type-of

`(type-of object)`

The type name of a value as a symbol: a `defstruct`/CLOS instance answers its structure/class NAME (unlike [`class-of`](class-of.md), which answers the instance's class tag), any other value answers the built-in type-name symbol `class-of` reports (`integer`, `string`, `cons`, ...), falling back to `t`.

```lisp
(type-of 42) ; => INTEGER
```
