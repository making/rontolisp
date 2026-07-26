;; Connects to a PostgreSQL server with the REAL cl-postgres (zlib, Marijn
;; Haverbeke / Sabra Crolleton -- Postmodern's low-level driver) loaded from its
;; unmodified upstream sources, and runs one query. Run with:
;;   rontolisp examples/db/postgres-hello.lisp
;;
;; It needs a server. The one-liner this example is written against:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;
;; Runs on the interpreter and on the JVM backend:
;;   rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
;;
;; The driver loads through ql:quickload, which pulls md5, split-sequence,
;; ironclad, cl-base64, cl-ppcre, uax-15 and alexandria (downloaded once into
;; ~/.rontolisp/quicklisp). Compiling takes a few seconds, and so does a run on
;; any backend, the interpreter included.
;; trust, password and md5 authentication all complete; SCRAM-SHA-256 also
;; completes, but on the INTERPRETER only if the server allows enough time: its
;; 4096-round PBKDF2 outruns the default 60-second authentication_timeout in
;; interpreted Lisp, so start the server with -c authentication_timeout=600 for
;; it. The compiled backend is fast enough for the default.
;;
;; On WASM the driver needs --component (TCP is WASI 0.3 sockets; Preview 1 has
;; no host socket API):
;;   rontolisp examples/db/postgres-hello.lisp -o postgres-hello.wasm --component --optimize
;;   wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y postgres-hello.wasm
;; TLS (sslmode) is interpreter/JVM only; use plain TCP on WASM.

(ql:quickload "cl-postgres")

(let ((conn (cl-postgres:open-database "postgres" "postgres" nil "127.0.0.1" 54329)))
  ;; A row reader turns the result rows into Lisp data; list-row-reader gives a
  ;; list of lists.
  (print (cl-postgres:exec-query conn "select 42, 'hello'"
                                 'cl-postgres:list-row-reader))
  (print (cl-postgres:exec-query conn "select generate_series(1, 3) as n"
                                 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
