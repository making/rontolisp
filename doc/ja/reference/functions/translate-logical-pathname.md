# translate-logical-pathname

`(translate-logical-pathname pathname &key)`

論理パス名を、そのホストの
[`logical-pathname-translations`](logical-pathname-translations.md) が指す物理パス名に
変換します。名前文字列の最初の `:` より前がそこで定義したホストであれば論理パス名です。
それ以外のパス名は物理パス名であり、Common Lisp の定めどおりそのまま返します。

論理部分は物理パス名の綴り (小文字化、`;` を `/` に、バージョンを除去) に直され、
各規則の from-wildcard も同様に直されます。
[`pathname-match-p`](pathname-match-p.md) が受け入れた最初の規則で
[`translate-pathname`](translate-pathname.md) により変換します。どの規則にも一致しなければ
`file-error` を送出します。

```lisp
(setf (logical-pathname-translations "APP")
      '(("SRC;**;*.*.*" "/opt/app/src/**/*.*")))
(list (namestring (translate-logical-pathname "APP:SRC;A;FOO.LISP"))
      (namestring (translate-logical-pathname "d/a.txt")))
; => ("/opt/app/src/a/foo.lisp" "d/a.txt")
```

ライト版: 論理パス名は独立した値ではないため、`merge-pathnames` はその名前文字列を他と
同じに扱います (相対のものは defaults のディレクトリの下にマージされます)。また相対の
to-wildcard は相対のまま残ります (SBCL は絶対パスを返します)。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
