# uiop:file-exists-p

`(uiop:file-exists-p pathname)`

ファイルが存在するかどうかを答えます。存在すればそのパス名を、存在しなければ `nil` を
返します。ASDF/UIOP 側の名前で呼ばれる [`probe-file`](probe-file.md) そのもの
（契約も挙動も同じ）であり、すべてのバックエンドでその基本操作へ落とされるため、
UIOP 流の綴りを使うライブラリ（たとえば postmodern の `execute-file`）にシムは
不要です。

rontolisp ではパス名は名前文字列そのものなので、成功時に返る「真の名前」は引数の文字列
そのものです。シンボリックリンクの解決も絶対パス化も、どのバックエンドでも行いません。
ディレクトリも存在すると見なされます。

```lisp
(uiop:file-exists-p "definitely-missing.txt")   ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。インタプリタは `probe-file` に委譲する
グローバル関数を登録し、コンパイルパスは 1 引数の呼び出しを `probe-file` の呼び出しへ
書き換えます。
