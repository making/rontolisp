;; The jzon flavour of httpbin.lisp: the very same program, but the JSON is
;; parsed and rendered by the real com.inuoe.jzon library instead of
;; rontolisp:json-parse / rontolisp:json-stringify. rontolisp:json-* is a
;; lightweight subset of jzon with the same value mapping, so the switch is
;; mechanical -- only the two call sites change (json-parse -> jzon:parse,
;; json-stringify -> jzon:stringify), and everything else (rontolisp:plist-hash-table
;; for the objects, the symbol null for JSON null) works unchanged. Reach for
;; jzon when you outgrow the subset (pretty printing, a streaming writer,
;; :replacer, custom serialization).
;;
;;   GET    /get      -> {"args": {...}, "headers": {...}, "method": "GET",  "path": "/get"}
;;   POST   /post     -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;   PUT/PATCH/DELETE  -> ditto ; a wrong method 405, an unknown path 404.
;;
;; ql:quickload downloads com.inuoe.jzon (and caches it) the first time -- at
;; compile time for the compiled backends, so the library is baked in.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/httpbin-jzon.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/httpbin-jzon.lisp -o HttpbinJzon.class && java -cp $JAR:. HttpbinJzon
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin-jzon.lisp -o httpbin-jzon.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y httpbin-jzon.wasm

(ql:quickload '#:com.inuoe.jzon)

;; --- request helpers ------------------------------------------------------

;; Parse the body with jzon when it looks like a JSON object or array, else the
;; symbol null (jzon's JSON-null sentinel, which jzon:stringify renders as null).
(defun body-json (body)
  (if (and (stringp body)
           (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (com.inuoe.jzon:parse body)
      'null))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (com.inuoe.jzon:stringify obj))))

;; The common echo fields, as a JSON object. rontolisp:plist-hash-table and
;; rontolisp:alist-hash-table (subsets of the alexandria utilities) build the
;; hash tables, which jzon:stringify serializes as objects (their keyword keys
;; down-cased); the standalone utilities need no change when the JSON library
;; does.
(defun request-info (request)
  (rontolisp:plist-hash-table
   (list :args (rontolisp:alist-hash-table (rontolisp:query-params (getf request :query)))
         :headers (rontolisp:alist-hash-table (getf request :headers))
         :method (getf request :method)
         :path (getf request :path))))

(defun echo (request)
  (json-response 200 (request-info request)))

(defun echo-with-body (request)
  (let ((info (request-info request)))
    (setf (gethash "data" info) (getf request :body))
    (setf (gethash "json" info) (body-json (getf request :body)))
    (json-response 200 info)))

(defun echo-when (request expected with-body)
  (cond ((not (string= (getf request :method) expected))
         (json-response 405 (rontolisp:plist-hash-table
                             (list :error "method not allowed" :allowed expected))))
        (with-body (echo-with-body request))
        (t (echo request))))

;; --- routing --------------------------------------------------------------

(defun route (request)
  (let ((path (getf request :path)))
    (cond ((string= path "/get") (echo-when request "GET" nil))
          ((string= path "/post") (echo-when request "POST" t))
          ((string= path "/put") (echo-when request "PUT" t))
          ((string= path "/patch") (echo-when request "PATCH" t))
          ((string= path "/delete") (echo-when request "DELETE" t))
          (t (json-response 404 (rontolisp:plist-hash-table
                                 (list :error "not found" :path path)))))))

;; The request :body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers a request whose :body is the whole string.
(rontolisp:async-defun handle (request)
  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
    (route (append (list :body body) request))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
