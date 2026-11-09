;; Shiori: the connection to PostgreSQL and the query helpers every page uses.

;;; --- the database ------------------------------------------------------------

(defun tagged-postgresql-p (instance)
  "Whether a VCAP_SERVICES entry carries the postgresql tag."
  (let ((tags (gethash "tags" instance)))
    (and (vectorp tags) (find "postgresql" tags :test #'equal))))

(defun vcap-database-url (vcap-services)
  "The credentials' uri of the first service tagged postgresql in
   VCAP_SERVICES, or nil. That is what Cloud Foundry gives an app bound to a
   user-provided service (cf create-user-provided-service ... -p
   '{\"uri\":\"postgres://...\"}' -t postgresql) or to a broker's PostgreSQL,
   whatever the offering is called."
  (when (and vcap-services (string/= vcap-services ""))
    (let ((services (rontolisp:json-parse vcap-services)))
      (when (hash-table-p services)
        (maphash (lambda (offering instances)
                   (declare (ignore offering))
                   (when (vectorp instances)
                     (loop for instance across instances
                           do
                             (when (and (hash-table-p instance)
                                        (tagged-postgresql-p instance))
                               (let ((credentials
                                      (gethash "credentials" instance)))
                                 (when (hash-table-p credentials)
                                   (let ((uri (gethash "uri" credentials)))
                                     (when (stringp uri)
                                       (return-from vcap-database-url
                                                    uri))))))))) services)
        nil))))

(defun database-url ()
  "Where the database is: a service tagged postgresql in VCAP_SERVICES, else
   DATABASE_URL."
  (or (vcap-database-url (uiop:getenv "VCAP_SERVICES"))
      (uiop:getenv "DATABASE_URL")))

(defun connect ()
  "A connection to the database. One per request -- see wrap-connection."
  (multiple-value-bind (database user password host port)
      (database-url-parts (database-url))
    (cl-postgres:open-database database user password host port)))

(defun column-key (name)
  "A column name as the keyword a template looks up: current_page becomes
   :current-page, so {{current-page}} finds it."
  (intern (substitute #\- #\_ (string-upcase name)) :keyword))

;; Rows as alists a template can read directly. SQL NULL arrives as :null,
;; which a Mustache section would treat as TRUE; nil is what it needs.
(cl-postgres:def-row-reader template-row-reader (fields)
  (loop while (cl-postgres:next-row)
        collect
          (loop for field across fields
                collect
                  (let ((value (cl-postgres:next-field field)))
                    (cons (column-key (cl-postgres:field-name field))
                          (if (eq value :null) nil value))))))

(defun query (db sql &rest params)
  "Rows of SQL with PARAMS bound to $1, $2 ... A value is never spliced into
   the text: the statement is parsed by the server first (the unnamed prepared
   statement) and the values travel separately. nil binds as SQL NULL."
  (cl-postgres:prepare-query db "" sql)
  (cl-postgres:exec-prepared db ""
                             (mapcar (lambda (p) (if (null p) :null p)) params)
                             'template-row-reader))

(defun query-row (db sql &rest params) (first (apply #'query db sql params)))

(defun query-value (db sql &rest params)
  (cdr (first (apply #'query-row db sql params))))

(defmacro with-transaction ((db) &body body)
  "BODY in one transaction: committed when it returns, rolled back when it
   exits any other way."
  (let ((done (gensym "DONE")))
    `(let ((,done nil))
       (cl-postgres:exec-query ,db "begin")
       (unwind-protect (multiple-value-prog1 (progn ,@body) (setf ,done t))
         (cl-postgres:exec-query ,db (if ,done "commit" "rollback"))))))

(defmacro with-database ((db) &body body)
  `(let ((,db (connect)))
     (unwind-protect (progn ,@body) (cl-postgres:close-database ,db))))
