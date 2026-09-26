package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * ({@link BuiltinFunctionWrappers}, the function VALUE every backend hands out), widened
 * where the operator's standard lambda list takes more than the wrapper spells: a sort
 * predicate is a two-argument call, so {@code #'<} is binary while {@code (< a b c)} is
 * legal. A count this class accepts is left to the lowering exactly as before; only a
 * count the standard lambda list rules out is rejected.
 */
public final class BuiltinCallArity {

	/** {@link Shape#max} of an operator that takes any number past its minimum. */
	public static final int UNBOUNDED = -1;

	/**
	 * The call-position shapes that are WIDER than the catalog wrapper's lambda list:
	 * name, minimum, maximum. Each is the operator's standard lambda list (the CLHS, or
	 * rontolisp's reference page for its own operators); keyword arguments count as
	 * unbounded (the keyword-tail check is the operator's own). A row that is not wider
	 * than its wrapper fails the class's initialization, so widening a wrapper retires
	 * its row here.
	 */
	private static final Object[][] STANDARD_WIDER = { { LispNames.EQ, 1, UNBOUNDED }, { LispNames.LT, 1, UNBOUNDED },
			{ LispNames.GT, 1, UNBOUNDED }, { LispNames.LE, 1, UNBOUNDED }, { LispNames.GE, 1, UNBOUNDED },
			{ LispNames.NE, 1, UNBOUNDED }, { LispNames.CHAR_EQ, 1, UNBOUNDED }, { LispNames.CHAR_NE, 1, UNBOUNDED },
			{ LispNames.CHAR_LT, 1, UNBOUNDED }, { LispNames.CHAR_GT, 1, UNBOUNDED },
			{ LispNames.CHAR_LE, 1, UNBOUNDED }, { LispNames.CHAR_GE, 1, UNBOUNDED },
			{ LispNames.CHAR_EQUAL, 1, UNBOUNDED }, { LispNames.LOGAND, 0, UNBOUNDED },
			{ LispNames.LOGIOR, 0, UNBOUNDED }, { LispNames.LOGXOR, 0, UNBOUNDED },
			{ LispNames.ADJUST_ARRAY, 2, UNBOUNDED }, { LispNames.CONSTANTP, 1, 2 }, { LispNames.DIGIT_CHAR_P, 1, 2 },
			{ LispNames.FILE_POSITION, 1, 2 }, { LispNames.FLOAT, 1, 2 }, { LispNames.GENSYM, 0, 1 },
			{ LispNames.GETHASH, 2, 3 }, { LispNames.INTERN, 1, 2 }, { LispNames.MAKE_BROADCAST_STREAM, 0, UNBOUNDED },
			{ LispNames.MAKE_STRING, 1, UNBOUNDED }, { LispNames.PAIRLIS, 2, 3 },
			{ LispNames.PARSE_INTEGER, 1, UNBOUNDED }, { LispNames.RANDOM, 1, 2 }, { LispNames.READ_BYTE, 1, 3 },
			{ LispNames.READ_CHAR_NO_HANG, 0, 4 }, { LispNames.READ_FROM_STRING, 1, UNBOUNDED },
			{ LispNames.STRING_CAPITALIZE, 1, UNBOUNDED }, { LispNames.STRING_DOWNCASE, 1, UNBOUNDED },
			{ LispNames.STRING_UPCASE, 1, UNBOUNDED }, { LispNames.TYPEP, 2, 3 }, { LispNames.UNREAD_CHAR, 1, 2 },
			{ LispNames.UPGRADED_COMPLEX_PART_TYPE, 1, 2 }, { LispNames.VECTOR_PUSH_EXTEND, 2, 3 },
			{ LispNames.WRITE_STRING, 1, UNBOUNDED }, { LispNames.WRITE_TO_STRING, 1, UNBOUNDED },
			{ PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIDEN_FLOAT_BITS), 3, UNBOUNDED },
			{ PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.NARROW_FLOAT_BITS), 3, UNBOUNDED } };

	private static final Map<String, Shape> SHAPES = buildShapes();

	private BuiltinCallArity() {
	}

	/**
	 * The argument counts a direct call may pass.
	 *
	 * @param min the fewest
	 * @param max the most, or {@link #UNBOUNDED}
	 */
	public record Shape(int min, int max) {

		/**
		 * {@return whether a call passing {@code count} arguments fits}
		 * @param count the argument count
		 */
		public boolean accepts(int count) {
			return count >= this.min && (this.max == UNBOUNDED || count <= this.max);
		}

		/**
		 * The report of a call that does not fit, spelled by {@link ClosRegistry}: the
		 * bound the count broke, {@code at least} / {@code at most} where the shape is a
		 * range and the plain count where it is one number.
		 * @param operator the operator's name
		 * @param count the argument count
		 * @return the message
		 */
		public String message(String operator, int count) {
			if (count < this.min) {
				return ClosRegistry.arityMessage(operator, this.min, this.max != this.min, count);
			}
			return this.max == this.min ? ClosRegistry.arityMessage(operator, this.min, false, count)
					: operator + ClosRegistry.ARITY_VERB + ClosRegistry.ARITY_AT_MOST
							+ ClosRegistry.arityExpectation(this.max, false) + ClosRegistry.ARITY_MESSAGE_INFIX + count;
		}

	}

	/**
	 * The call-position shape of a wrapped built-in.
	 * @param name the operator's name
	 * @return the shape, or {@code null} when the name is no wrapped built-in
	 */
	public static @Nullable Shape of(String name) {
		return SHAPES.get(name);
	}

	/**
	 * The report a direct call of this built-in with this many arguments makes, or
	 * {@code null} when the count fits (or the name is no wrapped built-in).
	 * @param name the operator's name
	 * @param count the argument count
	 * @return the message, or {@code null}
	 */
	public static @Nullable String wrongCountMessage(String name, int count) {
		Shape shape = SHAPES.get(name);
		return shape == null || shape.accepts(count) ? null : shape.message(name, count);
	}

	/**
	 * What a compiled backend compiles a direct built-in call with a wrong count to: its
	 * argument forms evaluated left to right, as the interpreter evaluates them before
	 * the built-in rejects the count, then {@code (%program-error "message")} -- whose
	 * literal message is what the compile paths' static warning reports
	 * ({@link CompileWarnings#warnStaticProgramError}).
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
			return null;
		}
		return signalAfterArguments(call, args, shape.message(head.name(), args.size()));
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
		for (Object[] row : STANDARD_WIDER) {
			String name = (String) row[0];
			if (!shapes.containsKey(name)) {
				throw new IllegalStateException(name + " is no wrapped built-in; drop it from STANDARD_WIDER");
			}
			Shape wrapper = shapes.get(name);
			Shape standard = new Shape((Integer) row[1], (Integer) row[2]);
			if (standard.equals(wrapper) || standard.min() > wrapper.min()
					|| standard.max() != UNBOUNDED && (wrapper.max() == UNBOUNDED || standard.max() < wrapper.max())) {
				throw new IllegalStateException(name + "'s standard shape " + standard
						+ " is not wider than its wrapper's " + wrapper + "; drop it from STANDARD_WIDER");
			}
			shapes.put(name, standard);
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
