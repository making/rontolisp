;;; worker.lisp -- the Cloudflare half: everything app.lisp deliberately is not.
;;;
;;; app.lisp is the Clack application, verbatim from
;;; ../../net/httpbin-clack.lisp; its last form there is
;;;
;;;   (clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
;;;
;;; and THIS FILE is what replaces that one form. A Worker hands over a request
;;; JavaScript has already parsed rather than a socket, so there is no server to
;;; run: the program exports ONE function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; and src/index.js -- which does have a real `Request` -- calls it.
;;;
;;; What is NOT here is any re-implementation of Clack. rontolisp's own server
;;; protocol IS Clack's, so the environment builder and the response normalizer
;;; exist once, backend-free, in http-server.lisp, and every transport meets in
;;; them (the JDK server, the WASI component, clack.handler.rontolisp):
;;;
;;;   (rontolisp::%http-make-env raw)         ; positional raw tuple -> the environment
;;;   (rontolisp::%http-normalize-response r) ; app's result -> (status headers body)
;;;
;;; Naming either from a user program splices the library into the build. So the
;;; adapter gets the percent-decoding, the "?" split, the header lowercasing and
;;; comma-joining, the Host split, the content-length parsing, the buffered
;;; :raw-body stream and the whole response normalizer for free -- and cannot
;;; drift from what a SERVED request sees. It is the fifteen lines below.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. The flip side is that adding a `print`
;;; or a `random` here traps at run time -- see the README.

(load "app.lisp")

;;; --- the request ------------------------------------------------------------

(defun header-alist (table)
  "The headers JSON object -> the ((name . value) ...) alist the raw tuple wants."
  (let ((out nil))
    (maphash (lambda (name value) (setq out (cons (cons name value) out)))
             table)
    (nreverse out)))

;; The positional raw tuple %http-make-env consumes:
;;
;;   (method request-uri header-alist body server-protocol url-scheme
;;    local-name local-port remote-addr remote-port)
;;
;; "target" is the RAW request target -- path and query still joined, still
;; percent-encoded. %http-make-env owns that split, and :path-info /
;; :query-string have to come from it for a Clack application to see what Clack
;; promises. The Host header supplies :server-name / :server-port, so the two
;; placeholders below never win; a Worker exposes no peer port, only the address.
(defun request-tuple (req)
  (list (gethash "method" req) (gethash "target" req)
        (header-alist (gethash "headers" req))
        (rontolisp::%http-body-stream (gethash "body" req)) "HTTP/1.1"
        (gethash "scheme" req) "localhost" 443 (gethash "remote-addr" req) nil))

;;; --- the response -----------------------------------------------------------

(defun header-pairs (alist)
  "The response header alist -> a JSON array of [name, value], NOT an object.
An object would collapse repeated names, and a Clack application that sets two
cookies answers two Set-Cookie headers. src/index.js feeds the array straight to
the Headers constructor, which keeps both."
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (nreverse out)))

(defun envelope (status headers body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status :headers (header-pairs headers) :body body))))

;;; --- the exported entry point ------------------------------------------------

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

(defun handle-request (request-json)
  ;; A Lisp error would otherwise become a WASM trap, taking the whole instance
  ;; down with it. Answer 500 and keep serving instead.
  (handler-case (let* ((tuple
                        (request-tuple (rontolisp:json-parse request-json)))
                       (env (rontolisp::%http-make-env tuple))
                       (triple
                        (rontolisp::%http-normalize-response
                         (funcall #'app env))))
                  (envelope (car triple) (car (cdr triple))
                            (car (cdr (cdr triple)))))
    (error (e)
      (envelope 500 (list (cons "content-type" "application/json"))
                (format nil "~a~%"
                        (rontolisp:json-stringify
                         (rontolisp:plist-hash-table
                          (list :error (format nil "~a" e)))))))))
