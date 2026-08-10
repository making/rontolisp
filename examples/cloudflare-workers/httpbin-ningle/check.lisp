;;; check.lisp -- drive worker.lisp's handler without Cloudflare.
;;;
;;; Same shape as ../httpbin-tiny-routes/check.lisp (read the notes there): the
;;; Worker's exported entry point calls the handler backend's `dispatch`, an
;;; ordinary function, so the whole Worker -- routes included -- runs on the
;;; interpreter, the JVM and the WASM backends:
;;;
;;;   rontolisp examples/cloudflare-workers/httpbin-ningle/check.lisp
;;;
;;; The probes are chosen for the four ningle mechanisms worker.lisp is built
;;; on: the parsed body arriving as `form`, the :ANY fallback route answering
;;; 405, the regex rule declining a non-numeric status, and ningle:not-found
;;; answering the 404.

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

;; GET /get with a query string -- it comes back as "args".
(try
 (list :method "GET"
       :target "/get?a=1&b=two"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com" :accept "application/json")
       :body ""))

;; POST /post with a JSON body -- it comes back as "form", already parsed:
;; lack/request did that before the controller ran.
(try
 (list :method "POST"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (json-headers "{\"name\":\"rontolisp\"}")
       :body "{\"name\":\"rontolisp\"}"))

;; A form-encoded body reaches the same field by the same route.
(try
 (list :method "PUT"
       :target "/put"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com"
                         :content-type "application/x-www-form-urlencoded"
                         :content-length "9")
       :body "name=lisp"))

;; The wrong method for an endpoint -- the method rule does not match, the :ANY
;; rule assigned right after it does, and that one answers 405.
(try
 (list :method "GET"
       :target "/post"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; /anything answers whatever method it is asked with.
(try
 (list :method "DELETE"
       :target "/anything"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; An unknown path -- ningle:not-found, the overridden method.
(try
 (list :method "GET"
       :target "/nope"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; The regex rule -- "418" is three digits, so it matches and :captures binds.
(try
 (list :method "GET"
       :target "/status/418"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))

;; "teapot" is not, so no rule matches at all and not-found answers the 404 --
;; no controller had to decide anything.
(try
 (list :method "GET"
       :target "/status/teapot"
       :scheme "https"
       :remote-addr "203.0.113.7"
       :headers (headers :host "example.com")
       :body ""))
