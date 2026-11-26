package am.ik.rontolisp.cli;

import java.util.List;

/**
 * One option of the command line: how {@link CliOptions} parses it and where
 * {@code --help} lists it. Every option is an entry of {@link CliOptionTable#OPTIONS}.
 *
 * @param names its spellings, the first the key {@link CliOptions} stores it under
 * @param arity whether and how it takes a value
 * @param metavar the value's name in the help, empty for a flag
 * @param repeatable whether every occurrence appends to the value (joined with a newline)
 * instead of the last one winning
 * @param testOnly whether only {@code rontolisp test} takes it ({@code TestCommand}
 * describes it)
 * @param groups the help groups it belongs to, in the order they are printed; empty
 * exactly when {@code testOnly}
 * @param help its description, one help line per line
 */
record CliOption(List<String> names, Arity arity, String metavar, boolean repeatable, boolean testOnly,
		List<Group> groups, String help) {

	CliOption {
		names = List.copyOf(names);
		groups = List.copyOf(groups);
	}

	/**
	 * How an option takes its value.
	 */
	enum Arity {

		/**
		 * No value. An {@code =value} glued on is still stored, for a check to refuse.
		 */
		FLAG,

		/** A value, as the following argument or glued with {@code =}. */
		VALUE,

		/** A value glued with {@code =} only; the bare flag keeps working. */
		OPTIONAL,

		/**
		 * A Clojure alias flag ({@code -A:dev}, {@code -M}, {@code -X}): the aliases
		 * glued on, parsed before the table is consulted.
		 */
		CLOJURE

	}

	/**
	 * A group of the help: the options every run takes, or those of one front end or one
	 * back end. {@code --help=SELECTOR,...} prints the common group and the named ones.
	 */
	enum Group {

		COMMON("common", "Common options"),

		COMMON_LISP("frontend:common-lisp", "Front end: Common Lisp"),

		CLOJURE("frontend:clojure", "Front end: Clojure"),

		SCHEME("frontend:scheme", "Front end: Scheme"),

		STARLARK("frontend:starlark", "Front end: Starlark"),

		JAVA("frontend:java", "Front end: Java"),

		INTERPRETER("backend:interpreter", "Back end: interpreter"),

		JVM("backend:jvm", "Back end: JVM"),

		WASM("backend:wasm", "Back end: WASM"),

		COMPONENT("backend:component", "Back end: WASI component"),

		NATIVE("backend:native", "Back end: native executable");

		private final String selector;

		private final String title;

		Group(String selector, String title) {
			this.selector = selector;
			this.title = title;
		}

		/**
		 * The name {@code --help=} selects the group by.
		 * @return {@code common}, {@code frontend:NAME} or {@code backend:NAME}
		 */
		String selector() {
			return this.selector;
		}

		/**
		 * The group's heading in the help.
		 * @return the title and the selector
		 */
		String heading() {
			return this.title + " (" + this.selector + ")";
		}

	}

	/**
	 * The option as the help writes it: its spellings and its value.
	 * @return the syntax, e.g. {@code -e, --eval FORMS} or {@code --optimize[=LEVEL]}
	 */
	String syntax() {
		String spelled = String.join(", ", this.names);
		return switch (this.arity) {
			case FLAG -> spelled;
			case VALUE -> spelled + " " + this.metavar;
			case OPTIONAL -> spelled + "[=" + this.metavar + "]";
			case CLOJURE -> spelled + this.metavar;
		};
	}

	/**
	 * Whether the option consumes the following argument as its value.
	 * @return {@code true} for a {@link Arity#VALUE}
	 */
	boolean takesFollowingValue() {
		return this.arity == Arity.VALUE;
	}

}
