# uiop:collect-sub*directories

`(uiop:collect-sub*directories directory collectp recursep collector)`

ディレクトリツリーを走査します。到達した各ディレクトリは `collectp` に渡され、真を返した
場合に `collector` へ渡されます。各サブディレクトリは `recursep` に渡され、真を返した場合に
そこへ降りていきます。3 つの関数に渡されるディレクトリは、ルートも含めてすべてディレクトリ
形式のパス名（末尾に `/`）なので、どの階層でも形が揃います。戻り値は `nil` です。

`(constantly t)` を 2 つ渡すのが「すべて走査する」書き方です。

```console
$ cat walk.lisp
(uiop:collect-sub*directories "src/" (constantly t) (constantly t)
                              (lambda (dir) (print dir)))
$ rontolisp walk.lisp
"src/"
"src/main/"
"src/test/"
```

## バックエンドサポート

4 バックエンドすべてです。[`directory`](directory.md) と同じ 1 つのプリミティブの上に
定義されています。
