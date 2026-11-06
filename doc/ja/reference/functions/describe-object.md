# describe-object

`(describe-object object stream)`

[`describe`](describe.md) が呼ぶ総称関数です。システムメソッドはオブジェクトを `prin1` と同じ形で書き、次の行にその型を角括弧で囲んで書きます。これは SBCL が書く最初の 2 行です。説明の残りは処理系依存 (CLHS) で、書きません。クラスに対するメソッドも、すべてのオブジェクトに対する `:after` メソッドも、どのパッケージで定義してもこの 1 つの総称関数に加わります。

```lisp
(subseq (with-output-to-string (s) (describe-object "abc" s)) 0 5) ; => "\"abc\""
```
