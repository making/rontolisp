;; A miniature httpbin (https://httpbin.org) built on rontolisp:http-handler --
;; the advanced companion of http-handler.lisp. Five echo endpoints respond
;; with a JSON document describing the request, built with
;; rontolisp:json-stringify (and rontolisp:json-parse for the request body):
;;
;;   GET    /get      -> {"args": {...}, "headers": {...}, "method": "GET",  "path": "/get"}
;;   POST   /post     -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;   PUT    /put      -> ditto
;;   PATCH  /patch    -> ditto
;;   DELETE /delete   -> ditto
;;
;; A wrong method answers 405, an unknown path 404. Query strings are parsed
;; into "args" (no %XX decoding); "json" is filled only when the body starts
;; with '{' or '[' (malformed JSON then signals an error -- rontolisp has no
;; condition handling to fall back to null like the real httpbin).
;; On the JVM and WASM component backends "headers" is always {} (request
;; headers are not marshalled there yet) and the response content-type header
;; is ignored; only the interpreter passes headers through.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/httpbin.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/httpbin.lisp -o Httpbin.class && java -cp $JAR:. Httpbin
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/httpbin.lisp -o httpbin.wasm --component && \
;;     wasmtime serve -W gc=y httpbin.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

;; --- request helpers ------------------------------------------------------

;; The path part of "path?query" (up to the first ?).
(defun path-only (path)
  (let ((q (position #\? path)))
    (if q (subseq path 0 q) path)))

;; The query part of "path?query", or "" when there is none.
(defun query-of (path)
  (let ((q (position #\? path)))
    (if q (subseq path (+ q 1)) "")))

;; Parse "a=1&b=two&flag" into a hash table {"a" "1", "b" "two", "flag" ""}.
;; A hash table (not a plist) so arbitrary key strings work and an empty
;; query still serializes as {}.
(defun parse-args (query)
  (let ((args (make-hash-table)))
    (parse-args-into query args)
    args))

(defun parse-args-into (query args)
  (unless (string= query "")
    (let* ((amp (position #\& query))
           (pair (if amp (subseq query 0 amp) query))
           (eq-pos (position #\= pair)))
      (if eq-pos
          (setf (gethash (subseq pair 0 eq-pos) args) (subseq pair (+ eq-pos 1)))
          (setf (gethash pair args) ""))
      (when amp
        (parse-args-into (subseq query (+ amp 1)) args)))))

;; Request headers arrive as an alist of (name . value); rebuild it as a hash
;; table so it serializes as a JSON object.
(defun headers-table (headers)
  (let ((table (make-hash-table)))
    (dolist (header headers)
      (setf (gethash (car header) table) (cdr header)))
    table))

;; Parse the body as JSON when it looks like a JSON object or array.
(defun body-json (body)
  (if (and (stringp body)
           (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (rontolisp:json-parse body :hash-table)
      nil))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; The common echo fields, as a plist (rontolisp:json-stringify serializes a
;; keyword plist as a JSON object, preserving this key order).
(defun request-info (request)
  (list :args (parse-args (query-of (getf request :path)))
        :headers (headers-table (getf request :headers))
        :method (getf request :method)
        :path (path-only (getf request :path))))

(defun echo (request)
  (json-response 200 (request-info request)))

(defun echo-with-body (request)
  (json-response 200
                 (append (request-info request)
                         (list :data (getf request :body)
                               :json (body-json (getf request :body))))))

;; Echo the request (with the body fields when with-body is non-nil) only
;; when the request used the expected method; otherwise 405.
(defun echo-when (request expected with-body)
  (cond ((not (string= (getf request :method) expected))
         (json-response 405 (list :error "method not allowed" :allowed expected)))
        (with-body (echo-with-body request))
        (t (echo request))))

;; --- routing --------------------------------------------------------------

(defun handle (request)
  (let ((path (path-only (getf request :path))))
    (cond ((string= path "/get") (echo-when request "GET" nil))
          ((string= path "/post") (echo-when request "POST" t))
          ((string= path "/put") (echo-when request "PUT" t))
          ((string= path "/patch") (echo-when request "PATCH" t))
          ((string= path "/delete") (echo-when request "DELETE" t))
          (t (json-response 404 (list :error "not found" :path path))))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
