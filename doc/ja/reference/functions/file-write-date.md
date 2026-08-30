# file-write-date

`(file-write-date pathname)`

ファイルの最終更新時刻を[ユニバーサルタイム](get-universal-time.md) (1900-01-01 GMTからの秒数) で返します。判定できない場合は `nil` を返し、存在しないファイルや読めないファイルはこれに当たります。[`probe-file`](probe-file.md) と同様にシグナルを発生させないので、プローブとして使えます。パスの解釈は `open` と同じです。

**2つのWASMバックエンドは常に `nil` を返します**。そこではタイムスタンプを取り出していないためです。すでに手元にあるディスクリプタをstatする [`file-length`](file-length.md) と違い、こちらは**パス**を指定するので、専用のopen/stat/closeが必要になります。`nil` はまさにCommon Lispが定める「時刻を判定できない」場合の答えなので、プログラムが失敗するのではなく移植性のある呼び出し側の時刻不明フォールバックが動きます。インタプリタとJVMは実際の値を返します。

```console
(let ((stamp (file-write-date "config.lisp")))
  (if stamp
      (print (decode-universal-time stamp))
      (print "unknown")))
```
