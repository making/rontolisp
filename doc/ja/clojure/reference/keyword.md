# keyword

`(keyword x)` / `(keyword ns nm)`

1引数: キーワードはそのまま、シンボルは demangle した綴り、文字列はそのまま
（`a/b` は分割されません）でキーワード化します。それ以外は `nil` です。2引数:
スラッシュで結合した綴りです（`nil` 名前空間は落ち、`nil` 名前はシグナルします）。
値としては両形に対する rest ディスパッチのラムダです。

```clojure
(println (keyword "a" "b")) ; :a/b
(println (keyword 'a)) ; :a
```
