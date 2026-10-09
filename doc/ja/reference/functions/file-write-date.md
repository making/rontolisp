# file-write-date

`(file-write-date pathname)`

ファイルの最終更新時刻を[ユニバーサルタイム](get-universal-time.md) (1900-01-01 GMTからの秒数) で返します。判定できない場合は `nil` を返し、存在しないファイルや読めないファイルはこれに当たります。[`probe-file`](probe-file.md) と同様にシグナルを発生させないので、プローブとして使えます。パスの解釈は `open` と同じです。

4つのバックエンドすべてで動作します。2つのWASMバックエンドは `path_filestat_get` というWASIインポートを通じてパスをstatし（Preview 1は直接、`--component` は `wasi:filesystem` の `stat-at` 経由）、他の2つと同じくシンボリックリンクをたどります。`--no-wasi` モジュールにはファイルがないため、そこでは `nil` を返します。

```console
(let ((stamp (file-write-date "config.lisp")))
  (if stamp
      (print (decode-universal-time stamp))
      (print "unknown")))
```
