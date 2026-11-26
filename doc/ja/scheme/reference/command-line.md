# command-line

`(command-line)`

プログラムのコマンドラインを文字列のリストで返します。先頭がプログラム名で、その後に引数が続きます。インタープリタで実行するプログラムの引数は `--` の後ろに書き、プログラム名はファイル名です。コンパイルしたプログラムには、どちらもホストが渡します。コマンドラインのない実行（REPL、組み込みでの実行）では `()` を返します。

```console
$ cat args.scm
(import (scheme base) (scheme write) (scheme process-context))
(write (command-line))
$ rontolisp args.scm -- alpha "two words"
("args.scm" "alpha" "two words")
$ rontolisp args.scm -o args.wasm && wasmtime run args.wasm alpha "two words"
("args.wasm" "alpha" "two words")
```

```scheme
(write (list? (command-line)))
```

```
#t
```
