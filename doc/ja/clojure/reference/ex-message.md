# ex-message

`(ex-message cond)`

コンディションのメッセージを返します。`ex-info` ならメッセージスロット、それ以外は Clojure 記法でのレンダリングです -- throw された文字列は自分自身を返します。関数値としても動きます。

```clojure
(println (ex-message (ex-info "boom" {}))) ; boom
(println (map ex-message [(ex-info "m" 1) "nope"])) ; (m nope)
```
