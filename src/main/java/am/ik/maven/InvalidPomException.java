package am.ik.maven;

import java.util.List;

/**
 * A POM Maven's model builder would reject: unreadable XML, a failed validation, a cycle.
 * Maven's descriptor reader turns this into "no dependencies" plus a warning, and so does
 * {@link MavenResolver#descriptor}; only a POM read from bytes
 * ({@link MavenResolver#projectDependencies}), which has no descriptor to fall back to,
 * turns it into a {@link MavenResolutionException}.
 */
final class InvalidPomException extends Exception {

	private static final long serialVersionUID = 1L;

	private final List<String> problems;

	/**
	 * Creates the exception.
	 * @param problems the problems Maven would report, at least one
	 */
	InvalidPomException(List<String> problems) {
		super(String.join("; ", problems));
		this.problems = List.copyOf(problems);
	}

	/**
	 * Creates the exception for one problem.
	 * @param problem the problem
	 */
	InvalidPomException(String problem) {
		this(List.of(problem));
	}

	/**
	 * Returns the problems.
	 * @return the problems
	 */
	List<String> problems() {
		return this.problems;
	}

}
