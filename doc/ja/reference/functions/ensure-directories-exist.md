# ensure-directories-exist

`(ensure-directories-exist pathspec)`

`pathspec` のディレクトリ部分を、欠けている親も含めて作成し、`pathspec` を返します。ディレクトリ部分は最後の `/` までを含む部分なので、`"logs/app.log"` は `logs/` を作成してファイル自体には触れません。すでに `/` で終わる名前文字列はそれ自体がディレクトリです。`/` を含まない名前文字列は作業ディレクトリ内のファイルを指すので何も作成しません。既存のディレクトリはエラーになりません。

lite 版: Common Lisp は `(values pathspec created)` を返しますが、こちらは pathspec のみを返します。コンパイル済みバックエンドでは第2の値が関数境界を越えられないため、返すと約束すると誤解を招くからです。

**2つのWASMバックエンドは呼び出し時にシグナルを発生させます。** そこではWASIのディレクトリ作成呼び出しをインポートしておらず、[`file-write-date`](file-write-date.md) と違ってこの操作の契約には「判定できない」という答えがありません。実行後にディレクトリが存在するかしないかのどちらかなので、エラー以外を返すのは嘘になります。

```console
(ensure-directories-exist "logs/2026/app.log")
(with-open-file (out "logs/2026/app.log" :direction :output)
  (write-line "started" out))
```
