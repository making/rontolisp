# subseq

`(subseq sequence start &optional end)`

`sequence` (文字列、リストまたはベクタ) の新しい部分シーケンスを返します。0 始まりのインデックスを用いて、インデックス `start` から `end` の手前まで (`end` を含まない) の半開区間を対象とします。`end` を省略した場合、部分シーケンスはシーケンスの末尾まで続きます。結果は入力と同じ種類 (文字列なら文字列、リストならリスト、ベクタなら同じ要素型のベクタ) になります。

範囲は `0 <= start <= end <= (length sequence)` を満たす必要があります。フィルポインタを持つベクタでは、長さはフィルポインタです。満たさない場合はどのバックエンドでも `SUBSEQ: invalid bounds START, END for KIND of length N` と報告する `type-error` になります (`KIND` は `string`、`list`、`vector` のいずれか)。その `type-error-datum` は範囲外の最初の境界 (`start` が `[0, N]` の外ならそれ、そうでなければ `[start, N]` の外にある `end`)、`type-error-expected-type` はその範囲です。`(subseq "abc" 2 1)` なら `1` と `(INTEGER 2 3)` です。

```lisp
(subseq "hello" 1 3) ; => "el"
```
