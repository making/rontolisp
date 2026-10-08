package am.ik.maven;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A POM Maven's model builder would reject: unreadable XML, a failed validation, a cycle.
 * Maven's descriptor reader turns this into "no dependencies" plus a warning, and so does
 * {@link MavenResolver#descriptor}; only a POM read from bytes
 * ({@link MavenResolver#projectDependencies}), which has no descriptor to fall back to,
 * turns it into a {@link MavenResolutionException}. Its message lists every problem the
 * build collected, warnings included, as Maven's {@code ModelBuildingException} does.
 */
final class InvalidPomException extends Exception {

	private static final long serialVersionUID = 1L;

	private final List<ModelProblem> problems;

	/**
	 * Creates the exception.
	 * @param problems the problems Maven would report, at least one an error
	 */
	InvalidPomException(List<ModelProblem> problems) {
		super(problems.stream().map(ModelProblem::toString).collect(Collectors.joining("; ")));
		this.problems = List.copyOf(problems);
	}

	/**
	 * Creates the exception for one problem.
	 * @param problem the problem
	 */
	InvalidPomException(ModelProblem problem) {
		this(List.of(problem));
	}

	/**
	 * Returns the problems.
	 * @return the problems, in the order they were found
	 */
	List<ModelProblem> problems() {
		return this.problems;
	}

}
