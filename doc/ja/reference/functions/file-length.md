# file-length

`(file-length stream)`

**ファイル**ストリームが開いているファイルのバイト長を返します。判定できない場合は `nil` を返します。それ以外のストリームはすべて `nil` を返します。文字列ストリーム、ソケット、標準ストリーム、そしてすでにクローズされたハンドルが該当します。出力ストリームは先にフラッシュされるので、ディスクに届いた分ではなく書き込まれた分を数えます。

**4つのバックエンドすべてが実際の値を返します**。インタプリタとJVMはストリームを開いたパスをstatし、2つのWASMバックエンドはディスクリプタ自体をstatします（Preview 1は `fd_filestat_get`、コンポーネントは `wasi:filesystem` の `descriptor.stat` を通じて）。`nil` を返すのは本当に長さを持たないものだけで、その範囲は他の2つが `nil` を返すものと同じです。ホストが通常ファイルとして報告しないものも同様に `nil` です。

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => NIL
```

```console
(with-open-file (in "data.txt")
  (print (file-length in)))
```
