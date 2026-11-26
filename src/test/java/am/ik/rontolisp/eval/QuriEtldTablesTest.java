package am.ik.rontolisp.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link QuriEtldTables} rewrite of quri's {@code src/etld.lisp}: the eager
 * read-time table build becomes a lazy one, the data file's path becomes a literal (so
 * the with-open-file fold can bundle it), and every marker is required to occur exactly
 * once.
 */
class QuriEtldTablesTest {

	// The spans this rewrite depends on, verbatim from quri-20260101-git.
	private static final String SOURCE = """
			(in-package :quri.etld)

			(eval-when (:compile-toplevel :load-toplevel :execute)
			  (defvar *default-etld-names*
			    #.(asdf:system-relative-pathname :quri #P"data/effective_tld_names.dat"))

			  (defun load-etld-data (&optional (etld-names-file *default-etld-names*))
			    (with-open-file (in etld-names-file
			                        :element-type #+lispworks :default #-lispworks 'character
			                        :external-format #+clisp charset:utf-8 #-clisp :utf-8)
			      (loop for line = (read-line in nil nil) while line collect line))))

			(defvar *etlds*
			    #+(or abcl (and ecl win32 msvc)) (load-etld-data)
			    #-(or abcl (and ecl win32 msvc)) '#.(load-etld-data))

			(defun parse-domain (hostname)
			  (dolist (tld (third *etlds*))
			    (when (ends-with-subseq tld hostname) (return-from parse-domain hostname)))
			  (loop for subdomain = (funcall iter)
			        while subdomain
			        if (gethash subdomain (second *etlds*)) do
			          (return pre-prev-subdomain)
			        else if (gethash subdomain (first *etlds*)) do
			          (return prev-subdomain)))
			""";

	@Test
	void onlyTheEtldComponentIsRewritten() {
		assertThat(QuriEtldTables.rewrite("src/quri.lisp", SOURCE, "/sw/quri")).isNull();
	}

	@Test
	void theReadTimeTableBuildBecomesALazyBuilder() {
		String out = QuriEtldTables.rewrite(QuriEtldTables.COMPONENT, SOURCE, "/sw/quri");
		assertThat(out).isNotNull();
		// The un-emittable hash-table literal is gone: no backend has to quote it.
		assertThat(out).doesNotContain("'#.(load-etld-data)")
			.contains("(defvar *etlds* nil)")
			.contains("(defun %lite-build-etlds ()\n  (setq *etlds* (load-etld-data)))");
	}

	@Test
	void everyTableReadForcesTheBuilder() {
		String out = QuriEtldTables.rewrite(QuriEtldTables.COMPONENT, SOURCE, "/sw/quri");
		assertThat(out).isNotNull();
		// All three reads in parse-domain, and only those: the defvar itself must stay
		// a plain nil binding or `or` would short-circuit on it forever.
		assertThat(out).contains("(third (or *etlds* (%lite-build-etlds)))")
			.contains("(second (or *etlds* (%lite-build-etlds)))")
			.contains("(first (or *etlds* (%lite-build-etlds)))");
		assertThat(out.split("%lite-build-etlds", -1).length - 1).isEqualTo(4);
	}

	@Test
	void theDataPathBecomesALiteralAndTheElementTypeIsDropped() {
		String out = QuriEtldTables.rewrite(QuriEtldTables.COMPONENT, SOURCE, "/sw/quri");
		assertThat(out).isNotNull();
		// A literal path with only :external-format left is exactly the shape
		// CompileTimePathnameFolder bundles into a with-input-from-string; the
		// :element-type option is what used to suppress it.
		assertThat(out).contains("(with-open-file (in \"/sw/quri/data/effective_tld_names.dat\"")
			.doesNotContain(":element-type");
	}

	@Test
	void aMovedMarkerIsALoudError() {
		// An upstream release that reshapes one of the spans must fail naming the
		// marker, not fall back to a source whose #. no backend can emit.
		String moved = SOURCE.replace("(defvar *etlds*\n", "(defvar *etlds* ; reshaped\n");
		assertThatThrownBy(() -> QuriEtldTables.rewrite(QuriEtldTables.COMPONENT, moved, "/sw/quri"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exactly one occurrence")
			.hasMessageContaining("QuriEtldTables");
	}

	@Test
	void aDuplicatedMarkerIsALoudErrorToo() {
		String twice = SOURCE + "\n(dolist (tld (third *etlds*)) nil)\n";
		assertThatThrownBy(() -> QuriEtldTables.rewrite(QuriEtldTables.COMPONENT, twice, "/sw/quri"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exactly one occurrence");
	}

}
