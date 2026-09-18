# raise

`(raise obj)`

`obj`（任意のオブジェクト）を発生させます。`with-exception-handler` で設定した最も内側のハンドラがそれを引数に呼ばれるか、最も内側の `guard` がそれを受け取ります。ハンドラが戻ると、ハンドラが実行された場所で二次エラーが発生します。ハンドラが一つもなければプログラムを終了し、`obj` を `write` の形式で報告して（`(raise (list 1 "a"))` は `(1 "a")` と報告します）終了ステータス 1 で終わります。

```scheme
(guard (e (#t (list 'caught e))) (raise 42)) ; => (caught 42)
(guard (e ((string? e) (string-append "got " e))) (raise "it")) ; => "got it"
```
