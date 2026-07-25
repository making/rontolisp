# decode-universal-time

`(decode-universal-time universal-time &optional time-zone)`

ユニバーサルタイムを 9 個の値 `second`、`minute`、`hour`、`date`、`month`、`year`、`day-of-week` (0 = 月曜)、`daylight-p`、`time-zone` に分解します。[`encode-universal-time`](encode-universal-time.md) と同様、ゾーン省略時は GMT を意味します。`daylight-p` は常に nil です (夏時間の規則を知るバックエンドはありません)。

```lisp
(multiple-value-list (decode-universal-time 2208988800 0)) ; => (0 0 0 1 1 1970 3 NIL 0)
```
