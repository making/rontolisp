# pathname-host

`(pathname-host pathname)`

パス名のホスト部分を返します。ここでは常に `nil` です。rontolisp の名前文字列は
ホスト構文を持たないフラットな Unix 形式のパスなので、この構成要素は存在せず、
存在しない構成要素に対して Common Lisp が定める答えが `nil` です。

引数は [`namestring`](namestring.md) と同じ規則でパス名指示子として検証されるため、
指示子でない値は `nil` ではなくエラーになります。同じ理由で同じ答えを返す兄弟が
[`pathname-device`](pathname-device.md) と [`pathname-version`](pathname-version.md) です。

```lisp
(pathname-host "d/a.txt")   ; => NIL
```

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
