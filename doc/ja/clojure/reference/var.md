# var

`(var name)`, `#'name`

プログラムの定義の var を返します。名前ごとに 1 つのオブジェクトで、`#'ns/name` と
表示されます。`deref`（または `@`）はルートを読み、呼び出すとルートを呼ぶため、関数を
渡せるところならどこでも `#'f` を `f` の代わりに使えます。宣言されただけで定義されない
名前の var は未束縛のルートを読みます（[declare](declare.md)）。[meta](meta.md) は、その位置より
上にある最新の定義が記録したもの（`:arglists`、docstring の `:doc`、名前のメタデータと
attr マップ、`:line`/`:column`/`:file`、`:name`、`:ns`）を返します。ローカルは var では
ありません（名前はローカルを越えて解決されます）。`clojure.core` の名前は core の var で、
`#'clojure.core/name` と表示されます。ルートは core の値、メタデータは `:name`、`:ns` と
マクロの `:macro` です。ここで値を持たない core の var（`#'*err*`）は拒否されます。

```clojure
(defn greet "Says hello." [who] (str "Hello, " who))
(println #'greet)                ; #'user/greet
(println (:doc (meta #'greet)))  ; Says hello.
(println (:arglists (meta #'greet))) ; ([who])
(println (#'greet "Ann"))        ; Hello, Ann
(println #'inc (#'inc 1))        ; #'clojure.core/inc 2
```
