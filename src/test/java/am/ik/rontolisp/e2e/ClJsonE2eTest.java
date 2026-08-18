package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): the REAL cl-json sources
 * (vendored unmodified under {@code src/test/resources/cl-json}, MIT, quicklisp dist
 * cl-json-20220707-git) load via {@code asdf:load-system} and DECODE on all four backends
 * via {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * cl-json is the consumer that drove {@code progv} onto the compile paths (todo-423,
 * {@code .kb/dynamic-special-variables.md}): its decoder's {@code aggregate-scope-progv}
 * macro expands to {@code (progv variables (mapcar #'symbol-value variables) ...)} around
 * every array, object and string it decodes, so before the lowering any program loading
 * cl-json failed to compile whole on all three compiled backends. The exercise therefore
 * decodes NESTED aggregates -- each nesting level is another dynamic re-binding of the
 * scope variables, and the {@code #'symbol-value} snapshot must see the accumulator
 * {@code setf}s made inside the enclosing extent (the dynamic-first {@code symbol-value}
 * rule). The expected alists are pinned against SBCL running the same vendored sources.
 *
 * <p>
 * Decoding floats is deliberately absent: cl-json's {@code parse-number} computes
 * {@code (expt 10 exponent)} with a runtime float exponent, which the compile backends'
 * integer-only {@code expt} fast path cannot take yet -- a pre-existing numeric gap this
 * library merely exposes (recorded in {@code .todo/}, the expt runtime-float-exponent
 * item), unrelated to progv.
 */
class ClJsonE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-json").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-json)
			(print (json:decode-json-from-string "{\\"a\\":[1,{\\"b\\":2}]}"))
			(print (json:decode-json-from-string "[\\"two\\",true,false,null,{\\"k\\":[]},[3,[4]]]"))
			(print (json:decode-json-from-string "{\\"camelCase\\":{\\"x\\":10},\\"list\\":[{\\"y\\":20},30]}"))
			""";

	private static final List<String> EXPECTED = List.of("((:A 1 ((:B . 2))))", "(\"two\" T NIL NIL ((:K)) (3 (4)))",
			"((:CAMEL-CASE (:X . 10)) (:LIST ((:Y . 20)) 30))");

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
		return "ClJsonE2e";
	}

}
