# logical-pathname

`(logical-pathname pathspec)`

常にエラーを送出します。Common Lisp は、引数が論理パス名または論理パス名の名前文字列で
ない限りエラーを要求しますが、rontolisp は論理ホストを定義できないため、これを満たす
引数は存在しません。代わりに物理パス名を返してしまうと、変換表が存在するかのように
装うことになります。

```console
> (logical-pathname "SYS:SRC;")
LOGICAL-PATHNAME: "SYS:SRC;" does not name a logical pathname (rontolisp defines no logical hosts)
```

名前文字列が指す物理パス名を作るには [`pathname`](pathname.md) を、移植性のあるコードが
開く前に正規化する箇所には (ここでは恒等写像である)
[`translate-logical-pathname`](translate-logical-pathname.md) を使ってください。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
