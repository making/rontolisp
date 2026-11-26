package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * with-user-abort 0.1 (vendored unmodified under
 * {@code src/test/resources/with-user-abort}, BSD) on all four backends via
 * {@link AsdfLibraryE2eSupport}. Its per-implementation condition is the derived form
 * {@code eval/WithUserAbortForms} ({@code .kb/asdf.md}, tier 4), whose marker this pins
 * against the release. The program signals the condition itself, since only the
 * interpreter receives SIGINT ({@code InterruptSignalTest}). Every line is SBCL 2.2.9's
 * answer to the same program with {@code sb-sys:interactive-interrupt}.
 */
class WithUserAbortE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "with-user-abort")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :with-user-abort)
			(print (list (subtypep 'with-user-abort:user-abort 'rontolisp:interactive-interrupt)
			             (subtypep 'with-user-abort:user-abort 'serious-condition)
			             (subtypep 'with-user-abort:user-abort 'error)))
			(print (handler-case
			           (with-user-abort:with-user-abort (signal 'rontolisp:interactive-interrupt) :ran-through)
			         (with-user-abort:user-abort (c) (list :aborted (type-of c)))))
			(print (with-user-abort:with-user-abort (signal 'rontolisp:interactive-interrupt) :declined))
			(print (with-user-abort:with-user-abort :untouched))
			(print (let ((log nil))
			         (handler-case
			             (unwind-protect
			                  (with-user-abort:with-user-abort
			                    (signal 'rontolisp:interactive-interrupt))
			               (push :cleanup log))
			           (with-user-abort:user-abort () (push :handled log)))
			         (reverse log)))
			""";

	private static final List<String> EXPECTED = List.of("(T T NIL)", "(:ABORTED WITH-USER-ABORT:USER-ABORT)",
			":DECLINED", ":UNTOUCHED", "(:CLEANUP :HANDLED)");

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
		return "WithUserAbortE2e";
	}

}
