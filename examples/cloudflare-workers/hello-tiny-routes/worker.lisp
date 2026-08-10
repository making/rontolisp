;;; tiny-routes: an application is COMPOSED. `define-routes` builds a handler
;;; that tries each route and takes the first non-nil answer, so returning nil
;;; DECLINES and "*" is the 404; `ok` / `not-found` name the status instead of
;;; spelling the triple; and `pipe` threads the whole table through middleware
;;; -- here the one that gives every response its content type. `tiny` is the
;;; library's own nickname, so nothing has to be imported to reach any of it.
;;;
;;; "tiny-routes/lite" is the opt-in system whose ppcre-free path-template
;;; matcher keeps the regex engine out of the module. It takes literal
;;; characters and :name tokens, and refuses a regex-shaped template.

(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

(tiny:define-routes *routes*
  (tiny:define-get "/" ()
    (tiny:ok (format nil "Hello from tiny-routes on Cloudflare Workers!~%")))
  (tiny:define-get "/hello/:name" (req)
    (tiny:ok (format nil "Hello, ~a!~%" (tiny:path-parameter req :name))))
  (tiny:define-any "*" (req)
    (tiny:not-found (format nil "no route for ~a~%" (tiny:path-info req)))))

(defparameter *app*
  (tiny:pipe *routes*
             (tiny:wrap-response-content-type "text/plain; charset=utf-8")))

(clack:clackup *app* :server :reactor :use-thread nil)
