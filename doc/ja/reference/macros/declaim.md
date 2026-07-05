# declaim

`(declaim declaration...)`

ファイルレベルの宣言で、`declare` と同様にパースされるだけの no-op です。フォームは nil に評価され、宣言（`optimize`、`inline`、`type`、`special` など）は評価も検証もされません。`declaim` を使うライブラリのソースを変更なしにロードするための機能です。

```lisp
(declaim (optimize (speed 3) (safety 1))) ; => nil
```
