# f16. A component's fetch of a URL with no authority fails inside `subseq`

Difficulty: Low

`%fetch-run`'s request builder (`http.lisp`) takes the authority from
`(subseq url (+ colon 3))`, assuming `://` after the scheme. A URL whose scheme is followed by
fewer than three characters signals the `subseq` bounds error instead of the transport's own
refusal. Measured 2026-10-10 (wasmtime 49, `-S http=y`):

```lisp
(dolist (u '("http:x" "http:/x" "http:///x"))
  (print (handler-case (rontolisp:await (rontolisp:fetch u))
           (error (c) (princ-to-string c)))))
;; --component  "SUBSEQ: invalid bounds 7, 6 for string of length 6"
;;              "fetch: not a URL the host can request: http:/x"
;;              "fetch: not a URL the host can request: http:///x"
;; interpreter  "HTTP request failed: unsupported URI http:x" (and the same for the other two)
```

`http:/x` reaches the host's refusal only because its `subseq` happens to stay in bounds.
clojure.java.io's read of such a URL (e84) passes this text on as its `IOException`.

## Plan

1. A `fetch-spec.yaml` case fetching `http:x` that prints whether the condition's message names
   the URL: every other transport's refusal does, the component's `subseq` error does not, so
   that leg fails first.
2. Check for `//` after the colon and signal the `rontolisp:wit-error` the other malformed URLs
   get (`fetch: not a URL the host can request: ...`) before any resource is made.
