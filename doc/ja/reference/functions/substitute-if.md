# substitute-if

`(substitute-if new predicate sequence &key key start end count from-end)`

`predicate` を満たすすべての要素を `new` に置き換えた新しいシーケンスを返します。その他の要素は変更されません。[`substitute`](substitute.md) の `eql` 比較を述語呼び出しに置き換えたものなので、`:test` は取りません (述語そのものが判定です)。省略可能な `:key` キーワードに渡したセレクタ関数は、述語が見る前に各要素へ適用されます (置き換える値は `new` そのものです)。シーケンスにはリスト・文字列・ベクタを渡せ、結果は同じ種類になります。元のシーケンスは変更されません。破壊的な操作にはリスト専用の [`nsubstitute-if`](nsubstitute-if.md) を使います。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。整数でも nil でもない `:count` も、境界の検査より前に同様に `type-error` を通知します。負の `:count` は 0 として働き、nil は無制限です。

```lisp
(substitute-if 0 #'oddp '(1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(substitute-if #\- (lambda (c) (member c '(#\. #\/) :test 'char=)) "lack/mw.backtrace") ; => "lack-mw-backtrace"
```

```lisp
(substitute-if 0 #'oddp '((1) (2) (3)) :key #'car) ; => (0 (2) 0)
```

```lisp
(substitute-if 0 #'oddp '(1 2 3) :count 1) ; => (0 2 3)
```
