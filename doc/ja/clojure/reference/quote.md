# quote

`(quote form)` `'form`

フォームを評価せずに返します。シンボルは `c%` 接頭辞の下で mangle されて渡り、表示時に再び demangle されるため、quote されたシンボルは読んだままに表示されます。quote されたベクターは `vector` 呼び出しとして再放出され、quote されたマップやセットは quote された要素の上の構築になります -- 3 者とも実際のコレクションを組み立てます。

```clojure
(println 'qi-a)         ; qi-a
(println '(1 2 qi-b))   ; (1 2 qi-b)
(println '[1 :a])       ; [1 :a]
(println (get '{:a 1} :a)) ; 1
```
