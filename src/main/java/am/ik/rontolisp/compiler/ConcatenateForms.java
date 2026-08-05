package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
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
 * each with its "simple" / compound spellings. Every family accepts any sequence
 * arguments, mixed freely: the list and vector families walk elements, and the string
 * family sends each argument that is not a literal string through {@code %seq-string}
 * (one call, never an inlined loop) before the binary {@code %string-concat} fold.
 *
 * <p>
 * The vector family carries its ELEMENT TYPE as well: an {@code (unsigned-byte 8|16|32)}
 * element type ({@code '(vector (unsigned-byte 8))},
 * {@code '(simple-array (unsigned-byte 8) (*))}) selects the packed integer-vector
 * representation {@code make-array} already builds
 * ({@code .kb/packed-integer-vectors.md}), through the {@code %seq-int-vector} helper;
 * every other element type is the general vector. ANSI requires the result to be of the
 * requested type, and real code checks: {@code md5:md5sum-sequence}'s {@code etypecase}
 * has a {@code (simple-array (unsigned-byte 8) (*))} arm and no general-vector one, so
 * cl-postgres' md5 authentication depends on it.
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

	/**
	 * A normalized result-type designator: its {@link ResultFamily} plus, for the vector
	 * family, the packed unsigned-integer element width the designator asks for.
	 *
	 * @param family the sequence family the result belongs to
	 * @param intWidth 8, 16 or 32 when the designator spells an {@code (unsigned-byte N)}
	 * element type the packed representation supports, 0 for a general
	 * (element-type-free) result
	 */
	public record ResultSpec(ResultFamily family, int intWidth) {
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
		return resultFamily(designator, null);
	}

	/**
	 * {@link #resultFamily(LispVal)} with a class registry to resolve user
	 * {@code deftype} aliases through: a designator (or compound-spec head) that names
	 * none of the built-in family members but is a registered {@code deftype} resolves
	 * through its expansion, transitively -- fast-http's multipart parser concatenates
	 * into {@code 'simple-byte-vector}, its own alias of
	 * {@code (simple-array (unsigned-byte 8) (*))}.
	 * @param designator the result-type designator
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the family, or {@code null} when the designator names none of them
	 */
	public static @Nullable ResultFamily resultFamily(LispVal designator, @Nullable ClosRegistry closRegistry) {
		ResultSpec spec = resultSpec(designator, closRegistry);
		return (spec == null) ? null : spec.family();
	}

	/**
	 * {@link #resultFamily(LispVal, ClosRegistry)} keeping the vector family's packed
	 * element width: the full normalization of an EVALUATED result-type designator.
	 * @param designator the result-type designator
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the spec, or {@code null} when the designator names no supported family
	 */
	public static @Nullable ResultSpec resultSpec(LispVal designator, @Nullable ClosRegistry closRegistry) {
		LispVal current = designator;
		// A deftype expansion may itself be an alias; cap the chain so a (registered)
		// self-referential alias cannot loop.
		for (int depth = 0; depth < 8; depth++) {
			LispVal head = (current instanceof LispCons spec) ? spec.car() : current;
			if (!(head instanceof LispSymbol sym)) {
				return null;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			String member = qn == null ? sym.name() : qn.member();
			switch (member) {
				case "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING" -> {
					return new ResultSpec(ResultFamily.STRING, 0);
				}
				case "LIST", "CONS" -> {
					return new ResultSpec(ResultFamily.LIST, 0);
				}
				case "VECTOR", "SIMPLE-VECTOR", "ARRAY", "SIMPLE-ARRAY", "BIT-VECTOR", "SIMPLE-BIT-VECTOR" -> {
					return new ResultSpec(ResultFamily.VECTOR, packedElementWidth(member, current));
				}
				default -> {
					LispVal expansion = (closRegistry == null) ? null : closRegistry.findDeftype(sym.name());
					if (expansion == null) {
						return null;
					}
					current = expansion;
				}
			}
		}
		return null;
	}

	/**
	 * The packed unsigned-integer element width a vector-family designator asks for, or 0
	 * for a general result. Only {@code (vector ELEMENT-TYPE ...)},
	 * {@code (array ELEMENT-TYPE ...)} and {@code (simple-array ELEMENT-TYPE ...)} carry
	 * an element type in the second position -- {@code (simple-vector SIZE)} carries a
	 * SIZE there (its element type is always {@code t}) and the bit-vector spellings a
	 * size too, so reading position 1 unconditionally would turn {@code (simple-vector
	 * 41)} into a specialized request. Same shape rule as {@code typep}'s array arm.
	 */
	private static int packedElementWidth(String member, LispVal designator) {
		boolean carriesElementType = switch (member) {
			case "VECTOR", "ARRAY", "SIMPLE-ARRAY" -> true;
			default -> false;
		};
		if (!carriesElementType || !(designator instanceof LispCons spec) || !(spec.cdr() instanceof LispCons rest)) {
			return 0;
		}
		return unsignedByteWidth(rest.car());
	}

	/**
	 * The width of an {@code (unsigned-byte 8|16|32)} element-type specifier -- the three
	 * widths the packed representation supports -- or 0 for anything else (including
	 * {@code *}, {@code t}, {@code character} and the unsupported widths, which all mean
	 * a general vector here).
	 * @param elementType the element-type specifier
	 * @return 8, 16, 32, or 0
	 */
	public static int unsignedByteWidth(LispVal elementType) {
		if (elementType instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& cons.cdr() instanceof LispCons widthCell && widthCell.car() instanceof LispInteger width
				&& widthCell.cdr() instanceof LispNil) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
			if (LispNames.UNSIGNED_BYTE.equals(qn == null ? head.name() : qn.member())
					&& (width.value() == 8 || width.value() == 16 || width.value() == 32)) {
				return (int) width.value();
			}
		}
		return 0;
	}

	/**
	 * Normalizes a result-type designator as WRITTEN in a call -- a literal
	 * {@code (quote designator)} form -- to its family. A computed (non-quoted) type form
	 * yields {@code null}: only the interpreter can resolve one, at runtime.
	 * @param typeForm the result-type argument as written
	 * @return the family, or {@code null} when the form is not a literal designator of a
	 * supported family
	 */
	public static @Nullable ResultFamily literalResultFamily(LispVal typeForm) {
		return literalResultFamily(typeForm, null);
	}

	/**
	 * {@link #literalResultFamily(LispVal)} with a class registry to resolve user
	 * {@code deftype} aliases through (see {@link #resultFamily(LispVal, ClosRegistry)}).
	 * @param typeForm the result-type argument as written
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the family, or {@code null} when the form is not a literal designator of a
	 * supported family
	 */
	public static @Nullable ResultFamily literalResultFamily(LispVal typeForm, @Nullable ClosRegistry closRegistry) {
		ResultSpec spec = literalResultSpec(typeForm, closRegistry);
		return (spec == null) ? null : spec.family();
	}

	/**
	 * {@link #literalResultFamily(LispVal, ClosRegistry)} keeping the vector family's
	 * packed element width.
	 * @param typeForm the result-type argument as written
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the spec, or {@code null} when the form is not a literal designator of a
	 * supported family
	 */
	public static @Nullable ResultSpec literalResultSpec(LispVal typeForm, @Nullable ClosRegistry closRegistry) {
		LispVal designator = unquoted(typeForm);
		return (designator == null) ? null : resultSpec(designator, closRegistry);
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
	public static LispVal expand(LispCons cons) {
		return expand(cons, false);
	}

	/**
	 * {@link #expand(LispCons)} with control over the string family's argument
	 * normalization.
	 * @param cons the concatenate expression
	 * @param normalizeArguments whether each string-family argument goes through
	 * {@code %seq-string} first. True for the concatenate calls the PROGRAM wrote (the
	 * ones {@link #needsSeqString} saw, so the helper is injected); false for the ones
	 * this compiler's own macro expansions produce during codegen -- {@code format},
	 * {@code with-output-to-string} and the string-stream builders all concatenate
	 * strings they just built, so wrapping them would cost a call per site and pull the
	 * helper into every program.
	 * @return the expanded expression
	 */
	public static LispVal expand(LispCons cons, boolean normalizeArguments) {
		return expand(cons, normalizeArguments, null);
	}

	/**
	 * {@link #expand(LispCons, boolean)} with a class registry to resolve user
	 * {@code deftype} alias designators through (see
	 * {@link #resultFamily(LispVal, ClosRegistry)}).
	 * @param cons the concatenate expression
	 * @param normalizeArguments whether each string-family argument goes through
	 * {@code %seq-string} first
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the expanded expression
	 */
	public static LispVal expand(LispCons cons, boolean normalizeArguments, @Nullable ClosRegistry closRegistry) {
		List<LispVal> parts = cons.toList();
		ResultSpec spec = (parts.size() >= 2) ? literalResultSpec(parts.get(1), closRegistry) : null;
		if (spec == null) {
			throw new UnsupportedOperationException(
					"Cannot compile concatenate: the result type must be a literal quoted 'list, 'vector or 'string "
							+ "designator");
		}
		List<LispVal> args = parts.subList(2, parts.size());
		return switch (spec.family()) {
			case STRING -> stringChain(args, normalizeArguments);
			case LIST -> appendedElements(args);
			case VECTOR -> (spec.intWidth() == 0) ? coerceCall(appendedElements(args), "VECTOR")
					: intVectorCall(appendedElements(args), spec.intWidth());
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

	/**
	 * Whether the program contains a {@code (concatenate 'string ...)} whose lowering
	 * needs the {@code %seq-string} helper -- i.e. one with an argument that is not
	 * already a string literal. The backends gate the helper's injection on this, so a
	 * program that only concatenates literals (or none at all) stays byte-identical.
	 * @param program the top-level forms
	 * @return {@code true} when at least one argument has to be normalized at run time
	 */
	public static boolean needsSeqString(List<LispVal> program) {
		return needsSeqString(program, null);
	}

	/**
	 * {@link #needsSeqString(List)} with a class registry, so a
	 * {@code (concatenate 'alias ...)} whose alias is a user {@code deftype} of the
	 * string family gates the helper in too (see
	 * {@link #resultFamily(LispVal, ClosRegistry)}).
	 * @param program the top-level forms
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return {@code true} when at least one argument has to be normalized at run time
	 */
	public static boolean needsSeqString(List<LispVal> program, @Nullable ClosRegistry closRegistry) {
		for (LispVal form : program) {
			if (needsSeqString(form, closRegistry)) {
				return true;
			}
		}
		return false;
	}

	private static boolean needsSeqString(LispVal form, @Nullable ClosRegistry closRegistry) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() >= 3 && parts.get(0) instanceof LispSymbol op && LispNames.CONCATENATE.equals(op.name())
				&& literalResultFamily(parts.get(1), closRegistry) == ResultFamily.STRING) {
			for (LispVal arg : parts.subList(2, parts.size())) {
				if (!isKnownString(arg)) {
					return true;
				}
			}
		}
		return needsSeqString(cons.car(), closRegistry) || needsSeqString(cons.cdr(), closRegistry);
	}

	/**
	 * Whether the program writes a {@code concatenate} whose result type asks for a
	 * PACKED unsigned-integer vector, i.e. whose lowering will call
	 * {@code %seq-int-vector}. The backends gate the helper's injection on this (plus a
	 * {@code #'concatenate} reference, whose wrapper spells the same dispatch at run
	 * time), so a program that never asks for one stays byte-identical.
	 *
	 * <p>
	 * Unlike {@link #needsSeqString} this gate cannot be outrun by a codegen-time
	 * expansion: nothing this compiler generates concatenates into a packed element type
	 * -- {@code format}, {@code with-output-to-string} and the string-stream builders all
	 * emit the {@code 'string} family.
	 * @param program the top-level forms
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return {@code true} when at least one call builds a packed vector
	 */
	public static boolean needsSeqIntVector(List<LispVal> program, @Nullable ClosRegistry closRegistry) {
		for (LispVal form : program) {
			if (needsSeqIntVector(form, closRegistry)) {
				return true;
			}
		}
		return false;
	}

	private static boolean needsSeqIntVector(LispVal form, @Nullable ClosRegistry closRegistry) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() >= 2 && parts.get(0) instanceof LispSymbol op && LispNames.CONCATENATE.equals(op.name())) {
			ResultSpec spec = literalResultSpec(parts.get(1), closRegistry);
			if (spec != null && spec.intWidth() != 0) {
				return true;
			}
		}
		return needsSeqIntVector(cons.car(), closRegistry) || needsSeqIntVector(cons.cdr(), closRegistry);
	}

	// Nested binary %string-concat calls; a lone argument is concatenated with "" so the
	// result is always a fresh string. Every argument that is not already a string
	// literal goes through %seq-string first: Common Lisp's string family takes any
	// character SEQUENCE, and nil -- the empty list -- is the one that shows up in real
	// code (s-sql builds "CREATE TABLE x" as (concatenate 'string (unless tableset
	// "TABLE ") name)). One call per argument, never an inlined coerce loop: see the
	// "Why the string family takes string arguments" re-evaluation trigger in
	// .kb/concatenate-result-families.md.
	private static LispVal stringChain(List<LispVal> args, boolean normalize) {
		if (args.isEmpty()) {
			return new LispString("");
		}
		LispVal acc = (args.size() == 1) ? concatCall(normalized(args.get(0), normalize), new LispString(""))
				: normalized(args.get(0), normalize);
		for (int i = 1; i < args.size(); i++) {
			acc = concatCall(acc, normalized(args.get(i), normalize));
		}
		return acc;
	}

	// (%seq-string arg), unless the argument is already a literal string.
	private static LispVal normalized(LispVal arg, boolean normalize) {
		return (!normalize || isKnownString(arg)) ? arg
				: listToCons(List.of(new LispSymbol(LispNames.SEQ_STRING), arg));
	}

	private static boolean isKnownString(LispVal arg) {
		return arg instanceof LispString;
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

	// (%seq-int-vector <elements> width) -- the packed vector family. A CALL, never an
	// inlined allocate-and-fill loop, for the reason the string family calls
	// %seq-string: one emitted body must not grow with the number of concatenate sites
	// (.kb/wasm-function-body-size.md). The helper also walks its list linearly, which
	// an inlined (make-array n :initial-contents list) would not (that fill indexes with
	// elt).
	private static LispVal intVectorCall(LispVal elements, int width) {
		return listToCons(List.of(new LispSymbol(LispNames.SEQ_INT_VECTOR), elements, new LispInteger(width)));
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
