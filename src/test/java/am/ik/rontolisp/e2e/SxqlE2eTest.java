package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL sxql sources
 * (vendored unmodified under {@code src/test/resources/sxql}, BSD 3-Clause; quicklisp
 * dist sxql-20260101-git) load via {@code asdf:load-system} over the vendored trivia (the
 * {@code trivia.trivial} route), alexandria, lisp-namespace and cl-package-locks
 * (vendored with it, BSD), and {@code sxql:yield} produces the PINNED SQL text of the
 * shapes mito emits ({@code .todo/238}) on ALL FOUR backends via
 * {@link AsdfLibraryE2eSupport}. Every expected line was verified against the same
 * sources on SBCL 2.2.9 (byte-identical), so the pin is upstream's own answer, not a
 * rontolisp-flavored one.
 *
 * <p>
 * The exercise walks the acceptance list of {@code .todo/244}: {@code select} with
 * {@code from}/{@code where} (incl. {@code :and}/{@code :or}/{@code :in}/{@code :like}),
 * {@code order-by} with {@code :desc}, {@code limit}/{@code offset}, {@code left-join}
 * with {@code :on}, {@code insert-into} with {@code set=}, {@code update},
 * {@code delete-from}, {@code create-table} with column options (the mito
 * {@code deftable} output shape), {@code drop-table}, {@code alter-table} with
 * {@code add-column}, and placeholder binding -- every {@code yield} goes through
 * {@code multiple-value-bind}, pinning the SQL string plus the bind-value list as
 * multiple values at the call sites.
 *
 * <p>
 * Substrate the load exercises, each landed for this milestone: the {@code package} type
 * specifier (cl-package-locks' {@code resolve-package} etypecase), case/ecase clause KEY
 * LISTS resolving like quoted data (define-op's struct-type ecase),
 * {@code uiop:split-string} (the sql-symbol tokenizer), a {@code #.} whose value is a
 * SYMBOL splicing as the object in code position ({@code (intern name #.*package*)}),
 * {@code delete-duplicates} with {@code :from-end}, {@code call-next-method} across a
 * struct {@code :include} chain plus {@code structure-object} methods and dispatcher
 * regeneration on a later defstruct (convert-for-sql), and the runtime slot-name
 * normalization ({@code compute-select-statement-children} reading struct slots through a
 * quoted clause-type list).
 */
class SxqlE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "sxql").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system "sxql")
			(defun show (q)
			  (multiple-value-bind (sql bind) (sxql:yield q)
			    (print sql)
			    (print bind)))
			(show (sxql:select :* (sxql:from :users)
			        (sxql:where (:and (:= :status "active")
			                          (:or (:in :role '("admin" "mod")) (:like :name "a%"))))
			        (sxql:order-by (:desc :created-at))
			        (sxql:limit 10)
			        (sxql:offset 20)))
			(show (sxql:select (:users.id :items.name)
			        (sxql:from :users)
			        (sxql:left-join :items :on (:= :users.id :items.user-id))))
			(show (sxql:select :* (sxql:from :users)
			        (sxql:where (:= :a 1))
			        (sxql:where (:= :b 2))))
			(show (sxql:insert-into :users (sxql:set= :name "taro" :age 30)))
			(show (sxql:update :users (sxql:set= :age 31) (sxql:where (:= :name "taro"))))
			(show (sxql:delete-from :users (sxql:where (:= :id 1))))
			(show (sxql:create-table :users
			        ((id :type 'bigint :primary-key t :auto-increment t)
			         (name :type '(:varchar 64) :not-null t)
			         (age :type 'integer :default 0))))
			(show (sxql:drop-table :users))
			(show (sxql:alter-table :users
			        (sxql:add-column :email :type '(:varchar 128) :not-null t)))
			(print (sxql:select :* (sxql:from :users) (sxql:where (:= :id 1))))
			(print (sxql:sql-compile (sxql:select :* (sxql:from :users) (sxql:where (:= :id 2)))))
			""";

	private static final List<String> EXPECTED = List.of(
			"\"SELECT * FROM users WHERE ((status = ?) AND ((role IN (?, ?)) OR (name LIKE ?))) ORDER BY created-at DESC LIMIT 10 OFFSET 20\"",
			"(\"active\" \"admin\" \"mod\" \"a%\")",
			"\"SELECT users.id, items.name FROM users LEFT JOIN items ON (users.id = items.user-id)\"", "NIL",
			// a SECOND where merges (where-clause is a multiple-allowed-clause --
			// the runtime (subtypep (type-of clause) 'multiple-allowed-clause) over
			// a deftype'd or of struct :include ancestry)
			"\"SELECT * FROM users WHERE ((a = ?) AND (b = ?))\"", "(1 2)",
			"\"INSERT INTO users (name, age) VALUES (?, ?)\"", "(\"taro\" 30)",
			"\"UPDATE users SET age = ? WHERE (name = ?)\"", "(31 \"taro\")", "\"DELETE FROM users WHERE (id = ?)\"",
			"(1)",
			// print of the multi-line CREATE TABLE string spans five physical lines;
			// the driver trims each.
			"\"CREATE TABLE users (", "id BIGINT AUTO_INCREMENT PRIMARY KEY,", "name VARCHAR(64) NOT NULL,",
			"age INTEGER DEFAULT ?", ")\"", "(0)", "\"DROP TABLE users\"", "NIL",
			"\"ALTER TABLE users ADD COLUMN email VARCHAR(128) NOT NULL\"", "NIL",
			// print-object on statement structs (sql-type.lisp) and on the
			// define-compile-struct products (compile.lisp's #.*package* symbolcat)
			"#<SXQL-STATEMENT: SELECT * FROM users WHERE (id = 1)>",
			"#<SXQL-COMPILED: SELECT * FROM users WHERE (id = ?) [2]>");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return List.of(Path.of("src", "test", "resources", "trivia").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "lisp-namespace").toAbsolutePath().toString(),
				Path.of("src", "test", "resources", "cl-package-locks").toAbsolutePath().toString());
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
		return "SxqlDemo";
	}

}
