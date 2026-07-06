# defmethod

`(defmethod name (param... ) body...)`

総称関数 `name` にメソッドを追加し、名前シンボルを返します（先行する [`defgeneric`](defgeneric.md) がなければ総称関数を暗黙に作ります）。specializer を付けられるのは **第 1 引数のみ**で、`(var specializer)` と書きます:

- `(var (eql literal))` — 第 1 引数がそのリテラル（キーワード、クォートされたシンボル、数値、文字）のときにマッチ
- `(var class-name)` — [`defclass`](defclass.md) クラスとそのサブクラスのインスタンスにマッチ
- `(var type-name)` — 組み込み型（`integer`、`float`、`number`、`string`、`symbol`、`keyword`、`character`、`cons`、`list`、`null`、`hash-table`、`function` など）にマッチ
- `(var t)` または素の `var` — デフォルトメソッド

呼び出しはマッチする最も特定的なメソッドを実行します: まず `eql` メソッド、次にクラスメソッド（サブクラスがスーパークラスより先）、次に組み込み型（`integer` のようなサブタイプが `number` のようなスーパータイプより先）、最後にデフォルトメソッドの順で、マッチがなければエラーを通知します。同じ specializer を再定義すると以前のメソッドを置き換えます。本体はドキュメント文字列と `(declare ...)` で始められます（どちらも無視されます）。

ライトサブセット: 必須引数のみで、第 2 引数以降の specializer はエラー、メソッド修飾子（`:before`/`:after`/`:around`）と `call-next-method` はサポートされません。コンパイルパスでは `defmethod` はトップレベルフォームとしてのみサポートされ、コンパイルされたプログラムのメソッド集合はコンパイル時に固定されます。

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(defgeneric speak (x))
(defmethod speak ((x dog)) "woof")
(defmethod speak ((x animal)) "some sound")
(defmethod speak ((x integer)) "a number")
(defmethod speak ((x (eql :cat))) "meow")
(defmethod speak (x) "?")
(list (speak (make-instance 'dog)) (speak (make-instance 'animal))
      (speak 42) (speak :cat) (speak "s")) ; => ("woof" "some sound" "a number" "meow" "?")
```
