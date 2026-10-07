# get-output-stream-string

`(get-output-stream-string stream)`

`make-string-output-stream` のストリームにこれまで書き込まれた内容を返し、ストリームを **空にします**。次の呼び出しは、この呼び出しより後に書き込まれた内容だけを返します。これは Common Lisp の仕様どおりの動作で、1 つの蓄積用ストリームを複数のトークンで使い回せる理由でもあります。別の種類のストリーム（文字列入力ストリーム、ファイル、シノニムストリームなど）と、閉じられた文字列出力ストリーム（閉じると内容は解放されます）には期待型 `(and string-stream (satisfies output-stream-p))` の `type-error` を、ストリームでない値には期待型 `stream` の `type-error` をシグナルします。

```lisp
(let ((s (make-string-output-stream)))
  (write-string "ab" s)
  (let ((first (get-output-stream-string s)))
    (write-string "cd" s)
    (list first (get-output-stream-string s) (get-output-stream-string s)))) ; => ("ab" "cd" "")
```
