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
import org.jspecify.annotations.Nullable;

/** The core forms the Java lowering builds, and the names it builds them of. */
final class JavaForms {

	static final LispVal NIL = LispNil.INSTANCE;

	static final LispVal T = LispTrue.INSTANCE;

	/** A {@code Boolean} box holding false: {@code null} is {@code NIL}. */
	static final LispSymbol BOXED_FALSE = new LispSymbol(":FALSE");

	/** What a {@code Long} box holds beside its number: {@code (number . :long)}. */
	static final LispSymbol BOXED_LONG = new LispSymbol(":LONG");

	/**
	 * What the box of a primitive holds beside its value when the value alone does not
	 * tell the box -- a {@code long}, a {@code byte}, a {@code short} and a {@code float}
	 * are an integer or a double as an {@code int} or a {@code double} is -- or
	 * {@code null} for a box that is its value.
	 */
	static @Nullable LispSymbol boxTag(JavaType type) {
		return type == JavaType.Primitive.LONG ? BOXED_LONG
				: type == JavaType.Primitive.BYTE ? new LispSymbol(":BYTE") : type == JavaType.Primitive.SHORT
						? new LispSymbol(":SHORT") : type == JavaType.Primitive.FLOAT ? new LispSymbol(":FLOAT") : null;
	}

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
	 * The Common Lisp type of the values of an integral type -- {@code (signed-byte 32)}
	 * for an {@code int}; for a {@code char} every code, a string's elements being code
	 * points here -- or {@code null} for any other type. What the lowering declares of a
	 * variable and asserts of a value with {@code the}: the backends that keep integers
	 * raw take such a value without the test that would send another to their generic
	 * path ({@code .kb/java-frontend.md}, "Numbers").
	 */
	static @Nullable LispVal integerType(JavaType type) {
		if (!(type instanceof JavaType.Primitive primitive)) {
			return null;
		}
		return switch (primitive) {
			case BYTE -> list(cl("signed-byte"), integer(8));
			case SHORT -> list(cl("signed-byte"), integer(16));
			case CHAR -> list(cl("integer"), integer(0), integer(Character.MAX_CODE_POINT));
			case INT -> list(cl("signed-byte"), integer(32));
			case LONG -> list(cl("signed-byte"), integer(64));
			default -> null;
		};
	}

	/**
	 * The Common Lisp type of the arrays of an array type -- the vector {@code new} makes
	 * of it, {@code (simple-array (unsigned-byte 32) (*))} for an {@code int[]}, whose
	 * element type is also what each element read answers, {@code (signed-byte 64)} for a
	 * {@code long[]} -- or {@code null} for any other type.
	 */
	static @Nullable LispVal arrayType(JavaType type) {
		if (!(type instanceof JavaType.ArrayType array)) {
			return null;
		}
		LispVal element = array.element() instanceof JavaType.Primitive primitive ? switch (primitive) {
			case INT -> list(cl("unsigned-byte"), integer(32));
			case BYTE -> list(cl("unsigned-byte"), integer(8));
			case SHORT, CHAR -> list(cl("unsigned-byte"), integer(16));
			case LONG -> list(cl("signed-byte"), integer(64));
			case FLOAT -> cl("single-float");
			case DOUBLE -> cl("double-float");
			case BOOLEAN -> cl("bit");
			default -> T;
		} : T;
		return list(cl("simple-array"), element, list(cl("*")));
	}

	/**
	 * What a variable of a type is declared of: an integral type's range
	 * ({@link #integerType}), or an array type's vector or {@code nil}
	 * ({@link #arrayType}); {@code null} for any other type.
	 */
	static @Nullable LispVal declaredType(JavaType type) {
		LispVal integer = integerType(type);
		if (integer != null) {
			return integer;
		}
		LispVal array = arrayType(type);
		return array == null ? null : list(cl("or"), cl("null"), array);
	}

	/**
	 * {@code (declare (type spec var...) ...)} for the variables of an integral or an
	 * array type ({@link #declaredType}), or {@code null} when none is.
	 */
	static @Nullable LispVal typeDeclaration(List<LispSymbol> variables, List<JavaType> types) {
		java.util.Map<String, List<LispVal>> byType = new java.util.LinkedHashMap<>();
		java.util.Map<String, LispVal> specs = new java.util.HashMap<>();
		for (int i = 0; i < variables.size(); i++) {
			LispVal spec = declaredType(types.get(i));
			if (spec != null) {
				specs.putIfAbsent(spec.print(), spec);
				byType.computeIfAbsent(spec.print(), key -> new ArrayList<>()).add(variables.get(i));
			}
		}
		if (byType.isEmpty()) {
			return null;
		}
		List<LispVal> clauses = new ArrayList<>();
		clauses.add(cl("declare"));
		byType.forEach((key, named) -> {
			List<LispVal> clause = new ArrayList<>();
			clause.add(cl("type"));
			clause.add(specs.get(key));
			clause.addAll(named);
			clauses.add(list(clause));
		});
		return list(clauses);
	}

	/**
	 * A value of an integral type as an operand: asserted of its type with {@code the}
	 * where the form does not tell it -- a call, a field, a conditional -- and as it is
	 * where it does: a literal, a variable (declared where it is bound), an operation the
	 * backends bound by their own arithmetic.
	 */
	static LispVal known(JavaType type, LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return form;
		}
		return switch (head.name()) {
			case "+", "-", "*", "LOGAND", "LOGIOR", "LOGXOR", "LOGNOT", "ASH", "REM", "%MASK-SIGNED-FIELD", "THE" ->
				form;
			default -> asserted(type, form);
		};
	}

	/**
	 * An array whose element is read or stored: asserted of its type
	 * ({@link #declaredType}, {@code nil} included) where the form does not tell it -- a
	 * field, a call, an element of an array of arrays -- and as it is where it does: a
	 * variable, declared where it is bound, a static field's read, asserted where it is
	 * read.
	 */
	static LispVal knownArray(JavaType type, LispVal form) {
		if (!(form instanceof LispCons cons) || cons.car().equals(cl("the"))) {
			return form;
		}
		return asserted(type, form);
	}

	/**
	 * An array that is no {@code null} -- tested where the form is, which raises
	 * {@code NullPointerException} -- asserted of its vector ({@link #arrayType}).
	 */
	static LispVal vector(JavaType type, LispVal form) {
		LispVal spec = arrayType(type);
		return spec == null ? form : list(cl("the"), spec, form);
	}

	/**
	 * {@code (the spec form)} of a value of an integral or an array type
	 * ({@link #declaredType}); any other as it is.
	 */
	static LispVal asserted(JavaType type, LispVal form) {
		LispVal spec = declaredType(type);
		return spec == null ? form : list(cl("the"), spec, form);
	}

	/**
	 * An integer kept in the range of its type: its low 32 or 64 bits as a signed field,
	 * which both compilers fuse with the operation under it into one wrapping machine
	 * operation and a sign extension, the interpreter answers in one call; a double is
	 * left as it is.
	 */
	static LispVal wrap(JavaType type, LispVal form) {
		if (type.isFloating()) {
			return form;
		}
		return call("%mask-signed-field", integer(type == JavaType.Primitive.LONG ? 64 : 32), form);
	}

	/**
	 * The low bits of an integer read as a signed number of that width, in the shape the
	 * backends compile raw.
	 */
	static LispVal signed(LispVal form, int bits) {
		return call("%mask-signed-field", integer(bits), form);
	}

	/**
	 * The Common Lisp symbol a {@code native} method of a program names: its name with a
	 * hyphen ahead of each capital letter after a lowercase letter or a digit, upcased --
	 * {@code fact} is {@code FACT}, {@code stringUpcase} {@code STRING-UPCASE},
	 * {@code toJSON} {@code TO-JSON} -- read in the package the program is evaluated in.
	 */
	static String lispName(String method) {
		StringBuilder name = new StringBuilder();
		for (int i = 0; i < method.length(); i++) {
			char c = method.charAt(i);
			if (Character.isUpperCase(c) && i > 0
					&& (Character.isLowerCase(method.charAt(i - 1)) || Character.isDigit(method.charAt(i - 1)))) {
				name.append('-');
			}
			name.append(c);
		}
		return name.toString().toUpperCase(Locale.ROOT);
	}

	/**
	 * A {@code native} method of a program: a call of the Common Lisp function its name
	 * names. A {@code char} crosses as a character, a {@code boolean} back as {@code T}
	 * or {@code NIL}, a string back as the program's immutable string, an integer back as
	 * a value of the integral type returned -- its low bits, which the type declares
	 * ({@link #integerType}); every other value as it is.
	 */
	static JavaEmitter lispFunction(String method, List<JavaType> parameters, JavaType returnType) {
		LispSymbol function = new LispSymbol(lispName(method));
		return (context, call) -> {
			List<LispVal> arguments = new ArrayList<>();
			for (int i = 0; i < parameters.size(); i++) {
				LispVal argument = call.argument(i);
				arguments.add(parameters.get(i) == JavaType.Primitive.CHAR ? call("code-char", argument) : argument);
			}
			LispVal result = form(function, arguments);
			if (returnType == JavaType.Primitive.CHAR) {
				return call("char-code", result);
			}
			if (returnType == JavaType.Primitive.BYTE || returnType == JavaType.Primitive.SHORT) {
				return signed(result, returnType == JavaType.Primitive.BYTE ? 8 : 16);
			}
			if (returnType == JavaType.Primitive.INT || returnType == JavaType.Primitive.LONG) {
				// a value of the type, as a method of it answers: its low bits
				return wrap(returnType, result);
			}
			if (returnType == JavaType.Primitive.BOOLEAN) {
				return call("if", result, LispTrue.INSTANCE, LispNil.INSTANCE);
			}
			if (returnType instanceof JavaType.ClassType type && type.declaration().name.equals("java.lang.String")) {
				return runtimeCall("lisp-string", result);
			}
			return result;
		};
	}

	/**
	 * Whether evaluating the form twice is evaluating it once: a variable or a literal.
	 */
	static boolean isSimple(LispVal form) {
		return !(form instanceof LispCons);
	}

}
