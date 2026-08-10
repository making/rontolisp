;;; Drive worker.lisp's handler without Cloudflare, on any backend:
;;; `handle-request` is an ordinary function of a string, adapter included.
;;;
;;; The requests below are the envelope src/index.js builds out of a real
;;; `Request`: the RAW target rather than a pre-split path and query object, and
;;; a content-length for anything with a body. Both fail quietly if you get them
;;; wrong -- see the README.

(load "worker.lisp")

(defun try (request-plist)
  (let ((request
         (rontolisp:json-stringify (rontolisp:plist-hash-table request-plist))))
    (format t "--> ~a~%" request)
    (format t "<-- ~a~%" (handle-request request))))

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
