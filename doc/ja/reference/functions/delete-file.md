# delete-file

`(delete-file pathname)`

指定したファイルを削除して `t` を返します。ファイルが残ってしまう場合はすべてエラーで、
「そもそも存在しなかった」場合も含みます (Common Lisp もこれを `file-error` とします)。
ファイルがなくても許容したい場合は [`probe-file`](probe-file.md) で先に確認するか、
呼び出しを `ignore-errors` で包んでください。

**2つのWASMバックエンドは呼び出し時にシグナルを発生させます。** そこでは WASI の
unlink 呼び出しをインポートしておらず、[`file-write-date`](file-write-date.md) と違って
この操作の契約には「判定できない」という答えがありません。実行後にファイルが消えている
かいないかのどちらかなので、エラー以外を返すのは嘘になります。これは
[`ensure-directories-exist`](ensure-directories-exist.md) と同じ取り決めで、理由も
同じです。呼び出しを**含む**だけのプログラムはそこでもコンパイルでき、実行したときに
だけシグナルを発生させます。

```console
(with-open-file (out "notes.txt" :direction :output)
  (write-line "draft" out))
(delete-file "notes.txt")   ; => T
(probe-file "notes.txt")    ; => NIL
(delete-file "notes.txt")   ; signals: DELETE-FILE: cannot delete notes.txt
```
