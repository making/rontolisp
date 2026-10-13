# load-file

`(load-file path)`

リテラルの `path` のファイルをその呼び出しの場所で読み下す。[load-string](load-string.md) と同じ。`load` と同じ探し方で、最後の形の値を返す。求まる形でないものは実行時に拒否される（実行時にはコンパイラは走らない）。値としては 1 引数を取る（拒否される）。

```console
clojure> (load-file "helper.clj")
41
```
