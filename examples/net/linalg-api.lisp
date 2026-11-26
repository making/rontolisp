;; A linear-algebra web service on rontolisp:http-handler -- the numerical
;; companion of httpbin.lisp. Two POST endpoints turn the linalg package into
;; a JSON API (rontolisp:json-parse in, rontolisp:json-stringify out):
;;
;;   POST /solve  {"a": [[2,1],[1,3]], "b": [5,10]}
;;     -> {"x": [1, 3], "det": 5}                      solves a.x = b
;;   POST /fit    {"degree": 1, "points": [[0,1],[1,2],[2,5],[3,5]]}
;;     -> {"coefficients": [1, 1.5], "fitted": [1, 2.5, 4, 5.5],
;;         "residuals": [0, -0.5, 1, -0.5],
;;         "squared-error": 1.5}                       least-squares polyfit
;;   GET  /       -> a JSON usage document
;;
;; /fit solves the normal equations (A^T A) c = A^T y over the Vandermonde
;; matrix of the xs, exactly like examples/ml/linear-regression.lisp -- but here
;; the samples arrive over HTTP. Integer inputs are solved exactly (ratios),
;; so the same request gives the same answer on every backend; ratios reach
;; the JSON as floats (json-stringify), e.g. 3/2 -> 1.5. Only the float
;; *rendering* of a ratio that is not binary-exact can differ on WASM
;; (33/10 prints as 3.3 on the interpreter/JVM but 3.299999 there).
;;
;; Invalid input (non-object body, a non-square or singular matrix, too few
;; points) answers 400 with {"error": ...}; a wrong method 405, an unknown
;; path 404. Because the service keeps no state between requests, it behaves
;; identically on all three backends -- including under wasmtime serve, where
;; each request runs in a fresh component instance.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/linalg-api.lisp
;; Run (JVM class; self-contained -- the embedded server travels beside it):
;;   java -jar $JAR examples/net/linalg-api.lisp -o LinalgApi.class && java -cp . LinalgApi
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/linalg-api.lisp -o linalg-api.wasm --component && \
;;     wasmtime serve linalg-api.wasm
;; Talk to it with:
;;   curl -X POST -d '{"a": [[2,1],[1,3]], "b": [5,10]}' http://127.0.0.1:8080/solve
;;   curl -X POST -d '{"degree": 1, "points": [[0,1],[1,2],[2,5],[3,5]]}' http://127.0.0.1:8080/fit

;; --- JSON request/response helpers ---------------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; rontolisp:plist-hash-table (a subset of alexandria:plist-hash-table) turns a
;; keyword plist into a string-keyed hash table, which json-stringify serializes
;; as a JSON object; the keyword keys are down-cased (:det becomes "det").
(defun bad-request (message)
  (json-response 400 (rontolisp:plist-hash-table (list :error message))))

(defun method-not-allowed ()
  (json-response 405
                 (rontolisp:plist-hash-table
                  (list :error "method not allowed" :allowed "POST"))))

;; Parse the body as a JSON object into a hash table (string keys), or nil
;; when the body is not a JSON object (the cheap guard answers 400 without
;; wrapping the parse in handler-case; a malformed OBJECT body still signals).
(defun body-object (body)
  (if (and (stringp body) (> (length body) 0) (eql (char body 0) #\{))
      (rontolisp:json-parse body)
      nil))

;; JSON arrays parse to vectors; the validators and linalg below are
;; list-oriented, so deep-convert any vector (a string is a vector too, and is
;; left alone) to a list before feeding the numeric code.
(defun json-array->list (v)
  (if (and (vectorp v) (not (stringp v)))
      (let ((out nil))
        (do ((i (- (length v) 1) (- i 1)))
            ((< i 0) out)
          (setq out (cons (json-array->list (aref v i)) out))))
      v))

;; --- input validation ------------------------------------------------------

;; True when row is a list of exactly n numbers (n > 0).
(defun number-row-p (row n)
  (and (listp row) row (= (length row) n) (every (lambda (v) (numberp v)) row)))

;; True when rows is a non-empty list of equal-length number rows.
(defun matrix-spec-p (rows)
  (and (listp rows) rows (listp (first rows)) (first rows)
       (every (lambda (row) (number-row-p row (length (first rows)))) rows)))

;; --- POST /solve : solve a.x = b -------------------------------------------

(defun handle-solve (env)
  (let* ((spec (body-object (getf env :body)))
         (a (and spec (json-array->list (gethash "a" spec))))
         (b (and spec (json-array->list (gethash "b" spec)))))
    (cond ((null spec) (bad-request "the body must be a JSON object"))
     ((not (matrix-spec-p a))
      (bad-request "a must be a non-empty array of equal-length number rows"))
     ((not (= (length a) (length (first a)))) (bad-request "a must be square"))
     ((not (number-row-p b (length a)))
      (bad-request "b must be a number array as long as a"))
     (t (let* ((m (linalg:from-list a)) (det (linalg:det m)))
          (if (= det 0)
              (bad-request "a is singular")
              (json-response 200
                             (rontolisp:plist-hash-table
                              (list :x (linalg:to-list
                                        (linalg:solve m (linalg:from-list b)))
                                    :det det)))))))))

;; --- POST /fit : least-squares polynomial fitting ---------------------------

;; One row per sample x: (1 x x^2 ... x^degree).
(defun vandermonde (xs degree)
  (let* ((n (length xs)) (m (make-array (list n (+ degree 1)))))
    (do ((row 0 (+ row 1)) (rest xs (cdr rest)))
        ((>= row n) m)
      (do ((col 0 (+ col 1)))
          ((> col degree))
        (setf (aref m row col) (expt (car rest) col))))))

(defun handle-fit (env)
  (let* ((spec (body-object (getf env :body)))
         (degree (and spec (gethash "degree" spec)))
         (points (and spec (json-array->list (gethash "points" spec)))))
    (cond ((null spec) (bad-request "the body must be a JSON object"))
          ((not (and (integerp degree) (>= degree 0)))
           (bad-request "degree must be a non-negative integer"))
          ((not
            (and (listp points) points
                 (every (lambda (p) (number-row-p p 2)) points)))
           (bad-request "points must be a non-empty array of [x, y] pairs"))
          ((< (length points) (+ degree 1))
           (bad-request "need at least degree + 1 points"))
          (t (let* ((xs (mapcar (lambda (p) (first p)) points))
                    (ys (mapcar (lambda (p) (nth 1 p)) points))
                    (a (vandermonde xs degree))
                    (at (linalg:transpose a))
                    (ata (linalg:matmul at a)))
               (if (= (linalg:det ata) 0)
                   (bad-request
                    "points do not determine the polynomial (duplicate xs?)")
                   (let* ((coeffs
                           (linalg:solve ata
                                         (linalg:dot at (linalg:from-list ys))))
                          (fitted (linalg:dot a coeffs))
                          (residuals (linalg:sub (linalg:from-list ys) fitted)))
                     (json-response 200
                                    (rontolisp:plist-hash-table
                                     (list :coefficients (linalg:to-list coeffs)
                                           :fitted (linalg:to-list fitted)
                                           :residuals (linalg:to-list residuals)
                                           :squared-error
                                           (linalg:dot residuals
                                                       residuals)))))))))))

;; --- routing ----------------------------------------------------------------

(defun usage ()
  (json-response 200
   (rontolisp:plist-hash-table
    (list :service "linalg-api"
          :endpoints (list (rontolisp:plist-hash-table
                            (list :method "POST"
                             :path "/solve"
                             :body "{\"a\": [[2,1],[1,3]], \"b\": [5,10]}"))
                           (rontolisp:plist-hash-table
                            (list :method "POST"
                                  :path "/fit"
                                  :body
                                  "{\"degree\": 1, \"points\": [[0,1],[1,2],[2,5],[3,5]]}")))))))

;; The env plist's :path-info carries the (percent-decoded) path only (any
;; query string arrives separately as :query-string), so the comparisons are
;; exact; :request-method is an interned keyword, so the comparison is eq.
(defun route (env)
  (let ((path (getf env :path-info)) (method (getf env :request-method)))
    (cond ((string= path "/solve")
           (if (eq method :POST) (handle-solve env) (method-not-allowed)))
          ((string= path "/fit")
           (if (eq method :POST) (handle-fit env) (method-not-allowed)))
          ((string= path "/") (usage))
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
