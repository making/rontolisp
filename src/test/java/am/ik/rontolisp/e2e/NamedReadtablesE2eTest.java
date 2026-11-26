package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * named-readtables 0.9 (vendored unmodified under
 * {@code src/test/resources/named-readtables}, BSD) on all four backends
 * ({@code .kb/readtables.md}): the system loads -- its readtable iterator is the derived
 * form {@code eval/NamedReadtablesForms} on the interpreter, and its {@code defpackage}
 * under a handler-less {@code handler-bind} reaches the compile path's resolver --
 * {@code defreadtable} merges the standard readtable into a new one and adds a macro
 * character and a case, and a loaded file that selects it with {@code in-readtable} reads
 * the rest of itself with it, the readtable dying with the load. The compile paths read
 * that file with the readtable the compile-time evaluator holds, which is why the
 * {@code defreadtable} sits in the {@code eval-when} its own documentation asks for.
 */
class NamedReadtablesE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "named-readtables")
		.toAbsolutePath()
		.toString();

	private static final String USER_FILE = Path.of("src", "test", "resources", "named-readtables-user", "user.lisp")
		.toAbsolutePath()
		.toString()
		.replace("\\", "\\\\");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return """
				(asdf:load-system :named-readtables)
				(eval-when (:compile-toplevel :load-toplevel :execute)
				  (named-readtables:defreadtable nr-e2e
				    (:merge :standard)
				    (:macro-char #\\! (lambda (s c) (declare (ignore c)) (list 'quote (list :bang (read s t nil t)))))
				    (:case :invert)))
				(load "%s")
				(print *read*)
				(print (named-readtables:readtable-name (named-readtables:find-readtable 'nr-e2e)))
				(print (list (readtable-case *readtable*) (get-macro-character #\\!)))
				(print (eq (named-readtables:find-readtable :standard) (named-readtables:find-readtable nil)))
				""".formatted(USER_FILE);
	}

	@Override
	protected List<String> expected() {
		return List.of("((:BANG FOO) '(:BANG (A B)) |Mixed| |Mixed|)", "NR-E2E", "(:UPCASE NIL)", "T");
	}

	@Override
	protected String artifactName() {
		return "NamedReadtablesE2e";
	}

}
