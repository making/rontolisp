# macro-function

`(macro-function symbol &optional environment)`

ライト版スタブ: 常に `nil` を返します。マクロはコンパイル時に完全展開されるため、実行時マクロテーブルは存在しません。

```lisp
(macro-function 'when) ; => NIL
```

`setf` の場所としては 1 つの形だけをサポートします。`(setf (macro-function 'new) (macro-function 'existing))` は既存の `defmacro` 定義マクロに 2 つ目の名前を与え、展開器を共有させます。以降、両方の名前は同一に展開されます。それ以外 — 任意の展開器関数や、ユーザマクロでない名前 — はエラーをシグナルします。保存すべきマクロ関数オブジェクトが存在しないためです。

```lisp
(defmacro greet (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet))
(hi "world") ; => (:HELLO "world")
```
