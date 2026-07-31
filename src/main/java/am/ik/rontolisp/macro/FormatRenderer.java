package am.ik.rontolisp.macro;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
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

	@Nullable private static volatile Set<String> names;

	private FormatRenderer() {
	}

	/**
	 * The renderer's {@code defun} forms, in definition order. Parsed once and cached;
	 * the list is immutable, so callers may splice it into a program directly.
	 * @return the renderer definitions
	 */
	public static List<LispVal> defuns() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource()));
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Whether the name is one of the renderer's own definitions -- the interpreter's test
	 * for "an unresolved function name that the renderer would define".
	 * @param name the function name being resolved
	 * @return true when {@link #defuns()} defines it
	 */
	public static boolean definesFunction(String name) {
		Set<String> cached = names;
		if (cached == null) {
			synchronized (FormatRenderer.class) {
				cached = names;
				if (cached == null) {
					Set<String> collected = new java.util.HashSet<>();
					for (LispVal form : defuns()) {
						if (form instanceof LispCons defun && defun.cdr() instanceof LispCons rest
								&& rest.car() instanceof LispSymbol defined) {
							collected.add(defined.name());
						}
					}
					cached = Set.copyOf(collected);
					names = cached;
				}
			}
		}
		return cached.contains(name);
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

	private static String readSource() {
		try (InputStream in = FormatRenderer.class.getResourceAsStream("format-render.lisp")) {
			if (in == null) {
				throw new IllegalStateException("format-render.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
