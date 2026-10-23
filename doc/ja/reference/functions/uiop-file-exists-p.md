# uiop:file-exists-p

`(uiop:file-exists-p pathname)`

ファイルが存在するかどうかを答えます。存在すればそのパス名を、存在しないか、パス名が
ディレクトリを指す場合は `nil` を返します。本家 UIOP の契約と同じなので、UIOP 流の
綴りを使うライブラリ（たとえば postmodern の `execute-file`）にシムは不要です。
ディレクトリ版は [`uiop:directory-exists-p`](uiop-directory-exists-p.md) です。

成功時に返るパス名は、[`probe-file`](probe-file.md) と同じく真名 (truename) です。
シンボリックリンクをすべて解決し、絶対パス化は行いません。指す先が存在しないリンクは、
`probe-file` と同じくリンク自身を返します。

```lisp
(uiop:file-exists-p "definitely-missing.txt")   ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。`probe-file` の上に書かれた rontolisp ソースに
よる 1 つの定義で、ディレクトリは真名の末尾の `/` で見分けます。
