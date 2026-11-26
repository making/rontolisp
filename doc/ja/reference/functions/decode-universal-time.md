# decode-universal-time

`(decode-universal-time universal-time &optional time-zone)`

ユニバーサルタイムを 9 個の値 `second`、`minute`、`hour`、`date`、`month`、`year`、`day-of-week` (0 = 月曜)、`daylight-p`、`time-zone` に分解します。`time-zone` を省略すると、夏時間を含むローカルタイムゾーンで分解します。このとき `time-zone` はそのゾーンの標準時の GMT から西へのオフセット (時間単位。インドの `-11/2` のように、ゾーンによっては比) で、`daylight-p` は夏時間の 1 時間が加わっている間だけ真です。SBCL と同じ答え方です。`time-zone` を渡すとそのゾーンで分解し、`daylight-p` は nil です。各バックエンドがローカルゾーンをどこから得るかは [時計と乱数のガイド](../../guides/clock-and-random.md#the-local-time-zone) にあります。

```lisp
(multiple-value-list (decode-universal-time 2208988800 0)) ; => (0 0 0 1 1 1970 3 NIL 0)
```
