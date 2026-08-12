;; A bulletin-board REST API: PostgreSQL for the comments (the real cl-postgres
;; driver, as in postgres-crud.lisp) and tiny-routes for the routing, served
;; through Clack. Where postgres-web.lisp renders HTML for a browser, this one
;; answers JSON for a client, so the interesting part is not the storage but
;; what a REST contract needs on top of it: a validated query string, a status
;; code per outcome, and ONE error document behind every failure.
;;
;;   POST   /api/v1/comments        {"author": ..., "content": ...} -> 201 + the comment
;;   GET    /api/v1/comments        ?page=1&limit=20&sort=newest    -> 200 + data + pagination
;;   DELETE /api/v1/comments/:id                                    -> 204, or 404
;;
;; Anything else is an error document -- timestamp, status, error, message,
;; path -- with 400 for a request that does not parse, 404 for a comment (or a
;; path) that is not there, 405 for a known path taken with the wrong method,
;; and 500 for whatever the handler did not foresee.
;;
;; "tiny-routes/lite" is the ppcre-free opt-in system; the full "tiny-routes"
;; runs this file unchanged and costs a regex engine.
;;
;; It needs a server, and reads where to find it out of DATABASE_URL
;; (database-url.lisp beside this file parses the URL). The one-liner this
;; example is written against, and the URL that reaches it:
;;   docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
;;   export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
;;   rontolisp examples/db/bbs-api.lisp
;;
;; Runs on the interpreter and on the JVM backend (keep the rontolisp jar on
;; the classpath, it carries the runtime):
;;   rontolisp examples/db/bbs-api.lisp -o BbsApi.class && \
;;     java -cp target/rontolisp-0.1.0-SNAPSHOT-exec.jar:. BbsApi
;; And as a WASI component under wasmtime serve, which both serves the API and
;; lets the driver dial out -- the same flag set postgres-web.lisp needs, -S
;; cli=y included:
;;   rontolisp examples/db/bbs-api.lisp -o bbs-api.wasm --component --optimize
;;   wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
;;     --env DATABASE_URL bbs-api.wasm
;; See README.md for the wasmCloud and Spin manifests, and for why the
;; component reads DATABASE_URL through an interface of its own.
;;
;;   curl -X POST -H 'content-type: application/json' \
;;        -d '{"author":"alice","content":"hello"}' http://127.0.0.1:8080/api/v1/comments
;;   curl 'http://127.0.0.1:8080/api/v1/comments?page=1&limit=20&sort=oldest'
;;   curl -X DELETE -i http://127.0.0.1:8080/api/v1/comments/1

(ql:quickload '("clack" "tiny-routes/lite" "cl-postgres"))

(load "database-url.lisp")

;;; --- the documents -----------------------------------------------------------

;; The response shapes are CLOS classes, not hash tables: json-stringify
;; serializes a standard-object slot by slot IN DEFINITION ORDER, so the class
;; is the schema and the JSON comes out in the order written here. A slot name
;; is down-cased for the key unless it already holds a lower-case letter, which
;; is what the |...| escapes are for -- the reader would otherwise upcase
;; createdAt and the key would ship as "createdat".
(defclass comment ()
  ((id :initarg :id) (author :initarg :author) (content :initarg :content)
   (|createdAt| :initarg :created-at)))

(defclass pagination ()
  ((|totalComments| :initarg :total-comments)
   (|totalPages| :initarg :total-pages) (|currentPage| :initarg :current-page)
   (limit :initarg :limit) (|hasNextPage| :initarg :has-next-page)
   (|hasPrevPage| :initarg :has-prev-page)))

(defclass comment-page ()
  ((data :initarg :data) (pagination :initarg :pagination)))

(defclass api-error ()
  ((timestamp :initarg :timestamp) (status :initarg :status)
   (error :initarg :error) (message :initarg :message) (path :initarg :path)))

(defun json-body (object) (format nil "~a~%" (rontolisp:json-stringify object)))

;;; --- the error document ------------------------------------------------------

(defparameter *reasons*
  '((400 . "Bad Request") (404 . "Not Found") (405 . "Method Not Allowed")
    (500 . "Internal Server Error")))

(defun iso-8601-now ()
  "The current instant as 2025-05-15T10:30:00Z. The zone argument 0 asks for
   GMT, which is the one zone every backend agrees on."
  (multiple-value-bind (second minute hour date month year)
      (decode-universal-time (get-universal-time) 0)
    (format nil "~4,'0d-~2,'0d-~2,'0dT~2,'0d:~2,'0d:~2,'0dZ" year month date
            hour minute second)))

(defun problem (req status message)
  "The one error shape the whole API answers with. Everything that can go
   wrong comes through here, so a client never has to tell two failure
   documents apart."
  (tiny:make-response :status status
                      :body (json-body
                             (make-instance 'api-error
                              :timestamp (iso-8601-now)
                              :status status
                              :error (cdr (assoc status *reasons*))
                              :message message
                              :path (tiny:path-info req)))))

;;; --- storage -----------------------------------------------------------------

(defun connect ()
  "A connection to the database. One per request -- see wrap-connection below."
  (multiple-value-bind (database user password host port)
      (database-url-parts (uiop:getenv "DATABASE_URL"))
    (cl-postgres:open-database database user password host port)))

;; if not exists, not drop + create: this top level runs once per INSTANCE, and
;; how long an instance lives is the host's business -- wasmtime serve keeps one
;; for the whole run, while wasmCloud starts a fresh one per request. A drop
;; here would empty the board on every request there.
(let ((db (connect)))
  (unwind-protect (cl-postgres:exec-query db
                                          "create table if not exists comments (
                                             id bigserial primary key,
                                             author text not null,
                                             content text not null,
                                             created_at timestamptz not null default now())")
    (cl-postgres:close-database db)))

;; The four columns every endpoint returns, in the order the comment class
;; declares them. id is cast to text because the contract says the id is a
;; string, and the timestamp is formatted BY THE SERVER: a `to_char` keeps the
;; ISO-8601 rendering in one place and spares the program a calendar library.
(defparameter *comment-columns*
  "id::text, author, content,
   to_char(created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')")

(defun row-comment (row)
  "One list-row-reader row as a comment instance."
  (make-instance 'comment
                 :id (first row)
                 :author (second row)
                 :content (third row)
                 :created-at (fourth row)))

(defun insert-comment (db author content)
  "One comment, through the extended query protocol: $1 and $2 are
   placeholders, so what the client sent stays data and never becomes SQL.
   `returning` hands back the row the server actually stored, id and timestamp
   included, which is what the 201 body has to carry."
  (cl-postgres:prepare-query db "insert-comment"
   (concatenate 'string
    "insert into comments (author, content) values ($1, $2) returning "
    *comment-columns*))
  (row-comment
   (first
    (cl-postgres:exec-prepared db "insert-comment" (list author content)
                               'cl-postgres:list-row-reader))))

(defun count-comments (db)
  (first
   (first
    (cl-postgres:exec-query db "select count(*)::int from comments"
                            'cl-postgres:list-row-reader))))

(defun page-comments (db limit offset direction)
  "One page, newest or oldest first. DIRECTION is spliced into the statement
   rather than passed as a parameter -- an ORDER BY direction cannot be one --
   and that is safe here only because it is never the client's text: the caller
   has already turned the sort parameter into one of exactly two literals.
   id breaks the tie so two comments of the same instant keep a total order."
  (cl-postgres:prepare-query db "page-comments"
                             (concatenate 'string "select " *comment-columns*
                                          " from comments order by created_at "
                                          direction ", id " direction
                                          " limit $1 offset $2"))
  (mapcar #'row-comment
          (cl-postgres:exec-prepared db "page-comments" (list limit offset)
                                     'cl-postgres:list-row-reader)))

(defun delete-comment (db id)
  "True when a row was deleted. `returning` is what tells 204 from 404: a
   delete that matched nothing reports no rows rather than an error."
  (cl-postgres:prepare-query db "delete-comment"
                             "delete from comments where id = $1 returning id")
  (and (cl-postgres:exec-prepared db "delete-comment" (list id)
                                  'cl-postgres:list-row-reader) t))

;;; --- reading the request -----------------------------------------------------

(defun positive-integer (text)
  "TEXT as a positive integer, or nil when it is not one. :junk-allowed stops
   parse-integer from signalling on its own terms, so the whole of TEXT has to
   be consumed for the number to count -- a 20x is a typo, not 20."
  (when (stringp text)
    (multiple-value-bind (n end) (parse-integer text :junk-allowed t)
      (when (and n (= end (length text)) (> n 0)) n))))

;; tiny-routes interns a query key with (intern key :keyword) and does NOT
;; upcase it, so ?page=2 arrives under the keyword whose name is the three
;; lower-case letters -- spelled :|page| here, because the reader would upcase
;; a bare :page and the lookup would miss. A path token is the other way round
;; (the matcher upcases it), which is why :commentId below needs no escape.
(defun query-param (req name default)
  (getf (tiny:request-get req :query-parameters) name default))

(defun json-object (text)
  "TEXT as a JSON object, or nil when it is not one. json-parse signals on
   malformed input, so this handler-case is what makes a broken body a 400
   rather than a 500."
  (when (and (stringp text) (> (length text) 0))
    (let ((value (handler-case (rontolisp:json-parse text) (error () nil))))
      (and (hash-table-p value) value))))

(defun non-empty-string-p (value) (and (stringp value) (string/= value "")))

;;; --- the handlers ------------------------------------------------------------

(defun create-comment (req)
  (let* ((body (json-object (tiny:request-body req "")))
         (author (and body (gethash "author" body)))
         (content (and body (gethash "content" body))))
    (cond
     ((null body) (problem req 400 "the request body is not a JSON object"))
     ((not (non-empty-string-p author))
      (problem req 400 "author is required and must be a non-empty string"))
     ((not (non-empty-string-p content))
      (problem req 400 "content is required and must be a non-empty string"))
     (t (let ((created
               (insert-comment (tiny:request-get req :db) author content)))
          ;; 201 + Location: the client learns where the comment it just
          ;; posted now lives.
          (tiny:created
           (concatenate 'string "/api/v1/comments/" (slot-value created 'id))
           (json-body created)))))))

(defun list-comments (req)
  (let* ((page-text (query-param req :|page| "1"))
         (limit-text (query-param req :|limit| "20"))
         (sort-text (query-param req :|sort| "newest"))
         (page (positive-integer page-text))
         (limit (positive-integer limit-text))
         ;; Two literals, chosen here and never taken from the request -- see
         ;; page-comments.
         (direction
          (cond ((string= sort-text "newest") "desc")
                ((string= sort-text "oldest") "asc"))))
    (cond ((null page)
           (problem req 400
            (format nil "page must be a positive integer, not ~s" page-text)))
          ((null limit)
           (problem req 400
            (format nil "limit must be a positive integer, not ~s" limit-text)))
          ((> limit 100)
           (problem req 400
                    (format nil "limit must be 100 or less, not ~a" limit)))
          ((null direction)
           (problem req 400
            (format nil "sort must be newest or oldest, not ~s" sort-text)))
          (t (let* ((db (tiny:request-get req :db))
                    (total (count-comments db))
                    (total-pages (ceiling total limit))
                    ;; A page past the end is not an error: it is an empty page,
                    ;; which is what a client walking the list has to handle
                    ;; anyway.
                    (rows
                     (page-comments db limit (* (- page 1) limit) direction)))
               (tiny:ok
                (json-body
                 (make-instance 'comment-page
                                ;; A vector, not a list: json-stringify renders nil as false,
                                ;; so an empty LIST of comments would ship as "data": false
                                ;; where the empty vector is the "data": [] a client expects.
                                :data (coerce rows 'vector)
                                :pagination
                                (make-instance 'pagination
                                 :total-comments total
                                 :total-pages total-pages
                                 :current-page page
                                 :limit limit
                                 :has-next-page (< page total-pages)
                                 :has-prev-page (> page 1))))))))))

(defun destroy-comment (req)
  (let* ((id-text (tiny:path-parameter req :commentId))
         (id (positive-integer id-text)))
    ;; An id that is not a number is not a bad request, it is a comment that
    ;; does not exist -- the same 404 a well-formed id nobody posted gets.
    (if (and id (delete-comment (tiny:request-get req :db) id))
        (tiny:no-content)
        (problem req 404 (format nil "no comment with id ~s" id-text)))))

(defun method-not-allowed (req allowed)
  "405 naming the methods the path does have, in the header the status is
   defined with."
  (tiny:clone-response
   (problem req 405 (format nil "~a accepts ~a" (tiny:path-info req) allowed))
   :headers (list :allow allowed)))

;;; --- middleware --------------------------------------------------------------

(defun wrap-connection (handler)
  "Middleware: one connection per request, handed to the handler as :db and
   closed even if a query signals. A connection is a single conversation with
   the server, and the interpreter/JVM servers run every request on its own
   thread, so requests that genuinely overlap stay isolated."
  (lambda (req)
    (let ((db (connect)))
      (unwind-protect (funcall handler (tiny:request-append req :db db))
        (cl-postgres:close-database db)))))

(defun wrap-errors (handler)
  "Middleware: whatever the handler did not foresee becomes the same error
   document as everything else. Without it a signalling handler is a dropped
   connection, which tells the client nothing."
  (lambda (req)
    (handler-case (funcall handler req)
      (error (c) (problem req 500 (format nil "~a" c))))))

(defun wrap-json-content-type (handler)
  "Middleware: label every response that HAS a body as JSON. That last clause
   is why this is not tiny:wrap-response-content-type -- a 204 carries no body
   and must not claim a type for it. A nil response is a route DECLINING, and
   passes through untouched so the next route still sees it."
  (lambda (req)
    (let ((res (funcall handler req)))
      (if (and res (tiny:response-body res))
          (tiny:clone-response res
                               :headers (append
                                         (list :content-type "application/json")
                                         (tiny:response-headers res)))
          res))))

;;; --- the routes --------------------------------------------------------------

;; Each route answers the one method it names and declines every other, so a
;; request that reaches the fallbacks below either used the wrong method on a
;; path that exists or asked for a path that does not.
(tiny:define-routes *comment-routes*
  (tiny:define-post "/api/v1/comments" (req) (create-comment req))
  (tiny:define-get "/api/v1/comments" (req) (list-comments req))
  (tiny:define-delete "/api/v1/comments/:commentId" (req)
    (destroy-comment req)))

(tiny:define-routes *fallback-routes*
  (tiny:define-any "/api/v1/comments" (req)
    (method-not-allowed req "GET, POST"))
  (tiny:define-any "/api/v1/comments/:commentId" (req)
    (method-not-allowed req "DELETE"))
  (tiny:define-any "*" (req)
    (problem req 404 (format nil "no resource at ~a" (tiny:path-info req)))))

;; wrap-post-match-middleware, not pipe: a POST-MATCH middleware runs only once
;; a route has claimed the request by method AND path. Piped in the ordinary
;; way, wrap-connection would open a connection for every request that merely
;; passes THROUGH this group on its way to a 404.
(defparameter *routes*
  (tiny:routes (tiny:pipe *comment-routes*
                          (tiny:wrap-post-match-middleware #'wrap-connection))
               *fallback-routes*))

;; pipe reads inside out: the routes are wrapped by wrap-errors first, so the
;; content type and the parsed request reach even a 500.
(defparameter *app*
  (tiny:pipe *routes* (wrap-errors) (wrap-json-content-type)
             (tiny:wrap-request-body) (tiny:wrap-query-parameters)))

(clack:clackup *app* :server :rontolisp :port 8080 :use-thread nil)
