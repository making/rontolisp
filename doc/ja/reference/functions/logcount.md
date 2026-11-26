# logcount

`(logcount integer)`

非負整数の 1 であるビットの数、負の整数の 0 であるビットの数(2 の補数表現での population count)を返します。

```lisp
(logcount 7) ; => 3
```

```lisp
(logcount -1) ; => 0
```
