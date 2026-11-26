# error

`(error message obj ...)`

文字列 `message` と irritant `obj ...` を持つエラーオブジェクトを発生させます。`error-object-message` と `error-object-irritants` で取り出せ、`guard` と `with-exception-handler` はほかの発生させたオブジェクトと同じように捕捉します。捕捉されなければプログラムを終了し、`message` と、`write` の形式で書かれた irritant を表示します（`(error "bad thing:" 42 "str")` は `bad thing: 42 "str"` と報告します）。プロセスは終了ステータス 1 で終わります。その前に、囲んでいる `dynamic-wind` の `after` が実行されます。

```scheme
(guard (e (#t (error-object-message e))) (error "division by zero:" 1)) ; => "division by zero:"
(guard (e (#t (error-object-irritants e))) (error "bad thing:" 42 "str")) ; => (42 "str")
```
