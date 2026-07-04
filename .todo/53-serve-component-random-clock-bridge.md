# Bridge random/clock (WASI) into serve components, then ship the Magic 8 Ball example

## Problem

A serve component (`rontolisp:http-handler` + `--component`) compiles the core
in the same way as `--no-wasi`: the eight WASI Preview-1-shaped imports
(`fd_write`, `fd_read`, `path_open`, `fd_close`, `random_get`,
`clock_time_get`, `environ_sizes_get`, `environ_get`) are not imported and
`unreachable` trap stubs are defined at function indices 0-7 instead
(`WasmLispCompiler.java` around lines 1488-1496, the `this.noWasi || this.serve`
branches). So inside a served handler, `random`, the time built-ins,
`getenv`, file streams and even `print` all trap at the first call — wasmtime
answers `500 Internal Server Error` (`wasm trap: wasm 'unreachable' instruction
executed`, then `guest never invoked 'response-outparam::set'`).

Verified 2026-07-04: `(random 100)` works under `wasmtime run` in both Preview 1
and `--component` mode, but a handler calling `(random 100)` 500s on every
request under `wasmtime serve`.

This is a rontolisp v1 limitation, NOT a host limitation: the `wasi:http`
proxy world that `wasmtime serve` provides DOES include `wasi:random/random`,
`wasi:clocks/{monotonic-clock,wall-clock}` and `wasi:cli` stdout/stderr.

## Plan

Extend `adapter-serve.wat` (and `WasmServeComponentBuilder.buildServe`) to
implement at least `random_get` and `clock_time_get` over `wasi:random` /
`wasi:clocks` instead of leaving them as trap stubs — the same bridging that
`adapter.wat` already does for `wasmtime run` components. Optionally also
bridge `fd_write` for fd 1/2 to `wasi:cli` stdout/stderr so `print`-style
debugging works in a served handler. `environ_get` and `path_open` stay
unavailable (not part of the proxy world).

## Goal: restore examples/magic-8-ball.lisp with true randomness

The Magic 8 Ball example (a reproduction of the classic Spin tutorial JSON
API) was written and fully verified on 2026-07-04 — interpreter, JVM class,
`wasmtime serve -W gc=y` and `jco serve` (jco 1.24.6; note jco binds
`localhost` as IPv6, so curl `localhost`, not `127.0.0.1`) all returned
byte-identical responses — but rolled back because serve components have no
entropy, which forced a hash-of-the-question workaround instead of the
tutorial's random draw. Once this bridge lands, restore it with
`(random (length *answers*))` (keep the question/body parsing as is) and
re-add its `examples/README.md` row.

Last verified source (hash-based; replace `consult` with a `random` draw when
restoring):

```lisp
;; The Magic 8 Ball -- a rontolisp reproduction of the classic Spin tutorial
;; (https://spinframework.dev/ "Building a Magic 8 Ball JSON API"), on
;; rontolisp:http-handler. Ask it a yes/no question and it answers with one
;; of the twenty canonical Magic 8 Ball replies as JSON:
;;
;;   GET  /?question=Will+it+work    -> {"question": "Will+it+work", "answer": "..."}
;;   POST /  with body               -> the body is the question -- either raw
;;                                      text, or JSON {"question": "..."}
;;   (also served on /magic-8, the tutorial's path; other paths 404,
;;    a missing question 400)
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/magic-8-ball.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/magic-8-ball.lisp -o Magic8Ball.class && java -cp $JAR:. Magic8Ball
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/magic-8-ball.lisp -o magic-8-ball.wasm --component && \
;;     wasmtime serve -W gc=y magic-8-ball.wasm
;; Or under jco (Node.js) / wasmCloud (gc proposal on) -- see http-handler.lisp.
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/?question=Will+rontolisp+run+everywhere'
;;   curl -X POST -d '{"question": "Should I deploy on Friday?"}' http://127.0.0.1:8080/magic-8

;; The twenty canonical answers: ten affirmative, five non-committal,
;; five negative.
(defvar *answers*
  '("It is certain." "It is decidedly so." "Without a doubt."
    "Yes definitely." "You may rely on it." "As I see it, yes."
    "Most likely." "Outlook good." "Yes." "Signs point to yes."
    "Reply hazy, try again." "Ask again later." "Better not tell you now."
    "Cannot predict now." "Concentrate and ask again."
    "Don't count on it." "My reply is no." "My sources say no."
    "Outlook not so good." "Very doubtful."))

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; --- pulling the question out of the request --------------------------------

(defun path-only (path)
  (let ((q (position #\? path)))
    (if q (subseq path 0 q) path)))

(defun query-of (path)
  (let ((q (position #\? path)))
    (if q (subseq path (+ q 1)) "")))

;; The value of name in an "a=1&b=2" query string, or nil.
(defun query-param (query name)
  (if (string= query "")
      nil
      (let* ((amp (position #\& query))
             (pair (if amp (subseq query 0 amp) query))
             (eq-pos (position #\= pair)))
        (cond ((and eq-pos (string= (subseq pair 0 eq-pos) name))
               (subseq pair (+ eq-pos 1)))
              (amp (query-param (subseq query (+ amp 1)) name))
              (t nil)))))

;; ?question=... first; otherwise a JSON body's "question" field; otherwise
;; a non-empty raw body is the question itself.
(defun question-of (request)
  (let ((q (query-param (query-of (getf request :path)) "question"))
        (body (getf request :body)))
    (cond ((and q (> (length q) 0)) q)
          ((and (stringp body) (> (length body) 0) (eql (char body 0) #\{))
           (getf (rontolisp:json-parse body) :question))
          ((and (stringp body) (> (length body) 0)) body)
          (t nil))))

;; --- consulting the ball -----------------------------------------------------

;; A 31x rolling hash. The modulus keeps every intermediate value inside the
;; WASM i31 fixnum range (1000003 * 31 + 255 < 2^30).
(defun ball-hash (s)
  (let ((h 0))
    (dotimes (i (length s) h)
      (setq h (mod (+ (* h 31) (char-code (char s i))) 1000003)))))

(defun consult (question)
  (nth (mod (ball-hash (string-downcase question)) (length *answers*))
       *answers*))

(defun handle (request)
  (let ((path (path-only (getf request :path))))
    (if (or (string= path "/") (string= path "/magic-8"))
        (let ((question (question-of request)))
          (if question
              (json-response 200 (list :question question
                                       :answer (consult question)))
              (json-response 400
                             (list :error "ask the ball a question"
                                   :usage "GET /?question=... or POST a question body"))))
        (json-response 404 (list :error "not found" :path path)))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
```
