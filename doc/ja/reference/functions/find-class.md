# find-class

`(find-class symbol &optional (errorp t) environment)`

`symbol` が名指すクラスメタオブジェクト — `closer-mop` のリーダー(`class-name`、`class-slots`、`slot-definition-name` など)がスロットを読み取れる `standard-class` インスタンス — を返します。結果はメモ化されるため、同じクラスへの 2 回の呼び出しは同一(`eq`)のオブジェクトを返します。`symbol` という名前のクラスが存在しない場合、`errorp` が `nil` でなければエラーをシグナルし、`nil` なら `nil` を返します。`environment` は無視されます。既知のクラスはプログラム中のすべての `defclass` / `define-condition` と組み込みコンディション階層です。コンパイルバックエンドではクラス集合はコンパイル時に固定され、実行時データから構築されるクラスは存在しません。

```lisp
(defclass point () ((x :initarg :x)))
(list (eq (find-class 'point) (find-class 'point))
      (find-class 'no-such-class nil)) ; => (T NIL)
```
