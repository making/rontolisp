# pathname-device

`(pathname-device pathname)`

パス名のデバイス部分を返します。ここでは常に `nil` です。理由は
[`pathname-host`](pathname-host.md) と同じで、フラットな名前文字列はデバイスを
持たないためです。これは Unix 上の SBCL の答えとも一致します。

```lisp
(pathname-device #P"d/a.txt")   ; => NIL
```

移植性のあるコードは、この値を使う前に `nil` と `:unspecific` の両方を検査します。
`nil` のデバイスは単に「考慮すべきデバイス構成要素がない」ことを意味します。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
