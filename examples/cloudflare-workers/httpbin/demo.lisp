;;; demo.lisp -- drive app.lisp's handler without Cloudflare.
;;;
;;; `handle-request` is an ordinary function of a string, so the whole Worker
;;; can be developed and debugged on the interpreter, where the edit/run loop
;;; costs nothing:
;;;
;;;   rontolisp examples/cloudflare-workers/httpbin/demo.lisp
;;;
;;; It runs identically on the JVM and WASM backends, which is what pins the
;;; handler against every backend the compiler has.

(load "app.lisp")

(defun try (request-plist)
  (let ((request (rontolisp:json-stringify
                  (rontolisp:plist-hash-table request-plist))))
    (format t "--> ~a~%" request)
    (format t "<-- ~a~%" (handle-request request))))

(defun empty-table ()
  (make-hash-table :test 'equal))

;; GET /get with a query string -- the query object becomes "args".
(try (list :method "GET" :path "/get"
           :query (rontolisp:plist-hash-table (list :a "1" :b "two"))
           :headers (rontolisp:plist-hash-table (list :accept "application/json"))
           :body ""))

;; POST /post with a JSON body -- "data" is the raw text, "json" the parsed value.
(try (list :method "POST" :path "/post"
           :query (empty-table) :headers (empty-table)
           :body "{\"name\":\"rontolisp\"}"))

;; POST /post with a body that does not parse -- "json" falls back to null,
;; which is `handler-case` doing its work.
(try (list :method "POST" :path "/post"
           :query (empty-table) :headers (empty-table)
           :body "{not json"))

;; The wrong method for an endpoint -- 405.
(try (list :method "GET" :path "/post"
           :query (empty-table) :headers (empty-table) :body ""))

;; An unknown path -- 404.
(try (list :method "GET" :path "/nope"
           :query (empty-table) :headers (empty-table) :body ""))
