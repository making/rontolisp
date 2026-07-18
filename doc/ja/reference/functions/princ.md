# princ

`(princ object &optional stream)`

`object` を人間が読みやすい形式で標準出力に書き出します。文字列を囲むクオートや文字の `#\` プレフィックスは付けず、末尾に改行も付けません。これは読み戻すためではなく表示するための形式です。シンボルは [`symbol-name`](symbol-name.md) として印字されます: キーワードの先頭の `:` と gensym の `#:` はパッケージマーカーであって名前の一部ではないため印字されません(`prin1`/`print` は保ちます)。オプションの stream 引数を指定すると、標準出力の代わりにそのストリームに出力されます。`object` を返します。

```lisp
(princ "hello")
(princ :ready)
```

```
helloready
```
