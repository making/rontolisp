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
;; Run (JVM class; self-contained -- the embedded server travels beside it):
;;   java -jar $JAR examples/net/httpbin-jzon.lisp -o HttpbinJzon.class && java -cp . HttpbinJzon
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin-jzon.lisp -o httpbin-jzon.wasm --component && \
;;     wasmtime serve httpbin-jzon.wasm

(ql:quickload '#:com.inuoe.jzon)

;; --- request helpers ------------------------------------------------------

;; Parse the body with jzon when it looks like a JSON object or array, else the
;; symbol null (jzon's JSON-null sentinel, which jzon:stringify renders as null).
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (com.inuoe.jzon:parse body)
      'null))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (com.inuoe.jzon:stringify obj)))))

;; The common echo fields, as a JSON object. rontolisp:plist-hash-table and
;; rontolisp:alist-hash-table (subsets of the alexandria utilities) build the
;; hash tables, which jzon:stringify serializes as objects (their keyword keys
;; down-cased); the standalone utilities need no change when the JSON library
;; does. The env :headers is already a string-keyed hash table, so it nests
;; as an object with no conversion.
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

;; :request-method is an interned keyword, so the comparison is eq.
(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- routing --------------------------------------------------------------

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
;; here and hand the helpers an env whose :body is the whole string.
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (route (append (list :body body) env))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
