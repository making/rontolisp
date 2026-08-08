;;; check.lisp -- drive worker.lisp's handler without Cloudflare.
;;;
;;; Same shape as ../httpbin-clack/check.lisp (read the notes there): the
;;; Worker's exported entry point calls the handler backend's `dispatch`, an
;;; ordinary function, so the whole Worker -- routes included -- runs on the
;;; interpreter, the JVM and the WASM backends:
;;;
;;;   rontolisp examples/cloudflare-workers/httpbin-tiny-routes/check.lisp
;;;
;;; The last two probes are the routed additions: the /status/:code path
;;; template binding a parameter, and a non-numeric :code making that route
;;; DECLINE into the catch-all 404.

(load "worker.lisp")

(defun try (request-plist)
  (let ((request
         (rontolisp:json-stringify (rontolisp:plist-hash-table request-plist))))
    (format t "~&--> ~a~%" request)
    (format t "<-- ~a~%" (clack.handler.cloudflare-workers:dispatch request))))

(defun headers (&rest plist) (rontolisp:plist-hash-table plist))

(defun json-headers (body)
  (headers :host "example.com"
           :content-type "application/json"
           :content-length (princ-to-string (length body))))

;; GET /get with a query string.
(try
 (list :method "GET"
       :target "/get?a=1&b=two"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com" :accept "application/json")
       :body ""))

;; POST /post with a JSON body -- "data" is the raw text, "json" the parsed
;; value, read off clack's :raw-body stream.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{\"name\":\"rontolisp\"}")
       :body "{\"name\":\"rontolisp\"}"))

;; The wrong method for an endpoint -- the method-specific route declines and
;; the define-any right after it answers 405.
(try
 (list :method "GET"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; An unknown path -- the catch-all 404.
(try
 (list :method "GET"
       :target "/nope"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; The /status/:code path template -- :code binds "418".
(try
 (list :method "GET"
       :target "/status/418"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; A :code that does not parse -- the route declines, 404.
(try
 (list :method "GET"
       :target "/status/teapot"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))
