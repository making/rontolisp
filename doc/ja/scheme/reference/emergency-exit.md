# emergency-exit

`(emergency-exit)` `(emergency-exit obj)`

囲んでいる `dynamic-wind` の `after` を実行せずに、呼び出した場所でプログラムを終了します。それまでに書き出した出力はフラッシュされます。終了ステータスは `exit` と同じ規則で決まります（なしまたは `#t` は 0、`#f` は 1、整数は下位 8 ビット）。

```scheme
(display "working")
(newline)
(dynamic-wind
  (lambda () #f)
  (lambda () (emergency-exit 4))
  (lambda () (display "cleanup") (newline)))
```

```
working
```
