# class-of

`(class-of object)`

Lite: the class-tag symbol of a CLOS instance, or a built-in type name symbol (`integer`, `string`, `cons`, ...) for other values — a name, not a class metaobject (rontolisp has no MOP).

```lisp
(class-of 42) ; => INTEGER
```
