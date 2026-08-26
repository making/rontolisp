# random

`(random limit &optional random-state)`

半開区間 `[0, limit)` の乱数を返します。結果の型は limit に従い、整数の limit は整数を、浮動小数点数の limit は浮動小数点数を返します（そのため `(random 1)` は常に `0` です）。どのバックエンドも、draw のたびにホストを呼ぶのではなく、プログラム内部の疑似乱数生成器から draw します — インタプリタと JVM は `ThreadLocalRandom`、WASM は組み込みの生成器です。draw が数ナノ秒で済むのはこのためです。ホストがある場合、その生成器は実行ごとに一度だけホストのエントロピー(Preview 1 では WASI `random_get`、`--component` では `wasi:random`)でシードされるため、列は実行のたびに異なります。`--no-wasi` モジュールには尋ねるホストがないので、シードを与えない限り同じ列を繰り返します — [時計と乱数のガイド](../../guides/clock-and-random.md)を参照してください。予測不可能なバイト列が必要な場合はエントロピー API である [`rontolisp:random-bytes`](rontolisp-random-bytes.md) を使ってください。省略可能な random-state 引数は受理された上で無視されます(副作用のために評価はされます)。random-state オブジェクトは存在せず — [`make-random-state`](make-random-state.md) は `nil` を返します。

```lisp
(random 1) ; => 0
```
