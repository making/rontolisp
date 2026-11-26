;;; Clack, plain: an application is a FUNCTION of the environment plist that
;;; returns the (status headers body) list. That is the whole API -- clack has
;;; no router and no request object -- so the same `app` runs on hunchentoot,
;;; on woo, under `wasmtime serve` and on the JVM, unchanged.
;;;
;;; :server :reactor is the handler backend for a host that CALLS you instead
;;; of handing you a socket; the compiler synthesizes the export src/index.js
;;; calls. :use-thread nil keeps clackup in the foreground off WASM.

(ql:quickload '("clack" "clack-handler-reactor"))

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on Cloudflare Workers!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

(clack:clackup #'app :server :reactor :use-thread nil)
