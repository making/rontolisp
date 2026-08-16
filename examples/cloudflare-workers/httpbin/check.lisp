;;; Drive worker.lisp's handler without Cloudflare, on any backend:
;;; `handle-request` is an ordinary function of a string, adapter included.
;;;
;;; The requests below are the envelope src/index.js builds out of a real
;;; `Request`: the RAW target rather than a pre-split path and query object, and
;;; a content-length for anything with a body. Both fail quietly if you get them
;;; wrong -- so what each probe expects back is written down as rove assertions
;;; rather than as printed lines, and the file ends by making its verdict the
;;; process exit code. A handler that stops answering them fails the run instead
;;; of scrolling past.
;;;
;;; rove is loaded with asdf, so pass the directories holding its .asd files
;;; (rove, dissect and cl-ppcre, all vendored in this repository) with
;;; --system-path; outside this repository (ql:quickload "rove") fetches the
;;; same sources. See the Testing guide: doc/en/guides/testing.md
;;;
;;;   SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
;;;   rontolisp check.lisp --system-path $SP
;;;   rontolisp check.lisp --system-path $SP -o Check.class && java -cp . Check
;;;   rontolisp check.lisp --system-path $SP -o check.wasm --optimize && \
;;;     wasmtime run -W gc -W exceptions=y check.wasm

(asdf:load-system :rove)
(use-package :rove)
;; rove colors its report for a terminal; a checked pipeline wants plain text.
(setf *enable-colors* nil)

(load "worker.lisp")

(defun headers (&rest plist) (rontolisp:plist-hash-table plist))

(defun json-headers (body)
  (headers :host "example.com"
           :content-type "application/json"
           :content-length (princ-to-string (length body))))

;;; One exchange: build the envelope, call the handler, print both halves (this
;;; is still the local edit/run loop, and seeing the JSON is the point of it),
;;; and answer the PARSED reply -- so the assertions below read it as data
;;; instead of matching substrings, and nothing has to escape a quote.
(defun probe (request-plist)
  (let* ((request
          (rontolisp:json-stringify (rontolisp:plist-hash-table request-plist)))
         (response (handle-request request)))
    (format t "--> ~a~%" request)
    (format t "<-- ~a~%~%" response)
    (rontolisp:json-parse response)))

;;; The echo document, which travels as a JSON string inside the reply.
(defun reply-body (reply) (rontolisp:json-parse (gethash "body" reply)))

;;; --- the exchanges ----------------------------------------------------------
;;; Most requests are driven BEFORE the suite: arrange first, then assert, so
;;; the local edit/run loop's printed JSON stays in one block above the report.
;;; The unparseable-body probe is the exception and runs INSIDE its test --
;;; read-body's "json": null fallback is a handler-case, and a handler-case
;;; nested in rove's failure recorder (a handler-bind around each test body) is
;;; exactly the shape that has to keep working.

;; GET /get with a query string. The target arrives raw -- path and query still
;; joined -- and the environment's :query-string is what becomes "args".
(defparameter *query-reply*
  (probe
   (list :method "GET"
         :target "/get?a=1&b=two"
         :scheme "https"
         :remote-addr "203.0.113.7"
         :headers (headers :host "example.com" :accept "application/json")
         :body "")))

;; A percent-encoded path: %http-make-env decodes it, so :path-info -- and the
;; "path" the echo document reports -- is the decoded form.
(defparameter *encoded-path-reply*
  (probe
   (list :method "GET"
         :target "/%67et"
         :scheme "https"
         :remote-addr "203.0.113.7"
         :headers (headers :host "example.com")
         :body "")))

;; POST /post with a JSON body -- "data" is the raw text, "json" the parsed
;; value. The body reaches the application as clack's :raw-body, a synchronous
;; bivalent stream that read-body drains with read-char.
(defparameter *json-body-reply*
  (probe
   (list :method "POST"
         :target "/post"
         :scheme "https"
         :remote-addr "203.0.113.7"
         :headers (json-headers "{\"name\":\"rontolisp\"}")
         :body "{\"name\":\"rontolisp\"}")))

;; The wrong method for an endpoint -- 405.
(defparameter *wrong-method-reply*
  (probe
   (list :method "GET"
         :target "/post"
         :scheme "https"
         :remote-addr "203.0.113.7"
         :headers (headers :host "example.com")
         :body "")))

;; An unknown path -- 404.
(defparameter *unknown-path-reply*
  (probe
   (list :method "GET"
         :target "/nope"
         :scheme "https"
         :remote-addr "203.0.113.7"
         :headers (headers :host "example.com")
         :body "")))

;;; --- what each one must answer ----------------------------------------------

(deftest get-with-a-query-string
  (let ((body (reply-body *query-reply*)))
    (ok (= (gethash "status" *query-reply*) 200))
    (testing "the target arrives RAW, so %http-make-env owns the ? split"
      (ok (string= (gethash "path" body) "/get"))
      (ok (string= (gethash "a" (gethash "args" body)) "1"))
      (ok (string= (gethash "b" (gethash "args" body)) "two")))
    (testing "the request headers reach the application, lowercased"
      (ok (string= (gethash "host" (gethash "headers" body)) "example.com"))
      (ok (string= (gethash "method" body) "GET")))
    (testing "the response headers cross as an ARRAY of pairs, not an object"
      ;; An object would collapse a repeated name -- two cookies mean two
      ;; Set-Cookie headers.
      (let ((pair (aref (gethash "headers" *query-reply*) 0)))
        (ok (string= (aref pair 0) "content-type"))
        (ok (string= (aref pair 1) "application/json"))))))

(deftest a-percent-encoded-path
  (ok (string= (gethash "path" (reply-body *encoded-path-reply*)) "/get")))

(deftest post-with-a-json-body
  (let ((body (reply-body *json-body-reply*)))
    (ok (string= (gethash "data" body) "{\"name\":\"rontolisp\"}"))
    (ok (string= (gethash "name" (gethash "json" body)) "rontolisp"))))

;; POST /post with a body that does not parse -- "json" falls back to null,
;; which is `handler-case` doing its work, inside rove's own handler-bind.
(deftest post-with-a-body-that-does-not-parse
  (let* ((reply
          (probe
           (list :method "POST"
                 :target "/post"
                 :scheme "https"
                 :remote-addr "203.0.113.7"
                 :headers (json-headers "{not json")
                 :body "{not json")))
         (body (reply-body reply)))
    (ok (eq (gethash "json" body) 'null))
    (testing "the raw text still comes back untouched"
      (ok (string= (gethash "data" body) "{not json")))))

(deftest the-wrong-method-for-an-endpoint
  (ok (= (gethash "status" *wrong-method-reply*) 405))
  (ok (string= (gethash "allowed" (reply-body *wrong-method-reply*)) "POST")))

(deftest an-unknown-path
  (ok (= (gethash "status" *unknown-path-reply*) 404))
  (ok (string= (gethash "path" (reply-body *unknown-path-reply*)) "/nope")))

;;; Loading this file runs its suite (rove's file-driven entry point), and the
;;; exit code is the verdict -- so a handler that drifts breaks the build rather
;;; than the deployment.
(uiop:quit (if (run-suite *package*) 0 1))
