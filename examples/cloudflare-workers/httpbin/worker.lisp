;;; No library: a mini httpbin (https://httpbin.org) whose Worker adapter is
;;; written out under it, so clack never loads and only the program ships.
;;;
;;; A Worker hands over a request JavaScript has already parsed rather than a
;;; socket, so there is no server to run -- the module exports ONE function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; which src/index.js calls. The adapter converts nothing itself:
;;; rontolisp::%http-make-env and %http-normalize-response are the entry points
;;; every SERVED request also goes through, so the "?" split, the
;;; percent-decoding, the header table and the buffered :raw-body come for free.
;;; All that is left to write is the JSON envelope.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile with --no-wasi:
;;; the module imports nothing at all.

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

;;; --- the endpoints -----------------------------------------------------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

;; Parse the body as JSON when it looks like one, and fall back to null when it
;; does not parse -- which is what the real httpbin does.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

(defun json-response (status object)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify object)))))

;; plist-hash-table and alist-hash-table give json-stringify the string-keyed
;; hash tables it serializes as objects (:method becomes "method"; an empty
;; query still renders {}), and the env :headers already is one.
(defun echo (env with-body)
  (let ((info
         (rontolisp:plist-hash-table
          (list :args (rontolisp:alist-hash-table
                       (rontolisp:query-params (getf env :query-string)))
                :headers (getf env :headers)
                :method (symbol-name (getf env :request-method))
                :path (getf env :path-info)))))
    (when with-body
      (let ((body (read-body (getf env :raw-body))))
        (setf (gethash "data" info) body)
        (setf (gethash "json" info) (body-json body))))
    (json-response 200 info)))

;; :request-method is an interned keyword, so the check is eq.
(defun endpoint (env method with-body)
  (if (eq (getf env :request-method) method)
      (echo env with-body)
      (json-response 405
                     (rontolisp:plist-hash-table
                      (list :error "method not allowed"
                            :allowed (symbol-name method))))))

;; :path-info carries the decoded path only -- the query string arrives
;; separately -- so the comparisons are exact.
(defun dispatch (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (endpoint env :GET nil))
          ((string= path "/post") (endpoint env :POST t))
          ((string= path "/put") (endpoint env :PUT t))
          ((string= path "/patch") (endpoint env :PATCH t))
          ((string= path "/delete") (endpoint env :DELETE t))
          (t (json-response 404
                            (rontolisp:plist-hash-table
                             (list :error "not found" :path path)))))))

;;; --- the reactor adapter -----------------------------------------------------
;;; What `clack:clackup :server :reactor` would install. Nothing above knows it
;;; exists.

;; The headers JSON object -> the ((name . value) ...) alist the raw tuple wants.
(defun %header-alist (table)
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

;; The response header alist -> a JSON ARRAY of [name, value]: a name may
;; repeat, and two cookies mean two Set-Cookie headers.
(defun %header-pairs (alist)
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (nreverse out)))

;; The positional tuple %http-make-env consumes. "target" is RAW -- path and
;; query still joined and still encoded, because %http-make-env owns that split
;; -- and the Host header supplies :server-name / :server-port, so the two
;; placeholders below never win when the host sends one.
(defun %request-tuple (req)
  (list (or (gethash "method" req) "GET") (or (gethash "target" req) "/")
        (%header-alist (gethash "headers" req))
        (rontolisp::%http-body-stream (gethash "body" req)) "HTTP/1.1"
        (gethash "scheme" req) "localhost" 80 (gethash "remote-addr" req) nil))

(defun %envelope (status headers body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status :headers (%header-pairs headers) :body body))))

;; The host's entry point. It CATCHES: on a reactor an uncaught Lisp error is a
;; trap that takes the whole instance down, so answer 500 and keep serving.
(defun handle-request (request-json)
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env (rontolisp::%http-make-env (%request-tuple req)))
                       (triple
                        (rontolisp::%http-normalize-response (dispatch env))))
                  (%envelope (car triple) (cadr triple) (caddr triple)))
    (error (e)
      (%envelope 500 (list (cons "content-type" "application/json"))
                 (format nil "~a~%"
                         (rontolisp:json-stringify
                          (rontolisp:plist-hash-table
                           (list :error (format nil "~a" e)))))))))
