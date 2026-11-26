# disj

`(disj s k ...)`

与えた要素を引いた新しいセットを返します。存在しない要素は無視されます。`nil` の `disj` は `nil` です。

値としてはセットに残り引数の要素列を取ります。

仕様との差異: マップへの `disj` はシグナルを上げます（Common Lisp の型エラーで、オラクルのメッセージではありません）。[`disj!`](disj-bang.md) はトランジェントのセットからメンバーをその場で取り除きます。

```clojure
(println (count (disj #{1 2 3} 2)))    ; 2
(println (contains? (disj #{1 2 3} 2) 2)) ; false
(println (count (disj #{1 2} 9)))      ; 2
(println (disj nil 1))                 ; nil
(println (map disj [#{1} #{2}] [1 2])) ; (#{} #{})
```
