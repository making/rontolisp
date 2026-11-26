# for-each

`(for-each proc list1 list2 ...)`

`map` と同様に `proc` をリストの要素に先頭から順に適用しますが、結果は集めず副作用のために呼び出します。リストが複数あるときは最も短いリストで止まります。戻り値は未規定です。

```scheme
(for-each (lambda (x y) (display (+ x y)) (newline)) '(1 2) '(10 20))
```

```
11
22
```
