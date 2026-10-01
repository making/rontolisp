# declare

`(declare name...)`

各名前を前方宣言します。pass one の事前走査が `declare` の名前もトップレベルの `def`/`defn` の名前とともに集めるため、上にある定義は下で初めて定義される関数を呼べます。実際の定義は宣言に勝り、宣言されただけで定義されない名前は direct-call のエラーのまま残ります。フォーム自身は `nil` を返します。

```clojure
(declare dcl-f)
(defn dcl-g [] (dcl-f 1))
(defn dcl-f [x] (* 2 x))
(println (dcl-g)) ; 2
```
