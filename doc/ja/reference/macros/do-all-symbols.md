# do-all-symbols

`(do-all-symbols (var [result]) body...)`

全登録パッケージから到達可能な重複のない各シンボルについて本体を1回ずつ評価し(`var`
に束縛)、その後 `result` を `var` が nil
に束縛された状態で評価してその値を返す(result
がなければ
nil)。各シンボルは1回だけ訪れ、コードが綴る形である -- 対象宇宙は
[`find-all-symbols`](../functions/find-all-symbols.md)
の探索と同一である。本体の `return`
は他の反復マクロ同様に全体を抜け、result を飛ばす。

```lisp
(let ((n 0))
  (do-all-symbols (s n) (when (eq s 'car) (return (incf n))))) ; => 1
```
