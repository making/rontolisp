# eof-object

`(eof-object)`

ファイル終端オブジェクトを返します。入力の終わりで読み取り手続きが返す値です。このオブジェクトは 1 つだけで、`#<eof>` と表示され、読み取り構文はありません。

```scheme
(write (eof-object))
(newline)
(write (eof-object? (eof-object)))
(newline)
```

```
#<eof>
#t
```
