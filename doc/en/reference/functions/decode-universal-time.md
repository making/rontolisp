# decode-universal-time

`(decode-universal-time universal-time &optional time-zone)`

Splits a universal time into the nine decoded values `second`, `minute`, `hour`, `date`, `month`, `year`, `day-of-week` (0 = Monday), `daylight-p` and `time-zone`. Like [`encode-universal-time`](encode-universal-time.md), a missing zone means GMT; `daylight-p` is always nil (no backend knows a daylight-saving rule).

```lisp
(multiple-value-list (decode-universal-time 2208988800 0)) ; => (0 0 0 1 1 1970 3 NIL 0)
```
