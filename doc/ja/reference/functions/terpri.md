# terpri

`(terpri &optional stream)`

無条件で改行を 1 つ書き込み、nil を返します。引数なしの場合は標準出力に書き込みます。オプションの stream 引数（ファイルストリームまたは `with-output-to-string` の文字列ストリーム）を指定すると、そのストリームに書き込みます。名前は "terminate print" の略です。`princ` や `prin1` で組み立てた行を終わらせるために使用します。

```lisp
(princ "a")
(terpri)
(princ "b")
```

```
a
b
```
