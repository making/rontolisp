# h51. `~E` ignores its scale factor `k`

Difficulty: Low

Found 2026-10-30 probing `st-json`, which writes every real with `~,,,0,,,'eE`
(`st-json.lisp:389`).

```lisp
(format nil "~,,,0,,,'eE|~,,,0E" 2.5 2.5d0)
;; SBCL 2.2.9: "0.25e+1|0.25d+1"   here: "2.5e+0|2.5e+0"
```

The scale factor is fixed at 1 (`.kb/format.md`; the `~e` notes of `.todo/001`). Implement
`k` per CLHS 22.3.3.2: k <= 0 puts -k zeros after the point, k > 0 puts k digits before it.
Both `format` renderings, all four backends. Add the failing case first (ci-spec). The
second difference in that example (`d+1` for a double) is the float-format model and is not
in scope.
