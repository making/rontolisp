# torch:shuffled-batches

`(torch:shuffled-batches data batch-size &key shuffle drop-last)`

`data` をミニバッチに切り分け、リストのリストとして返します。各バッチは `batch-size` 要素で、最後のものだけ短くなることがあります (`:drop-last t` を指定すると `DataLoader` の `drop_last` のように捨てられます)。`data` は例の**リスト**、または非負の**整数** `n` (インデックスリスト `0..n-1` を表します) です。後者は複数の並行した配列を同時にバッチ化するための書き方で、呼び出し側が各配列から同じ行を選びます。

順序はシード付きの [`linalg:seed`](linalg-seed.md) 生成器から得られるので、エポックはどのバックエンドでも再現します。`:shuffle nil` では `data` の順序をそのまま保つため、評価パスにも同じ関数が使えます。

```lisp
(linalg:seed 1)
(torch:shuffled-batches 7 3)                         ; => ((6 0 5) (1 4 3) (2))
(torch:shuffled-batches '(a b c d e) 2 :shuffle nil) ; => ((A B) (C D) (E))
(torch:shuffled-batches '(a b c d e) 2 :shuffle nil :drop-last t) ; => ((A B) (C D))
```
