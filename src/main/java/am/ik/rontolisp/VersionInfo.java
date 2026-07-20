package am.ik.rontolisp;

import java.util.List;

/**
 * Builds the version information property list returned by the {@code rontolisp:version}
 * function. The values are taken from the build-time generated {@link Version} class, so
 * they match the output of {@code rontolisp --version}. Shared by the interpreter and
 * both compilers (the constants are baked in at build time / compile time).
 */
public final class VersionInfo {

	private VersionInfo() {
	}

	/**
	 * Builds the version property list:
	 * {@code (:version "..." :build-timestamp "..." :git-commit "..." :git-branch "...")}.
	 * @return the version information as a Lisp property list
	 */
	public static LispVal plist() {
		return list(new LispSymbol(":VERSION"), new LispString(Version.getVersion()),
				new LispSymbol(":BUILD-TIMESTAMP"), new LispString(Version.getBuild()), new LispSymbol(":GIT-COMMIT"),
				new LispString(Version.getGitCommit()), new LispSymbol(":GIT-BRANCH"),
				new LispString(Version.getGitBranch()));
	}

	private static LispVal list(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

}
