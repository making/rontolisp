# class-name

`(class-name class)`

Returns the name symbol of a class metaobject -- what [`find-class`](find-class.md) and [`class-of`](class-of.md) answer. Signals when the argument is not a class metaobject. `(setf (class-name class) name)` renames the metaobject; `find-class` keeps answering it for its old name.

```lisp
(class-name (class-of "hello")) ; => STRING
```
