;; The Clack flavour of httpbin.lisp: the same five echo endpoints (/get,
;; /post, /put, /patch, /delete -- see there for the JSON they answer), served
;; through clack:clackup on the real clack (ql:quickload) instead of the
;; rontolisp:http-handler directive. rontolisp's server protocol IS Clack's,
;; so only the edges differ:
;;
;;   * the application is a function VALUE handed to clackup, where
;;     rontolisp:http-handler takes a literally quoted defun name;
;;   * clack's :raw-body is a SYNCHRONOUS stream (nil for a bodiless request),
;;     so it is drained with read-char and app is an ordinary defun -- not
;;     httpbin.lisp's async-defun + (await (read-all ...)).
;;
;; :use-thread nil serves in the foreground (Ctrl-C to stop); the default
;; :use-thread t returns a handler for (clack:stop handler) instead.
;;
;; Everything from the quickload down to `app` is also, verbatim,
;; examples/cloudflare-workers/httpbin-clack/app.lisp -- a Cloudflare Worker
;; hands over a parsed request instead of a socket, so there the clackup line
;; below is the ONE form replaced, by a call into the built-in
;; clack-handler-cloudflare-workers backend. That is the point of writing the handler as
;; a Clack application rather than as a server: it is the same function on every
;; host, and swapping the host swaps only the handler backend.
;;
;; Run (the first run downloads clack/lack into ~/.rontolisp/quicklisp):
;;   rontolisp examples/net/httpbin-clack.lisp
;;   rontolisp examples/net/httpbin-clack.lisp -o HttpbinClack.class && \
;;     java -cp rontolisp-exec.jar:. HttpbinClack
;;   rontolisp examples/net/httpbin-clack.lisp -o httpbin-clack.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
;;       httpbin-clack.wasm
;; Preview 1 has no incoming TCP: the program compiles, clackup fails at run
;; time. Under --component the host owns the socket, so :port is ignored.
;;
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

(ql:quickload "clack")

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

;; :path-info carries the (percent-decoded) path only -- the query string
;; arrives separately -- so the comparisons are exact.
(defun app (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (echo-when env :GET nil))
          ((string= path "/post") (echo-when env :POST t))
          ((string= path "/put") (echo-when env :PUT t))
          ((string= path "/patch") (echo-when env :PATCH t))
          ((string= path "/delete") (echo-when env :DELETE t))
          (t
           (json-response 404
                          (rontolisp:plist-hash-table
                           (list :error "not found" :path path)))))))

(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
