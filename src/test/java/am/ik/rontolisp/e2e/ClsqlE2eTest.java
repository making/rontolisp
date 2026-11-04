package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * clsql 6.4's core (vendored under {@code src/test/resources/clsql}: the release's
 * {@code clsql.asd} and {@code sql/} without the clisp-only {@code ansi-loop.lisp},
 * LLGPL) on all four backends via {@link AsdfLibraryE2eSupport}. Its MOP package and
 * method shapes are the derived forms of {@code eval/ClsqlForms} and {@code clsql.asd}
 * the replacement {@code clsql-closer-mop.asd} ({@code .kb/asdf.md}), whose markers this
 * pins against the release. The program defines a database type of its own that prints
 * every statement, so no foreign library is involved and the SQL a view class generates
 * is what is compared. Every line is SBCL 2.2.9's answer to the same program over the
 * real {@code clsql.asd}.
 */
class ClsqlE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "clsql").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :clsql)
			(defclass fake-database (clsql-sys:database) ())
			(defvar *rows* nil)
			(defvar *fields* nil)
			(defmethod clsql-sys:database-type-library-loaded ((type (eql :fake))) t)
			(defmethod clsql-sys:database-initialize-database-type ((type (eql :fake))) t)
			(defmethod clsql-sys:database-name-from-spec (spec (type (eql :fake))) (first spec))
			(defmethod clsql-sys:database-connect (spec (type (eql :fake)))
			  (make-instance 'fake-database :name (first spec) :database-type :fake
			                 :connection-spec spec :encoding :utf-8))
			(defmethod clsql-sys:database-disconnect ((database fake-database)) t)
			(defmethod clsql-sys:database-execute-command (sql (database fake-database)) (print sql) t)
			(defmethod clsql-sys:database-query (sql (database fake-database) result-types field-names)
			  (print sql)
			  (values *rows* (when field-names *fields*)))
			(push :fake clsql-sys::*loaded-database-types*)
			(defvar *db* (clsql:connect '("mem") :database-type :fake))
			(print (list (clsql:database-name *db*) (clsql:database-type *db*) (eq *db* clsql:*default-database*)))

			(print (clsql:sql (clsql:sql-expression :table 'people :attribute 'name)))
			(print (clsql:sql "it's" 1 2.5 nil 'foo))
			(print (clsql:select (clsql:sql-expression :attribute 'a) :from (clsql:sql-expression :table 't)
			                     :where (clsql:sql-operation '> (clsql:sql-expression :attribute 'a) 2)
			                     :order-by (list (clsql:sql-expression :attribute 'a)) :flatp t))
			(setq *rows* '((1 "one") (2 "two")) *fields* '("a" "b"))
			(print (multiple-value-list (clsql:query "select a, b from t" :field-names t)))
			(clsql:insert-records :into (clsql:sql-expression :table 't)
			                      :attributes '(a b) :values '(3 "it's"))
			(clsql:with-transaction ()
			  (clsql:execute-command "insert into t values (9)"))
			(print (clsql:iso-timestring (clsql:time+ (clsql:make-time :year 2024 :month 2 :day 28)
			                                          (clsql:make-duration :day 2))))

			;; A view class: the metaclass, its slot definitions and the DDL they give.
			(clsql:def-view-class dept ()
			  ((id :db-kind :key :db-constraints :not-null :type integer :initarg :id :accessor dept-id)
			   (title :type (string 20) :initarg :title :accessor dept-title)))
			(clsql:def-view-class person ()
			  ((id :db-kind :key :db-constraints (:not-null) :type integer :initarg :id :accessor person-id)
			   (name :type (clsql:varchar 30) :initarg :name :accessor person-name)
			   (age :type integer :initarg :age :initform 7 :accessor person-age)
			   (score :type float :column "points" :initarg :score :accessor person-score)
			   (note :db-kind :virtual :initform "v" :accessor person-note)
			   (dept-id :type integer :initarg :dept-id :accessor person-dept-id)
			   (dept :db-kind :join :accessor person-dept
			         :db-info (:join-class dept :home-key dept-id :foreign-key id :set nil :retrieval :immediate)))
			  (:base-table people))
			(print (class-name (class-of (find-class 'person))))
			(print (clsql-sys::view-table (find-class 'person)))
			(print (mapcar #'clsql-sys::slot-definition-name (clsql-sys::key-slots (find-class 'person))))
			(dolist (slot (clsql-sys::ordered-class-slots (find-class 'person)))
			  (print (list (clsql-sys::slot-definition-name slot)
			               (clsql-sys::view-class-slot-db-kind slot)
			               (clsql-sys::specified-type slot))))
			(clsql:create-view-from-class 'person)
			(clsql:create-view-from-class 'dept)
			(let ((p (make-instance 'person :id 1 :name "Ann" :age 30 :score 1.5d0 :dept-id 2)))
			  (print (list (person-note p) (clsql-sys::view-database p)))
			  (clsql:update-records-from-instance p)
			  (print (eq *db* (clsql-sys::view-database p)))
			  (setf (person-age p) 31)
			  (clsql:update-record-from-slot p 'age)
			  (clsql:delete-instance-records p))

			;; Instances read back: the SELECT a class names and the columns it converts.
			(setq *rows* '((1 "Ann" 30 1.5d0 2)
			               (2 "Bob" nil 2.5d0 2)))
			(let ((people (clsql:select 'person :flatp t :caching nil
			                            :order-by (clsql:sql-expression :attribute 'id))))
			  (print (mapcar #'person-name people))
			  (print (mapcar #'person-age people))
			  (print (mapcar #'person-note people)))
			(setq *rows* nil)
			(print (clsql:select 'dept :flatp t :caching nil))

			(print (handler-case (error 'clsql:sql-database-data-error :database *db* :expression "select"
			                            :error-id 1 :message "bad")
			         (clsql:sql-database-error (c)
			           (list (type-of c) (clsql:sql-error-expression c) (clsql:sql-error-database-message c)))))
			(print (list (clsql:disconnect) (clsql:connected-databases) clsql:*default-database*))
			""";

	private static final List<String> EXPECTED = List.of("(\"mem\" :FAKE T)", "\"PEOPLE.NAME\"",
			"\"'it''s' 1 2.5 NULL FOO\"", "\"SELECT A FROM T WHERE (A > 2) ORDER BY A\"", "NIL",
			"\"select a, b from t\"", "(((1 \"one\") (2 \"two\")) (\"a\" \"b\"))",
			"\"INSERT INTO T (A,B) VALUES (3,'it''s')\"", "\"BEGIN\"", "\"insert into t values (9)\"", "\"COMMIT\"",
			"\"2024-03-01T00:00:00\"", "CLSQL-SYS::STANDARD-DB-CLASS", "PEOPLE", "(ID)", "(ID :KEY INTEGER)",
			"(NAME :BASE (CLSQL-SYS:VARCHAR 30))", "(AGE :BASE INTEGER)", "(SCORE :BASE FLOAT)", "(NOTE :VIRTUAL NIL)",
			"(DEPT-ID :BASE INTEGER)", "(DEPT :JOIN NIL)", "(CLSQL-SYS::VIEW-DATABASE :VIRTUAL NIL)",
			"\"CREATE TABLE PEOPLE (ID INT NOT NULL , NAME VARCHAR(30), AGE INT, \\\"points\\\" FLOAT, DEPT_ID INT, CONSTRAINT PEOPLE_PK PRIMARY KEY (ID))\"",
			"\"CREATE TABLE DEPT (ID INT NOT NULL , TITLE VARCHAR(20), CONSTRAINT DEPT_PK PRIMARY KEY (ID))\"",
			"(\"v\" NIL)", "\"INSERT INTO PEOPLE (ID,NAME,AGE,\\\"points\\\",DEPT_ID) VALUES (1,'Ann',30,'1.5',2)\"",
			"T", "\"UPDATE PEOPLE SET AGE = 31 WHERE PEOPLE.ID = 1\"", "\"DELETE FROM PEOPLE WHERE PEOPLE.ID = 1\"",
			"\"SELECT PEOPLE.ID,PEOPLE.NAME,PEOPLE.AGE,PEOPLE.\\\"points\\\",PEOPLE.DEPT_ID,DEPT.ID,DEPT.TITLE FROM PEOPLE,DEPT WHERE DEPT.ID = PEOPLE.DEPT_ID ORDER BY PEOPLE.ID\"",
			"(\"Ann\" \"Bob\")", "(30 NIL)", "(\"v\" \"v\")", "\"SELECT DEPT.ID,DEPT.TITLE FROM DEPT\"", "NIL",
			"(CLSQL-SYS:SQL-DATABASE-DATA-ERROR \"select\" \"bad\")", "(T NIL NIL)");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "ClsqlE2e";
	}

}
