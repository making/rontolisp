;;; demo.lisp -- drive worker.lisp's handler without Cloudflare.
;;;
;;; The Worker's entry point is a WASM export, which only the WASM backends
;;; have. What is underneath it exists everywhere: `dispatch` -- a JSON request
;;; string in, a JSON response string out, over the application clackup stored
;;; -- is an ordinary function of the handler backend, and it is exactly what
;;; the synthesized export calls. So the whole Worker -- the Clack application
;;; (app.lisp) AND the entry point that feeds it (worker.lisp) -- can be
;;; developed and debugged on the interpreter, where the edit/run loop costs
;;; nothing:
;;;
;;;   rontolisp examples/cloudflare-workers/httpbin-clack/demo.lisp
;;;
;;; It runs identically on the JVM and WASM backends, which is what pins the
;;; handler against every backend the compiler has.
;;;
;;; The two lines before the first --> are upstream clack's: clackup announces
;;; the server it is about to start, and clack.handler:run announces debug mode.
;;; On a Worker (--no-wasi) they go to a discarding stdout; here they do not,
;;; and that is the honest picture of what running a real clackup costs. Pass
;;; :silent t :debug nil to quiet them.

(load "worker.lisp")

(defun try (request-plist)
  (let ((request
         (rontolisp:json-stringify (rontolisp:plist-hash-table request-plist))))
    ;; ~& rather than plain ~: clack.handler:run's debug NOTICE ends without a
    ;; newline, so the first line here would otherwise be glued onto it.
    (format t "~&--> ~a~%" request)
    (format t "<-- ~a~%" (clack.handler.cloudflare-workers:dispatch request))))

(defun headers (&rest plist) (rontolisp:plist-hash-table plist))

(defun json-headers (body)
  (headers :host "example.com"
           :content-type "application/json"
           :content-length (princ-to-string (length body))))

;; GET /get with a query string. The target arrives raw -- path and query still
;; joined -- and the environment's :query-string is what becomes "args".
(try
 (list :method "GET"
       :target "/get?a=1&b=two"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com" :accept "application/json")
       :body ""))

;; A percent-encoded path: %http-make-env decodes it, so :path-info -- and the
;; "path" the echo document reports -- is the decoded form.
(try
 (list :method "GET"
       :target "/%67et"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; POST /post with a JSON body -- "data" is the raw text, "json" the parsed
;; value. The body reaches the application as clack's :raw-body, a synchronous
;; bivalent stream that read-body drains with read-char.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{\"name\":\"rontolisp\"}")
       :body "{\"name\":\"rontolisp\"}"))

;; POST /post with a body that does not parse -- "json" falls back to null,
;; which is `handler-case` doing its work.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{not json")
       :body "{not json"))

;; The wrong method for an endpoint -- 405.
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
