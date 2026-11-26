# get-output-string

`(get-output-string port)`

`open-output-string` で作ったポート `port` にそれまで書かれたすべてを文字列で返します。ポートは中身を保つので、もう一度呼ぶとそれ以降に書かれたものを加えて再び返します。

```scheme
(let ((p (open-output-string))) (display "ab" p) (get-output-string p)) ; => "ab"
```
