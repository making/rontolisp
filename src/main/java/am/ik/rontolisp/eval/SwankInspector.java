package am.ik.rontolisp.eval;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispJavaObject;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;

/**
 * One editor's inspector, as SWANK's with the {@code swank-fancy-inspector} contrib: a
 * value's parts -- the elements of a list or an array, a hash table's entries, an
 * instance's slots, what a symbol names, a number's representations -- each a value the
 * editor can inspect in turn, with actions on the value, and the chain of values
 * inspected so far to step back and forth in.
 *
 * <p>
 * A page is SWANK's {@code (:title T :id N :content (PARTS NEXT START END))}: a part is
 * text, {@code (:value TEXT INDEX)} or {@code (:action LABEL INDEX)}, an index naming the
 * value or action in this page's tables, which grow each time the page is shown, as
 * SWANK's do. A value printed again after it was inspected carries its place in the
 * history ({@code @1=1}).
 */
public final class SwankInspector {

	/** How many of a page's parts the first showing carries, as SWANK's. */
	private static final int FIRST_RANGE = 500;

	/** How wide a value's line is before it is cut, as SWANK's {@code to-line}. */
	private static final int LINE_WIDTH = 512;

	/** A part of a page as the editor sees it. */
	public sealed interface Part {

	}

	/**
	 * Text.
	 *
	 * @param text the text
	 */
	public record Text(String text) implements Part {
	}

	/**
	 * A value the editor can inspect.
	 *
	 * @param text the value printed
	 * @param index its place in the page's value table
	 */
	public record Value(String text, int index) implements Part {
	}

	/**
	 * An action the editor can call.
	 *
	 * @param label its label
	 * @param index its place in the page's action table
	 */
	public record Action(String label, int index) implements Part {
	}

	/**
	 * Some of a page's parts.
	 *
	 * @param parts the parts
	 * @param next where the next range starts, or past the end when this one was full
	 * @param start where this range starts
	 * @param end where it was asked to end
	 */
	public record Range(List<Part> parts, int next, int start, int end) {
	}

	/**
	 * A page.
	 *
	 * @param title what is inspected, printed
	 * @param id the inspected value's place in the value table
	 * @param content its first parts
	 */
	public record Page(String title, int id, Range content) {
	}

	/** A part of a page's content as it is made, before it is shown. */
	private sealed interface Raw {

	}

	private record RText(String text) implements Raw {
	}

	private record RNewline() implements Raw {
	}

	private record RValue(LispVal value, @Nullable String text) implements Raw {
	}

	private record RAction(String label, Runnable action, boolean refresh) implements Raw {
	}

	/** SWANK's {@code iline}: one part that shows as a label, a colon and a value. */
	private record RLine(String label, LispVal value) implements Raw {
	}

	/**
	 * A method of a generic function. No method is a first-class object here (a method is
	 * a registry row and a generated function), so a page holds this, wrapped, where
	 * SWANK's holds a method metaobject.
	 *
	 * @param generic the generic function's name
	 * @param key the method's key in its generic's table
	 * @param info the method
	 */
	private record Method(String generic, String key, ClosRegistry.MethodInfo info) {
	}

	/**
	 * SWANK's {@code %package-symbols-container}: some of a package's symbols, listed
	 * alphabetically or grouped by what they name.
	 */
	private static final class Symbols {

		final String title;

		final List<Raw> description;

		final List<String> spellings;

		boolean byClassification;

		Symbols(String title, List<Raw> description, List<String> spellings) {
			this.title = title;
			this.description = description;
			this.spellings = spellings;
		}

	}

	/** An inspected value and what its page shows. */
	private final class State {

		final LispVal object;

		boolean verbose;

		final List<LispVal> parts = new ArrayList<>();

		final List<RAction> actions = new ArrayList<>();

		final Map<String, Object> metadata = new HashMap<>();

		List<Raw> content = List.of();

		@Nullable State previous;

		@Nullable State next;

		State(LispVal object, @Nullable State previous) {
			this.object = object;
			this.previous = previous;
			this.verbose = previous != null && previous.verbose;
		}

	}

	private final SwankLisp lisp;

	private final LispEvaluator evaluator;

	/** The record of what the editor's code defines, for documentation strings. */
	private final SwankSources sources;

	/** The class each slot definition a class page showed belongs to. */
	private final Map<LispVal, String> slotOwners = new IdentityHashMap<>();

	private @Nullable State state;

	/** Every value inspected, in the order first inspected. */
	private final List<LispVal> history = new ArrayList<>();

	/** What reads a line from the editor while an action runs, or null. */
	private @Nullable Function<String, @Nullable String> asking;

	SwankInspector(SwankLisp lisp, LispEvaluator evaluator, SwankSources sources) {
		this.lisp = lisp;
		this.evaluator = evaluator;
		this.sources = sources;
	}

	// ------------------------------------------------------------------------------------
	// Requests

	/**
	 * Starts over with a value, as {@code init-inspector}: the history cleared, the value
	 * of a form inspected, or the value a definition names
	 * ({@code (:defmethod name qualifier... (specializer...))} is not one this answers).
	 * @param code the form
	 * @param definition the definition to inspect instead, or null
	 * @return its page
	 */
	public synchronized Page init(String code, @Nullable String definition) {
		reset();
		LispVal form = this.lisp.readResolved(definition != null ? definition : code);
		return inspect(this.evaluator.eval(form));
	}

	/**
	 * Inspects a value the server kept, as SWANK's {@code inspect-object}: a printed
	 * object Lem asks for by its id, an entry of SLY's REPL history, another inspector's
	 * part. The history goes on; {@link #quit} first starts it over.
	 * @param value the value
	 * @return its page
	 */
	public synchronized Page inspectValue(LispVal value) {
		return inspect(value);
	}

	/**
	 * A value described, as SWANK's {@code describe-to-string}.
	 * @param value the value
	 * @return the description
	 */
	public synchronized String describeValue(LispVal value) {
		return describe(value);
	}

	/**
	 * Inspects one of the page's values, as {@code inspect-nth-part}.
	 * @param index its place in the value table
	 * @return its page
	 */
	public synchronized Page inspectNthPart(int index) {
		return inspect(nthPart(index));
	}

	/**
	 * One of the page's values, as {@code inspector-nth-part}.
	 * @param index its place in the value table
	 * @return the value, nil when there is none
	 */
	public synchronized LispVal nthPart(int index) {
		State current = current();
		return index >= 0 && index < current.parts.size() ? current.parts.get(index) : LispNil.INSTANCE;
	}

	/**
	 * More of the page's parts, as {@code inspector-range}.
	 * @param from the first
	 * @param to past the last
	 * @return the range
	 */
	public synchronized Range range(int from, int to) {
		return prepare(current(), from, to);
	}

	/**
	 * Calls one of the page's actions, as {@code inspector-call-nth-action}: the page
	 * shown again when the action changes it.
	 * @param index its place in the action table
	 * @param ask reads a line from the editor's minibuffer, null when the editor gave
	 * none
	 * @return the page shown again, or null
	 */
	public synchronized @Nullable Page callNthAction(int index, Function<String, @Nullable String> ask) {
		RAction action = current().actions.get(index);
		this.asking = ask;
		try {
			action.action().run();
		}
		finally {
			this.asking = null;
		}
		return action.refresh() ? reinspect() : null;
	}

	/**
	 * Steps back to the value inspected before, as {@code inspector-pop}.
	 * @return its page, or null at the first
	 */
	public synchronized @Nullable Page pop() {
		State previous = current().previous;
		if (previous == null) {
			return null;
		}
		this.state = previous;
		return show(previous);
	}

	/**
	 * Steps forward again, as {@code inspector-next}.
	 * @return the page, or null at the last
	 */
	public synchronized @Nullable Page next() {
		State next = current().next;
		if (next == null) {
			return null;
		}
		this.state = next;
		return show(next);
	}

	/**
	 * Makes the page again from the value, as {@code inspector-reinspect}.
	 * @return the page
	 */
	public synchronized Page reinspect() {
		State current = current();
		current.content = content(current);
		return show(current);
	}

	/**
	 * Prints the title as the value prints, or as its type, as
	 * {@code inspector-toggle-verbose}.
	 * @return the page
	 */
	public synchronized Page toggleVerbose() {
		State current = current();
		current.verbose = !current.verbose;
		return show(current);
	}

	/**
	 * Evaluates a form with {@code *} the inspected value, as {@code inspector-eval}.
	 * @param code the form
	 * @return its value, printed
	 */
	public synchronized String eval(String code) {
		LispVal form = this.lisp.readResolved(code);
		LispVal bound = list(new LispSymbol("LET"),
				list(list(new LispSymbol("*"), list(new LispSymbol(LispNames.QUOTE), current().object)),
						list(new LispSymbol("-"), list(new LispSymbol(LispNames.QUOTE), form))),
				form);
		return this.lisp.print(this.evaluator.eval(bound));
	}

	/**
	 * The chain of values stepped through and every value inspected, as
	 * {@code inspector-history}.
	 * @return the text
	 */
	public synchronized String historyText() {
		StringBuilder out = new StringBuilder("--- next/prev chain ---");
		State newest = current();
		while (newest.next != null) {
			newest = newest.next;
		}
		for (State s = newest; s != null; s = s.previous) {
			out.append('\n')
				.append(s == this.state ? " *" : "  ")
				.append('@')
				.append(position(s.object))
				.append(' ')
				.append(title(s.object));
		}
		out.append("\n\n--- all visited objects ---");
		for (int i = 0; i < this.history.size(); i++) {
			out.append('\n').append(String.format("%2d ", i)).append(title(this.history.get(i)));
		}
		return out.toString();
	}

	/** Ends the inspection, as {@code quit-inspector}. */
	public synchronized void quit() {
		reset();
	}

	/**
	 * Describes the inspected value, as {@code describe-inspectee}.
	 * @return the description
	 */
	public synchronized String describe() {
		return describe(current().object);
	}

	/**
	 * Describes one of the page's values, as {@code describe-inspector-part}.
	 * @param index its place in the value table
	 * @return the description
	 */
	public synchronized String describePart(int index) {
		return describe(nthPart(index));
	}

	/**
	 * One of the page's values printed, as {@code pprint-inspector-part}.
	 * @param index its place in the value table
	 * @return the text
	 */
	public synchronized String printPart(int index) {
		return display(nthPart(index));
	}

	// ------------------------------------------------------------------------------------
	// Pages

	private void reset() {
		this.state = null;
		this.history.clear();
		this.slotOwners.clear();
	}

	private State current() {
		State current = this.state;
		if (current == null) {
			throw new LispEvalException("The inspector is not inspecting anything.");
		}
		return current;
	}

	private Page inspect(LispVal object) {
		State previous = this.state;
		State next = new State(object, previous);
		this.state = next;
		next.content = content(next);
		if (position(object) < 0) {
			this.history.add(object);
		}
		if (previous != null) {
			previous.next = next;
		}
		return show(next);
	}

	private Page show(State s) {
		String title = s.verbose ? display(s.object) : title(s.object);
		int id = assign(s.parts, s.object);
		return new Page(title, id, prepare(s, 0, FIRST_RANGE));
	}

	private String title(LispVal object) {
		return "#<" + typeName(object) + " {"
				+ Integer.toHexString(System.identityHashCode(object)).toUpperCase(Locale.ROOT) + "}>";
	}

	/** What a value is, as a title names it: its {@code type-of}, or SWANK's class. */
	private String typeName(LispVal object) {
		if (object instanceof LispJavaObject wrapped && wrapped.ref() instanceof Method) {
			return "STANDARD-METHOD";
		}
		if (object instanceof LispJavaObject wrapped && wrapped.ref() instanceof Symbols) {
			return "SWANK::%PACKAGE-SYMBOLS-CONTAINER";
		}
		if (object instanceof LispSymbol symbol && packageDesignator(symbol)) {
			return "PACKAGE";
		}
		if (object instanceof LispLambda lambda && generic(lambda.name()) != null
				|| object instanceof LispFunction function && generic(function.name()) != null) {
			return "STANDARD-GENERIC-FUNCTION";
		}
		try {
			return this.lisp.print(this.lisp.helper("(lambda (o) (type-of o))", object));
		}
		catch (RuntimeException ex) {
			return "T";
		}
	}

	/**
	 * A value printed: a metaobject as SBCL prints one ({@code #<STANDARD-CLASS NAME>}),
	 * a method by its generic function and specializers, anything else as {@code prin1}.
	 */
	private String display(LispVal value) {
		if (value instanceof LispJavaObject wrapped && wrapped.ref() instanceof Method method) {
			StringBuilder text = new StringBuilder("#<STANDARD-METHOD ")
				.append(this.lisp.print(functionName(method.generic())));
			for (LispVal qualifier : qualifiers(method)) {
				text.append(' ').append(this.lisp.print(qualifier));
			}
			return text.append(' ').append(princ(list(specializerNames(method)))).append('>').toString();
		}
		if (value instanceof LispJavaObject wrapped && wrapped.ref() instanceof Symbols) {
			return "#<SWANK::%PACKAGE-SYMBOLS-CONTAINER>";
		}
		if (value instanceof LispInstance instance
				&& (isA(instance, ClosRegistry.CLASS_NAME) || slotDefinition(instance))) {
			return "#<" + instance.layout().printName() + " " + this.lisp.print(slot(instance, "NAME")) + ">";
		}
		return this.lisp.print(value);
	}

	private Range prepare(State s, int start, int end) {
		List<Part> parts = new ArrayList<>();
		for (int i = Math.max(0, start); i < Math.min(s.content.size(), end); i++) {
			switch (s.content.get(i)) {
				case RText text -> parts.add(new Text(text.text()));
				case RNewline newline -> parts.add(new Text("\n"));
				case RValue value -> parts.add(valuePart(s, value.value(), value.text()));
				case RAction action -> {
					s.actions.add(action);
					parts.add(new Action(action.label(), s.actions.size() - 1));
				}
				case RLine line -> {
					parts.add(new Text(line.label()));
					parts.add(new Text(": "));
					parts.add(valuePart(s, line.value(), null));
					parts.add(new Text("\n"));
				}
			}
		}
		int next = parts.size() < end - start ? start + parts.size() : end + 1000;
		return new Range(parts, next, start, end);
	}

	private Value valuePart(State s, LispVal value, @Nullable String text) {
		return new Value(text != null ? text : printPart(value), assign(s.parts, value));
	}

	private static int assign(List<LispVal> table, LispVal value) {
		table.add(value);
		return table.size() - 1;
	}

	private String printPart(LispVal value) {
		String text;
		try {
			text = display(value);
		}
		catch (RuntimeException ex) {
			text = "#<error printing object>";
		}
		if (text.length() > LINE_WIDTH) {
			text = text.substring(0, LINE_WIDTH) + "..";
		}
		int pos = position(value);
		return pos >= 0 ? "@" + pos + "=" + text : text;
	}

	private int position(LispVal value) {
		for (int i = 0; i < this.history.size(); i++) {
			if (eql(this.history.get(i), value)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Lisp's {@code eql}: the same object, or numbers or characters of one type and
	 * value.
	 */
	private static boolean eql(LispVal a, LispVal b) {
		if (a == b) {
			return true;
		}
		boolean byValue = a instanceof LispInteger || a instanceof LispBigInteger || a instanceof LispRatio
				|| a instanceof LispDouble || a instanceof LispChar || a instanceof LispSymbol
				|| a instanceof LispComplex;
		return byValue && a.getClass() == b.getClass() && a.equals(b);
	}

	// ------------------------------------------------------------------------------------
	// Content

	private List<Raw> content(State s) {
		List<Raw> out = new ArrayList<>();
		LispVal o = s.object;
		switch (o) {
			case LispCons cons -> cons(out, cons);
			case LispNil nil -> symbol(out, o);
			case LispTrue t -> symbol(out, o);
			case LispSymbol symbol when packageDesignator(symbol) -> packagePage(out, symbol);
			case LispSymbol symbol -> symbol(out, o);
			case LispInteger i -> integer(out, o, BigInteger.valueOf(i.value()));
			case LispBigInteger big -> integer(out, o, big.value());
			case LispRatio ratio -> lines(out, "(lambda (r) (list (numerator r) (denominator r) (float r)))", o,
					"Numerator", "Denominator", "As float");
			case LispComplex complex ->
				lines(out, "(lambda (c) (list (realpart c) (imagpart c)))", o, "Real part", "Imaginary part");
			case LispDouble d -> flonum(out, d);
			case LispChar c -> lines(out, "(lambda (c) (list (char-code c) (char-downcase c) (char-upcase c)))", o,
					"Char code", "Lower cased", "Upper cased");
			case LispString string -> array(out, o);
			case LispArray array -> array(out, o);
			case LispFloatArray array -> array(out, o);
			case LispIntVector array -> array(out, o);
			case LispHashTable table -> hashTable(out, table);
			case LispLambda lambda when generic(lambda.name()) != null ->
				genericFunction(out, Objects.requireNonNull(generic(lambda.name())));
			case LispLambda lambda -> function(out, o, lambda.name());
			case LispFunction function when generic(function.name()) != null ->
				genericFunction(out, Objects.requireNonNull(generic(function.name())));
			case LispFunction function -> function(out, o, function.name());
			case LispInstance instance when instance.layout().kind() == LispLayout.Kind.PATHNAME ->
				pathname(out, instance);
			case LispInstance instance when isA(instance, ClosRegistry.CLASS_NAME) -> classPage(out, s, instance);
			case LispInstance instance when slotDefinition(instance) -> slotDefinitionPage(out, s, instance);
			case LispInstance instance when instance.layout().kind() == LispLayout.Kind.CLASS ->
				standardObject(out, s, instance);
			case LispInstance instance when instance.layout().kind() == LispLayout.Kind.STRUCT ->
				structure(out, instance);
			case LispJavaObject wrapped when wrapped.ref() instanceof Method method -> method(out, method);
			case LispJavaObject wrapped when wrapped.ref() instanceof Symbols symbols -> symbols(out, symbols);
			default -> unknown(out, o);
		}
		return out;
	}

	/** SWANK's {@code label-value-line}: a label, a colon, a value and a newline. */
	private static void line(List<Raw> out, String label, LispVal value) {
		out.add(new RText(label));
		out.add(new RText(": "));
		out.add(new RValue(value, null));
		out.add(new RNewline());
	}

	/** Lines labelled in turn, their values the list a helper answers. */
	private void lines(List<Raw> out, String helper, LispVal object, String... labels) {
		List<LispVal> values = elements(this.lisp.helper(helper, object));
		for (int i = 0; i < labels.length && i < values.size(); i++) {
			line(out, labels[i], values.get(i));
		}
	}

	private void cons(List<Raw> out, LispCons cons) {
		if (!(cons.cdr() instanceof LispCons) && !(cons.cdr() instanceof LispNil)) {
			line(out, "CAR", cons.car());
			line(out, "CDR", cons.cdr());
			return;
		}
		// the elements up to a cycle back into the list, if there is one
		Map<LispCons, Integer> seen = new IdentityHashMap<>();
		List<LispVal> elements = new ArrayList<>();
		LispVal rest = cons;
		int cycle = -1;
		while (rest instanceof LispCons cell) {
			Integer at = seen.putIfAbsent(cell, elements.size());
			if (at != null) {
				cycle = at;
				break;
			}
			elements.add(cell.car());
			rest = cell.cdr();
		}
		if (cycle == 0) {
			out.add(new RText("A circular list:"));
		}
		else if (cycle > 0) {
			out.add(new RText("A list containing a cycle, starting from element " + cycle + ":"));
		}
		else {
			out.add(new RText(rest instanceof LispNil ? "A proper list:" : "An improper list:"));
		}
		out.add(new RNewline());
		for (int i = 0; i < elements.size(); i++) {
			line(out, String.valueOf(i), elements.get(i));
		}
		if (cycle < 0 && !(rest instanceof LispNil)) {
			line(out, "TAIL", rest);
		}
	}

	private void symbol(List<Raw> out, LispVal symbol) {
		String spelling = switch (symbol) {
			case LispSymbol s -> s.name();
			case LispTrue t -> "T";
			default -> "NIL";
		};
		List<LispVal> facts = elements(this.lisp
			.helper("(lambda (s) (list (symbol-name s)" + " (ignore-errors (boundp s)) (ignore-errors (constantp s))"
					+ " (ignore-errors (and (boundp s) (symbol-value s))) (ignore-errors (fboundp s))"
					+ " (ignore-errors (and (fboundp s) (macro-function s)))"
					+ " (ignore-errors (and (fboundp s) (symbol-function s))) (ignore-errors (symbol-package s))"
					+ " (ignore-errors (nth-value 1 (find-symbol (symbol-name s) (symbol-package s))))"
					+ " (ignore-errors (symbol-plist s)) (ignore-errors (find-class s nil))"
					+ " (ignore-errors (find-package s))))", symbol));
		line(out, "Its name is", facts.get(0));
		if (truthy(facts.get(1))) {
			out.add(new RText(truthy(facts.get(2)) ? "It is a constant of value" : "It is a global variable bound to"));
			out.add(new RText(": "));
			out.add(new RValue(facts.get(3), null));
			out.add(new RText(" "));
			out.add(new RAction("[unbind]", () -> call("(lambda (s) (makunbound s))", symbol), true));
			out.add(new RNewline());
		}
		else {
			out.add(new RText("It is unbound."));
			out.add(new RNewline());
		}
		if (truthy(facts.get(4))) {
			boolean macro = truthy(facts.get(5)) || this.evaluator.isUserMacro(spelling);
			if (macro) {
				out.add(new RText("It is a macro with macro-function: "));
				out.add(new RValue(facts.get(5), null));
			}
			else {
				out.add(new RText("It is a function: "));
				out.add(new RValue(facts.get(6), null));
			}
			out.add(new RText(" "));
			out.add(new RAction("[unbind]", () -> call("(lambda (s) (fmakunbound s))", symbol), true));
			out.add(new RNewline());
		}
		else {
			out.add(new RText("It has no function value."));
			out.add(new RNewline());
		}
		LispEvaluator.Definition definition = this.evaluator.definition(spelling);
		docstring(out, "Function documentation", definition == null ? null : definition.documentation());
		LispVal pkg = facts.get(7);
		if (truthy(pkg)) {
			String status = facts.get(8) instanceof LispSymbol s ? LispSymbol.memberName(s.name()) : "internal";
			out.add(new RText("It is "));
			out.add(new RText(status.toLowerCase(Locale.ROOT)));
			out.add(new RText(" to the package: "));
			out.add(new RValue(pkg, packageName(pkg)));
			if ("INTERNAL".equalsIgnoreCase(status)) {
				out.add(new RText(" "));
				out.add(new RAction("[export]", () -> call("(lambda (s) (export s (symbol-package s)))", symbol),
						true));
			}
			out.add(new RText(" "));
			out.add(new RAction("[unintern]", () -> call("(lambda (s) (unintern s (symbol-package s)))", symbol),
					true));
			out.add(new RNewline());
		}
		else {
			out.add(new RText("It is a non-interned symbol."));
			out.add(new RNewline());
		}
		line(out, "Property list", facts.get(9));
		if (truthy(facts.get(10))) {
			out.add(new RText("It names the class "));
			out.add(new RValue(facts.get(10), LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT)));
			out.add(new RText(" "));
			out.add(new RAction("[remove]", () -> call("(lambda (s) (setf (find-class s) nil))", symbol), true));
			out.add(new RNewline());
		}
		if (truthy(facts.get(11))) {
			line(out, "It names the package", facts.get(11));
		}
	}

	private String packageName(LispVal pkg) {
		LispVal name = this.lisp.helper("(lambda (p) (package-name p))", pkg);
		return name instanceof LispString text ? text.value() : this.lisp.print(pkg);
	}

	/** SWANK's {@code docstring-ispec}: a documentation string on its line, or below. */
	private static void docstring(List<Raw> out, String label, @Nullable String doc) {
		if (doc == null) {
			return;
		}
		if (label.length() + doc.length() < 75) {
			out.add(new RText(label));
			out.add(new RText(": "));
			out.add(new RText(doc));
		}
		else {
			out.add(new RText(label));
			out.add(new RText(":"));
			out.add(new RNewline());
			out.add(new RText("  "));
			out.add(new RText(doc));
		}
		out.add(new RNewline());
	}

	private void integer(List<Raw> out, LispVal object, BigInteger value) {
		LispVal text = this.lisp
			.helper("(lambda (i) (format nil \"Value: ~D = #x~8,'0X = #o~O = #b~,,' ,8:B~@[ = ~E~]\""
					+ " i i i i (ignore-errors (coerce i 'float))))", object);
		out.add(new RText(text instanceof LispString s ? s.value() : "Value: " + value));
		out.add(new RNewline());
		if (value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Character.MAX_CODE_POINT)) <= 0) {
			line(out, "Code-char", new LispChar(value.intValue()));
		}
		line(out, "Integer-length", new LispInteger(value.signum() >= 0 ? value.bitLength() : value.not().bitLength()));
		String time = universalTime(value);
		if (time != null) {
			line(out, "Universal-time", LispString.literal(time));
		}
	}

	/** A universal time in ISO 8601 with the zone, as the fancy inspector prints one. */
	private static @Nullable String universalTime(BigInteger value) {
		if (value.signum() < 0 || value.bitLength() > 40) {
			return null;
		}
		try {
			ZonedDateTime time = Instant.parse("1900-01-01T00:00:00Z")
				.plusSeconds(value.longValueExact())
				.atZone(ZoneId.systemDefault());
			int offset = time.getOffset().getTotalSeconds();
			String zone = offset == 0 ? "Z" : String.format("%s%02d:%02d", offset > 0 ? "+" : "-",
					Math.abs(offset) / 3600, Math.abs(offset) % 3600 / 60);
			return String.format("%04d-%02d-%02dT%02d:%02d:%02d%s", time.getYear(), time.getMonthValue(),
					time.getDayOfMonth(), time.getHour(), time.getMinute(), time.getSecond(), zone);
		}
		catch (DateTimeException | ArithmeticException ex) {
			return null;
		}
	}

	private void flonum(List<Raw> out, LispDouble d) {
		double value = d.value();
		if (Double.isNaN(value)) {
			out.add(new RText("Not a Number."));
			return;
		}
		if (Double.isInfinite(value)) {
			out.add(new RText(value > 0 ? "Positive infinity." : "Negative infinity."));
			return;
		}
		List<LispVal> facts = elements(this.lisp
			.helper("(lambda (f) (list (format nil \"~E\" f) (multiple-value-list (decode-float f))))", d));
		List<LispVal> decoded = elements(facts.get(1));
		out.add(new RText("Scientific: "));
		out.add(new RText(facts.get(0) instanceof LispString s ? s.value() : String.valueOf(value)));
		out.add(new RNewline());
		out.add(new RText("Decoded: "));
		out.add(new RValue(decoded.size() > 2 ? decoded.get(2) : d, null));
		out.add(new RText(" * "));
		out.add(new RValue(decoded.isEmpty() ? d : decoded.get(0), null));
		out.add(new RText(" * "));
		out.add(new RValue(new LispInteger(2), null));
		out.add(new RText("^"));
		out.add(new RValue(decoded.size() > 1 ? decoded.get(1) : new LispInteger(0), null));
		out.add(new RNewline());
		line(out, "Digits", new LispInteger(53));
		line(out, "Precision", new LispInteger(53));
	}

	private void array(List<Raw> out, LispVal array) {
		List<LispVal> facts = elements(this.lisp.helper("(lambda (a) (list (array-dimensions a)"
				+ " (array-element-type a) (array-total-size a) (adjustable-array-p a)"
				+ " (and (array-has-fill-pointer-p a) (fill-pointer a))"
				+ " (ignore-errors (multiple-value-list (array-displacement a)))"
				+ " (let ((out nil)) (dotimes (i (array-total-size a) (nreverse out))"
				+ " (push (row-major-aref a i) out)))))", array));
		out.add(new RLine("Dimensions", facts.get(0)));
		out.add(new RLine("Element type", facts.get(1)));
		out.add(new RLine("Total size", facts.get(2)));
		out.add(new RLine("Adjustable", facts.get(3)));
		out.add(new RLine("Fill pointer", facts.get(4)));
		List<LispVal> displacement = elements(facts.get(5));
		if (!displacement.isEmpty() && truthy(displacement.getFirst())) {
			out.add(new RLine("Displaced to", displacement.get(0)));
			out.add(new RLine("Displaced index offset",
					displacement.size() > 1 ? displacement.get(1) : LispNil.INSTANCE));
		}
		List<LispVal> elements = elements(facts.get(6));
		if (facts.get(4) instanceof LispInteger fill) {
			int active = (int) Math.min(fill.value(), elements.size());
			if (active > 0) {
				out.add(new RText("Active elements:"));
				out.add(new RNewline());
				for (int i = 0; i < active; i++) {
					out.add(new RLine(String.valueOf(i), elements.get(i)));
				}
			}
			if (active < elements.size()) {
				out.add(new RText("Inactive elements:"));
				out.add(new RNewline());
				for (int i = active; i < elements.size(); i++) {
					out.add(new RLine(String.valueOf(i), elements.get(i)));
				}
			}
		}
		else if (!elements.isEmpty()) {
			out.add(new RText("Contents:"));
			out.add(new RNewline());
			for (int i = 0; i < elements.size(); i++) {
				out.add(new RLine(String.valueOf(i), elements.get(i)));
			}
		}
	}

	private void hashTable(List<Raw> out, LispHashTable table) {
		lines(out,
				"(lambda (h) (list (hash-table-count h) (hash-table-size h) (hash-table-test h)"
						+ " (hash-table-rehash-size h) (hash-table-rehash-threshold h)))",
				table, "Count", "Size", "Test", "Rehash size", "Rehash threshold");
		List<LispHashTable.Entry> entries = new ArrayList<>(table.entries());
		if (entries.isEmpty()) {
			return;
		}
		out.add(new RAction("[clear hashtable]", table::clear, true));
		out.add(new RNewline());
		out.add(new RText("Contents: "));
		out.add(new RNewline());
		if (entries.stream().allMatch(e -> e.key() instanceof LispString || e.key() instanceof LispSymbol)) {
			entries.sort(Comparator.comparing(e -> stringDesignator(e.key())));
		}
		else if (entries.stream().allMatch(e -> real(e.key()) != null)) {
			entries.sort(Comparator.comparing(e -> real(e.key())));
		}
		for (LispHashTable.Entry entry : entries) {
			out.add(new RValue(entry.key(), null));
			out.add(new RText(" = "));
			out.add(new RValue(entry.value(), null));
			out.add(new RText(" "));
			out.add(new RAction("[remove entry]", () -> table.remove(entry.key()), true));
			out.add(new RNewline());
		}
	}

	private static String stringDesignator(LispVal key) {
		return key instanceof LispString s ? s.value() : LispSymbol.memberName(((LispSymbol) key).name());
	}

	private static @Nullable Double real(LispVal value) {
		return switch (value) {
			case LispInteger i -> (double) i.value();
			case LispBigInteger big -> big.value().doubleValue();
			case LispDouble d -> d.value();
			case LispRatio r -> new java.math.BigDecimal(r.numerator())
				.divide(new java.math.BigDecimal(r.denominator()), java.math.MathContext.DECIMAL64)
				.doubleValue();
			default -> null;
		};
	}

	private void function(List<Raw> out, LispVal function, @Nullable String name) {
		line(out, "Name", name == null ? LispNil.INSTANCE : new LispSymbol(name));
		LispVal arglist = name == null ? null : this.lisp.arglist(name);
		if (arglist == null && function instanceof LispLambda lambda) {
			List<LispVal> params = new ArrayList<>(lambda.params());
			if (lambda.rest() != null) {
				params.add(new LispSymbol("&REST"));
				params.add(java.util.Objects.requireNonNull(lambda.rest()));
			}
			arglist = list(params);
		}
		out.add(new RText("Its argument list is: "));
		out.add(new RText(arglist == null || arglist instanceof LispNil ? "()" : this.lisp.arglistText(arglist)));
		out.add(new RNewline());
		LispEvaluator.Definition definition = name == null ? null : this.evaluator.definition(name);
		docstring(out, "Documentation", definition == null ? null : definition.documentation());
	}

	/** A structure's slots, as SBCL's inspector shows one. */
	private void structure(List<Raw> out, LispInstance instance) {
		out.add(new RText("The object is a STRUCTURE-OBJECT of type " + instance.layout().printName() + "."));
		out.add(new RNewline());
		List<String> names = instance.layout().slotNames();
		for (int i = 0; i < names.size(); i++) {
			line(out, LispSymbol.memberName(names.get(i)).toUpperCase(Locale.ROOT), instance.slot(i));
		}
	}

	/**
	 * An instance's class and slots, as the fancy inspector's: check boxes to pick slots,
	 * the slots grouped by the class that declares them or all together, sorted or not,
	 * and the picked slots set or made unbound.
	 */
	private void standardObject(List<Raw> out, State s, LispInstance instance) {
		LispVal classObject = this.lisp.helper("(lambda (o) (class-of o))", instance);
		out.add(new RText("Class: "));
		out.add(new RValue(classObject, null));
		out.add(new RNewline());
		allSlots(out, s, instance);
	}

	/**
	 * SWANK's {@code all-slots-for-inspector}: an instance's slots, the check boxes and
	 * the actions on the picked ones.
	 */
	private void allSlots(List<Raw> out, State s, LispInstance instance) {
		String className = instance.layout().printName();
		ClosRegistry.ClassInfo info = this.evaluator.closRegistry().findClass(className);
		List<String> slots = new ArrayList<>(instance.layout().slotNames());
		boolean[] checked = (boolean[]) s.metadata.computeIfAbsent("checklist", k -> new boolean[slots.size()]);
		boolean byInheritance = Boolean.TRUE.equals(s.metadata.get("inheritance"));
		boolean alphabetically = !Boolean.FALSE.equals(s.metadata.get("alphabetically"));
		if (alphabetically) {
			slots.sort(Comparator.comparing(name -> LispSymbol.memberName(name)));
		}
		Map<String, String> home = new HashMap<>();
		if (info != null) {
			List<String> cpl = new ArrayList<>(info.cpl());
			Collections.reverse(cpl);
			for (String name : slots) {
				for (String each : cpl) {
					ClosRegistry.ClassInfo declaring = this.evaluator.closRegistry().findClass(each);
					if (declaring != null && declaring.directSlots()
						.stream()
						.anyMatch(slot -> LispSymbol.memberName(slot.name()).equals(LispSymbol.memberName(name)))) {
						home.putIfAbsent(name, LispSymbol.memberName(each));
						break;
					}
				}
			}
		}
		if (byInheritance) {
			slots.sort(Comparator.comparing(name -> home.getOrDefault(name, className)));
		}
		int longest = slots.stream().mapToInt(name -> LispSymbol.memberName(name).length()).max().orElse(0);
		out.add(new RText("--------------------"));
		out.add(new RNewline());
		out.add(new RText(" Group slots by inheritance "));
		out.add(new RAction(byInheritance ? "[X]" : "[ ]", () -> {
			java.util.Arrays.fill(checked, false);
			s.metadata.put("inheritance", !byInheritance);
		}, true));
		out.add(new RNewline());
		out.add(new RText(" Sort slots alphabetically  "));
		out.add(new RAction(alphabetically ? "[X]" : "[ ]", () -> {
			java.util.Arrays.fill(checked, false);
			s.metadata.put("alphabetically", !alphabetically);
		}, true));
		out.add(new RNewline());
		int[] button = { 0 };
		if (byInheritance) {
			String group = null;
			List<String> members = new ArrayList<>();
			for (String name : slots) {
				String declaring = home.getOrDefault(name, className);
				if (group != null && !group.equals(declaring)) {
					slotGroup(out, instance, group, members, checked, button, longest);
					members.clear();
				}
				group = declaring;
				members.add(name);
			}
			if (group != null) {
				slotGroup(out, instance, group, members, checked, button, longest);
			}
		}
		else {
			out.add(new RNewline());
			out.add(new RText("All Slots:"));
			out.add(new RNewline());
			slotListing(out, instance, slots, checked, button, longest);
		}
		out.add(new RNewline());
		List<String> listed = List.copyOf(slots);
		out.add(new RAction("[set value]", () -> {
			for (int i = 0; i < listed.size() && i < checked.length; i++) {
				if (checked[i]) {
					setSlot(instance, listed.get(i));
				}
			}
		}, true));
		out.add(new RText("  "));
		out.add(new RAction("[make unbound]", () -> {
			for (int i = 0; i < listed.size() && i < checked.length; i++) {
				if (checked[i]) {
					call("(lambda (args) (slot-makunbound (car args) (cadr args)))",
							list(instance, new LispSymbol(listed.get(i))));
				}
			}
		}, true));
		out.add(new RNewline());
	}

	private void slotGroup(List<Raw> out, LispInstance instance, String group, List<String> members, boolean[] checked,
			int[] button, int longest) {
		out.add(new RNewline());
		out.add(new RText(group + ":"));
		out.add(new RNewline());
		slotListing(out, instance, members, checked, button, longest);
	}

	private void slotListing(List<Raw> out, LispInstance instance, List<String> names, boolean[] checked, int[] button,
			int longest) {
		for (String name : names) {
			int index = button[0]++;
			out.add(new RAction(index < checked.length && checked[index] ? "[X]" : "[ ]", () -> {
				if (index < checked.length) {
					checked[index] = !checked[index];
				}
			}, true));
			out.add(new RText("  "));
			String member = LispSymbol.memberName(name).toUpperCase(Locale.ROOT);
			out.add(new RValue(new LispSymbol(name), member));
			out.add(new RText(" ".repeat(Math.max(0, longest - member.length()))));
			out.add(new RText(" = "));
			LispVal args = list(instance, new LispSymbol(name));
			if (truthy(
					this.lisp.helper("(lambda (args) (ignore-errors (slot-boundp (car args) (cadr args))))", args))) {
				out.add(new RValue(this.lisp.helper("(lambda (args) (slot-value (car args) (cadr args)))", args),
						null));
			}
			else {
				out.add(new RText("#<unbound>"));
			}
			out.add(new RNewline());
		}
	}

	private void setSlot(LispInstance instance, String slot) {
		Function<String, @Nullable String> ask = this.asking;
		String text = ask == null ? null
				: ask.apply("Set slot " + LispSymbol.memberName(slot).toUpperCase(Locale.ROOT) + " to (evaluated) : ");
		if (text == null || text.isEmpty()) {
			return;
		}
		LispVal value = this.evaluator.eval(this.lisp.readResolved(text));
		call("(lambda (args) (setf (slot-value (car args) (cadr args)) (caddr args)))",
				list(instance, new LispSymbol(slot), value));
	}

	// ------------------------------------------------------------------------------------
	// Metaobjects, packages and pathnames

	/** Whether an instance's class is a class or has it among its superclasses. */
	private boolean isA(LispInstance instance, String className) {
		ClosRegistry.ClassInfo info = this.evaluator.closRegistry().findClass(instance.layout().printName());
		return info != null && info.cpl().contains(className);
	}

	private boolean slotDefinition(LispInstance instance) {
		return isA(instance, ClosRegistry.STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME)
				|| isA(instance, ClosRegistry.STANDARD_DIRECT_SLOT_DEFINITION_NAME);
	}

	/** The value of an instance's slot by its member name, nil when it has none. */
	private static LispVal slot(LispInstance instance, String member) {
		List<String> names = instance.layout().slotNames();
		for (int i = 0; i < names.size() && i < instance.slotCount(); i++) {
			if (LispSymbol.memberName(names.get(i)).equalsIgnoreCase(member)) {
				LispVal value = instance.slot(i);
				return value == null ? LispNil.INSTANCE : value;
			}
		}
		return LispNil.INSTANCE;
	}

	/** A value printed as {@code princ-to-string} prints it. */
	private String princ(LispVal value) {
		LispVal text = this.lisp.helper("(lambda (x) (princ-to-string x))", value);
		return text instanceof LispString s ? s.value() : this.lisp.print(value);
	}

	/** SWANK's {@code common-seperated-spec}: the parts, a comma between two. */
	private static <T> void separated(List<Raw> out, List<T> items, Function<T, Raw> part) {
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				out.add(new RText(", "));
			}
			out.add(part.apply(items.get(i)));
		}
	}

	/** A class's metaobject by its name, or the name when it has none. */
	private LispVal classValue(String name) {
		LispInstance metaobject = null;
		try {
			metaobject = this.evaluator.closRegistry().classMetaobject(name);
		}
		catch (RuntimeException ex) {
			// no metaobject: the name stands for it
		}
		return metaobject != null ? metaobject : new LispSymbol(name);
	}

	/** A class's name as SWANK's page shows it: {@code princ}'d. */
	private String className(String name) {
		return princ(new LispSymbol(name));
	}

	/**
	 * A class, as the fancy inspector's page for one: its name, superclasses, slots,
	 * documentation, subclasses, precedence list, the methods specialized on it and a
	 * prototype, then its own slots.
	 */
	private void classPage(List<Raw> out, State s, LispInstance klass) {
		ClosRegistry registry = this.evaluator.closRegistry();
		LispVal name = slot(klass, "NAME");
		String spelling = name instanceof LispSymbol symbol ? symbol.name() : this.lisp.print(name);
		ClosRegistry.ClassInfo info = registry.findClass(spelling);
		String canonical = info != null ? info.name() : spelling;
		out.add(new RText("Name: "));
		out.add(new RValue(name, null));
		out.add(new RNewline());
		out.add(new RText("Super classes: "));
		separated(out, elements(slot(klass, "DIRECT-SUPERCLASSES")), each -> new RValue(each, null));
		out.add(new RNewline());
		List<LispVal> effective = elements(slot(klass, "EFFECTIVE-SLOTS"));
		List<LispVal> direct = elements(slot(klass, "DIRECT-SLOTS"));
		if (direct.isEmpty() && info != null) {
			// a plain class keeps no direct slot metaobjects: its own slots' effective
			// ones
			java.util.Set<String> own = new java.util.HashSet<>();
			for (ClosRegistry.SlotSpec spec : info.directSlots()) {
				own.add(spec.baseName().toUpperCase(Locale.ROOT));
			}
			direct = effective.stream()
				.filter(each -> each instanceof LispInstance def && own
					.contains(LispSymbol.memberName(this.lisp.print(slot(def, "NAME"))).toUpperCase(Locale.ROOT)))
				.toList();
		}
		for (LispVal each : effective) {
			this.slotOwners.put(each, canonical);
		}
		for (LispVal each : direct) {
			this.slotOwners.put(each, canonical);
		}
		out.add(new RText("Direct Slots: "));
		separated(out, direct, each -> new RValue(each, slotName(each)));
		out.add(new RNewline());
		out.add(new RText("Effective Slots: "));
		separated(out, effective, each -> new RValue(each, slotName(each)));
		out.add(new RNewline());
		String doc = this.sources.classDocumentation(canonical);
		if (doc != null) {
			out.add(new RText("Documentation:"));
			out.add(new RNewline());
			out.add(new RText(doc));
			out.add(new RNewline());
		}
		out.add(new RText("Sub classes: "));
		separated(out, registry.directSubclassNames(canonical), each -> new RValue(classValue(each), className(each)));
		out.add(new RNewline());
		out.add(new RText("Precedence List: "));
		separated(out, info == null ? List.<String>of() : info.cpl(),
				each -> new RValue(classValue(each), className(each)));
		out.add(new RNewline());
		List<Method> methods = specializedOn(canonical);
		if (!methods.isEmpty()) {
			out.add(new RText("It is used as a direct specializer in the following methods:"));
			out.add(new RNewline());
			for (Method method : methods) {
				out.add(new RText("  "));
				out.add(new RValue(new LispJavaObject(method), princ(methodForInspect(method))));
				out.add(new RNewline());
				String methodDoc = info == null ? null : accessorDocumentation(method, info);
				if (methodDoc != null) {
					out.add(new RText("    Documentation: "));
					out.add(new RText(abbreviate(methodDoc)));
					out.add(new RNewline());
				}
			}
		}
		out.add(new RText("Prototype: "));
		out.add(new RValue(this.lisp.helper("(lambda (c) (ignore-errors (allocate-instance c)))", klass), null));
		out.add(new RNewline());
		allSlots(out, s, klass);
	}

	private String slotName(LispVal slotDefinition) {
		return slotDefinition instanceof LispInstance def ? princ(slot(def, "NAME")) : princ(slotDefinition);
	}

	/** SWANK's {@code abbrev-doc}: the first sentence, at most 80 characters. */
	private static String abbreviate(String doc) {
		int period = doc.indexOf('.');
		return doc.substring(0, Math.min(Math.min(period >= 0 ? period + 1 : 79, 80), doc.length()));
	}

	/**
	 * The methods with a class among their specializers, ordered by their generic
	 * function's name, as SWANK's {@code specializer-direct-methods}.
	 */
	private List<Method> specializedOn(String className) {
		List<Method> out = new ArrayList<>();
		for (ClosRegistry.GenericInfo generic : List.copyOf(this.evaluator.closRegistry().generics().values())) {
			for (Map.Entry<String, ClosRegistry.MethodInfo> entry : List.copyOf(generic.methods().entrySet())) {
				boolean on = entry.getValue()
					.specializers()
					.stream()
					.anyMatch(spec -> spec.kind() == ClosRegistry.SpecializerKind.CLASS && spec.name() != null
							&& ClosRegistry.normalize(spec.name()).equals(ClosRegistry.normalize(className)));
				if (on) {
					out.add(new Method(generic.name(), entry.getKey(), entry.getValue()));
				}
			}
		}
		out.sort(Comparator.comparing(method -> placeName(method.generic())));
		return out;
	}

	/** The documentation a slot accessor's method carries: its slot's. */
	private @Nullable String accessorDocumentation(Method method, ClosRegistry.ClassInfo info) {
		String member = LispSymbol.memberName(method.generic());
		String place = member.startsWith(ClosRegistry.SETF_FUNCTION_PREFIX)
				? member.substring(ClosRegistry.SETF_FUNCTION_PREFIX.length()) : null;
		for (ClosRegistry.SlotSpec spec : info.directSlots()) {
			boolean accessor = spec.readers().stream().anyMatch(r -> LispSymbol.memberName(r).equalsIgnoreCase(member))
					|| spec.accessors()
						.stream()
						.anyMatch(a -> LispSymbol.memberName(a).equalsIgnoreCase(place != null ? place : member))
					|| spec.writers().stream().anyMatch(w -> LispSymbol.memberName(w).equalsIgnoreCase(member));
			if (accessor) {
				return this.sources.slotDocumentation(info.name(), spec.baseName());
			}
		}
		return null;
	}

	/** A function's name: a symbol, or {@code (setf place)} for a writer. */
	private static LispVal functionName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		String member = qn == null ? name : qn.member();
		if (member.startsWith(ClosRegistry.SETF_FUNCTION_PREFIX)) {
			String place = member.substring(ClosRegistry.SETF_FUNCTION_PREFIX.length());
			return list(new LispSymbol(LispNames.SETF), new LispSymbol(qn == null ? place : qn.pkg() + "::" + place));
		}
		return new LispSymbol(name);
	}

	/** The name a generic function sorts by: its place's for a writer. */
	private static String placeName(String name) {
		String member = LispSymbol.memberName(name);
		return member.startsWith(ClosRegistry.SETF_FUNCTION_PREFIX)
				? member.substring(ClosRegistry.SETF_FUNCTION_PREFIX.length()).toUpperCase(Locale.ROOT)
				: member.toUpperCase(Locale.ROOT);
	}

	private static List<LispVal> qualifiers(Method method) {
		String qualifier = method.info().qualifier();
		return qualifier.isEmpty() ? List.of() : List.of(new LispSymbol(qualifier));
	}

	/** A method's specializers as SWANK prints them: class names, {@code (eql x)}. */
	private static List<LispVal> specializerNames(Method method) {
		List<LispVal> out = new ArrayList<>();
		for (ClosRegistry.Specializer spec : method.info().specializers()) {
			out.add(switch (spec.kind()) {
				case EQL -> list(new LispSymbol(LispNames.EQL), Objects.requireNonNull(spec.eqlValue()));
				case CLASS, TYPE -> new LispSymbol(Objects.requireNonNull(spec.name()));
				case DEFAULT -> new LispSymbol("T");
			});
		}
		return out;
	}

	/**
	 * SWANK's {@code method-for-inspect-value}: the generic's name, qualifiers,
	 * specializers.
	 */
	private static LispVal methodForInspect(Method method) {
		List<LispVal> parts = new ArrayList<>();
		parts.add(functionName(method.generic()));
		parts.addAll(qualifiers(method));
		parts.addAll(specializerNames(method));
		return list(parts);
	}

	/** A slot definition, as the fancy inspector's page for one, then its own slots. */
	private void slotDefinitionPage(List<Raw> out, State s, LispInstance definition) {
		LispVal name = slot(definition, "NAME");
		out.add(new RText("Name: "));
		out.add(new RValue(name, null));
		out.add(new RNewline());
		String owner = this.slotOwners.get(definition);
		String doc = owner == null ? null : this.sources.slotDocumentation(owner, princ(name));
		if (doc != null) {
			out.add(new RText("Documentation:"));
			out.add(new RNewline());
			out.add(new RValue(LispString.literal(doc), null));
			out.add(new RNewline());
		}
		out.add(new RText("Init args: "));
		out.add(new RValue(slot(definition, "INITARGS"), null));
		out.add(new RNewline());
		out.add(new RText("Init form: "));
		LispVal initfunction = slot(definition, "INITFUNCTION");
		LispVal initform = slot(definition, "INITFORM");
		// a plain class's slot definition carries the initform it wrote, no function
		if (truthy(initfunction) || truthy(initform)) {
			out.add(new RValue(initform, null));
		}
		else {
			out.add(new RText("#<unspecified>"));
		}
		out.add(new RNewline());
		out.add(new RText("Init function: "));
		out.add(new RValue(initfunction, null));
		out.add(new RNewline());
		allSlots(out, s, definition);
	}

	/** The generic function a function value's name names, or null. */
	private ClosRegistry.@Nullable GenericInfo generic(@Nullable String name) {
		return name == null ? null : this.evaluator.closRegistry().findGeneric(name);
	}

	/**
	 * A generic function, as the fancy inspector's page for one: its name, argument list,
	 * documentation, method combination and methods, each with {@code [remove method]}.
	 */
	private void genericFunction(List<Raw> out, ClosRegistry.GenericInfo generic) {
		String name = generic.name();
		line(out, "Name", functionName(name));
		LispVal arglist = this.lisp.arglist(name);
		if (arglist == null) {
			arglist = list(generic.paramNames().stream().<LispVal>map(LispSymbol::new).toList());
		}
		line(out, "Arguments", arglist);
		LispEvaluator.Definition definition = this.evaluator.definition(name);
		String doc = generic.documentation();
		docstring(out, "Documentation", doc != null ? doc : definition == null ? null : definition.documentation());
		String combination = generic.methodCombination();
		line(out, "Method combination",
				new LispSymbol(combination == null ? "STANDARD" : combination.toUpperCase(Locale.ROOT)));
		out.add(new RText("Methods: "));
		out.add(new RNewline());
		List<Method> methods = new ArrayList<>();
		for (Map.Entry<String, ClosRegistry.MethodInfo> entry : List.copyOf(generic.methods().entrySet())) {
			methods.add(new Method(name, entry.getKey(), entry.getValue()));
		}
		for (Method method : byApplicability(methods)) {
			List<LispVal> label = new ArrayList<>(qualifiers(method));
			label.addAll(specializerNames(method));
			out.add(new RValue(new LispJavaObject(method), princ(list(label))));
			out.add(new RText(" "));
			out.add(new RAction("[remove method]", () -> this.evaluator.removeMethod(name, method.key()), false));
			out.add(new RNewline());
		}
		out.add(new RNewline());
	}

	/**
	 * Methods ordered most specific first, as SWANK's {@code methods-by-applicability}: a
	 * stable sort on the first specializer pair that differs.
	 */
	private List<Method> byApplicability(List<Method> methods) {
		List<Method> out = new ArrayList<>();
		for (Method method : methods) {
			int at = out.size();
			while (at > 0 && before(method, out.get(at - 1))) {
				at--;
			}
			out.add(at, method);
		}
		return out;
	}

	private boolean before(Method a, Method b) {
		List<ClosRegistry.Specializer> as = a.info().specializers();
		List<ClosRegistry.Specializer> bs = b.info().specializers();
		for (int i = 0; i < as.size() && i < bs.size(); i++) {
			if (moreSpecific(bs.get(i), as.get(i))) {
				return false;
			}
			if (moreSpecific(as.get(i), bs.get(i))) {
				return true;
			}
		}
		return false;
	}

	private boolean moreSpecific(ClosRegistry.Specializer a, ClosRegistry.Specializer b) {
		if (a.kind() == ClosRegistry.SpecializerKind.EQL) {
			return b.kind() != ClosRegistry.SpecializerKind.EQL;
		}
		if (a.kind() == ClosRegistry.SpecializerKind.DEFAULT || b.kind() == ClosRegistry.SpecializerKind.EQL) {
			return false;
		}
		if (b.kind() == ClosRegistry.SpecializerKind.DEFAULT) {
			return true;
		}
		ClosRegistry.ClassInfo info = this.evaluator.closRegistry().findClass(Objects.requireNonNull(a.name()));
		return info != null && !Objects.equals(a.name(), b.name())
				&& info.cpl()
					.stream()
					.anyMatch(each -> ClosRegistry.normalize(each)
						.equals(ClosRegistry.normalize(Objects.requireNonNull(b.name()))));
	}

	/**
	 * A method, as the fancy inspector's page for one: its generic function, lambda list
	 * (the generic's: a method's own is not kept), specializers, qualifiers and function.
	 */
	private void method(List<Raw> out, Method method) {
		LispVal generic = this.evaluator.globalEnvironment().lookupFunctionOrNull(method.generic());
		if (generic != null) {
			out.add(new RText("Method defined on the generic function "));
			out.add(new RValue(generic, princ(functionName(method.generic()))));
		}
		else {
			out.add(new RText("Method without a generic function"));
		}
		out.add(new RNewline());
		LispVal arglist = this.lisp.arglist(method.generic());
		ClosRegistry.GenericInfo info = generic(method.generic());
		if (arglist == null) {
			arglist = list(
					info == null ? List.of() : info.paramNames().stream().<LispVal>map(LispSymbol::new).toList());
		}
		out.add(new RText("Lambda List: "));
		out.add(new RValue(arglist, null));
		out.add(new RNewline());
		List<LispVal> specializers = new ArrayList<>();
		for (ClosRegistry.Specializer spec : method.info().specializers()) {
			specializers.add(switch (spec.kind()) {
				case EQL -> list(new LispSymbol(LispNames.EQL), Objects.requireNonNull(spec.eqlValue()));
				case CLASS, TYPE -> classValue(Objects.requireNonNull(spec.name()));
				case DEFAULT -> classValue("T");
			});
		}
		out.add(new RText("Specializers: "));
		out.add(new RValue(list(specializers), princ(list(specializerNames(method)))));
		out.add(new RNewline());
		out.add(new RText("Qualifiers: "));
		out.add(new RValue(list(qualifiers(method)), null));
		out.add(new RNewline());
		LispVal function = this.evaluator.globalEnvironment().lookupFunctionOrNull(method.info().functionName());
		out.add(new RText("Method function: "));
		out.add(new RValue(function == null ? LispNil.INSTANCE : function, null));
		out.add(new RNewline());
	}

	/** Whether a keyword names a package: a package is its keyword here. */
	private boolean packageDesignator(LispSymbol symbol) {
		return symbol.name().startsWith(":")
				&& truthy(this.lisp.helper("(lambda (o) (ignore-errors (packagep o)))", symbol));
	}

	/**
	 * A package, as the fancy inspector's page for one: its name, nicknames, use lists
	 * and its symbols counted by status, each count a page listing them.
	 */
	private void packagePage(List<Raw> out, LispSymbol pkg) {
		List<LispVal> facts = elements(this.lisp
			.helper("(lambda (p) (list (package-name p)" + " (sort (copy-list (package-nicknames p)) #'string<)"
					+ " (ignore-errors (package-local-nicknames p)) (ignore-errors (documentation p t))"
					+ " (sort (copy-list (package-use-list p)) #'string< :key #'package-name)"
					+ " (sort (copy-list (package-used-by-list p)) #'string< :key #'package-name)"
					+ " (mapcar #'symbol-name (package-shadowing-symbols p))))", pkg));
		String name = facts.get(0) instanceof LispString s ? s.value() : this.lisp.print(facts.get(0));
		out.add(new RText(""));
		out.add(new RText("Name: "));
		out.add(new RValue(facts.get(0), null));
		out.add(new RNewline());
		out.add(new RText("Nicknames: "));
		separated(out, elements(facts.get(1)), each -> new RValue(each, null));
		out.add(new RNewline());
		if (truthy(facts.get(2))) {
			out.add(new RText("Package-local nicknames: "));
			out.add(new RValue(facts.get(2), null));
			out.add(new RNewline());
		}
		if (facts.get(3) instanceof LispString doc) {
			out.add(new RText("Documentation:"));
			out.add(new RNewline());
			out.add(new RText(doc.value()));
			out.add(new RNewline());
		}
		out.add(new RText("Use list: "));
		separated(out, elements(facts.get(4)), each -> new RValue(each, packageName(each)));
		out.add(new RNewline());
		out.add(new RText("Used by list: "));
		separated(out, elements(facts.get(5)), each -> new RValue(each, packageName(each)));
		out.add(new RNewline());
		String canonical = this.lisp.guessPackage(LispSymbol.memberName(pkg.name()));
		List<String> present = new ArrayList<>();
		List<String> external = new ArrayList<>();
		List<String> internal = new ArrayList<>();
		List<String> inherited = new ArrayList<>();
		if (canonical != null) {
			for (PackageResolver.Accessible each : this.lisp.accessibleSymbols(canonical).values()) {
				switch (each.status()) {
					case LispNames.STATUS_INHERITED -> inherited.add(each.spelling());
					case LispNames.STATUS_EXTERNAL -> {
						present.add(each.spelling());
						external.add(each.spelling());
					}
					default -> {
						present.add(each.spelling());
						internal.add(each.spelling());
					}
				}
			}
		}
		List<String> shadowed = new ArrayList<>();
		for (LispVal each : elements(facts.get(6))) {
			if (each instanceof LispString member) {
				String found = this.lisp.symbolNamed(name + "::" + member.value());
				shadowed.add(found != null ? found : member.value());
			}
		}
		shadowed.sort(Comparator.comparing(LispSymbol::memberName));
		symbolsLink(out, "present", present, name,
				texts("A symbol is considered present in a package if it's",
						"\"accessible in that package directly, rather than", "being inherited from another package.\"",
						"(CLHS glossary entry for `present')"));
		out.add(new RNewline());
		symbolsLink(out, "external", external, name, texts("A symbol is considered external of a package if it's",
				"\"part of the `external interface' to the package and",
				"[is] inherited by any other package that uses the", "package.\" (CLHS glossary entry of `external')"));
		out.add(new RNewline());
		symbolsLink(out, "internal", internal, name, texts("A symbol is considered internal of a package if it's",
				"present and not external---that is if the package is",
				"the home package of the symbol, or if the symbol has", "been explicitly imported into the package.",
				"", "Notice that inherited symbols will thus not be listed,",
				"which deliberately deviates from the CLHS glossary",
				"entry of `internal' because it's assumed to be more", "useful this way."));
		out.add(new RNewline());
		symbolsLink(out, "inherited", inherited, name,
				texts("A symbol is considered inherited in a package if it", "was made accessible via USE-PACKAGE."));
		out.add(new RNewline());
		symbolsLink(out, "shadowed", shadowed, name, List.of());
	}

	/** Lines of text, each ended by a newline, as SWANK's descriptions are written. */
	private static List<Raw> texts(String... lines) {
		List<Raw> out = new ArrayList<>();
		for (String line : lines) {
			if (!line.isEmpty()) {
				out.add(new RText(line));
			}
			out.add(new RNewline());
		}
		return out;
	}

	/** SWANK's {@code display-link}: a count of symbols, a page listing them. */
	private static void symbolsLink(List<Raw> out, String kind, List<String> spellings, String pkg,
			List<Raw> description) {
		int n = spellings.size();
		if (n == 0) {
			out.add(new RText("0 " + kind + " symbols."));
			return;
		}
		Symbols symbols = new Symbols("All " + kind + " symbols of package \"" + pkg + "\"", description,
				List.copyOf(spellings));
		out.add(new RValue(new LispJavaObject(symbols), n + " " + kind + " symbol" + (n == 1 ? "" : "s") + "."));
	}

	/** The classifications SWANK groups symbols by, each with its flag letter. */
	private static final List<Map.Entry<String, Character>> CLASSIFICATIONS = List.of(Map.entry("BOUNDP", 'b'),
			Map.entry("CLASS", 'c'), Map.entry("FUNCTION", 'f'), Map.entry("GENERIC-FUNCTION", 'g'),
			Map.entry("MACRO", 'm'), Map.entry("PACKAGE", 'p'), Map.entry("SPECIAL-OPERATOR", 's'),
			Map.entry("TYPESPEC", 't'));

	/**
	 * Some of a package's symbols, as SWANK's {@code %package-symbols-container} page:
	 * listed with their classification flags, or grouped by classification.
	 */
	private void symbols(List<Raw> out, Symbols symbols) {
		out.add(new RText(symbols.title));
		out.add(new RNewline());
		out.add(new RNewline());
		out.addAll(symbols.description);
		out.add(new RNewline());
		out.add(new RText("  "));
		boolean grouped = symbols.byClassification;
		out.add(new RAction(grouped ? "[Group by symbol]" : "[Group by classification]",
				() -> symbols.byClassification = !grouped, true));
		out.add(new RNewline());
		out.add(new RNewline());
		if (!grouped) {
			int longest = symbols.spellings.stream().mapToInt(s -> LispSymbol.memberName(s).length()).max().orElse(0);
			int distance = 10;
			out.add(new RText(""));
			out.add(new RText("Symbols:"));
			out.add(new RText(" ".repeat(Math.max(0, longest + distance - 8))));
			out.add(new RText("Flags:"));
			out.add(new RNewline());
			out.add(new RText("-".repeat(Math.max(0, longest + distance - 1)) + " ---------"));
			out.add(new RNewline());
			for (String spelling : symbols.spellings) {
				String member = LispSymbol.memberName(spelling);
				out.add(new RValue(new LispSymbol(spelling),
						member + " ".repeat(longest - member.length() + distance)));
				out.add(new RText(this.lisp.classification(spelling)));
				out.add(new RNewline());
			}
			return;
		}
		Map<String, List<String>> groups = new java.util.TreeMap<>();
		for (String spelling : symbols.spellings) {
			String flags = this.lisp.classification(spelling);
			boolean any = false;
			for (Map.Entry<String, Character> each : CLASSIFICATIONS) {
				char letter = each.getValue();
				boolean has = flags.indexOf(letter) >= 0
						&& (letter != 'f' || flags.indexOf('m') < 0 && flags.indexOf('s') < 0);
				if (has) {
					groups.computeIfAbsent(each.getKey(), k -> new ArrayList<>()).add(spelling);
					any = true;
				}
			}
			if (!any) {
				groups.computeIfAbsent("~MISC", k -> new ArrayList<>()).add(spelling);
			}
		}
		for (Map.Entry<String, List<String>> group : groups.entrySet()) {
			out.add(new RText(group.getKey().equals("~MISC") ? "MISC" : group.getKey()));
			out.add(new RNewline());
			out.add(new RText("-".repeat(64)));
			out.add(new RNewline());
			for (String spelling : group.getValue()) {
				out.add(new RValue(new LispSymbol(spelling), LispSymbol.memberName(spelling)));
				out.add(new RNewline());
			}
			out.add(new RNewline());
		}
	}

	/** A pathname, as the fancy inspector's page for one. */
	private void pathname(List<Raw> out, LispInstance pathname) {
		List<LispVal> facts = elements(this.lisp.helper("(lambda (p) (list (wild-pathname-p p) (namestring p)"
				+ " (pathname-host p) (pathname-device p) (pathname-directory p) (pathname-name p)"
				+ " (pathname-type p) (pathname-version p)"
				+ " (and (not (wild-pathname-p p)) (ignore-errors (probe-file p)))))", pathname));
		out.add(new RText(truthy(facts.get(0)) ? "A wild pathname." : "A pathname."));
		out.add(new RNewline());
		String[] labels = { "Namestring", "Host", "Device", "Directory", "Name", "Type", "Version" };
		for (int i = 0; i < labels.length; i++) {
			line(out, labels[i], facts.get(i + 1));
		}
		if (truthy(facts.get(8))) {
			line(out, "Truename", facts.get(8));
		}
	}

	private void unknown(List<Raw> out, LispVal object) {
		out.add(new RText("Type: "));
		out.add(new RValue(this.lisp.helper("(lambda (o) (type-of o))", object), null));
		out.add(new RNewline());
		out.add(new RText("Don't know how to inspect the object, dumping output of CL:DESCRIBE:"));
		out.add(new RNewline());
		out.add(new RNewline());
		out.add(new RText(describe(object)));
	}

	/** A value described as SBCL's {@code describe} starts: printed, then its type. */
	private String describe(LispVal object) {
		if (object instanceof LispSymbol symbol && !symbol.name().startsWith(":")) {
			return this.lisp.describeSymbol(this.lisp.print(symbol));
		}
		String type;
		try {
			LispVal typeOf = this.lisp.helper("(lambda (o) (type-of o))", object);
			type = this.lisp.print(typeOf instanceof LispCons cell ? cell.car() : typeOf);
		}
		catch (RuntimeException ex) {
			type = "T";
		}
		if (object instanceof LispCons) {
			type = "LIST";
		}
		return display(object) + "\n  [" + type.toLowerCase(Locale.ROOT) + "]\n";
	}

	// ------------------------------------------------------------------------------------
	// Helpers

	private void call(String helper, LispVal argument) {
		this.lisp.helper(helper, argument);
	}

	private static boolean truthy(LispVal value) {
		return !(value instanceof LispNil);
	}

	private static List<LispVal> elements(LispVal list) {
		List<LispVal> out = new ArrayList<>();
		for (LispVal rest = list; rest instanceof LispCons cell; rest = cell.cdr()) {
			out.add(cell.car());
		}
		return out;
	}

	private static LispVal list(List<LispVal> items) {
		LispVal out = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

}
