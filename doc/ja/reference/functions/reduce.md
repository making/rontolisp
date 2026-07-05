# reduce

`(reduce function sequence &key initial-value from-end key)`

`sequence` の要素を 2 引数の `function` で左結合的に畳み込みます。`(reduce #'f '(a b c))` は `(f (f a b) c)` を計算します。シーケンスにはリストまたは文字列を渡せます。文字列の場合は文字が畳み込まれます。`:initial-value` を指定すると、その値が初期値として明示的に与えられ最初に畳み込まれます (`(f (f (f init a) b) c)`)。指定しない場合はシーケンスの先頭要素が初期値になります。`:from-end t` を指定すると要素は右結合的に畳み込まれ、アキュムレータが右側になります。`(reduce #'f '(a b c) :from-end t)` は `(f a (f b c))` を、`:initial-value i` を伴う場合は `(f a (f b (f c i)))` を計算します。`:key` は各要素 (初期値は除く) を畳み込む前に適用する 1 引数関数を指定します。キーワードは任意の順序で記述でき、`:initial-value`/`:from-end`/`:key` はコンパイル時に読み取られます。空のシーケンスの場合は初期値を返すか、初期値が与えられていなければ引数なしで `function` を呼び出します。

```lisp
(reduce #'+ '(1 2 3) :initial-value 0) ; => 6
```

```lisp
(reduce (lambda (acc c) (if (char= c #\a) (+ acc 1) acc)) "banana" :initial-value 0) ; => 3
```

```lisp
(reduce #'cons '((1) (2) (3)) :from-end t :key #'car :initial-value nil) ; => (1 2 3)
```
