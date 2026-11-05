package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;

/**
 * The run-time half of the built-in macros for a compiled {@code eval}
 * ({@code eval-macros.lisp}, {@code .kb/eval-runtime.md}): a program gets the rows its
 * forms can reach, the definitions those rows need, and the dispatch {@code _eval}'s
 * unknown-operator path and {@code _store}'s unknown-place path call through the
 * registry. Which rows a program can reach is {@code EvalReferences}' scan; a row a
 * selected row expands to comes along here.
 */
public final class EvalMacroRows {

	private static final String ROW_PREFIX = "%EVAL-ROW-";

	private static final String PLACE_PREFIX = "%EVAL-PLACE-";

	private static final String SOFT_PREFIX = "%EVAL-SOFT-";

	private static final Map<String, LispVal> DEFINITIONS = load();

	private static final Set<String> ROW_OPERATORS = operatorsOf(ROW_PREFIX);

	private static final Set<String> PLACE_OPERATORS = operatorsOf(PLACE_PREFIX);

	private EvalMacroRows() {
	}

	/**
	 * What a selection brings into a program.
	 *
	 * @param forms the definitions and the generated dispatches, the variables first
	 * @param quoted every symbol the selected definitions quote: what their expansions
	 * call, which the operator gates have to see
	 */
	public record Selection(List<LispVal> forms, Set<String> quoted) {

	}

	/**
	 * Whether a form spelling {@code name} needs the table.
	 * @param name a standard operator's name
	 * @return true when a row or a place row holds it
	 */
	public static boolean holds(String name) {
		return ROW_OPERATORS.contains(name) || PLACE_OPERATORS.contains(name);
	}

	/**
	 * Every operator the table holds, for a program whose forms can name anything.
	 * @return the operators
	 */
	public static Set<String> operators() {
		Set<String> all = new TreeSet<>(ROW_OPERATORS);
		all.addAll(PLACE_OPERATORS);
		return all;
	}

	/**
	 * Whether a top-level definition is the table's, which the library tree-shaker may
	 * drop once no {@code eval} is left to reach it.
	 * @param name a defined name
	 * @return true for a definition of {@code eval-macros.lisp} or a generated dispatch
	 */
	public static boolean defines(String name) {
		return DEFINITIONS.containsKey(name) || LispNames.EVAL_EXPANSION.equals(name)
				|| LispNames.EVAL_PLACE_EXPANSION.equals(name);
	}

	/**
	 * The rows of {@code operators}, what they need, and the dispatches over them.
	 * @param operators the operators a program's forms spell
	 * @param specialLet whether an eval'd {@code let} has to ask whether it binds a
	 * special, which a run-time {@code defvar} always makes so
	 * @return the selection, empty when nothing is selected
	 */
	public static Selection select(Collection<String> operators, boolean specialLet) {
		Set<String> selected = new LinkedHashSet<>();
		Set<String> quoted = new TreeSet<>();
		Deque<String> queue = new ArrayDeque<>();
		for (String operator : operators) {
			if (ROW_OPERATORS.contains(operator)) {
				queue.add(ROW_PREFIX + operator);
			}
			if (PLACE_OPERATORS.contains(operator)) {
				queue.add(PLACE_PREFIX + operator);
			}
		}
		if (specialLet || operators.contains(LispNames.DEFVAR) || operators.contains(LispNames.DEFPARAMETER)) {
			queue.add(LispNames.EVAL_SPECIAL_LET);
		}
		while (!queue.isEmpty()) {
			String name = queue.poll();
			if (selected.add(name)) {
				walk(definition(name), false, name.startsWith(SOFT_PREFIX), queue, quoted);
			}
		}
		List<LispVal> forms = new ArrayList<>();
		for (String name : selected) {
			if (isVariable(definition(name))) {
				forms.add(definition(name));
			}
		}
		for (String name : selected) {
			if (!isVariable(definition(name))) {
				forms.add(definition(name));
			}
		}
		List<String> rows = selectedOperators(selected, ROW_PREFIX);
		if (!rows.isEmpty()) {
			forms.add(expansion(rows));
		}
		List<String> places = selectedOperators(selected, PLACE_PREFIX);
		if (!places.isEmpty()) {
			forms.add(placeExpansion(places));
		}
		return new Selection(List.copyOf(forms), Set.copyOf(quoted));
	}

	/**
	 * Follows a definition: any symbol naming a definition is a reference to it, and a
	 * symbol in quoted data, which is what an expansion is built of, a reference to that
	 * operator's row. A soft definition's data references nothing: it names a row only
	 * for a form spelling what that row serves.
	 */
	private static void walk(LispVal form, boolean inQuote, boolean soft, Deque<String> queue, Set<String> quoted) {
		if (form instanceof LispSymbol symbol) {
			if (DEFINITIONS.containsKey(symbol.name())) {
				queue.add(symbol.name());
			}
			if (inQuote) {
				quoted.add(symbol.name());
				if (ROW_OPERATORS.contains(symbol.name())) {
					queue.add(ROW_PREFIX + symbol.name());
				}
			}
			return;
		}
		LispVal cursor = form;
		if (cursor instanceof LispCons head && head.car() instanceof LispSymbol operator
				&& LispNames.QUOTE.equals(operator.name())) {
			if (!soft) {
				walk(head.cdr(), true, false, queue, quoted);
			}
			return;
		}
		while (cursor instanceof LispCons cons) {
			walk(cons.car(), inQuote, soft, queue, quoted);
			cursor = cons.cdr();
		}
	}

	private static List<String> selectedOperators(Set<String> selected, String prefix) {
		List<String> operators = new ArrayList<>();
		for (String name : selected) {
			if (name.startsWith(prefix)) {
				operators.add(name.substring(prefix.length()));
			}
		}
		operators.sort(null);
		return operators;
	}

	/**
	 * {@code (defun %eval-expansion (op form env) (if (%symbol-is op 'NAME) (row (cdr
	 * form) env) ... form))}. A chain the compilers turn into a search.
	 */
	private static LispVal expansion(List<String> operators) {
		LispSymbol op = new LispSymbol("%EVAL-OP");
		LispSymbol form = new LispSymbol("%EVAL-FORM");
		LispSymbol env = new LispSymbol("%EVAL-ENV");
		LispVal chain = LispMacroExpander.nameChain(op, operators,
				(name) -> list(new LispSymbol(ROW_PREFIX + name.name()), list(new LispSymbol(LispNames.CDR), form),
						env),
				form);
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol(LispNames.EVAL_EXPANSION), list(op, form, env),
				chain);
	}

	/**
	 * {@code (defun %eval-place-expansion (op args value) (if (%symbol-is op 'NAME) (row
	 * args value) ... nil))}.
	 */
	private static LispVal placeExpansion(List<String> operators) {
		LispSymbol op = new LispSymbol("%EVAL-OP");
		LispSymbol args = new LispSymbol("%EVAL-ARGS");
		LispSymbol value = new LispSymbol("%EVAL-VALUE");
		LispVal chain = LispMacroExpander.nameChain(op, operators,
				(name) -> list(new LispSymbol(PLACE_PREFIX + name.name()), args, value), LispNil.INSTANCE);
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol(LispNames.EVAL_PLACE_EXPANSION),
				list(op, args, value), chain);
	}

	private static LispVal list(LispVal... elements) {
		LispVal list = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			list = new LispCons(elements[i], list);
		}
		return list;
	}

	private static LispVal definition(String name) {
		return Objects.requireNonNull(DEFINITIONS.get(name), name);
	}

	private static boolean isVariable(LispVal definition) {
		return definition instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFVAR.equals(head.name());
	}

	private static Set<String> operatorsOf(String prefix) {
		Set<String> operators = new TreeSet<>();
		for (String name : DEFINITIONS.keySet()) {
			if (name.startsWith(prefix)) {
				operators.add(name.substring(prefix.length()));
			}
		}
		return Set.copyOf(operators);
	}

	private static Map<String, LispVal> load() {
		Map<String, LispVal> definitions = new LinkedHashMap<>();
		for (LispVal form : LispReader.readAllFromString(readSource())) {
			LispCons definition = (LispCons) form;
			LispCons rest = (LispCons) definition.cdr();
			definitions.put(((LispSymbol) rest.car()).name(), form);
		}
		return definitions;
	}

	private static String readSource() {
		try (InputStream in = EvalMacroRows.class.getResourceAsStream("eval-macros.lisp")) {
			if (in == null) {
				throw new IllegalStateException("eval-macros.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
