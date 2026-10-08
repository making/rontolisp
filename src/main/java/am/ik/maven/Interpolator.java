package am.ik.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * {@code ${...}} expansion with the semantics of plexus-interpolation's
 * {@code StringSearchInterpolator}, which Maven's model builder uses: the value sources
 * are asked in order, a value is expanded again before it is used, an expression nothing
 * answers stays as written, and an expression met again while it is being expanded is a
 * cycle. The {@code project.} and {@code pom.} prefixes name the same expression for the
 * cycle check ({@code ${project.version}} and {@code ${version}} are one).
 */
final class Interpolator {

	/** One place an expression may be answered from. */
	@FunctionalInterface
	interface ValueSource {

		/**
		 * Answers an expression.
		 * @param expression the expression between the braces ({@code project.version}
		 * for {@code ${project.version}})
		 * @return the value, or {@code null} when this source does not know it
		 */
		@Nullable String get(String expression);

	}

	/** An expression whose expansion needs itself. */
	static final class CycleException extends Exception {

		private static final long serialVersionUID = 1L;

		CycleException(String message) {
			super(message);
		}

	}

	private final List<ValueSource> sources;

	private final Map<String, String> answers = new HashMap<>();

	private final Set<String> unresolvable = new HashSet<>();

	/**
	 * Creates an interpolator.
	 * @param sources the value sources, asked in order
	 */
	Interpolator(List<ValueSource> sources) {
		this.sources = List.copyOf(sources);
	}

	/**
	 * A value source over a map.
	 * @param map the values
	 * @return the source
	 */
	static ValueSource of(Map<String, String> map) {
		return map::get;
	}

	/**
	 * Expands every expression in {@code input}.
	 * @param input the text
	 * @return the expanded text
	 * @throws CycleException if an expression's expansion needs itself
	 */
	String interpolate(String input) throws CycleException {
		return interpolate(input, new ArrayList<>());
	}

	/**
	 * Expands every expression in a value that may be absent.
	 * @param input the text, or {@code null}
	 * @return the expanded text, or {@code null}
	 * @throws CycleException if an expression's expansion needs itself
	 */
	@Nullable String interpolateNullable(@Nullable String input) throws CycleException {
		return input == null ? null : interpolate(input);
	}

	private String interpolate(String input, List<String> resolving) throws CycleException {
		StringBuilder result = new StringBuilder(input.length());
		int start;
		int end = -1;
		while ((start = input.indexOf("${", end + 1)) > -1) {
			result.append(input, end + 1, start);
			end = input.indexOf('}', start + 1);
			if (end < 0) {
				break;
			}
			String whole = input.substring(start, end + 1);
			String expression = whole.substring(2, whole.length() - 1);
			boolean resolved = false;
			if (!this.unresolvable.contains(whole)) {
				if (expression.startsWith(".")) {
					expression = expression.substring(1);
				}
				String naked = naked(expression);
				if (resolving.contains(naked)) {
					throw cycle(whole, expression, resolving);
				}
				resolving.add(naked);
				try {
					String value = this.answers.get(expression);
					String selfReferring = null;
					for (ValueSource source : this.sources) {
						if (value != null) {
							break;
						}
						value = source.get(expression);
						if (value != null && value.contains(whole)) {
							selfReferring = value;
							value = null;
						}
					}
					if (value == null && selfReferring != null) {
						throw cycle(whole, expression, resolving);
					}
					if (value != null) {
						value = interpolate(value, resolving);
						this.answers.put(expression, value);
						result.append(value);
						resolved = true;
					}
					else {
						this.unresolvable.add(whole);
					}
				}
				finally {
					resolving.remove(resolving.size() - 1);
				}
			}
			if (!resolved) {
				result.append(whole);
			}
		}
		if (end == -1 && start > -1) {
			result.append(input, start, input.length());
		}
		else if (end < input.length()) {
			result.append(input, end + 1, input.length());
		}
		return result.toString();
	}

	/**
	 * The expression without the {@code project.}/{@code pom.} prefix, for the cycle
	 * check.
	 */
	private static String naked(String expression) {
		if (expression.startsWith("project.")) {
			return expression.substring("project.".length());
		}
		if (expression.startsWith("pom.")) {
			return expression.substring("pom.".length());
		}
		return expression;
	}

	private static CycleException cycle(String whole, String expression, List<String> resolving) {
		int from = resolving.indexOf(naked(expression));
		List<String> cycle = from < 0 ? List.of() : List.copyOf(resolving.subList(from, resolving.size()));
		return new CycleException("Resolving expression: '" + whole
				+ "': Detected the following recursive expression cycle in '" + expression + "': " + cycle);
	}

}
