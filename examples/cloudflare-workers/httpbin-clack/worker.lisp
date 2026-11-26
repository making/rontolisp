;;; Clack, plain: a mini httpbin as an application FUNCTION -- the environment
;;; plist in, the (status headers body) list out -- and one MIDDLEWARE, which in
;;; Clack is just a function from application to application. Clack has no
;;; router, so dispatch is a `cond` over :path-info. Nothing here mentions
;;; Cloudflare: the same `app` runs on hunchentoot, on woo, under
;;; `wasmtime serve` and on the JVM.
;;;
;;; :server :reactor is the handler backend for a host that CALLS you instead of
;;; handing you a socket; the compiler synthesizes the handle-request export
;;; src/index.js calls, because rontolisp:wasm-export needs a literal name at
;;; compile time and a clackup call has none to give.

(ql:quickload '("clack" "clack-handler-reactor"))

;;; --- the endpoints -----------------------------------------------------------

;; clack's :raw-body is a synchronous stream, and nil when there is no body.
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

(defun json-body (object)
  (list (format nil "~a~%" (rontolisp:json-stringify object))))

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
    (list 200 nil (json-body info))))

;; :request-method is an interned keyword, so the check is eq.
(defun endpoint (env method with-body)
  (if (eq (getf env :request-method) method)
      (echo env with-body)
      (list 405 nil
            (json-body
             (rontolisp:plist-hash-table
              (list :error "method not allowed"
                    :allowed (symbol-name method)))))))

;;; --- the application ---------------------------------------------------------

;; :path-info carries the decoded path only -- the query string arrives
;; separately -- so the comparisons are exact.
(defun app (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (endpoint env :GET nil))
          ((string= path "/post") (endpoint env :POST t))
          ((string= path "/put") (endpoint env :PUT t))
          ((string= path "/patch") (endpoint env :PATCH t))
          ((string= path "/delete") (endpoint env :DELETE t))
          (t (list 404 nil
                   (json-body
                    (rontolisp:plist-hash-table
                     (list :error "not found" :path path))))))))

;; A middleware takes an application and returns one, which is why no endpoint
;; above sets a header. Several of them compose with lack:builder.
(defun wrap-json (app)
  (lambda (env)
    (let ((response (funcall app env)))
      (list* (first response)
             (list* :content-type "application/json" (second response))
             (cddr response)))))

(clack:clackup (wrap-json #'app) :server :reactor :use-thread nil)
