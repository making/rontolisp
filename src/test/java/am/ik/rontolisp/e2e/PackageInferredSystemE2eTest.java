package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): a
 * {@code :class :package-inferred-system} loads on all four backends. The fixture under
 * {@code src/test/resources/package-inferred-demo} is written in the shape ningle and
 * rove have -- the {@code .asd} declares NO {@code :components} and names exactly one
 * sub-system, so everything else is reachable only by reading each component file's own
 * {@code defpackage}:
 *
 * <ul>
 * <li>{@code package-inferred-demo/main} -> {@code main.lisp}, whose
 * {@code uiop:define-package} is the only edge to the rest of the graph,</li>
 * <li>{@code package-inferred-demo/util/text} -> {@code util/text.lisp}: a NESTED
 * sub-system name ({@code x/a/b}), reached through a {@code :use-reexport} clause,</li>
 * <li>{@code pkg.inferred.tag} -> the {@code package-inferred-demo-tag} system, through
 * the {@code .asd}'s {@code register-system-packages} line -- the mapping that makes
 * ningle's {@code (:import-from #:lack.request ...)} name the {@code lack-request} system
 * instead of a {@code lack.request.asd} that does not exist.</li>
 * </ul>
 *
 * <p>
 * Both {@code .asd} consumers are covered: the interpreter leg drives
 * {@code LispEvaluator.loadSystem}, the three compile legs drive
 * {@code LoadInliner.spliceSystem}.
 */
class PackageInferredSystemE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "package-inferred-demo")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system "package-inferred-demo")
			(print (package-inferred-demo:greet "world"))
			(print (package-inferred-demo:shout "reexported"))
			(print (pkg.inferred.tag:tag))
			""";

	private static final List<String> EXPECTED = List.of("\"HELLO, world! [tag]\"", "\"REEXPORTED\"", "\"tag\"");

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
		return "TestPackageInferredSystem";
	}

}
