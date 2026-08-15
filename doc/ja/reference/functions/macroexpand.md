# macroexpand

`(macroexpand form)`

トップレベルのフォームに対して [`macroexpand-1`](macroexpand-1.md) を、それ以上展開されなくなるまで繰り返し、その結果と同じ `expanded-p` を 2 番目の値として返します。`macroexpand-1` と同様、展開されるのは演算子の位置だけで(サブフォーム内のマクロ呼び出しは展開されないまま残ります)、環境引数は無視され、コンパイルパスで畳み込まれるのはリテラルのクォートされた引数だけです(計算された引数はフォームをそのまま返し、そのフォームがマクロ呼び出しならシグナルします)。

```lisp
(defmacro inner (x) `(+ ,x 1))
(defmacro outer (x) `(inner ,x))
(macroexpand '(outer 41)) ; => (+ 41 1)
```

サブフォームは走査されません:

```lisp
(macroexpand '(when a (when b c))) ; => (IF A (WHEN B C) NIL)
```
