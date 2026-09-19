package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;

/**
 * {@code cond-expand} (R7RS 4.2.1): the feature identifiers this front end declares and
 * which clause a {@code cond-expand} takes. The choice is made while the program is
 * LOWERED, so a feature is one that holds on every backend alike -- never the host's
 * operating system or processor, which a compiled program does not share
 * ({@code .kb/scheme-frontend.md}, "{@code cond-expand}").
 */
final class SchemeFeatures {

	/**
	 * What {@code (features)} answers, in R7RS appendix B's order. Each is true on the
	 * interpreter, the JVM and both WASM targets: exact rationals and bignums
	 * ({@code exact-closed}, {@code ratios}), IEEE doubles ({@code ieee-float}), and a
	 * character per Unicode code point ({@code full-unicode}).
	 */
	static final List<String> FEATURES = List.of("r7rs", "exact-closed", "ieee-float", "full-unicode", "ratios",
			"rontolisp");

	/** What deciding a requirement needs from the lowering. */
	interface Host {

		/**
		 * Whether {@code (library name)} holds: the library can be imported.
		 * @param name the library name datum
		 * @param form the {@code cond-expand}, for a positioned error
		 * @return whether it is available
		 */
		boolean libraryAvailable(LispVal name, LispCons form);

		LispReadException error(String message, LispCons form);

	}

	private SchemeFeatures() {
	}

	/**
	 * The clause a {@code cond-expand} takes: the first whose requirement holds, or a
	 * last {@code else}. None is an error, as in Gauche -- R7RS leaves it unspecified.
	 * @param form the whole {@code (cond-expand clause...)}, its identifiers plain
	 * @param host the library lookup and the error factory
	 * @return the taken clause's index among the clauses
	 */
	static int clause(LispCons form, Host host) {
		List<LispVal> clauses = elements(form.cdr(), form, host);
		for (int i = 0; i < clauses.size(); i++) {
			if (!(clauses.get(i) instanceof LispCons clause)) {
				throw host.error("malformed cond-expand clause: " + SchemeExpander.written(clauses.get(i)), form);
			}
			if (isNamed(clause.car(), "else")) {
				if (i != clauses.size() - 1) {
					throw host.error("else must be the last cond-expand clause", form);
				}
				return i;
			}
			if (holds(clause.car(), form, host)) {
				return i;
			}
		}
		throw host.error("no cond-expand clause is fulfilled and there is no else clause", form);
	}

	/**
	 * The datums of a clause after its requirement.
	 * @param clause the clause {@link #clause} picked
	 * @param form the {@code cond-expand}
	 * @param host the error factory
	 * @return the clause's body, possibly empty
	 */
	static List<LispVal> body(LispVal clause, LispCons form, Host host) {
		List<LispVal> parts = elements(clause, form, host);
		return parts.subList(1, parts.size());
	}

	private static boolean holds(LispVal requirement, LispCons form, Host host) {
		if (requirement instanceof LispSymbol feature) {
			return FEATURES.contains(feature.name());
		}
		if (requirement instanceof LispCons compound && compound.car() instanceof LispSymbol head) {
			List<LispVal> operands = elements(compound.cdr(), form, host);
			switch (head.name()) {
				case "and" -> {
					for (LispVal operand : operands) {
						if (!holds(operand, form, host)) {
							return false;
						}
					}
					return true;
				}
				case "or" -> {
					for (LispVal operand : operands) {
						if (holds(operand, form, host)) {
							return true;
						}
					}
					return false;
				}
				case "not" -> {
					if (operands.size() == 1) {
						return !holds(operands.getFirst(), form, host);
					}
				}
				case "library" -> {
					if (operands.size() == 1) {
						return host.libraryAvailable(operands.getFirst(), form);
					}
				}
				default -> {
				}
			}
		}
		throw host.error("malformed cond-expand requirement: " + SchemeExpander.written(requirement), form);
	}

	private static boolean isNamed(LispVal datum, String name) {
		return datum instanceof LispSymbol symbol && symbol.name().equals(name);
	}

	private static List<LispVal> elements(LispVal list, LispCons form, Host host) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = list;
		while (rest instanceof LispCons cell) {
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (rest != LispNil.INSTANCE) {
			throw host.error("malformed cond-expand: " + SchemeExpander.written(form), form);
		}
		return elements;
	}

	/**
	 * {@code (defun rontolisp::%scheme-features () (list ...))}: what {@code (features)}
	 * and {@code eval}'s {@code cond-expand} read, generated so the list is spelled once.
	 * @return the definition, in the library's canonical shape
	 */
	static LispVal featuresForm() {
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol("LIST"));
		for (String feature : FEATURES) {
			call.add(SchemeBuiltins.list(new LispSymbol("QUOTE"), new LispSymbol(feature)));
		}
		return SchemeBuiltins.list(new LispSymbol("DEFUN"), new LispSymbol("RONTOLISP::%SCHEME-FEATURES"),
				LispNil.INSTANCE, SchemeBuiltins.listOf(call));
	}

}
