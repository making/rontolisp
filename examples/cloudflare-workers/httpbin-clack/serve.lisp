;;; serve.lisp -- the SAME app.lisp, served by the real clack. No Worker.
;;;
;;; worker.lisp and this file are peers over one application: both load
;;; app.lisp, and each supplies the one thing the host it targets needs.
;;;
;;;   app.lisp  +  worker.lisp  ->  a Cloudflare Worker (an exported function)
;;;   app.lisp  +  serve.lisp   ->  an HTTP server on port 8080
;;;
;;; serve.lisp is one form, and it is exactly the form worker.lisp replaces, so
;;; app.lisp + serve.lisp is ../../net/httpbin-clack.lisp form for form. That
;;; makes "the application is portable" checkable by RUNNING it, not only by
;;; diffing it: point curl at this and at `npx wrangler dev`, and compare.
;;;
;;; Run (the first run downloads clack/lack into ~/.rontolisp/quicklisp):
;;;   rontolisp serve.lisp
;;;   rontolisp serve.lisp -o Serve.class && \
;;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Serve
;;;   rontolisp serve.lisp -o serve.wasm --component && \
;;;     wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y \
;;;       -S inherit-network=y serve.wasm
;;; Preview 1 has no incoming TCP: the program compiles, clackup fails at run
;;; time. Under --component the host owns the socket, so :port is ignored.
;;;
;;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post
;;;
;;; :use-thread nil serves in the foreground (Ctrl-C to stop).

(load "app.lisp")

(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
