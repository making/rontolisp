# cell-error-name

`(cell-error-name condition)`

`cell-error` コンディションの `name` スロット — アクセスできなかったセルの名前です。`unbound-variable`、`undefined-function`、[`unbound-slot`](../macros/slot-boundp.md) を含むすべての `cell-error` のサブタイプが持っています。

```lisp
(defclass ce-box () ((v)))
(handler-case (slot-value (make-instance 'ce-box) 'v)
  (unbound-slot (e) (cell-error-name e))) ; => V
```

未定義の名前を呼び出したときに通知される `undefined-function` は、その名前を持ちます — `funcall`、`apply`、`symbol-function`、直接の呼び出しのいずれでも、すべてのバックエンドで:

```lisp
(handler-case (funcall (intern "CE-NO-SUCH-FUNCTION"))
  (undefined-function (e) (cell-error-name e))) ; => CE-NO-SUCH-FUNCTION
```

未束縛の名前を [`symbol-value`](symbol-value.md) で読み出したとき、または値なしで宣言したスペシャル変数を参照したときに通知される `unbound-variable` も、すべてのバックエンドでその名前を持ちます:

```lisp
(handler-case (symbol-value (intern "CE-NO-SUCH-VARIABLE"))
  (unbound-variable (e) (cell-error-name e))) ; => CE-NO-SUCH-VARIABLE
```

```lisp
(defvar *ce-unset*)
(handler-case *ce-unset*
  (unbound-variable (e) (cell-error-name e))) ; => *CE-UNSET*
```
