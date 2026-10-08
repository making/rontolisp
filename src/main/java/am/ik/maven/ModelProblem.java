package am.ik.maven;

/**
 * A problem Maven's model builder reports, with its severity: a fatal one stops the build
 * at the next POM read, an error invalidates the model once it is built, a warning only
 * travels with them.
 *
 * @param severity the severity
 * @param message Maven's message
 */
record ModelProblem(Severity severity, String message) {

	/** Maven's {@code ModelProblem.Severity}. */
	enum Severity {

		/** Stops the build. */
		FATAL,

		/** Invalidates the model. */
		ERROR,

		/** Reported only with an error. */
		WARNING

	}

	static ModelProblem fatal(String message) {
		return new ModelProblem(Severity.FATAL, message);
	}

	static ModelProblem error(String message) {
		return new ModelProblem(Severity.ERROR, message);
	}

	static ModelProblem warning(String message) {
		return new ModelProblem(Severity.WARNING, message);
	}

	/**
	 * The message, a warning's marked {@code [WARNING]}.
	 * @return the problem as a refusal lists it
	 */
	@Override
	public String toString() {
		return this.severity == Severity.WARNING ? "[WARNING] " + this.message : this.message;
	}

}
