# f21. wasm: `cast failure` when a failed async request awaited one more future

Difficulty: High

Both wasm legs (`--native`'s Preview 1 module and `--component`) trap `wasm trap: cast failure`
where the interpreter and the JVM answer, when the Clojure client's response step awaits its
`:string` body through one more `(funcall (async-lambda ...))` layer and the request then fails.
Met 2026-10-10 while the client's `:string` body moved to the JDK decoder (f07); the shipped
code decodes after the octets' own await and does not trap.

The variant, in `clojure.lisp`'s `%clojure-http-respond` (in place of the `octets` binding and
`%clojure-http-body-as`):

```lisp
(defun rontolisp::%clojure-http-text (body)
  (funcall
   (rontolisp:async-lambda ()
     (let ((octets (rontolisp:await (rontolisp::%clojure-http-octets body))))
       (rontolisp::%octets-to-string-replacing octets 0 (length octets))))))
;; respond:
;;   (text (rontolisp:await (cond ((equal as stream) nil)
;;                                ((equal as bytes) (rontolisp::%clojure-http-octets body))
;;                                (t (rontolisp::%clojure-http-text body)))))
;;   ... "body" (cond (stream ...) (bytes (rontolisp::%clojure-bytes-of text)) (t text))
```

The program, against an origin answering `/status/404` with 404 and a body:

```clojure
(prn (try @(http/get (url "/status/404") {:async true})
          (catch clojure.lang.ExceptionInfo e :caught-ex-info)
          (catch java.util.concurrent.ExecutionException e
            [:execution (:status (ex-data (ex-cause e)))])))
```

Interpreter and JVM: `[:execution 404]`. `--native` and `--component`: the trap. Through the same
variant a 200 under `:async true` and a synchronous 404 answer normally. A Common Lisp program
nesting three `(funcall (async-lambda ...))` futures whose outer one signals after awaiting the
middle one does not trap, so the shape needs more of the Clojure path: the `ex-info` condition
signalled in the async body, `deref`'s `ExecutionException` re-signal (`%clojure-future-get`'s
`handler-case`), the Clojure `try`.

## Plan

Rewritten 2026-10-10: the premise did not hold. `clojure.lisp` already defines
`%clojure-http-text (c)`, the condition text `%clojure-future-get` puts in its
`ExecutionException`; the variant's second definition replaced it, so the deref fed the `ex-info`
condition to the body decoder, and `stream-read` of a condition trapped on both wasm legs where
the interpreter and the JVM signalled. The same variant under a fresh name answers on all four.
Numbers and the story: `.kb/async-await.md`, "A value that is no stream".

1. `stream-read` / `stream-close` / `stream-write` of a non-stream: the operator's `type-error`
   expecting `(SATISFIES RONTOLISP:STREAMP)` on all four backends (wasm-GC in EH mode), pinned by
   a shared fixture and a ci-spec case.
2. The JVM's await and thread join lost a built-in type-error's datum and type on the way to the
   awaiting thread (a `simple-error` there): carry the record in the error payload.
