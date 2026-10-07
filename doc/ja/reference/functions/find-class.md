# find-class

`(find-class symbol &optional (errorp t) environment)`

`symbol` が名指すクラスメタオブジェクト — [`class-name`](class-name.md) や `closer-mop` のリーダー(`class-slots`、`slot-definition-name` など)がスロットを読み取れる `standard-class` インスタンス — を返します。結果はメモ化されるため、同じクラスへの 2 回の呼び出しは同一(`eq`)のオブジェクトを返し、そのクラスのインスタンスに対して [`class-of`](class-of.md) が返すオブジェクトとも同一です。`symbol` という名前のクラスが存在しない場合、`errorp` が `nil` でなければエラーをシグナルし、`nil` なら `nil` を返します。`environment` は無視されます。既知のクラスはプログラム中のすべての `defclass` / `define-condition` / `defstruct`、組み込みコンディション階層、そして組み込みクラスです。組み込みクラスは [`class-of`](class-of.md) が返すもの(`integer`、`ratio`、`float`、`complex`、`string`、`character`、`symbol`、`keyword`、`cons`、`null`、`boolean`、`hash-table`、`function`、`vector`、`array`、ストリームクラスの `stream`、`file-stream`、`string-stream`、`synonym-stream`、`two-way-stream`、`broadcast-stream`、`echo-stream`、`concatenated-stream`、`t`)に加えて、束の内部にあたる名前 — `class-of` が常により狭い答えを持つため名前でしか到達できません — (`sequence`、`list`、`number`、`real`、`rational`、`bit-vector`、`structure-object`、`built-in-class`、`standard-object`)です。組み込みクラスのメタオブジェクトはスロットもスーパークラスも持ちません。型指定子の位置に置かれたときはその名前と同じ集合を意味するので、束を持っているのは [`typep`](../macros/typep.md) と [`subtypep`](subtypep.md) であってメタオブジェクトではありません。コンパイルバックエンドではクラス集合はコンパイル時に固定され、実行時データから構築されるクラスは存在しません。

```lisp
(defclass point () ((x :initarg :x)))
(list (eq (find-class 'point) (find-class 'point))
      (find-class 'no-such-class nil)) ; => (T NIL)
```

```lisp
(list (class-name (find-class 'bit-vector))
      (typep #(1 2) (find-class 'array))
      (subtypep (find-class 'vector) 'array)) ; => (BIT-VECTOR T T)
```

`(setf (find-class alias) class)` は `class` を 2 つ目の名前で登録します。これ以降、`find-class`、`make-instance`、`typep`、`subtypep`、`handler-case` の節はいずれも別名を同一のクラスへ解決します(メタオブジェクトは `eq`)。サポートするのはこの別名付けの形だけで、値は既に定義済みのクラスを名指すリテラルな `(find-class 'target)` である必要があり、かつトップレベルでのみ使えます。コンパイルバックエンドはクラステーブルをコンパイル時に構築するためです。

```lisp
(defclass shape () ((n :initarg :n :reader shape-n)))
(setf (find-class '<shape>) (find-class 'shape))
(list (eq (find-class '<shape>) (find-class 'shape))
      (shape-n (make-instance '<shape> :n 7))) ; => (T 7)
```
