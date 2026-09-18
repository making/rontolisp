# quote

`(quote datum)` `'datum`

`datum` を評価せずにそのまま返します。`'datum` は `(quote datum)` のリーダ省略形です。`write` と REPL は quote された datum を長い形で表示します。`''x` は `'x` ではなく `(quote x)` と表示されます。

```scheme
(quote (a b c)) ; => (a b c)
'sym ; => sym
''x ; => (quote x)
```
