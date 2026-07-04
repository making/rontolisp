;; A rontolisp port of wasmCloud's http-kv-handler template
;; (templates/http-kv-handler in the wasmCloud repo): an HTTP handler backed
;; by a key-value store. The original stores through wasi:keyvalue with a
;; pluggable backend; rontolisp has no wasi:keyvalue binding yet
;; (.todo/52-wasi-keyvalue.md), so this port implements the template's
;; default "in_memory" backend as a global hash table.
;;
;;   POST /            {"key":"...","value":"..."}  -> stores the pair
;;   GET  /?key=<key>                               -> the stored value, or 404
;;   other methods                                  -> 405 "Method Not Allowed"
;;
;; Interpreter / JVM only: both keep the process (and so the hash table)
;; alive across requests. It compiles under --component too, but WASI HTTP
;; hosts either instantiate the component per request (wasmtime serve) or
;; reset the heap between requests (jco, wasmCloud), so the store would be
;; empty on every request -- a real WASM port needs wasi:keyvalue.
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-kv-handler/app.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/http-kv-handler/app.lisp -o App.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Talk to it with:
;;   curl -X POST -d '{"key":"greeting","value":"hello"}' http://127.0.0.1:8080/
;;   curl 'http://127.0.0.1:8080/?key=greeting'

;; The in-memory store; string keys work as hash keys on every backend.
(defvar *store* (make-hash-table))

;; --- request helpers --------------------------------------------------------

;; The query part of "path?query", or "" when there is none.
(defun query-of (path)
  (let ((q (position #\? path)))
    (if q (subseq path (+ q 1)) "")))

;; The value of `name` in "a=1&b=two", or nil (no %XX decoding).
(defun query-param (query name)
  (unless (string= query "")
    (let* ((amp (position #\& query))
           (pair (if amp (subseq query 0 amp) query))
           (eq-pos (position #\= pair)))
      (if (and eq-pos (string= (subseq pair 0 eq-pos) name))
          (subseq pair (+ eq-pos 1))
          (when amp (query-param (subseq query (+ amp 1)) name))))))

;; t when the body looks like a JSON object (json-parse signals on garbage,
;; and rontolisp has no condition handling to recover from that).
(defun json-object-p (body)
  (and (stringp body) (> (length body) 0) (eql (char body 0) #\{)))

(defun text-response (status body)
  (list :status status
        :headers (list (cons "content-type" "text/plain"))
        :body body))

;; --- handlers ----------------------------------------------------------------

;; POST / with {"key":"...","value":"..."} stores the pair.
(defun handle-post (request)
  (let ((body (getf request :body)))
    (if (json-object-p body)
        (let* ((payload (rontolisp:json-parse body))
               (key (getf payload :key))
               (value (getf payload :value)))
          (if (and (stringp key) (stringp value))
              (progn
                (setf (gethash key *store*) value)
                (text-response 200 (format nil "[in_memory] Stored key '~a'~%" key)))
              (text-response 400 (format nil "Invalid JSON (expected key and value string fields)~%"))))
        (text-response 400 (format nil "Invalid JSON (expected key and value string fields)~%")))))

;; GET /?key=<key> answers the stored value, or 404 when the key is unknown.
(defun handle-get (request)
  (let ((key (query-param (query-of (getf request :path)) "key")))
    (if key
        (let ((value (gethash key *store*)))
          (if value
              (text-response 200 (format nil "[in_memory] ~a~%" value))
              (text-response 404 (format nil "[in_memory] Key '~a' not found~%" key))))
        (text-response 400 (format nil "Missing required query parameter: key~%")))))

(defun handle (request)
  (let ((method (getf request :method)))
    (cond ((string= method "POST") (handle-post request))
          ((string= method "GET") (handle-get request))
          (t (text-response 405 (format nil "Method Not Allowed~%"))))))

;; Blocks and serves on port 8080.
(rontolisp:http-handler 'handle 8080)
