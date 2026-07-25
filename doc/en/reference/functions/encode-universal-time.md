# encode-universal-time

`(encode-universal-time second minute hour date month year &optional time-zone)`

Returns the universal time -- seconds since the Common Lisp epoch of 1900-01-01 00:00:00 GMT -- denoted by the decoded components. `time-zone` is the offset west of GMT in hours; **a missing (or nil) zone means GMT, not the machine's local zone**, because no backend-portable local-zone source exists (the WASM targets expose no timezone at all). The calendar arithmetic is exact for any year: it runs the era-based proleptic-Gregorian algorithm over integers, so every backend answers identically.

```lisp
(encode-universal-time 0 0 0 1 1 1970 0) ; => 2208988800
```

The inverse is [`decode-universal-time`](decode-universal-time.md), and [`get-universal-time`](get-universal-time.md) reads the clock in the same units.
