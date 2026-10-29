# load-file

`(load-file path)`

リテラルの `path` のファイルをその呼び出しの場所で読み下す。[load-string](load-string.md) と同じ。`load` と同じ探し方で、最後の形の値を返す。求まる形のパスは、インタプリタでは呼び出しの実行時に読み下される（インタプリタはプログラムの lower を実行中も保持する）。コンパイル済みプログラムは lower の仕組みを持たないため、実行時に拒否される。値としては 1 引数を取る。

```console
clojure> (load-file "helper.clj")
41
```
