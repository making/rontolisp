;;; check.lisp -- drive worker.lisp's handler without Cloudflare.
;;;
;;; The Worker's entry point is a WASM export, which only the WASM backends
;;; have; what sits under it does not. `dispatch` -- a JSON request string in, a
;;; JSON response string out, over the application clackup stored -- is an
;;; ordinary function of the :reactor handler backend, and it is exactly what
;;; the synthesized export calls. So the whole Worker runs on the interpreter,
;;; the JVM and the WASM backends:
;;;
;;;   rontolisp examples/cloudflare-workers/httpbin-clack/check.lisp
;;;
;;; The probes are ../httpbin/check.lisp's, over the same application: what
;;; differs between the two directories is only what installs it, so a
;;; divergence shows up as the two cases disagreeing.
;;;
;;; The two lines before the first --> are upstream clack's -- the clackup
;;; banner and clack.handler:run's debug notice. On a Worker (--no-wasi) they go
;;; to a discarding stdout; here they do not. The request dump in the middle is
;;; lack's backtrace middleware, which clackup installs by default and which
;;; prints even for an error the application CATCHES: the unparseable body is
;;; still answered 200 with "json":null, on the Worker as here.

(load "worker.lisp")

(defun try (request-plist)
  (let ((request
         (rontolisp:json-stringify (rontolisp:plist-hash-table request-plist))))
    (format t "~&--> ~a~%" request)
    (format t "<-- ~a~%" (clack.handler.reactor:dispatch request))))

(defun headers (&rest plist) (rontolisp:plist-hash-table plist))

(defun json-headers (body)
  (headers :host "example.com"
           :content-type "application/json"
           :content-length (princ-to-string (length body))))

;; GET /get with a query string -- the "?" split and the percent-decoding are
;; %http-make-env's, over the RAW target the envelope carries.
(try
 (list :method "GET"
       :target "/%67et?a=1&b=two"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com" :accept "application/json")
       :body ""))

;; POST /post with a JSON body -- "data" is the raw text, "json" the parsed
;; value, read off clack's buffered :raw-body stream.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{\"name\":\"rontolisp\"}")
       :body "{\"name\":\"rontolisp\"}"))

;; A body that does not parse -- "json" is null and the answer is still 200,
;; which is what the real httpbin does.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{not json")
       :body "{not json"))

;; The wrong method for an endpoint -- 405, naming the one it wanted.
(try
 (list :method "GET"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; An unknown path -- 404.
(try
 (list :method "GET"
       :target "/nope"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))
