package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;

/**
 * The warnings a compiler prints of a program that compiles: the unchecked ones and a
 * catch clause nothing reaches, each where it is in the program's own files, gathered
 * while a program is lowered and printed as a compiler prints them before it runs -- the
 * line, the line's text, a caret under the place, what the warning names, the type
 * variables it mentions -- in the order of the files and of the places in them, then the
 * count. A declaration annotated {@code @SuppressWarnings("unchecked")} says none of its
 * own; one annotated {@code ("all")} says them, as a JDK 25 compiler does.
 */
final class JavaWarnings {

	/** As many as a compiler prints. */
	private static final int SHOWN = 100;

	/**
	 * One warning.
	 *
	 * @param source the file it is in
	 * @param pos where the caret goes
	 * @param text what it says: its first line, then the lines under the caret
	 */
	private record Warning(Source source, int pos, String text) {
	}

	private final List<Warning> warnings = new ArrayList<>();

	/** How many are gathered: where a lowering that is dropped puts them back. */
	int size() {
		return this.warnings.size();
	}

	/** Drops those gathered past a count. */
	void truncate(int size) {
		this.warnings.subList(size, this.warnings.size()).clear();
	}

	/**
	 * An unchecked warning.
	 * @param unit the file being lowered
	 * @param pos where the caret goes
	 * @param message its first line, after {@code [unchecked]}
	 * @param details the lines under the caret: {@code required:} and {@code found:}
	 * @param mentioned the types the warning names, in the order it names them: the type
	 * variables among them are explained under it
	 */
	void unchecked(JavaClasses.Unit unit, int pos, String message, List<String> details, List<JavaType> mentioned) {
		if (unit.source().library() || pos < 0 || unit.unchecked().stream().anyMatch(span -> span.contains(pos))) {
			return;
		}
		StringBuilder text = new StringBuilder("[unchecked] ").append(message);
		for (String detail : details) {
			text.append("\n  ").append(detail);
		}
		this.warnings.add(new Warning(unit.source(), pos, text.append(where(mentioned)).toString()));
	}

	/**
	 * A warning no annotation suppresses.
	 * @param unit the file being lowered
	 * @param pos where the caret goes
	 * @param text its first line, then the lines under the caret
	 */
	void warn(JavaClasses.Unit unit, int pos, String text) {
		if (!unit.source().library()) {
			this.warnings.add(new Warning(unit.source(), pos, text));
		}
	}

	/**
	 * What a compiler prints of the warnings, or the empty string for none: the program's
	 * own file first, then the others in the order a warning was first found in them;
	 * each file's in the order of their places.
	 * @param program the file the program starts at
	 * @return the text, each line ended
	 */
	String text(Source program) {
		Map<Source, Integer> order = new IdentityHashMap<>();
		order.put(program, 0);
		List<Warning> distinct = new ArrayList<>();
		Set<List<Object>> seen = new java.util.HashSet<>();
		for (Warning warning : this.warnings) {
			order.putIfAbsent(warning.source(), order.size());
			if (seen.add(List.of(warning.source().text(), warning.pos(), warning.text()))) {
				distinct.add(warning);
			}
		}
		distinct.sort(Comparator.comparingInt((Warning warning) -> order.get(warning.source()))
			.thenComparingInt(Warning::pos));
		StringBuilder out = new StringBuilder();
		for (Warning warning : distinct.subList(0, Math.min(SHOWN, distinct.size()))) {
			String text = warning.source().text();
			int start = text.lastIndexOf('\n', warning.pos() - 1) + 1;
			int end = text.indexOf('\n', warning.pos());
			String line = text.substring(start, end < 0 ? text.length() : end).replaceFirst("\r$", "");
			StringBuilder caret = new StringBuilder();
			for (int i = start; i < warning.pos(); i++) {
				caret.append(text.charAt(i) == '\t' ? '\t' : ' ');
			}
			int number = (int) text.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
			String file = warning.source().file();
			int newline = warning.text().indexOf('\n');
			out.append(file == null ? "" : file.replace('\\', '/'))
				.append(':')
				.append(number)
				.append(": warning: ")
				.append(newline < 0 ? warning.text() : warning.text().substring(0, newline))
				.append('\n')
				.append(line)
				.append('\n')
				.append(caret)
				.append("^\n");
			if (newline >= 0) {
				out.append(warning.text().substring(newline + 1)).append('\n');
			}
		}
		if (distinct.size() > SHOWN) {
			out.append(SHOWN)
				.append(" warnings\nonly showing the first ")
				.append(SHOWN)
				.append(" warnings, of ")
				.append(distinct.size())
				.append(" total; use -Xmaxwarns if you would like to see more\n");
		}
		else if (!distinct.isEmpty()) {
			out.append(distinct.size()).append(distinct.size() == 1 ? " warning\n" : " warnings\n");
		}
		return out.toString();
	}

	/**
	 * The lines explaining the type variables a warning mentions: each, its bounds and
	 * what declares it, in the order they are mentioned, those mentioned by their bounds
	 * after.
	 */
	private static String where(List<JavaType> mentioned) {
		Set<TypeParameter> variables = new LinkedHashSet<>();
		for (JavaType type : mentioned) {
			variables(type, variables);
		}
		for (int i = 0; i < variables.size(); i++) {
			for (JavaType bound : List.copyOf(variables).get(i).bounds) {
				variables(bound, variables);
			}
		}
		if (variables.isEmpty()) {
			return "";
		}
		StringBuilder where = new StringBuilder("\n  where ")
			.append(String.join(",", variables.stream().map(variable -> variable.name).toList()))
			.append(variables.size() == 1 ? " is a type-variable:" : " are type-variables:");
		for (TypeParameter variable : variables) {
			where.append("\n    ")
				.append(variable.name)
				.append(" extends ")
				.append(variable.bounds.isEmpty() ? "Object"
						: String.join(",", variable.bounds.stream().map(JavaType::toString).toList()))
				.append(" declared in ")
				.append(declaredIn(variable));
		}
		return where.toString();
	}

	private static void variables(JavaType type, Set<TypeParameter> into) {
		switch (type) {
			case Variable variable -> {
				TypeParameter parameter = variable.parameter();
				if (parameter.captured == null && !parameter.open && !parameter.intersection
						&& (parameter.owner != null || parameter.method != null)) {
					into.add(parameter);
				}
			}
			case ClassType named -> named.arguments().forEach(argument -> variables(argument, into));
			case ArrayType array -> variables(array.element(), into);
			case Wildcard wildcard -> {
				if (wildcard.upper() != null) {
					variables(wildcard.upper(), into);
				}
				if (wildcard.lower() != null) {
					variables(wildcard.lower(), into);
				}
			}
			default -> {
			}
		}
	}

	private static String declaredIn(TypeParameter variable) {
		JavaClass owner = variable.owner;
		if (owner != null) {
			return (owner.isInterface ? "interface" : owner.isEnum ? "enum" : owner.isRecord ? "record" : "class") + " "
					+ owner.simpleName();
		}
		Method method = java.util.Objects.requireNonNull(variable.method);
		return (method.isConstructor() ? "constructor " : "method ") + signature(method);
	}

	/**
	 * A method as a compiler names it in a warning: its type parameters, its name, its
	 * parameter types, the last {@code T...} when it takes the arguments past the others.
	 */
	static String signature(Method method) {
		StringBuilder text = new StringBuilder();
		if (!method.typeParameters.isEmpty()) {
			text.append('<')
				.append(String.join(",", method.typeParameters.stream().map(parameter -> parameter.name).toList()))
				.append('>');
		}
		text.append(method.isConstructor() ? method.owner.simpleName() : method.name).append('(');
		for (int i = method.hidden; i < method.parameters.size(); i++) {
			JavaType parameter = method.parameters.get(i);
			text.append(i > method.hidden ? "," : "")
				.append(method.varargs && i == method.parameters.size() - 1 ? ((ArrayType) parameter).element() + "..."
						: parameter.toString());
		}
		return text.append(')').toString();
	}

	/**
	 * The types a warning about a method names: its type parameters, then its
	 * parameters'.
	 */
	static List<JavaType> mentioned(Method method) {
		List<JavaType> types = new ArrayList<>();
		for (TypeParameter parameter : method.typeParameters) {
			types.add(new Variable(parameter));
		}
		types.addAll(method.parameters.subList(method.hidden, method.parameters.size()));
		return types;
	}

}
