package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin for the shape-driven prune: a {@code typecase} clause no call site's argument
 * can select leaves the program, and every case where the program does not decide that
 * keeps it. The shape of the test is clack's -- {@code clackup} dispatching on whether it
 * was handed a pathname or a function -- because that is the one that put
 * {@code %load-file} into every clack Worker.
 */
class DeadTypeBranchPrunerTest {

	/** clack's shape: the dispatch is inside a local function of the callee. */
	private static final String LIBRARY = """
			(defun eval-file (path) (probe-file path))
			(defun start (app)
			  (flet ((build (app)
			           (let ((app (typecase app
			                        ((or pathname string) (eval-file app))
			                        (otherwise app))))
			             app)))
			    (build app)))
			(defun handler () nil)
			""";

	private static String pruned(String source) {
		List<LispVal> program = DeadTypeBranchPruner.prune(LispReader.readAllFromString(source));
		StringBuilder out = new StringBuilder();
		program.forEach(form -> out.append(form.print()).append('\n'));
		return out.toString();
	}

	@Test
	void aFunctionArgumentTakesThePathnameClauseOutOfTheProgram() {
		// #'handler is neither a pathname nor a string, so the file loader behind that
		// clause is unreachable -- and the tree-shakers, which are name reachability, can
		// only see that once the clause is gone.
		String result = pruned(LIBRARY + "(start #'handler)\n");
		assertThat(result).doesNotContain("EVAL-FILE APP").contains("(TYPECASE APP (OTHERWISE APP))");
		// Only the clause goes: the definition it called is still there for anything else
		// that calls it, and dropping THAT is the tree-shaker's job.
		assertThat(result).contains("DEFUN EVAL-FILE");
	}

	@Test
	void aPathnameArgumentKeepsIt() {
		assertThat(pruned(LIBRARY + "(start \"app.lisp\")\n")).contains("EVAL-FILE APP");
	}

	@Test
	void twoCallSitesThatDisagreeKeepIt() {
		// The rewrite is whole-program, so one call that could take the clause is enough.
		assertThat(pruned(LIBRARY + "(start #'handler)\n(start \"app.lisp\")\n")).contains("EVAL-FILE APP");
	}

	@Test
	void aNameTakenAsAValueHasNoKnownCallSites() {
		// #'start / 'start reach funcall with arguments no call site in the program
		// spells, so its parameters state nothing.
		assertThat(pruned(LIBRARY + "(start #'handler)\n(defvar *f* #'start)\n")).contains("EVAL-FILE APP");
		assertThat(pruned(LIBRARY + "(start #'handler)\n(funcall 'start \"app.lisp\")\n")).contains("EVAL-FILE APP");
	}

	@Test
	void aProgramThatCanEvaluateDataPrunesNothing() {
		// (eval (read)) calls a function whose name is in the input, with arguments that
		// are in the input too -- the one case no scan of the source can bound.
		assertThat(pruned(LIBRARY + "(start #'handler)\n(eval (read))\n")).contains("EVAL-FILE APP");
	}

	@Test
	void aShapeTheLatticeDoesNotDecideKeepsEveryClause() {
		// A defclass name is not in the decided set, so an instance may be one -- and a
		// computed argument states nothing at all.
		String classes = """
				(defclass thing () ())
				(defun dispatch (x) (typecase x (thing :instance) (string :string) (otherwise :other)))
				""";
		assertThat(pruned(classes + "(dispatch (make-instance 'thing))\n")).contains("(THING :INSTANCE)")
			.doesNotContain("(STRING :STRING)");
		assertThat(pruned(classes + "(dispatch (compute))\n")).contains("(THING :INSTANCE)")
			.contains("(STRING :STRING)");
	}

}
