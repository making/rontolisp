# get-decoded-time

`(get-decoded-time)`

Returns the current time as the nine values of [`decode-universal-time`](decode-universal-time.md) in the local time zone: `second`, `minute`, `hour`, `date`, `month`, `year`, `day-of-week` (0 = Monday), `daylight-p` and `time-zone`. It is `(decode-universal-time (get-universal-time))`; where each backend finds the local zone is in [the clock and randomness guide](../../guides/clock-and-random.md#the-local-time-zone).

```lisp
(length (multiple-value-list (get-decoded-time))) ; => 9
```
