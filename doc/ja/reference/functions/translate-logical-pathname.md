# translate-logical-pathname

`(translate-logical-pathname pathname &key)`

引数のパス名そのものを返します。rontolisp のパス名はすべて**物理**パス名であり、
論理ホストも `logical-pathname-translations` の変換表も存在しないため、変換は
恒等写像になります。これは物理パス名を渡されたときに Common Lisp が定める動作なので、
開く前にこの関数でパスを正規化する移植性のあるコードはそのまま動きます。

```lisp
(namestring (translate-logical-pathname "d/a.txt"))   ; => "d/a.txt"
```

この判断のもう半分が [`logical-pathname`](logical-pathname.md) です。ここでは論理パス名に
なりうる値が存在しないため、常にエラーを送出します。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
