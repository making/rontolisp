# re-matcher

`(re-matcher pattern s)`

`s` に対する `pattern` のマッチャーを返します。各 `re-find` が最後のマッチの先へ進み（空マッチは1文字進みます。オラクル通り）、`re-groups` が最後のマッチを読み戻し、`nth` がそのうちの1グループを読みます。パターンのみ -- 文字列はオラクル同様にシグナルします。関数値としても動きます。

マッチャーはすべてのバックエンドで `java.util.regex.Matcher` のメソッドに答えます。`.find`（引数なし、または開始位置。開始位置を渡すと先にリセットします）、`.lookingAt`（先頭でのマッチ）、`.matches`（入力全体にわたるマッチ）はマッチを記録し、次の `.find` はその後ろから探します。`.group`・`.start`・`.end`（マッチ全体、グループ番号またはグループ名）はそれを読み戻し、マッチに参加しなかったグループには `nil` と `-1` を返します。`.groupCount`・`.reset`・`.pattern` もあります。マッチがなければオラクルの `IllegalStateException`（`No match found`）、パターンのグループを超えると `IndexOutOfBoundsException`（`No group n`）、パターンにない名前には `IllegalArgumentException`（`No group with name <n>`）を投げます。`.hitEnd` とリージョン系のメソッドは名前で拒否します。

```clojure
(let [m (re-matcher #"\w+" "the quick brown fox")]
  (loop [match (re-find m)]
    (when match
      (println match)
      (recur (re-find m)))))
```
