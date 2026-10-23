# get-decoded-time

`(get-decoded-time)`

現在時刻を、ローカルタイムゾーンでの [`decode-universal-time`](decode-universal-time.md) の 9 個の値 `second`、`minute`、`hour`、`date`、`month`、`year`、`day-of-week` (0 = 月曜)、`daylight-p`、`time-zone` として返します。`(decode-universal-time (get-universal-time))` と同じです。各バックエンドがローカルゾーンをどこから得るかは [時計と乱数のガイド](../../guides/clock-and-random.md#the-local-time-zone) にあります。

```lisp
(length (multiple-value-list (get-decoded-time))) ; => 9
```
