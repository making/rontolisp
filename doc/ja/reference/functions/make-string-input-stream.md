# make-string-input-stream

`(make-string-input-stream string &optional start end)`

`string` から読み込む文字入力ストリームを返します。`read-char`、`read-line`、`peek-char`、`read` は他の入力ストリームと同様にこれを消費します。`with-input-from-string` が束縛するストリームを明示的に作る形で、ストリームが 1 つの式より長生きする必要がある場合 (保存する、ストリームを受け取る関数に渡す) に使います。`start` と `end` は読み込む範囲を文字単位で指定します。`string` が文字列でなければ期待型 `string` の `type-error` を、範囲が不正なら `subseq` と同じ `type-error` をシグナルします。

```lisp
(let ((s (make-string-input-stream "one
two")))
  (list (read-line s) (read-line s) (read-line s nil :eof))) ; => ("one" "two" :EOF)
```
