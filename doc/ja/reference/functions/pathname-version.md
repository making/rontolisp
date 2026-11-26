# pathname-version

`(pathname-version pathname)`

パス名のバージョン部分を返します。ここでは常に `nil` です。理由は
[`pathname-host`](pathname-host.md) と同じで、rontolisp にファイルのバージョンは
存在せず、名前文字列がそれを持つことはないためです。

```lisp
(pathname-version #P"d/a.txt")   ; => NIL
```

差異: SBCL は名前文字列から解析したパス名には `:newest` を、
[`make-pathname`](make-pathname.md) で作ったパス名には `nil` を返します。ここでは
どのパス名についても真である答え、つまり「構成要素が存在しない」を意味する `nil` に
統一しています。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
