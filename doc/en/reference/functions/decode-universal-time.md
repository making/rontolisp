# decode-universal-time

`(decode-universal-time universal-time &optional time-zone)`

Splits a universal time into the nine decoded values `second`, `minute`, `hour`, `date`, `month`, `year`, `day-of-week` (0 = Monday), `daylight-p` and `time-zone`. Without `time-zone` the time is decoded in the local time zone, daylight saving time included: `time-zone` is then the zone's standard offset west of GMT in hours (a ratio where the zone has one, `-11/2` in India) and `daylight-p` is true while daylight saving time adds its hour, as in SBCL. With `time-zone` the time is decoded in that zone and `daylight-p` is nil. Where each backend finds the local zone: [the clock and randomness guide](../../guides/clock-and-random.md#the-local-time-zone).

```lisp
(multiple-value-list (decode-universal-time 2208988800 0)) ; => (0 0 0 1 1 1970 3 NIL 0)
```
