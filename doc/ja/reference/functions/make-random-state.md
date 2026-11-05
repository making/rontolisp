# make-random-state

`(make-random-state &optional state)`

新しい random-state を返します。`state` は `nil`、`t`、random-state のいずれかで、それ以外は `type-error` をシグナルします。ここでの random-state は生成器を持ちません。[`random`](random.md) はこれを受け取ってバックエンド自身の生成器から乱数を取り出すため、`(make-random-state t)` を変数に保存して `random` に渡し直す一般的なイディオムは動きます(uuid の `*uuid-random-state*` が動機となった利用例です)。一方、コピーが元の state と同じ列を返すことはありません。

```lisp
(random-state-p (make-random-state t)) ; => T
```
