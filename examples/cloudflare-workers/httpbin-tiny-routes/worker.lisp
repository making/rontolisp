;;; worker.lisp -- ../httpbin-clack with a real routing library on top.
;;;
;;; The helpers and the five echo endpoints are ../httpbin-clack/worker.lisp's,
;;; unchanged; what replaces its hand-written `cond` over :path-info is
;;; tiny-routes -- define-routes, define-any, a "/status/:code" path template
;;; and the route-decline protocol. The interesting line is the FIRST one:
;;;
;;;   (ql:quickload "tiny-routes/lite")
;;;
;;; "tiny-routes/lite" is the same tiny-routes source tree with ONE component
;;; swapped: path-template.lisp's cl-ppcre-backed matcher is replaced by a
;;; ppcre-free one, and the :cl-ppcre dependency is dropped with it. That is
;;; an OPT-IN, and it is what keeps this module ~0.45 MB: a route template
;;; compiles to a regex scanner at RUN time, so with the full system the whole
;;; regex engine is genuinely reachable and ships -- 1,118,916 B raw where
;;; this build is 449,411 B (the numbers in the README). The trade is loud,
;;; never silent: the lite matcher accepts exactly the templates made of
;;; literal characters and :name tokens (what almost every routed application
;;; uses), matches them exactly as the full system does -- pinned
;;; template-for-template against the real cl-ppcre engine -- and REFUSES at
;;; route-build time (a clear error, not a wrong match) on a regex
;;; metacharacter or a :regex t template. A program that needs those loads
;;; the full "tiny-routes" instead and pays for the engine.
;;;
;;; Everything said in ../httpbin-clack/worker.lisp's header about the
;;; clackup call, the synthesized `handle-request` export and the two keyword
;;; arguments holds here verbatim.

(ql:quickload '("clack" "clack-handler-cloudflare-workers" "tiny-routes/lite"))

;; The application lives in its own package, like any real consumer of a
;; routing library -- the route macros and path-parameter come from
;; tiny-routes' exports.
(defpackage :httpbin-tiny-routes (:use :cl :tiny-routes))
(in-package :httpbin-tiny-routes)

;; --- request helpers (../httpbin-clack/worker.lisp, verbatim) --------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

;; --- responses (../httpbin-clack/worker.lisp, verbatim) --------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

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

(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- the routes ------------------------------------------------------------

;; A tiny-routes request IS the Clack environment plist, so echo-when takes it
;; unchanged. define-any (any method) + echo-when keeps httpbin's own
;; behavior: the wrong method on a known path answers 405, where define-get
;; would DECLINE and fall through to the 404.
;;
;; /status/:code is the route the hand-written cond could not spell: :code
;; binds whatever the segment holds, path-parameter reads it, and a value that
;; does not parse as an integer makes the route answer nil -- a DECLINE, so
;; the request falls through to the catch-all 404.
(define-routes *app*
  (define-any "/get" (req) (echo-when req :GET nil))
  (define-any "/post" (req) (echo-when req :POST t))
  (define-any "/put" (req) (echo-when req :PUT t))
  (define-any "/patch" (req) (echo-when req :PATCH t))
  (define-any "/delete" (req) (echo-when req :DELETE t))
  (define-any "/status/:code" (req)
    (let ((code (handler-case (parse-integer (path-parameter req :code))
                  (error () nil))))
      (when code
        (list code '(:content-type "text/plain; charset=utf-8")
              (list (format nil "~a~%" code))))))
  (define-any "*" (req)
    (json-response 404
                   (rontolisp:plist-hash-table
                    (list :error "not found" :path (path-info req))))))

(clack:clackup *app*
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
