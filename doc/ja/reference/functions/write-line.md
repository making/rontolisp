# write-line

`(write-line string &optional stream &key start end)`

指定された文字列に続けて改行を書き込み、その文字列を返します。stream 引数がない場合は標準出力に書き込みます。出力ストリームを指定すると、そちらに書き込みます。`open` や `with-open-file` で開いたファイルストリーム、ソケット、`with-output-to-string` の文字列ストリームが使えます。3 つすべてのバックエンドで動作します。`print`/`prin1` とは異なり、文字列の生の内容を周囲のクォートなしで書き込みます。`:start`/`:end` キーワードは書き込む部分文字列を制限します（`nil` の `:end` は文字列の末尾を意味します）。戻り値は依然として文字列全体です。rontolisp の Gray 出力ストリーム基底クラスを継承した CLOS インスタンスも stream として使えます。その場合、境界は `rontolisp:stream-write-string` 独自の `start`/`end` に届きます。

```console
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out)
  (write-line "world" out))
```

これは `greeting.txt` に `hello` と `world` の 2 行を書き込みます。各呼び出しは末尾に自身の改行を付加し、書き込んだ文字列を返します。

```lisp
(string= (with-output-to-string (s)
           (write-line "hello" s :start 1 :end 3))
         (format nil "el~%")) ; => T
```

`write-line` の戻り値は依然として文字列全体 `"hello"` です。制限されるのは書き込まれるバイトだけです。
