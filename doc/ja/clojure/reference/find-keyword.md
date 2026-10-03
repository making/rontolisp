# find-keyword

`(find-keyword x)` / `(find-keyword ns nm)`

`clojure.core/find-keyword`: キーワードはそのまま、シンボルはその綴り、文字列はそのままでキーワードを
返し、それ以外は `nil` です。2引数では `ns/nm` のキーワードを返します（`nil` 名前空間は落ち、
`nil` の名前や文字列でない部分はシグナルします）。キーワードはインターンされないため、
一度も使われていない綴りでも、オラクルが `nil` を返すところでそのキーワードを返します。
値としては1引数か2引数を取ります。

```clojure
(println (find-keyword "a"))       ; :a
(println (find-keyword "b" "c"))   ; :b/c
(println (find-keyword 1))         ; nil
```
