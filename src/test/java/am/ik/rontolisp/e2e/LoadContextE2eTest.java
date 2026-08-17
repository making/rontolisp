package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The load-context variables per SPLICED file ({@code .kb/load-inliner.md}): on all four
 * backends {@code *load-pathname*} / {@code *load-truename*} hold the file being loaded
 * while its top-level forms run, go back to the enclosing file's values after a nested
 * load, and are {@code nil} once the load has finished -- read from a function called
 * later as much as at top level. The interpreter BINDS them around each file; the compile
 * paths lower the {@code LoadInliner} brackets to assignments, so the two agree byte for
 * byte. An ASDF component is loaded by its resolved path, which is what makes its
 * {@code *load-pathname*} equal {@code asdf:component-pathname} -- the correlation rove's
 * suite-to-file map is built on.
 *
 * <p>
 * The context is established at READ time as well ({@code .todo/428}): a {@code #.} datum
 * runs before any top-level form of its own file, so the interpreter's binding and the
 * compile paths' lowered assignments are both too late for it --
 * {@code UserMacroExpander} establishes the {@code %begin-file} bracket's two values
 * around the file's forms instead, which is what makes the portable
 * {@code (or *compile-file-pathname* *load-truename*)} idiom find a data file shipping
 * beside the source that reads it. Fixture: {@code src/test/resources/load-context-demo}.
 */
class LoadContextE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "load-context-demo")
		.toAbsolutePath()
		.toString();

	// A relative path is enough for the plain load: it is resolved against the working
	// directory on both the interpreter and the compile path, so the value the program
	// records is the same short spelling on every machine.
	private static final String PLAIN_FILE = "src/test/resources/load-context-demo/plain.lisp";

	private static final String EXERCISE = """
			(asdf:load-system "lc-demo")
			(load "%s")
			(defun ends-with (s suffix)
			  (and (>= (length s) (length suffix))
			       (string= suffix s :start2 (- (length s) (length suffix)))))
			;; Outside every load both are nil -- at top level, and read at call time by
			;; a function the load defined.
			(print (list *load-pathname* *load-truename*))
			(print (one-context))
			;; A component records the path asdf:component-pathname answers for it, and
			;; its truename is the same path (ASDF loads a component by resolved path).
			(print (equal *one-pathname*
			              (asdf:component-pathname
			               (car (asdf:component-children (asdf:find-system "lc-demo"))))))
			(print (equal *one-pathname* *one-truename*))
			;; A nested load restores the enclosing file's context when it finishes.
			(print (equal *one-pathname* *one-pathname-after*))
			;; That nested load: the pathname is the spelling load was called with, the
			;; truename the path it resolved to (against the loading file's directory).
			(print *helper-pathname*)
			(print (ends-with *helper-truename* "/src/helper.lisp"))
			(print (ends-with *one-truename* "/src/one.lisp"))
			;; A plain top-level load resolves against the working directory, so both
			;; halves are the path as written.
			(print (list *plain-pathname* *plain-truename*))
			;; READ time: a #. datum inside a loaded file sees that file's context, and
			;; the value it captured is byte-identical with the run-time pair -- for a
			;; component, for a load nested inside one, and for a plain load.
			(print (equal *one-read-time* (format nil "~A|~A" *one-pathname* *one-truename*)))
			(print (equal *helper-read-time* (format nil "~A|~A" *helper-pathname* *helper-truename*)))
			(print (equal *plain-read-time* (format nil "~A|~A" *plain-pathname* *plain-truename*)))
			;; The idiom that motivated it: a data file read at read time from beside the
			;; source that ships it, through (or *compile-file-pathname* *load-truename*).
			(print *one-data*)
			""".formatted(PLAIN_FILE);

	private static final List<String> EXPECTED = List.of("(NIL NIL)", "(NIL NIL)", "T", "T", "T", "\"helper.lisp\"",
			"T", "T", "(\"" + PLAIN_FILE + "\" \"" + PLAIN_FILE + "\")", "T", "T", "T", "\"lc-demo-data\"");

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
		return "TestLoadContext";
	}

}
