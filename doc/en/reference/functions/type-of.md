# type-of

`(type-of object)`

The type name of a value as a symbol: a `defstruct`/CLOS instance answers its structure/class NAME, any other value answers a built-in type-name symbol (`integer`, `string`, `cons`, ...), falling back to `t`. It is the name-only view of what [`class-of`](class-of.md) answers as a class metaobject: `(type-of x)` and `(class-name (class-of x))` agree.

```lisp
(type-of 42) ; => INTEGER
```
