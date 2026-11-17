package am.ik.rontolisp.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/** The core forms the Java lowering builds, and the names it builds them of. */
final class JavaForms {

	static final LispVal NIL = LispNil.INSTANCE;

	static final LispVal T = LispTrue.INSTANCE;

	/** A {@code Boolean} box holding false: {@code null} is {@code NIL}. */
	static final LispSymbol BOXED_FALSE = new LispSymbol(":FALSE");

	/** What a {@code Long} box holds beside its number: {@code (number . :long)}. */
	static final LispSymbol BOXED_LONG = new LispSymbol(":LONG");

	static final LispSymbol THIS = new LispSymbol("this");

	static final LispSymbol FUNCTION_BLOCK = new LispSymbol("%fn");

	static final LispSymbol BREAK_BLOCK = new LispSymbol("%break");

	static final LispSymbol CONTINUE_BLOCK = new LispSymbol("%continue");

	private JavaForms() {
	}

	/** A Common Lisp operator or one of the pipeline's internal ones. */
	static LispSymbol cl(String name) {
		return new LispSymbol(name.toUpperCase(Locale.ROOT));
	}

	/** A function of the run-time library, {@code eval/java.lisp}. */
	static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%JAVA-" + name.toUpperCase(Locale.ROOT));
	}

	static LispVal list(LispVal... elements) {
		return list(Arrays.asList(elements));
	}

	static LispVal list(List<LispVal> elements) {
		LispVal list = NIL;
		for (int i = elements.size() - 1; i >= 0; i--) {
			list = new LispCons(elements.get(i), list);
		}
		return list;
	}

	/** A form: an operator and its operands. */
	static LispVal form(LispVal operator, List<LispVal> operands) {
		List<LispVal> elements = new ArrayList<>(operands.size() + 1);
		elements.add(operator);
		elements.addAll(operands);
		return list(elements);
	}

	static LispVal call(String operator, LispVal... operands) {
		return form(cl(operator), Arrays.asList(operands));
	}

	static LispVal runtimeCall(String name, LispVal... operands) {
		return form(runtime(name), Arrays.asList(operands));
	}

	static LispVal integer(long value) {
		return new LispInteger(value);
	}

	static LispVal integer(BigInteger value) {
		return value.bitLength() < Long.SIZE ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	static LispVal string(String value) {
		return LispString.literal(value);
	}

	static LispVal quote(LispVal datum) {
		return list(cl("quote"), datum);
	}

	static LispVal function(LispSymbol name) {
		return list(cl("function"), name);
	}

	/** Several forms as one: the form itself when there is one, {@code nil} for none. */
	static LispVal progn(List<LispVal> forms) {
		if (forms.isEmpty()) {
			return NIL;
		}
		return forms.size() == 1 ? forms.get(0) : form(cl("progn"), forms);
	}

	/** {@code (let ((variable value)) body..)}. */
	static LispVal let(LispSymbol variable, LispVal value, List<LispVal> body) {
		List<LispVal> elements = new ArrayList<>();
		elements.add(cl("let"));
		elements.add(list(list(variable, value)));
		elements.addAll(body);
		return list(elements);
	}

	/**
	 * The mask keeping an integer in the range of its type, a shape the backends compile
	 * raw; a double is left as it is.
	 */
	static LispVal wrap(JavaType type, LispVal form) {
		if (type == JavaType.Primitive.DOUBLE) {
			return form;
		}
		BigInteger half = BigInteger.ONE.shiftLeft(type == JavaType.Primitive.LONG ? 63 : 31);
		LispVal offset = integer(half);
		return call("-", call("logand", call("+", form, offset), integer(half.shiftLeft(1).subtract(BigInteger.ONE))),
				offset);
	}

	/**
	 * Whether evaluating the form twice is evaluating it once: a variable or a literal.
	 */
	static boolean isSimple(LispVal form) {
		return !(form instanceof LispCons);
	}

}
