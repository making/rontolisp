# delete-file

`(delete-file pathname)`

指定したファイルを削除して `t` を返します。ファイルが残ってしまう場合はすべてエラーで、
「そもそも存在しなかった」場合も含みます (Common Lisp もこれを `file-error` とします)。
ファイルがなくても許容したい場合は [`probe-file`](probe-file.md) で先に確認するか、
呼び出しを `ignore-errors` で包んでください。

4つのバックエンドすべてで動作します。2つのWASMバックエンドは `path_unlink_file` という
WASIインポートを通じてunlinkし（Preview 1は直接、`--component` は `wasi:filesystem` の
`unlink-file-at` 経由）、存在しないファイルはどこでも同じ `file-error` になります。
空のディレクトリもすべてのバックエンドで削除します（WASMでは `path_remove_directory`、
`--component` は `remove-directory-at` 経由）。空でないディレクトリは残り、`file-error` を
通知します。

```console
(with-open-file (out "notes.txt" :direction :output)
  (write-line "draft" out))
(delete-file "notes.txt")   ; => T
(probe-file "notes.txt")    ; => NIL
(delete-file "notes.txt")   ; signals: DELETE-FILE: cannot delete notes.txt
```
