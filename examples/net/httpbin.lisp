;; A miniature httpbin (https://httpbin.ik.am) built on rontolisp:http-handler --
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
;; into "args" with rontolisp:query-params (keys and values url-decoded);
;; "json" is filled only when the body starts
;; with '{' or '[' (malformed JSON then signals an error -- rontolisp has no
;; condition handling to fall back to null like the real httpbin).
;; "headers" echoes the request header hash table the Clack environment
;; carries (names lowercased, repeated headers joined with ", ").
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/httpbin.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/httpbin.lisp -o Httpbin.class && java -cp $JAR:. Httpbin
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin.lisp -o httpbin.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y httpbin.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

;; --- request helpers ------------------------------------------------------

;; Parse the body as JSON when it looks like a JSON object or array.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (rontolisp:json-parse body)
      'null))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; The common echo fields, as a JSON object. rontolisp:plist-hash-table (a
;; subset of alexandria:plist-hash-table) turns a keyword plist into a
;; string-keyed hash table, which json-stringify serializes as an object --
;; keyword keys are down-cased, so :method becomes "method". "args" is itself
;; an object: query-params gives an alist, so rontolisp:alist-hash-table (a
;; subset of alexandria:alist-hash-table) turns it into a hash table -- an
;; empty (or missing) query still serializes as {}. "headers" needs no
;; conversion: the env :headers is already a string-keyed hash table.
(defun request-info (env)
  (rontolisp:plist-hash-table
   (list :args (rontolisp:alist-hash-table
                (rontolisp:query-params (getf env :query-string)))
         :headers (getf env :headers)
         :method (symbol-name (getf env :request-method))
         :path (getf env :path-info))))

(defun echo (env) (json-response 200 (request-info env)))

(defun echo-with-body (env)
  (let ((info (request-info env)))
    (setf (gethash "data" info) (getf env :body))
    (setf (gethash "json" info) (body-json (getf env :body)))
    (json-response 200 info)))

;; Echo the request (with the body fields when with-body is non-nil) only
;; when the request used the expected method; otherwise 405. :request-method
;; is an interned keyword, so the comparison is eq.
(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- routing --------------------------------------------------------------

;; The env plist's :path-info carries the (percent-decoded) path only (the
;; query string arrives separately as :query-string), so the comparisons are
;; exact.
(defun route (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (echo-when env :GET nil))
          ((string= path "/post") (echo-when env :POST t))
          ((string= path "/put") (echo-when env :PUT t))
          ((string= path "/patch") (echo-when env :PATCH t))
          ((string= path "/delete") (echo-when env :DELETE t))
          (t (json-response 404
                            (rontolisp:plist-hash-table
                             (list :error "not found" :path path)))))))

;; The env :raw-body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers an env whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (route (append (list :body body) env))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
