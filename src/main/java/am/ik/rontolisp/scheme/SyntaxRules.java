package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * One {@code syntax-rules} transformer (R7RS 4.3.2): its rules, matched against a use in
 * order, and the instantiation of the first matching rule's template. What an identifier
 * MEANS -- whether it is the ellipsis, the underscore, or the same binding as a literal
 * -- depends on environments this class does not see, so it asks the expander through
 * {@link Identifiers}; so does every identifier the template introduces, which the
 * expander renames for hygiene ({@code .kb/scheme-frontend.md}, "Macros").
 */
final class SyntaxRules {

	/**
	 * What matching and instantiation need from the expander.
	 */
	interface Identifiers {

		/**
		 * Whether a pattern or template identifier is the ellipsis where the transformer
		 * was defined: the custom one when the transformer names one, else {@code ...}.
		 * @param identifier a pattern or template identifier
		 * @return whether it is the ellipsis
		 */
		boolean isEllipsis(LispSymbol identifier);

		/**
		 * Whether a pattern identifier is {@code _} where the transformer was defined.
		 * @param identifier a pattern identifier
		 * @return whether it is the underscore
		 */
		boolean isUnderscore(LispSymbol identifier);

		/**
		 * Whether an input identifier and a literal denote the same binding, each in its
		 * own environment (R7RS {@code free-identifier=?}).
		 * @param input the identifier of the use
		 * @param literal the literal of the transformer
		 * @return whether they match
		 */
		boolean matchesLiteral(LispSymbol input, LispSymbol literal);

		/**
		 * The key two occurrences of one identifier of the transformer share (R7RS
		 * {@code bound-identifier=?}).
		 * @param identifier an identifier of the transformer
		 * @return its key
		 */
		Object key(LispSymbol identifier);

		/**
		 * The identifier a template symbol that is not a pattern variable becomes: the
		 * same alias for every occurrence within one expansion.
		 * @param identifier a template identifier
		 * @return its alias
		 */
		LispSymbol rename(LispSymbol identifier);

		/**
		 * A cons of the expansion, positioned where the use stands.
		 * @param car the car
		 * @param cdr the cdr
		 * @return the cons
		 */
		LispCons cons(LispVal car, LispVal cdr);

	}

	private record Rule(LispVal pattern, LispVal template) {
	}

	/**
	 * What a pattern variable matched: one datum, or one match per ellipsis repetition.
	 */
	private sealed interface Match {

	}

	private record One(LispVal value) implements Match {
	}

	private record Many(List<Match> items) implements Match {
	}

	private final @Nullable LispSymbol ellipsis;

	private final List<LispSymbol> literals;

	private final List<Rule> rules;

	private SyntaxRules(@Nullable LispSymbol ellipsis, List<LispSymbol> literals, List<Rule> rules) {
		this.ellipsis = ellipsis;
		this.literals = literals;
		this.rules = rules;
	}

	/**
	 * Parses {@code (syntax-rules (literal ...) (pattern template) ...)} or
	 * {@code (syntax-rules ellipsis (literal ...) (pattern template) ...)}.
	 * @param parts the elements of the spec after {@code syntax-rules}
	 * @return the transformer
	 * @throws IllegalArgumentException when the spec is malformed
	 */
	static SyntaxRules parse(List<LispVal> parts) {
		int index = 0;
		LispSymbol ellipsis = null;
		if (!parts.isEmpty() && isIdentifier(parts.get(0))) {
			ellipsis = (LispSymbol) parts.get(0);
			index = 1;
		}
		if (index >= parts.size()) {
			throw new IllegalArgumentException("syntax-rules needs a literal list");
		}
		List<LispSymbol> literals = new ArrayList<>();
		for (LispVal literal : properList(parts.get(index), "syntax-rules literals")) {
			if (!isIdentifier(literal)) {
				throw new IllegalArgumentException("a syntax-rules literal must be an identifier: " + literal.print());
			}
			literals.add((LispSymbol) literal);
		}
		List<Rule> rules = new ArrayList<>();
		for (LispVal rule : parts.subList(index + 1, parts.size())) {
			List<LispVal> pair = properList(rule, "syntax rule");
			if (pair.size() != 2 || !(pair.get(0) instanceof LispCons)) {
				throw new IllegalArgumentException("a syntax rule is (pattern template): " + rule.print());
			}
			rules.add(new Rule(pair.get(0), pair.get(1)));
		}
		return new SyntaxRules(ellipsis, literals, rules);
	}

	/**
	 * Expands one use: the first rule whose pattern matches instantiates its template.
	 * @param form the use, keyword first
	 * @param identifiers the expander's view of identifiers
	 * @return the expansion, or {@code null} when no rule matches
	 * @throws IllegalArgumentException when a pattern or template is malformed
	 */
	@Nullable LispVal expand(LispCons form, Identifiers identifiers) {
		for (Rule rule : this.rules) {
			Context context = new Context(identifiers);
			LispCons pattern = (LispCons) rule.pattern();
			// The keyword position is ignored (R7RS 4.3.2).
			Map<Object, Match> bindings = new HashMap<>();
			if (context.match(pattern.cdr(), form.cdr(), bindings)) {
				return context.instantiate(rule.template(), bindings, true);
			}
		}
		return null;
	}

	private final class Context {

		private final Identifiers identifiers;

		Context(Identifiers identifiers) {
			this.identifiers = identifiers;
		}

		private boolean isEllipsis(LispVal datum) {
			if (!isIdentifier(datum)) {
				return false;
			}
			LispSymbol identifier = (LispSymbol) datum;
			LispSymbol custom = SyntaxRules.this.ellipsis;
			return custom != null ? this.identifiers.key(identifier).equals(this.identifiers.key(custom))
					: this.identifiers.isEllipsis(identifier);
		}

		private @Nullable LispSymbol literal(LispSymbol identifier) {
			Object key = this.identifiers.key(identifier);
			for (LispSymbol literal : SyntaxRules.this.literals) {
				if (this.identifiers.key(literal).equals(key)) {
					return literal;
				}
			}
			return null;
		}

		// ---------------------------------------------------------------- matching

		boolean match(LispVal pattern, LispVal input, Map<Object, Match> bindings) {
			if (isIdentifier(pattern)) {
				LispSymbol identifier = (LispSymbol) pattern;
				if (this.identifiers.isUnderscore(identifier) && literal(identifier) == null) {
					return true;
				}
				LispSymbol literal = literal(identifier);
				if (literal != null) {
					return isIdentifier(input) && this.identifiers.matchesLiteral((LispSymbol) input, literal);
				}
				if (isEllipsis(identifier)) {
					throw new IllegalArgumentException("misplaced ellipsis in a syntax-rules pattern");
				}
				if (bindings.put(this.identifiers.key(identifier), new One(input)) != null) {
					throw new IllegalArgumentException(
							"a pattern variable appears twice in a syntax-rules pattern: " + identifier.name());
				}
				return true;
			}
			if (pattern instanceof LispCons) {
				return matchSequence(pattern, input, bindings);
			}
			if (pattern instanceof LispArray vector) {
				return input instanceof LispArray inputVector
						&& matchSequence(SchemeBuiltins.listOf(List.of(vector.data())),
								SchemeBuiltins.listOf(List.of(inputVector.data())), bindings);
			}
			return datumEquals(pattern, input);
		}

		// (P1 ... Pk Pe <ellipsis> Pm+1 ... Pn . Px) against a list or improper list.
		private boolean matchSequence(LispVal pattern, LispVal input, Map<Object, Match> bindings) {
			List<LispVal> elements = new ArrayList<>();
			LispVal patternTail = pattern;
			while (patternTail instanceof LispCons cell) {
				elements.add(cell.car());
				patternTail = cell.cdr();
			}
			int ellipsisAt = -1;
			for (int i = 0; i < elements.size(); i++) {
				if (isEllipsis(elements.get(i))) {
					if (i == 0 || ellipsisAt >= 0) {
						throw new IllegalArgumentException("misplaced ellipsis in a syntax-rules pattern");
					}
					ellipsisAt = i;
				}
			}
			if (ellipsisAt < 0) {
				LispVal rest = input;
				for (LispVal element : elements) {
					if (!(rest instanceof LispCons cell) || !match(element, cell.car(), bindings)) {
						return false;
					}
					rest = cell.cdr();
				}
				return match(patternTail, rest, bindings);
			}
			List<LispVal> items = new ArrayList<>();
			LispVal inputTail = input;
			while (inputTail instanceof LispCons cell) {
				items.add(cell.car());
				inputTail = cell.cdr();
			}
			int before = ellipsisAt - 1;
			int after = elements.size() - ellipsisAt - 1;
			if (items.size() < before + after) {
				return false;
			}
			for (int i = 0; i < before; i++) {
				if (!match(elements.get(i), items.get(i), bindings)) {
					return false;
				}
			}
			LispVal repeated = elements.get(before);
			int repetitions = items.size() - before - after;
			List<Map<Object, Match>> each = new ArrayList<>();
			for (int i = 0; i < repetitions; i++) {
				Map<Object, Match> one = new HashMap<>();
				if (!match(repeated, items.get(before + i), one)) {
					return false;
				}
				each.add(one);
			}
			for (Object variable : variables(repeated)) {
				List<Match> matches = new ArrayList<>();
				for (Map<Object, Match> one : each) {
					matches.add(one.get(variable));
				}
				if (bindings.put(variable, new Many(matches)) != null) {
					throw new IllegalArgumentException("a pattern variable appears twice in a syntax-rules pattern");
				}
			}
			for (int i = 0; i < after; i++) {
				if (!match(elements.get(ellipsisAt + 1 + i), items.get(before + repetitions + i), bindings)) {
					return false;
				}
			}
			return match(patternTail, inputTail, bindings);
		}

		// The pattern variables of a subpattern, by key.
		private SequencedSet<Object> variables(LispVal pattern) {
			SequencedSet<Object> out = new LinkedHashSet<>();
			collectVariables(pattern, out);
			return out;
		}

		private void collectVariables(LispVal pattern, SequencedSet<Object> out) {
			switch (pattern) {
				case LispSymbol identifier when isIdentifier(identifier) -> {
					if (!isEllipsis(identifier) && literal(identifier) == null
							&& !this.identifiers.isUnderscore(identifier)) {
						out.add(this.identifiers.key(identifier));
					}
				}
				case LispCons cons -> {
					LispVal rest = cons;
					while (rest instanceof LispCons cell) {
						collectVariables(cell.car(), out);
						rest = cell.cdr();
					}
					collectVariables(rest, out);
				}
				case LispArray vector -> {
					for (LispVal element : vector.data()) {
						collectVariables(element, out);
					}
				}
				default -> {
				}
			}
		}

		// ---------------------------------------------------------------- templates

		LispVal instantiate(LispVal template, Map<Object, Match> bindings, boolean ellipsisActive) {
			if (isIdentifier(template)) {
				LispSymbol identifier = (LispSymbol) template;
				Match match = bindings.get(this.identifiers.key(identifier));
				if (match instanceof One one) {
					return one.value();
				}
				if (match instanceof Many) {
					throw new IllegalArgumentException(
							"the pattern variable " + identifier.name() + " is used without an ellipsis");
				}
				if (ellipsisActive && isEllipsis(identifier)) {
					throw new IllegalArgumentException("misplaced ellipsis in a syntax-rules template");
				}
				return this.identifiers.rename(identifier);
			}
			if (template instanceof LispCons cons) {
				// (<ellipsis> <template>): the template with the ellipsis an ordinary
				// identifier.
				if (ellipsisActive && isEllipsis(cons.car()) && cons.cdr() instanceof LispCons rest
						&& rest.cdr() == LispNil.INSTANCE) {
					return instantiate(rest.car(), bindings, false);
				}
				return instantiateSequence(cons, bindings, ellipsisActive);
			}
			if (template instanceof LispArray vector) {
				LispVal list = instantiateSequence(SchemeBuiltins.listOf(List.of(vector.data())), bindings,
						ellipsisActive);
				List<LispVal> elements = new ArrayList<>();
				LispVal rest = list;
				while (rest instanceof LispCons cell) {
					elements.add(cell.car());
					rest = cell.cdr();
				}
				return new LispArray(new int[] { elements.size() }, elements.toArray(new LispVal[0]));
			}
			return template;
		}

		private LispVal instantiateSequence(LispVal template, Map<Object, Match> bindings, boolean ellipsisActive) {
			List<LispVal> out = new ArrayList<>();
			LispVal rest = template;
			while (rest instanceof LispCons cell) {
				LispVal element = cell.car();
				rest = cell.cdr();
				int depth = 0;
				while (ellipsisActive && rest instanceof LispCons next && isEllipsis(next.car())) {
					depth++;
					rest = next.cdr();
				}
				if (depth == 0) {
					out.add(instantiate(element, bindings, ellipsisActive));
				}
				else {
					repeat(element, bindings, depth, out);
				}
			}
			LispVal tail = rest == LispNil.INSTANCE ? LispNil.INSTANCE : instantiate(rest, bindings, ellipsisActive);
			for (int i = out.size() - 1; i >= 0; i--) {
				tail = this.identifiers.cons(out.get(i), tail);
			}
			return tail;
		}

		// <element> followed by `depth` ellipses: one instantiation per repetition of the
		// pattern variables in it that matched under an ellipsis, flattened.
		private void repeat(LispVal element, Map<Object, Match> bindings, int depth, List<LispVal> out) {
			Map<Object, Many> iterated = new java.util.LinkedHashMap<>();
			int count = -1;
			for (Object variable : templateVariables(element, bindings)) {
				if (bindings.get(variable) instanceof Many many) {
					if (count >= 0 && count != many.items().size()) {
						throw new IllegalArgumentException(
								"pattern variables under one ellipsis matched different numbers of forms");
					}
					count = many.items().size();
					iterated.put(variable, many);
				}
			}
			if (iterated.isEmpty()) {
				throw new IllegalArgumentException(
						"an ellipsis in a syntax-rules template follows no pattern variable that matched under one");
			}
			for (int i = 0; i < count; i++) {
				Map<Object, Match> one = new HashMap<>(bindings);
				for (Map.Entry<Object, Many> variable : iterated.entrySet()) {
					one.put(variable.getKey(), variable.getValue().items().get(i));
				}
				if (depth == 1) {
					out.add(instantiate(element, one, true));
				}
				else {
					repeat(element, one, depth - 1, out);
				}
			}
		}

		private SequencedSet<Object> templateVariables(LispVal template, Map<Object, Match> bindings) {
			SequencedSet<Object> out = new LinkedHashSet<>();
			collectTemplateVariables(template, bindings, out);
			return out;
		}

		private void collectTemplateVariables(LispVal template, Map<Object, Match> bindings, SequencedSet<Object> out) {
			switch (template) {
				case LispSymbol identifier when isIdentifier(identifier) -> {
					Object key = this.identifiers.key(identifier);
					if (bindings.containsKey(key)) {
						out.add(key);
					}
				}
				case LispCons cons -> {
					LispVal rest = cons;
					while (rest instanceof LispCons cell) {
						collectTemplateVariables(cell.car(), bindings, out);
						rest = cell.cdr();
					}
					collectTemplateVariables(rest, bindings, out);
				}
				case LispArray vector -> {
					for (LispVal element : vector.data()) {
						collectTemplateVariables(element, bindings, out);
					}
				}
				default -> {
				}
			}
		}

	}

	// #t and #f are symbols to the reader, but data to a pattern.
	static boolean isIdentifier(LispVal datum) {
		return datum instanceof LispSymbol symbol && symbol != SchemeReader.TRUE && symbol != SchemeReader.FALSE;
	}

	private static boolean datumEquals(LispVal pattern, LispVal input) {
		if (pattern instanceof LispCons || pattern instanceof LispArray) {
			return false;
		}
		// A boolean is not equal to the identifier |#t| its symbol is equal to.
		if (SchemeReader.isBoolean(pattern) || SchemeReader.isBoolean(input)) {
			return pattern == input;
		}
		return pattern.equals(input);
	}

	private static List<LispVal> properList(LispVal list, String what) {
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = list;
		while (rest instanceof LispCons cell) {
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (rest != LispNil.INSTANCE) {
			throw new IllegalArgumentException("malformed " + what + ": " + list.print());
		}
		return elements;
	}

}
