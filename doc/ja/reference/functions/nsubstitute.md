# nsubstitute

`(nsubstitute new old list)`

`substitute` の破壊的対応版です。`list` の car をその場で書き換え、`old` に `eql` な要素をすべて `new` に置き換えます。引数は位置指定のみです（`:test`/`:key` はありません）。リスト構造が再利用されるため、変更は元の変数を通して見えます。

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```
