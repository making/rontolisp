# var

`(var name)`, `#'name`

プログラムの定義の var を返します。名前ごとに 1 つのオブジェクトで、`#'ns/name` と
表示されます。`deref`（または `@`）はルートを読み、呼び出すとルートを呼ぶため、関数を
渡せるところならどこでも `#'f` を `f` の代わりに使えます。[meta](meta.md) は、その位置より
上にある最新の定義が記録したもの（`:arglists`、docstring の `:doc`、名前のメタデータと
attr マップ、`:line`/`:column`/`:file`、`:name`、`:ns`）を返します。ローカルは var では
なく（名前はローカルを越えて解決されます）、`clojure.core` の var は名前で拒否されます。

```clojure
(defn greet "Says hello." [who] (str "Hello, " who))
(println #'greet)                ; #'user/greet
(println (:doc (meta #'greet)))  ; Says hello.
(println (:arglists (meta #'greet))) ; ([who])
(println (#'greet "Ann"))        ; Hello, Ann
```
