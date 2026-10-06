package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The argument counts a DIRECT call of a wrapped built-in may pass, and the report a call
 * outside them makes: {@code (car x 2)} is {@code CAR expects 1 argument, got 2}, a
 * {@code program-error} at run time, on every backend.
 *
 * <p>
 * Every call-position lowering indexes its argument list by the shape it expects -- a
 * surplus is dropped, a shortfall indexes past the form -- so the count is judged ONCE,
 * where each backend decides a form is a built-in call, and a wrong one never reaches a
 * lowering. The shape is the catalog wrapper's lambda list
 * ({@link BuiltinFunctionWrappers}, the function VALUE every backend hands out), which
 * takes the operator's standard lambda list. A count this class accepts is left to the
 * lowering exactly as before; only a count the standard lambda list rules out is
 * rejected.
 *
 * <p>
 * The built-ins outside the catalog that the interpreter implements natively take their
 * shapes from {@link NativeCallShapes} and are judged the same way, reported under the
 * interpreter's name for them ({@code TCP-CONNECT expects 2 arguments, got 1}).
 */
public final class BuiltinCallArity {

	/** {@link Shape#max} of an operator that takes any number past its minimum. */
	public static final int UNBOUNDED = -1;

	private static final Map<String, Shape> SHAPES = buildShapes();

	private BuiltinCallArity() {
	}

	/**
	 * The argument counts a direct call may pass.
	 *
	 * @param min the fewest
	 * @param max the most, or {@link #UNBOUNDED}
	 * @param paired whether the counts are exactly {@code min} and {@code max} -- the
	 * surplus being one keyword/value pair ({@code tls-connect}: 2 or 4)
	 */
	public record Shape(int min, int max, boolean paired) {

		/**
		 * A contiguous range of counts.
		 * @param min the fewest
		 * @param max the most, or {@link #UNBOUNDED}
		 */
		public Shape(int min, int max) {
			this(min, max, false);
		}

		/**
		 * {@return whether a call passing {@code count} arguments fits}
		 * @param count the argument count
		 */
		public boolean accepts(int count) {
			if (this.paired) {
				return count == this.min || count == this.max;
			}
			return count >= this.min && (this.max == UNBOUNDED || count <= this.max);
		}

		/**
		 * The report of a call that does not fit, spelled by {@link ClosRegistry}: the
		 * bound the count broke, {@code at least} / {@code at most} where the shape is a
		 * range and the plain count where it is one number; both counts of a paired
		 * shape.
		 * @param operator the operator's name
		 * @param count the argument count
		 * @return the message
		 */
		public String message(String operator, int count) {
			if (this.paired) {
				return operator + ClosRegistry.ARITY_VERB + this.min + " or "
						+ ClosRegistry.arityExpectation(this.max, false) + ClosRegistry.ARITY_MESSAGE_INFIX + count;
			}
			if (count < this.min) {
				return ClosRegistry.arityMessage(operator, this.min, this.max != this.min, count);
			}
			return this.max == this.min ? ClosRegistry.arityMessage(operator, this.min, false, count)
					: operator + ClosRegistry.ARITY_VERB + ClosRegistry.ARITY_AT_MOST
							+ ClosRegistry.arityExpectation(this.max, false) + ClosRegistry.ARITY_MESSAGE_INFIX + count;
		}

	}

	/**
	 * The call-position shape of a wrapped or native built-in.
	 * @param name the operator's name
	 * @return the shape, or {@code null} when the name is neither
	 */
	public static @Nullable Shape of(String name) {
		return SHAPES.get(name);
	}

	/**
	 * {@return the native built-ins outside the wrapper catalog that have a shape here}
	 * ({@link NativeCallShapes})
	 */
	public static Set<String> nativeNames() {
		return NativeCallShapes.names();
	}

	/**
	 * The operator a wrong-count report names for a built-in with a shape here: the name
	 * the interpreter's implementation reports under ({@link NativeCallShapes#operator})
	 * for a native one, the name itself for a wrapped one.
	 * @param name the operator's canonical name
	 * @return the operator to report
	 */
	public static String operator(String name) {
		String nativeOperator = NativeCallShapes.operator(name);
		return nativeOperator != null ? nativeOperator : name;
	}

	/**
	 * The report a direct call of this built-in with this many arguments makes, or
	 * {@code null} when the count fits (or the name is no built-in with a shape here).
	 * @param name the operator's name
	 * @param count the argument count
	 * @return the message, or {@code null}
	 */
	public static @Nullable String wrongCountMessage(String name, int count) {
		Shape shape = SHAPES.get(name);
		return shape == null || shape.accepts(count) ? null : shape.message(operator(name), count);
	}

	/**
	 * The top-level {@code defun}s of a program whose lambda list takes exactly the
	 * counts of the NATIVE built-in they are named after ({@link NativeCallShapes}): a
	 * library's own implementation of that built-in on a compiled backend (sockets.lisp's
	 * {@code rontolisp:tcp-listen} on the component, the prelude's {@code char-name} and
	 * {@code find-class}), where the interpreter runs the built-in itself. A direct call
	 * of one is judged by {@link #wrongCountSignal} rather than by the defun's own count
	 * check, so it reports as the built-in on every backend -- the defun's check says
	 * {@code Function expects at most 3 arguments} from inside the callee for the surplus
	 * past an {@code &optional} tail. A paired shape is taken by the range its lambda
	 * list spells ({@code (host port &optional opt value)}). A catalog name is not
	 * listed: a defun of one keeps its own call path, as before.
	 * @param program the top-level forms, before their lambda lists are desugared
	 * @return the names
	 */
	public static Set<String> builtinShapedDefuns(List<LispVal> program) {
		Map<String, @Nullable Shape> defined = new HashMap<>();
		collectDefunShapes(program, defined);
		Set<String> names = new HashSet<>();
		for (Map.Entry<String, @Nullable Shape> entry : defined.entrySet()) {
			NativeCallShapes.Row builtin = NativeCallShapes.of(entry.getKey());
			Shape own = entry.getValue();
			if (builtin != null && own != null && builtin.min() == own.min() && builtin.max() == own.max()) {
				names.add(entry.getKey());
			}
		}
		return Set.copyOf(names);
	}

	// The last top-level (defun name lambda-list ...) of each name, through progn; a
	// lambda list this class cannot read maps the name to null (its own call path).
	private static void collectDefunShapes(List<LispVal> forms, Map<String, @Nullable Shape> defined) {
		for (LispVal form : forms) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				continue;
			}
			if (LispNames.PROGN.equals(head.name())) {
				List<LispVal> body = new ArrayList<>();
				for (LispVal rest = cons.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
					body.add(cell.car());
				}
				collectDefunShapes(body, defined);
			}
			else if (LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
					&& nameCell.car() instanceof LispSymbol name && nameCell.cdr() instanceof LispCons listCell) {
				defined.put(name.name(), definedShape(listCell.car()));
			}
		}
	}

	// A defun's lambda list: required parameters, &optional, &rest/&body, &key (and
	// &allow-other-keys) as unbounded, &aux ignored.
	private static @Nullable Shape definedShape(LispVal lambdaList) {
		int required = 0;
		int optional = 0;
		boolean inOptional = false;
		LispVal rest = lambdaList;
		for (; rest instanceof LispCons cell; rest = cell.cdr()) {
			if (cell.car() instanceof LispSymbol marker && marker.name().startsWith("&")) {
				switch (marker.name()) {
					case LispNames.LAMBDA_OPTIONAL -> inOptional = true;
					case LispNames.LAMBDA_REST, LispNames.LAMBDA_BODY, LispNames.LAMBDA_KEY -> {
						return new Shape(required, UNBOUNDED);
					}
					case LispNames.LAMBDA_AUX -> {
						return new Shape(required, required + optional);
					}
					default -> {
						return null;
					}
				}
			}
			else if (inOptional) {
				optional++;
			}
			else {
				required++;
			}
		}
		return rest instanceof LispNil ? new Shape(required, required + optional) : null;
	}

	/**
	 * What a compiled backend compiles a direct built-in call with a wrong count to: its
	 * argument forms evaluated left to right, as the interpreter evaluates them before
	 * the built-in rejects the count, then {@code (%program-error "message")} -- whose
	 * literal message is what the compile paths' static warning reports
	 * ({@link CompileWarnings#warnStaticProgramError}). A three-argument {@code close}
	 * whose second argument is not the literal {@code :abort} is rejected as the
	 * interpreter's implementation rejects it ({@code CLOSE expects 1 argument, got 3}).
	 * @param call the call, a proper list headed by a symbol
	 * @return the replacement form, or {@code null} when the count fits
	 */
	public static @Nullable LispVal wrongCountSignal(LispCons call) {
		if (!(call.car() instanceof LispSymbol head)) {
			return null;
		}
		Shape shape = SHAPES.get(head.name());
		if (shape == null) {
			return null;
		}
		List<LispVal> args = new ArrayList<>();
		for (LispVal rest = call.cdr(); rest instanceof LispCons cell; rest = cell.cdr()) {
			args.add(cell.car());
		}
		if (shape.accepts(args.size())) {
			// close's pair is :abort v and nothing else: the lowerings strip a LITERAL
			// :abort, and anything else in that position is the interpreter's
			// implementation rejecting the count (a computed keyword included -- no
			// lowering can take one).
			if (LispNames.CLOSE.equals(head.name()) && args.size() == 3
					&& !(args.get(1) instanceof LispSymbol keyword && LispNames.ABORT_KEYWORD.equals(keyword.name()))) {
				return signalAfterArguments(call, args, ClosRegistry.arityMessage(LispNames.CLOSE, 1, false, 3));
			}
			return null;
		}
		return signalAfterArguments(call, args, shape.message(operator(head.name()), args.size()));
	}

	/**
	 * {@code (progn args... (%program-error "message"))}, positioned at the call: the
	 * arguments evaluated left to right, as the interpreter evaluates them before a
	 * callee rejects the count, then the signal.
	 * @param call the rejected call
	 * @param args its argument forms
	 * @param message the report
	 * @return the replacement form
	 */
	static LispVal signalAfterArguments(LispCons call, List<LispVal> args, String message) {
		List<LispVal> body = new ArrayList<>();
		body.add(new LispSymbol(LispNames.PROGN));
		body.addAll(args);
		body.add(LispMacroExpander.programErrorForm(call, message));
		LispVal form = LispNil.INSTANCE;
		for (int i = body.size() - 1; i >= 0; i--) {
			form = new LispCons(body.get(i), form);
		}
		return SourceProvenance.inherit(call, form);
	}

	private static Map<String, Shape> buildShapes() {
		Map<String, Shape> shapes = new HashMap<>();
		for (String name : BuiltinFunctionWrappers.wrapperNames()) {
			LispVal lambda = BuiltinFunctionWrappers.lambdaFor(name);
			if (lambda instanceof LispCons lambdaCons && lambdaCons.cdr() instanceof LispCons rest) {
				shapes.put(name, lambdaListShape(rest.car()));
			}
		}
		for (String name : NativeCallShapes.names()) {
			NativeCallShapes.Row row = java.util.Objects.requireNonNull(NativeCallShapes.of(name));
			if (shapes.containsKey(name)) {
				throw new IllegalStateException(name + " is a wrapped built-in; drop it from NativeCallShapes");
			}
			shapes.put(name, new Shape(row.min(), row.max(), row.paired()));
		}
		return Map.copyOf(shapes);
	}

	// A wrapper lambda list is required parameters, then an optional &optional section,
	// then an optional &rest parameter.
	private static Shape lambdaListShape(LispVal lambdaList) {
		int required = 0;
		int optional = 0;
		boolean inOptional = false;
		for (LispVal rest = lambdaList; rest instanceof LispCons cell; rest = cell.cdr()) {
			if (cell.car() instanceof LispSymbol marker && LispNames.LAMBDA_REST.equals(marker.name())) {
				return new Shape(required, UNBOUNDED);
			}
			if (cell.car() instanceof LispSymbol marker && LispNames.LAMBDA_OPTIONAL.equals(marker.name())) {
				inOptional = true;
			}
			else if (inOptional) {
				optional++;
			}
			else {
				required++;
			}
		}
		return new Shape(required, required + optional);
	}

}
