# cond

`(cond test expr... test expr...)`

テスト/式のペアを順に取り、最初に真となったテストの式を評価して返します。どのペアも取られなければ `nil` です。`:else` は真なので、総称の受け皿として働きます。奇数個の末尾アーム -- 式を伴わない単独のテスト -- はデフォルトと見なされ、テストの値をそのまま返します。Clojure はこの形の奇数を拒否します。

仕様との差異: 奇数個の末尾アームはデフォルトとして受け付けられます。Clojure は偶数個のフォームを要求します。

```clojure
(println (cond (= 1 2) :one (= 1 1) :two :else :other)) ; two
(println (cond (= 1 2) :one :fallback))                 ; fallback
```
