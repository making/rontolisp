;;; worker.lisp -- a miniature httpbin (https://httpbin.org) for Cloudflare
;;; Workers, as a Clack application with the reactor adapter written out by hand.
;;;
;;; Everything from `read-body` down to `*app*` is ../httpbin-clack/worker.lisp
;;; -- and ../../net/httpbin-clack.lisp -- VERBATIM: the same five echo
;;; endpoints, the same helpers, no Cloudflare anywhere in them. `*app*` holds a
;;; function of the Clack environment plist returning the Clack (status headers
;;; body) list, so it is the same application that runs on hunchentoot, on woo,
;;; under `wasmtime serve` and on the JVM, unchanged.
;;;
;;; What differs from ../httpbin-clack is the LAST section of this file rather
;;; than the application. That directory installs it with its ordinary
;;;
;;;   (clack:clackup *app* :server :reactor ...)
;;;
;;; -- the built-in handler backend for a host that calls an export, which
;;; bridges the host's JSON envelope to the Clack environment. Here that bridge
;;; is the thirty lines under "the reactor adapter" and clack is never loaded at
;;; all -- which is the whole point of this directory: the module is ~2x smaller
;;; (see the README's size table). The envelope both sides speak is the same
;;; one, so src/index.js is byte-for-byte ../httpbin-clack/src/index.js.
;;;
;;; The adapter converts nothing itself. rontolisp's own server protocol IS
;;; Clack's, and both halves of that boundary are backend-free entry points in
;;; the compiler's own http-server.lisp: rontolisp::%http-make-env turns a
;;; positional raw tuple into the environment, rontolisp::%http-normalize-response
;;; turns whatever the application returned into the (status header-alist
;;; body-string) triple. So the percent-decoding, the "?" split, the header
;;; lowercasing and comma-joining, the Host split, the content-length parsing and
;;; the buffered :raw-body all come for free, and cannot drift from what a SERVED
;;; request sees. All that is left to write is the JSON envelope.
;;;
;;; A Worker hands over a request JavaScript has already parsed rather than a
;;; socket, so there is no server to run: the program exports ONE function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; which src/index.js -- which does have a real `Request` -- calls.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. The flip side is that adding a `random`
;;; or a `get-universal-time` here traps at run time -- see the README.

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

;; --- request helpers ------------------------------------------------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

;; Parse the body as JSON when it looks like a JSON object or array, and fall
;; back to null when it does not parse -- which is what the real httpbin does.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; The common echo fields, as a JSON object: plist-hash-table and
;; alist-hash-table give json-stringify the string-keyed hash tables it
;; serializes as objects (:method becomes "method"; an empty query still
;; renders {}), and the env :headers already is one.
(defun request-info (env)
  (rontolisp:plist-hash-table
   (list :args (rontolisp:alist-hash-table
                (rontolisp:query-params (getf env :query-string)))
         :headers (getf env :headers)
         :method (symbol-name (getf env :request-method))
         :path (getf env :path-info))))

(defun echo (env) (json-response 200 (request-info env)))

(defun echo-with-body (env)
  (let ((info (request-info env)) (body (read-body (getf env :raw-body))))
    (setf (gethash "data" info) body)
    (setf (gethash "json" info) (body-json body))
    (json-response 200 info)))

;; Echo the request only when it used the expected method; otherwise 405.
;; :request-method is an interned keyword, so the comparison is eq.
(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- the Clack application ------------------------------------------------

;; A Clack application is a function VALUE -- what clackup, lack:builder and
;; every middleware take and return -- so it is a lambda in a variable rather
;; than a defun. :path-info carries the (percent-decoded) path only; the query
;; string arrives separately, so the comparisons are exact.
(defparameter *app*
  (lambda (env)
    (let ((path (getf env :path-info)))
      (cond ((string= path "/get") (echo-when env :GET nil))
            ((string= path "/post") (echo-when env :POST t))
            ((string= path "/put") (echo-when env :PUT t))
            ((string= path "/patch") (echo-when env :PATCH t))
            ((string= path "/delete") (echo-when env :DELETE t))
            (t (json-response 404
                              (rontolisp:plist-hash-table
                               (list :error "not found" :path path))))))))

;; --- the reactor adapter --------------------------------------------------
;; What `clack:clackup :server :reactor` would install, written out.
;; Nothing above this line knows it exists.

;; The headers JSON object -> the ((name . value) ...) alist the raw tuple
;; wants. nil (no headers) passes straight through.
(defun %header-alist (table)
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

;; The response header alist -> a JSON array of [name, value]. An ARRAY and not
;; an object: a name may repeat, and an application that sets two cookies has to
;; answer two Set-Cookie headers.
(defun %header-pairs (alist)
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (nreverse out)))

;; The positional raw tuple %http-make-env consumes:
;;   (method request-uri header-alist body server-protocol url-scheme
;;    local-name local-port remote-addr remote-port)
;; "target" is RAW -- path and query still joined and still encoded, because
;; %http-make-env owns that split. The Host header supplies :server-name /
;; :server-port, so the two placeholders below never win when the host sends one,
;; and %http-body-stream is what turns the body text into Clack's :raw-body.
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
;; trap that takes the whole instance down with it, and the host would have to
;; throw the instance away. Answer 500 and keep serving instead -- which is what
;; every other rontolisp transport does with a handler error.
(defun handle-request (request-json)
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env (rontolisp::%http-make-env (%request-tuple req)))
                       (triple
                        (rontolisp::%http-normalize-response
                         (funcall *app* env))))
                  (%envelope (car triple) (cadr triple) (caddr triple)))
    (error (e)
      (%envelope 500 (list (cons "content-type" "application/json"))
                 (format nil "~a~%"
                         (rontolisp:json-stringify
                          (rontolisp:plist-hash-table
                           (list :error (format nil "~a" e)))))))))
