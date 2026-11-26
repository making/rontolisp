# apropos-list

`(apropos-list string &optional package)`

名前が `string` を部分文字列として含む(大文字小文字を区別しない)到達可能シンボルを、`package`
-- 省略時は全登録パッケージ -- から集める。各結果は
[`find-all-symbols`](find-all-symbols.md)
と同様にコードが綴る形である。

```lisp
(apropos-list "CAR" :cl) ; => (CAR MAPCAR)
```
