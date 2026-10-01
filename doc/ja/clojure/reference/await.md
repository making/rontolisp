# await

`(await a...)`

agent への送信を待ち合わせ、`nil` を返します。送信は送信時に済んでいるため、各引数が agent セルである検査と待ち合わせだけが行われます。

```clojure
(def a (agent 0))
(send a inc)
(println (await a)) ; nil
(println @a) ; 1
```
