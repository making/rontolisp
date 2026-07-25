package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * The one home of the {@code concatenate} contract: the result-type designator normalizer
 * both the interpreter and the compilers resolve through ({@link #resultFamily}), and the
 * compile-path lowering of {@code (concatenate 'type
 * args...)} into existing primitives ({@link #expand}).
 *
 * <p>
 * Three result families are supported -- {@code list}, {@code vector} and {@code string},
 * each with its "simple" / compound spellings, so
 * {@code (concatenate '(vector (unsigned-byte 8)) a b)} is the vector family (element
 * types are dropped: rontolisp vectors are generic). A {@code list} or {@code vector}
 * result accepts any sequence arguments, mixed freely; a {@code string} result takes
 * string arguments (a character LIST is not a string here -- coerce it first), because it
 * lowers straight to the binary {@code %string-concat} primitive instead of walking
 * elements.
 *
 * <p>
 * The compilers additionally require the result type to be written as a literal quoted
 * designator: the interpreter evaluates it at runtime, a compiler has to resolve it
 * statically.
 */
public final class ConcatenateForms {

	/**
	 * The sequence result-type families {@code concatenate} can build. Every supported
	 * result-type designator normalizes to one of these.
	 */
	public enum ResultFamily {

		/** A character string -- the {@code string} family. */
		STRING,

		/** A cons list -- the {@code list} / {@code cons} family. */
		LIST,

		/**
		 * A general (element-type-free) vector -- the {@code vector} / {@code array}
		 * family.
		 */
		VECTOR

	}

	private ConcatenateForms() {
	}

	/**
	 * Normalizes a result-type designator -- the evaluated designator, i.e. with any
	 * {@code quote} already stripped -- to its sequence family: a symbol ({@code string},
	 * {@code simple-string}, {@code base-string}, {@code simple-base-string},
	 * {@code list}, {@code cons}, {@code vector}, {@code simple-vector}, {@code array},
	 * {@code simple-array}) or a compound spec whose head is one of them
	 * ({@code (vector (unsigned-byte 8))}, {@code (simple-array character (*))},
	 * {@code (string 5)}, ...). A package-qualified spelling normalizes through its
	 * member name.
	 * @param designator the result-type designator
	 * @return the family, or {@code null} when the designator names none of them
	 */
	public static @Nullable ResultFamily resultFamily(LispVal designator) {
		LispVal head = (designator instanceof LispCons spec) ? spec.car() : designator;
		if (!(head instanceof LispSymbol sym)) {
			return null;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return switch (qn == null ? sym.name() : qn.member()) {
			case "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING" -> ResultFamily.STRING;
			case "LIST", "CONS" -> ResultFamily.LIST;
			case "VECTOR", "SIMPLE-VECTOR", "ARRAY", "SIMPLE-ARRAY", "BIT-VECTOR", "SIMPLE-BIT-VECTOR" ->
				ResultFamily.VECTOR;
			default -> null;
		};
	}

	/**
	 * Expands {@code (concatenate 'type args...)} into existing primitives:
	 *
	 * <pre>
	 * (concatenate 'string)         -> ""
	 * (concatenate 'string a)       -> (%string-concat a "")
	 * (concatenate 'string a b c)   -> (%string-concat (%string-concat a b) c)
	 * (concatenate 'list)           -> nil
	 * (concatenate 'list a b)       -> (append (coerce a 'list) (coerce b 'list) nil)
	 * (concatenate 'vector a b)     -> (coerce (append (coerce a 'list) (coerce b 'list) nil) 'vector)
	 * </pre>
	 *
	 * The trailing {@code nil} is what makes the list family copy its LAST argument too
	 * ({@code append} shares it otherwise), so the result is always a fresh sequence.
	 * @param cons the concatenate expression
	 * @return the expanded expression
	 */
	/**
	 * Normalizes a result-type designator as WRITTEN in a call -- a literal
	 * {@code (quote designator)} form -- to its family. A computed (non-quoted) type form
	 * yields {@code null}: only the interpreter can resolve one, at runtime.
	 * @param typeForm the result-type argument as written
	 * @return the family, or {@code null} when the form is not a literal designator of a
	 * supported family
	 */
	public static @Nullable ResultFamily literalResultFamily(LispVal typeForm) {
		LispVal designator = unquoted(typeForm);
		return (designator == null) ? null : resultFamily(designator);
	}

	public static LispVal expand(LispCons cons) {
		List<LispVal> parts = cons.toList();
		ResultFamily family = (parts.size() >= 2) ? literalResultFamily(parts.get(1)) : null;
		if (family == null) {
			throw new UnsupportedOperationException(
					"Cannot compile concatenate: the result type must be a literal quoted 'list, 'vector or 'string "
							+ "designator");
		}
		List<LispVal> args = parts.subList(2, parts.size());
		return switch (family) {
			case STRING -> stringChain(args);
			case LIST -> appendedElements(args);
			case VECTOR -> coerceCall(appendedElements(args), "VECTOR");
		};
	}

	// (quote X) -> X; anything else is not a literal designator.
	private static @Nullable LispVal unquoted(LispVal form) {
		if (!(form instanceof LispCons quoted)) {
			return null;
		}
		List<LispVal> parts = quoted.toList();
		return (parts.size() == 2 && parts.get(0) instanceof LispSymbol q && LispNames.QUOTE.equals(q.name()))
				? parts.get(1) : null;
	}

	// Nested binary %string-concat calls; a lone argument is concatenated with "" so the
	// result is always a fresh string.
	private static LispVal stringChain(List<LispVal> args) {
		if (args.isEmpty()) {
			return new LispString("");
		}
		LispVal acc = (args.size() == 1) ? concatCall(args.get(0), new LispString("")) : args.get(0);
		for (int i = 1; i < args.size(); i++) {
			acc = concatCall(acc, args.get(i));
		}
		return acc;
	}

	// (append (coerce a 'list) (coerce b 'list) ... nil) -- every argument's elements in
	// order, in a fresh list.
	private static LispVal appendedElements(List<LispVal> args) {
		if (args.isEmpty()) {
			return LispNil.INSTANCE;
		}
		List<LispVal> call = new java.util.ArrayList<>();
		call.add(new LispSymbol(LispNames.APPEND));
		for (LispVal arg : args) {
			call.add(coerceCall(arg, "LIST"));
		}
		call.add(LispNil.INSTANCE);
		return listToCons(call);
	}

	private static LispVal coerceCall(LispVal value, String type) {
		return listToCons(List.of(new LispSymbol(LispNames.COERCE), value,
				listToCons(List.of(new LispSymbol(LispNames.QUOTE), new LispSymbol(type)))));
	}

	private static LispVal concatCall(LispVal a, LispVal b) {
		return listToCons(List.of(new LispSymbol(LispNames.STRING_CONCAT), a, b));
	}

	private static LispVal listToCons(List<LispVal> items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			result = new LispCons(items.get(i), result);
		}
		return result;
	}

}
