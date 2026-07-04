# reduce

`(reduce function sequence &key initial-value)`

`sequence` の要素を 2 引数の `function` で左結合的に畳み込みます。`(reduce #'f '(a b c))` は `(f (f a b) c)` を計算します。シーケンスにはリストまたは文字列を渡せます。文字列の場合は文字が畳み込まれます。`:initial-value` を指定すると、その値が初期値として明示的に与えられ最初に畳み込まれます (`(f (f (f init a) b) c)`)。指定しない場合はシーケンスの先頭要素が初期値になります。`:initial-value` キーワードはリテラルで記述する必要があります (コンパイラはコンパイル時にこれを読み取ります)。空のシーケンスの場合は初期値を返すか、初期値が与えられていなければ引数なしで `function` を呼び出します。

```lisp
(reduce #'+ '(1 2 3) :initial-value 0) ; => 6
```

```lisp
(reduce (lambda (acc c) (if (char= c #\a) (+ acc 1) acc)) "banana" :initial-value 0) ; => 3
```
