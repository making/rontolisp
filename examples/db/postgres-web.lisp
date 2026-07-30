;; A tiny web app: the two libraries of the other examples in this directory
;; put together. PostgreSQL keeps the notes (the REAL cl-postgres driver, as in
;; postgres-hello.lisp), cl-who renders the page (the real upstream (X)HTML
;; library, as in ../net/http-handler-cl-who.lisp) and rontolisp:http-handler
;; serves it. Two routes: GET / lists the notes and shows a form, POST /add
;; inserts one and redirects back.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (database-url.lisp beside this file parses the URL). The one-liner this
;; example is written against, and the URL that reaches it:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/postgres-web.lisp
;; Then open http://127.0.0.1:8080 in a browser.
;;
;; Runs on the interpreter and on the JVM backend (keep the rontolisp jar on
;; the classpath, it carries the runtime):
;;   rontolisp examples/db/postgres-web.lisp -o App.class && \
;;     java -cp target/rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; And as a WASI component under wasmtime serve, which both serves the page and
;; lets the driver dial out -- but only with -S cli=y in the flag set. Without
;; it the linker rejects the module with "resource implementation is missing",
;; which reads like the host has no sockets at all:
;;   rontolisp examples/db/postgres-web.lisp -o app.wasm --component --optimize
;;   wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y --env DATABASE_URL app.wasm
;; wasmCloud runs the same component (wash dev; its startup log lists 0.2
;; interfaces but it does provide wasi:sockets 0.3).
;; The component reaches the server over the network it is given, so DATABASE_URL
;; has to name an address that is reachable from inside it -- a container IP
;; under wasmtime-in-Docker, and under wash a NON-loopback address, because wash
;; routes loopback to a per-workload virtual network.
;;
;; --env DATABASE_URL is what puts the variable inside the component: the WASI
;; 0.3 service world carries no wasi:cli/environment, so a program that calls
;; uiop:getenv imports the interface itself and the served component declares it
;; (visible in `wasm-tools component wit app.wasm`). Drop the flag and this
;; program stops on "no database URL given" -- which an uncaught error on WASM
;; turns into a bare unreachable trap, so the missing flag reads as a crash.

(ql:quickload "cl-postgres")
(ql:quickload "cl-who")

(load "database-url.lisp")

(defun connect ()
  "A connection to the database. One per request: a connection is a single
   conversation with the server, and the interpreter/JVM servers run every
   request on its own thread with its own dynamic bindings, so requests that
   genuinely overlap stay isolated. (See the README for how each host holds
   up under a concurrent load test.)"
  (multiple-value-bind (database user password host port)
      (database-url-parts (uiop:getenv "DATABASE_URL"))
    (cl-postgres:open-database database user password host port)))

;; if not exists, not drop + create: this top level runs once per INSTANCE, and
;; how long an instance lives is the host's business -- wasmtime serve keeps one
;; for the whole run, while wasmCloud starts a fresh one per request. A drop
;; here would empty the table on every request there.
(let ((db (connect)))
  (unwind-protect
      (cl-postgres:exec-query
       db "create table if not exists notes (id serial primary key, body text)")
    (cl-postgres:close-database db)))

(defun all-notes (db)
  "Every note, newest first. A row reader decides the shape; list-row-reader
   gives one list per row, so each note is a one-element list."
  (cl-postgres:exec-query db "select body from notes order by id desc"
                          'cl-postgres:list-row-reader))

(defun add-note (db body)
  "One note, through the extended query protocol. $1 is a placeholder, so what
   the visitor typed stays data and never becomes SQL. The statement is
   prepared on this connection; the next request's connection starts fresh."
  (cl-postgres:prepare-query db "add-note" "insert into notes (body) values ($1)")
  (cl-postgres:exec-prepared db "add-note" (list body)))

(defun page (db)
  "The whole site: the form, then the notes."
  (cl-who:with-html-output-to-string (s)
    (:html
     (:head (:title "Notes"))
     (:body
      (:h1 "Notes")
      (:form :method "post" :action "/add"
             (:input :name "body" :size "40" :autofocus t)
             (:button "Add"))
      (:ul
       ;; Markup inside a plain Lisp form needs an htm to go back into the
       ;; markup DSL; esc escapes whatever the visitor typed.
       (dolist (note (all-notes db))
         (cl-who:htm (:li (cl-who:esc (first note))))))))))

;; async-defun because the request :body is a stream: read-all drains it and
;; await waits for the whole thing. Draining comes first, and taking a
;; connection second, so none is held open while we wait on the client.
(rontolisp:async-defun handle (request)
  (let* ((form (rontolisp:await (rontolisp:read-all (getf request :body))))
         (db (connect)))
    (unwind-protect
        (if (string= (getf request :path) "/add")
            ;; What arrived is url-encoded, the same shape as a query string.
            (let ((body (rontolisp:query-param form "body")))
              (when (and body (string/= body ""))
                (add-note db body))
              ;; 303 + Location: back to the list, so a reload does not re-post.
              '(:status 303 :headers (("location" . "/")) :body ""))
            (list :status 200
                  :headers '(("content-type" . "text/html; charset=utf-8"))
                  :body (page db)))
      ;; Handed back even if a query signals.
      (cl-postgres:close-database db))))

;; Blocks and serves on port 8080.
(rontolisp:http-handler 'handle 8080)
