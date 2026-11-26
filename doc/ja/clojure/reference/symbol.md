# symbol

`(symbol x)` / `(symbol ns nm)`

1引数: シンボルはそのまま、キーワード・文字列は綴りのシンボル（接頭辞の裏で
mangle されるため、表示も比較も全体で行われます）で答えます。それ以外はシグナルします。
2引数: スラッシュで結合した綴りです（`nil` 名前空間は1引数形です）。値としては両形に
対する rest ディスパッチのラムダです。

```clojure
(println (symbol "a" "b")) ; a/b
(println (= (symbol "a") 'a)) ; true
```
