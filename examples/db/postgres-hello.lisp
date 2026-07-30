;; Connects to a PostgreSQL server with the REAL cl-postgres (zlib, Marijn
;; Haverbeke / Sabra Crolleton -- Postmodern's low-level driver) loaded from its
;; unmodified upstream sources, and runs one query. Run with:
;;   rontolisp examples/db/postgres-hello.lisp
;;
;; It needs a server, and it reads where to find it out of DATABASE_URL
;; (database-url.lisp beside this file parses the URL). The one-liner this
;; example is written against, and the URL that reaches it:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;
;; Runs on the interpreter and on the JVM backend:
;;   rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
;;
;; The driver loads through ql:quickload, which pulls md5, split-sequence,
;; ironclad, cl-base64, cl-ppcre, uax-15 and alexandria (downloaded once into
;; ~/.rontolisp/quicklisp). Compiling takes a couple of seconds, and a run is
;; well under a second on any backend, the interpreter included -- the library
;; tables uax-15 would otherwise build at load time are derived at compile time
;; and built only if something asks for one, and nothing here does.
;; trust, password, md5 and SCRAM-SHA-256 authentication all complete within
;; the default 60-second authentication_timeout. The INTERPRETER cuts it
;; closest: its 4096-round PBKDF2 takes ~50 s, so on a slow machine start the
;; server with -c authentication_timeout=600 for headroom. The compiled
;; backends finish in seconds.
;;
;; On WASM the driver needs --component (TCP is WASI 0.3 sockets; Preview 1 has
;; no host socket API):
;;   rontolisp examples/db/postgres-hello.lisp -o postgres-hello.wasm --component --optimize
;;   wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y --env DATABASE_URL postgres-hello.wasm
;; TLS (sslmode) is interpreter/JVM only; use plain TCP on WASM.

(ql:quickload "cl-postgres")

(load "database-url.lisp")

;; uiop:getenv reads the variable -- rontolisp's one spelling of that, ANSI CL
;; having none -- and the parser hands back the five values in the order
;; open-database wants them, which is what makes this a multiple-value-bind.
(multiple-value-bind (database user password host port)
    (database-url-parts (uiop:getenv "DATABASE_URL"))
  (let ((conn (cl-postgres:open-database database user password host port)))
    ;; A row reader turns the result rows into Lisp data; list-row-reader gives a
    ;; list of lists.
    (print (cl-postgres:exec-query conn "select 42, 'hello'"
                                   'cl-postgres:list-row-reader))
    (print (cl-postgres:exec-query conn "select generate_series(1, 3) as n"
                                   'cl-postgres:list-row-reader))
    (cl-postgres:close-database conn)))
