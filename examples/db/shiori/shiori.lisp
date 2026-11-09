;; Shiori (栞) -- a reading journal: a bookshelf, reading progress, quotes and
;; notes, tags, and a dashboard that charts the books finished per month.
;;
;; The stack, every layer the real upstream library loaded unmodified:
;;
;;   cl-postgres      the PostgreSQL wire protocol; every statement prepared,
;;                    every value a $n parameter
;;   cl-mustache      the HTML: templates/*.mustache, one layout and a partial
;;                    per page and per reusable piece
;;   tiny-routes/lite the routing and the middleware, served through Clack
;;   htmx 4           in the browser (loaded from a CDN): page navigation,
;;                    incremental search and in-place updates
;;
;; htmx is an enhancement, not a requirement. Every form posts and every link
;; navigates without JavaScript; when the HX-Request header says htmx sent the
;; request, the same route answers with a fragment -- a single partial -- or with
;; an HX-Location instead of a 303. See README.md for the request flow.
;;
;; The templates, the stylesheet and the script are read when the program is
;; COMPILED (define-asset in view.lisp), so the built class or component
;; carries them and runs from any directory.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (../database-url.lisp parses the URL), or on Cloud Foundry out of the
;; credentials' uri of a service tagged postgresql in VCAP_SERVICES (db.lisp):
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/shiori/shiori.lisp
;; Then open http://127.0.0.1:8080. The first start creates the schema and
;; shelves eighteen books, so there is something to look at.
;;
;; JVM class (self-contained: the server travels beside it):
;;   rontolisp examples/db/shiori/shiori.lisp -o Shiori.class && java -cp . Shiori
;; Native executable:
;;   rontolisp examples/db/shiori/shiori.lisp -o shiori --native && ./shiori
;; WASI component under wasmtime serve:
;;   rontolisp examples/db/shiori/shiori.lisp -o shiori.wasm --component --optimize
;;   wasmtime serve -S cli=y -S tcp=y -S inherit-network=y --env DATABASE_URL shiori.wasm

(ql:quickload '("clack" "tiny-routes/lite" "cl-postgres" "cl-mustache"))

(load "../database-url.lisp")

;; The parts, in the order they depend on one another. A literal top-level load
;; is spliced in where it stands when the program is compiled, so the built
;; program is still one unit.
(load "db.lisp")        ; the connection and the query helpers
(load "schema.lisp")    ; the schema and the first-run data
(load "view.lisp")      ; templates, assets, how a book is drawn
(load "http.lisp")      ; requests, responses, middleware
(load "home.lisp")      ; GET /
(load "library.lisp")   ; GET /books
(load "book.lisp")      ; GET /books/:id and its in-place updates
(load "book-form.lisp") ; the form: create, edit, delete

;;; --- routes ------------------------------------------------------------------------

;; Literal paths before parameterized ones: /books/new would otherwise match
;; /books/:id.
(tiny:define-routes *app-routes*
  (tiny:define-get "/" (req) (dashboard req))
  (tiny:define-get "/books" (req) (library req))
  (tiny:define-post "/books" (req) (create-book req))
  (tiny:define-get "/books/new" (req) (new-book req))
  (tiny:define-get "/books/:id" (req) (show-book req))
  (tiny:define-post "/books/:id" (req) (update-book req))
  (tiny:define-get "/books/:id/edit" (req) (edit-book req))
  (tiny:define-post "/books/:id/delete" (req) (delete-book req))
  (tiny:define-post "/books/:id/progress" (req) (record-progress req))
  (tiny:define-post "/books/:id/rating" (req) (record-rating req))
  (tiny:define-post "/books/:id/notes" (req) (add-note req))
  (tiny:define-post "/notes/:id/delete" (req) (delete-note req)))

(tiny:define-routes *static-routes*
  (tiny:define-get "/static/app.css" (req)
    (static "text/css; charset=utf-8" *stylesheet*
            ;; Its URL changes with its text (*stylesheet-url*).
            "public, max-age=31536000, immutable"))
  (tiny:define-get "/static/app.js" (req)
    (static "text/javascript; charset=utf-8" *script*
            "public, max-age=31536000, immutable"))
  (tiny:define-get "/favicon.svg" (req)
    (static "image/svg+xml" *favicon* "public, max-age=86400"))
  (tiny:define-any "*" (req) (not-found req)))

(defparameter *app*
  (tiny:pipe (tiny:routes (tiny:pipe *app-routes*
                           (tiny:wrap-post-match-middleware #'wrap-connection))
                          *static-routes*) (wrap-same-origin) (wrap-errors)
             (wrap-params) (tiny:wrap-request-body :max-bytes 65536)))

(clack:clackup *app*
               :server :rontolisp
               :address "0.0.0.0"
               :port 8080
               :use-thread nil
               ;; wrap-errors answers what a handler did not foresee; the
               ;; default :debug t only adds a debug-mode notice to the log.
               :debug nil)
