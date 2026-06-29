# terpri

`(terpri)`

標準出力に無条件で改行を 1 つ書き込み、nil を返します。名前は "terminate print" の略です。`princ` や `prin1` で組み立てた行を終わらせるために使用します。

```lisp
(princ "a")
(terpri)
(princ "b")
```

```
a
b
```
