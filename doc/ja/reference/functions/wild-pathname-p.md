# wild-pathname-p

`(wild-pathname-p pathname &optional field-key)`

パス名がワイルドカードを含むかどうかを返します。`field-key` を省略 (または `nil`) すると
**いずれか**の構成要素がワイルドであれば真を返し、`:directory` / `:name` / `:type` を
渡すとその構成要素だけを調べます。`:host` / `:device` / `:version` はその構成要素自体が
存在しないため常に `nil` です ([`pathname-host`](pathname-host.md))。

構成要素がワイルドとは、`*` (任意の長さの並び) か `?` (1 文字) を含むことです。
[`directory`](directory.md) が照合に使うワイルドカードとまったく同じなので、この述語と
照合器が食い違うことはありません。

```lisp
(list (wild-pathname-p "d/*.txt")
      (wild-pathname-p "d/a.txt")
      (wild-pathname-p "d/*.txt" :name)
      (wild-pathname-p "d/*.txt" :type)
      (wild-pathname-p "*/a.txt" :directory))   ; => (T NIL T NIL T)
```

未知のフィールドキーはエラーになります。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
