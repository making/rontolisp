package am.ik.maven;

import java.util.ArrayList;
import java.util.List;

/**
 * The problems one model build collects, in the order Maven's
 * {@code DefaultModelProblemCollector} holds them.
 */
final class ModelProblems {

	private final List<ModelProblem> problems = new ArrayList<>();

	void add(ModelProblem problem) {
		this.problems.add(problem);
	}

	void add(ModelProblem.Severity severity, String message) {
		this.problems.add(new ModelProblem(severity, message));
	}

	void fatal(String message) {
		add(ModelProblem.fatal(message));
	}

	void error(String message) {
		add(ModelProblem.error(message));
	}

	void warning(String message) {
		add(ModelProblem.warning(message));
	}

	void addAll(List<ModelProblem> more) {
		this.problems.addAll(more);
	}

	/**
	 * Whether a fatal problem was collected: Maven's {@code hasFatalErrors}, checked
	 * after each POM it reads.
	 * @return whether the build stops
	 */
	boolean hasFatal() {
		for (ModelProblem problem : this.problems) {
			if (problem.severity() == ModelProblem.Severity.FATAL) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether an error or a fatal problem was collected: Maven's {@code hasModelErrors}.
	 * @return whether the model is invalid
	 */
	boolean hasErrors() {
		for (ModelProblem problem : this.problems) {
			if (problem.severity() != ModelProblem.Severity.WARNING) {
				return true;
			}
		}
		return false;
	}

	List<ModelProblem> list() {
		return List.copyOf(this.problems);
	}

	/**
	 * The exception reporting every problem collected.
	 * @return the exception
	 */
	InvalidPomException invalid() {
		return new InvalidPomException(this.problems);
	}

}
