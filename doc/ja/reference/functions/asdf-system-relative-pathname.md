# asdf:system-relative-pathname

`(asdf:system-relative-pathname system relative)`

`system` という名前のシステムのソースディレクトリを基準に `relative` を解決した名前文字列 (namestring) を返します。`(asdf:system-source-directory system)` に相対パスをマージする操作を 1 回の呼び出しで行うものです。ライブラリが `.asd` の隣に同梱するデータファイルを指すときにこの形が使われます。`system` は文字列・キーワード・シンボルの指定子 ([`asdf:find-system`](asdf-load-system.md) の戻り値も可) で、登録されていないシステムはエラーです。

`relative` はどちらの綴りでも受け付けます — 名前文字列でも、`#P"data/list.dat"` が表すパス名でも構いません。返り値は名前文字列のままです (ここでは ASDF のロケータはコンパイル時の事実であり、パス名の生成側ではありません)。

コンパイルパス (JVM/WASM) ではこの呼び出しはビルド時にその名前文字列リテラルへ畳み込まれます。したがって結果を `with-open-file` で開く箇所は成果物にインライン化でき、コンパイル済みプログラムは実行時にシステムレジストリもファイルも必要としません。

```console
$ cat my-lib.asd
(defsystem :my-lib :components ((:file "main")))

$ cat main.lisp
(print (asdf:system-relative-pathname :my-lib "data/tlds.dat"))

$ rontolisp run.lisp --system-path .
"/home/me/my-lib/data/tlds.dat"
```
