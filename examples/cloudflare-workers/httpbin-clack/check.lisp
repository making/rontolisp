;;; Drive worker.lisp's handler without Cloudflare, on any backend: the
;;; synthesized export calls `dispatch`, an ordinary function of the handler
;;; backend, over the application clackup stored.
;;;
;;; Two kinds of noise are upstream clack's, and both are gone on a Worker,
;;; where stdout and *error-output* are a sink: the clackup banner before the
;;; first -->, and the request dump from lack's default backtrace middleware,
;;; which prints even for an error the application CATCHES -- the unparseable
;;; body is still answered 200 with "json":null.

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
