package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The vendored systems jose's {@code :depends-on} graph reaches, as an
 * {@code AsdfLibraryE2eSupport#extraSystemPath()} value. Shared by {@link JoseE2eTest}
 * and {@link JoseTestSuiteE2eTest} so the two cannot drift apart: a dependency missing
 * from one of them fails as "system not found" rather than as anything about jose.
 */
final class JoseSystems {

	/**
	 * Everything {@code jose/main} pulls in, in dependency order. trivial-utf-8's own
	 * {@code :depends-on (#:mgl-pax-bootstrap)} needs no directory: that one is a
	 * built-in shim system ({@code eval/ShimLibraries}).
	 */
	static final List<String> DEPENDENCY_PATH = List.of(dir("cl-json"), dir("ironclad"), dir("cl-base64"),
			dir("split-sequence"), dir("assoc-utils"), dir("alexandria"), dir("trivial-utf-8"));

	private static String dir(String name) {
		return Path.of("src", "test", "resources", name).toAbsolutePath().toString();
	}

	private JoseSystems() {
	}

}
