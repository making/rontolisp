# functionp

`(functionp object)`

`object` が関数値 — `(lambda ...)`、`#'name`、`symbol-function` の結果 — であれば `t` を、それ以外のオブジェクトには `nil` を返します。Lisp-2 では裸のシンボルは決して関数ではないため、`(functionp 'car)` は `nil`、`(functionp #'car)` は `t` です。

```lisp
(functionp #'car) ; => t
```

```lisp
(functionp (lambda (x) x)) ; => t
```

```lisp
(functionp 'car) ; => nil
```
