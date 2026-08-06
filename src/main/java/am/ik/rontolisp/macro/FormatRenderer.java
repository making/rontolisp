package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The runtime {@code format} renderer: the ONE implementation of the directive set that
 * runs when the control string is not a compile-time literal.
 *
 * <p>
 * {@code (format dest "literal" args...)} is lowered by
 * {@link LispMacroExpander#expandFormat} into string pieces and never reaches this class.
 * Every other shape funnels into {@code %fmt-render}: a computed control expression,
 * {@code #'format} taken as a value (its {@code BuiltinFunctionWrappers} wrapper), the
 * {@code ~?} directive, a literal control the static expansion declines
 * ({@code UnsupportedOperationException}), and a condition's {@code format-control} slot
 * ({@code %format-condition}). Before this class existed each of those inlined a
 * hand-assembled lambda that understood {@code ~~ ~% ~a ~s ~d ~x ~c} only and emitted
 * every other directive LITERALLY while still consuming its argument -- so the tail of a
 * mixed control string came out shifted, not merely unrendered.
 *
 * <p>
 * The renderer is Lisp source ({@code format-render.lisp} on the classpath), read with
 * the real {@link LispReader}. That is why this package sits ABOVE {@code reader} in the
 * dependency order: an expander pass may BUILD the AST it injects by reading Lisp instead
 * of assembling {@code LispCons} nodes in Java. The forms are plain {@code defun}s, so
 * both consumers get the identical implementation:
 * <ul>
 * <li>the compile path injects {@link #defuns()} once per program from
 * {@link LispMacroExpander#expandTopLevelDefinitions} (gated by
 * {@code needsFormatRenderer}), like the condition-report runtime beside it;</li>
 * <li>the interpreter evaluates the same forms into the global environment on first
 * use.</li>
 * </ul>
 *
 * <p>
 * <strong>The {@code ~/name/} arm is a separate file</strong>
 * ({@link #functionDesignatorDefuns()}) because it is the one part of the renderer that
 * resolves a function out of runtime data -- and the renderer is spliced into every
 * program that formats a computed control, so carrying that arm cost every such program
 * {@code --optimize}'s funcall-dispatch gate
 * ({@code .kb/optimize-dead-code-elimination.md}). The compile path injects it only when
 * {@link #namesFunctionDesignator} holds (or under {@code --dynamic}) and
 * {@link #functionDesignatorStubDefuns()} otherwise; the interpreter always loads the
 * real one.
 */
public final class FormatRenderer {

	/**
	 * The renderer entry point: {@code (%fmt-render control-string argument-list)}
	 * answers the rendered string.
	 */
	public static final String RENDER = "%FMT-RENDER";

	/**
	 * The prefix every renderer definition carries. A cheap pre-test for the
	 * interpreter's lazy load, so an unresolved user name never parses the source.
	 */
	public static final String NAME_PREFIX = "%FMT-";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> slashForms;

	@Nullable private static volatile List<LispVal> slashStubForms;

	@Nullable private static volatile Set<String> names;

	private FormatRenderer() {
	}

	/**
	 * The renderer's {@code defun} forms, in definition order, WITHOUT the
	 * {@code ~/name/} arm -- a consumer splices {@link #functionDesignatorDefuns()} or
	 * {@link #functionDesignatorStubDefuns()} after them. Parsed once and cached; the
	 * list is immutable, so callers may splice it into a program directly.
	 * @return the renderer definitions
	 */
	public static List<LispVal> defuns() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("format-render.lisp")));
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The {@code ~/name/} arm: {@code %fmt-user-function} and the designator resolution
	 * below it. Split from {@link #defuns()} because it is the only part of the renderer
	 * that names a function out of runtime data.
	 * @return the arm's definitions
	 */
	public static List<LispVal> functionDesignatorDefuns() {
		List<LispVal> cached = slashForms;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = slashForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("format-render-slash.lisp")));
					slashForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The stand-in for {@link #functionDesignatorDefuns()}: one
	 * {@code %fmt-user-function} that signals when called, for a compiled program whose
	 * control strings never spell the directive.
	 * @return the stub definition
	 */
	public static List<LispVal> functionDesignatorStubDefuns() {
		List<LispVal> cached = slashStubForms;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = slashStubForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("format-render-slash-stub.lisp")));
					slashStubForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Whether the name is one of the renderer's own definitions -- the interpreter's test
	 * for "an unresolved function name that the renderer would define". Covers the
	 * {@code ~/name/} arm too: the interpreter loads it along with the rest.
	 * @param name the function name being resolved
	 * @return true when {@link #defuns()} or {@link #functionDesignatorDefuns()} defines
	 * it
	 */
	public static boolean definesFunction(String name) {
		Set<String> cached = names;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = names;
				if (cached == null) {
					Set<String> collected = new java.util.HashSet<>();
					collectDefinedNames(defuns(), collected);
					collectDefinedNames(functionDesignatorDefuns(), collected);
					cached = Set.copyOf(collected);
					names = cached;
				}
			}
		}
		return cached.contains(name);
	}

	private static void collectDefinedNames(List<LispVal> definitions, Set<String> out) {
		for (LispVal form : definitions) {
			if (form instanceof LispCons defun && defun.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol defined) {
				out.add(defined.name());
			}
		}
	}

	/**
	 * Builds a call to the renderer.
	 * @param control the form producing the control string
	 * @param args the form producing the argument list
	 * @return {@code (%fmt-render control args)}
	 */
	public static LispVal call(LispVal control, LispVal args) {
		return new LispCons(new LispSymbol(RENDER), new LispCons(control, new LispCons(args, LispNil.INSTANCE)));
	}

	/**
	 * Whether the expression already contains a renderer call -- the test the injection
	 * gate uses for code that was lowered before it ran (an injected {@code #'format}
	 * wrapper, a spliced library).
	 * @param form the expression to scan
	 * @return true when {@code %fmt-render} appears anywhere in it
	 */
	public static boolean isUsed(LispVal form) {
		if (form instanceof LispSymbol sym) {
			return RENDER.equals(sym.name());
		}
		if (form instanceof LispCons cons) {
			return isUsed(cons.car()) || isUsed(cons.cdr());
		}
		return false;
	}

	/**
	 * Whether any string literal in the program spells a {@code ~/name/} directive -- the
	 * compile path's gate for injecting the arm rather than the stub. A control string is
	 * runtime data, so this is the honest question the source CAN answer: a program whose
	 * every control the compile sees carries no such directive cannot render one, unless
	 * it assembles the control out of pieces.
	 * @param program the top-level forms, after every splice the backend performs
	 * @return true when the arm has to be injected
	 */
	public static boolean namesFunctionDesignator(List<LispVal> program) {
		for (LispVal form : program) {
			if (namesFunctionDesignator(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean namesFunctionDesignator(LispVal form) {
		if (form instanceof LispString literal) {
			return !functionDesignatorNames(literal.value().toUpperCase(java.util.Locale.ROOT)).isEmpty();
		}
		if (form instanceof LispCons cons) {
			return namesFunctionDesignator(cons.car()) || namesFunctionDesignator(cons.cdr());
		}
		return false;
	}

	/**
	 * The function names a {@code format} control string names with {@code ~/name/}, in
	 * the reader's upcased spelling. The scan is deliberately shallow -- it does not
	 * parse the control, it just pairs slashes after a {@code ~} -- because both
	 * consumers err safely on a false positive: {@code LibraryDefunPruner} only KEEPS a
	 * definition, and the injection gate only keeps the arm. A MISS is what costs: the
	 * pruner deletes a definition the program calls, and the gate leaves a stub where the
	 * program renders. They share this one scanner so those two answers cannot drift
	 * apart.
	 * @param upcasedControl the string literal, already upcased
	 * @return the names, or an empty list
	 */
	public static List<String> functionDesignatorNames(String upcasedControl) {
		List<String> names = new java.util.ArrayList<>();
		int i = upcasedControl.indexOf('~');
		while (i >= 0 && i + 1 < upcasedControl.length()) {
			int open = upcasedControl.indexOf('/', i + 1);
			// Only a directive whose PARAMETERS/modifiers separate the ~ from the /, or
			// nothing at all: a / further away belongs to some other directive's text.
			if (open < 0) {
				break;
			}
			int close = upcasedControl.indexOf('/', open + 1);
			if (close > open && upcasedControl.substring(i + 1, open).matches("[0-9,'vV#:@]*")) {
				names.add(upcasedControl.substring(open + 1, close));
				i = upcasedControl.indexOf('~', close + 1);
			}
			else {
				i = upcasedControl.indexOf('~', i + 1);
			}
		}
		return names;
	}

	private static String readSource(String resource) {
		try (InputStream in = FormatRenderer.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
