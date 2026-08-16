# merge-pathnames

`(merge-pathnames pathname &optional defaults)`

`pathname` に欠けている部分を `defaults` から補い、マージしたパス名を返します。
どちらの引数もパス名と名前文字列の両方の綴りを受け付け、規則は名前文字列が持つ 2 つの
部分、すなわち*ディレクトリ*（最後の `/` までのすべて）と*ファイル*（その後ろ）に対して
働きます。`pathname` のディレクトリは、絶対パスならそれが優先され、相対パスなら
`defaults` のディレクトリの後ろに連結され、無ければ `defaults` のものが使われます。
`pathname` のファイルは、空でない限り優先されます。`defaults` を省略すると作業ディレクトリ
に対してマージされ、`pathname` はそのまま返ります。

ライブラリが、以前に計算したディレクトリからの相対でファイルを指名するのはこの方法です
（たとえば自分のソースの隣にあるデータファイル）。`uiop:merge-pathnames*` は同じマージの
ASDF/UIOP 側の綴りです。

```lisp
(merge-pathnames "zoneinfo/" "/opt/local-time/")   ; => #P"/opt/local-time/zoneinfo/"
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。rontolisp のソースによる 1 つの定義があり、
参照されたときにプログラムへ差し込まれます。
