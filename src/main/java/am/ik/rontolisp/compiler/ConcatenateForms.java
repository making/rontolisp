package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFloatArray;
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
 * The vector family carries its ELEMENT TYPE as well, as one of the closed
 * {@link ArrayElementTypes} codes, and one lowering per PACKED FAMILY builds the
 * representation that code names: an {@code (unsigned-byte 8|16|32)} element type
 * ({@code '(vector (unsigned-byte 8))}, {@code '(simple-array (unsigned-byte 8) (*))})
 * selects the packed integer-vector representation
 * ({@code .kb/packed-integer-vectors.md}) through {@code %seq-int-vector}, and a
 * {@code single-float} / {@code double-float} / {@code bfloat16} one the packed float
 * array ({@code .kb/vec.md}) through {@code %seq-float-vector}; every other element type
 * is the general vector. ANSI requires the result to be of the requested type, and real
 * code checks: {@code md5:md5sum-sequence}'s {@code etypecase} has a
 * {@code (simple-array (unsigned-byte 8) (*))} arm and no general-vector one, so
 * cl-postgres' md5 authentication depends on it.
 *
 * <p>
 * The compilers additionally require the result type to be written as a literal quoted
 * designator: the interpreter evaluates it at runtime, a compiler has to resolve it
 * statically.
 *
 * <p>
 * <b>{@code coerce} shares the packed arms.</b> A result-type designator means the same
 * thing whichever operator reads it, so {@link #packedVectorCoerce} lowers
 * {@code (coerce seq '(vector (unsigned-byte 8)))} and
 * {@code (coerce seq '(vector single-float))} through the very same helpers and the very
 * same gates ({@link #needsSeqIntVector} / {@link #needsSeqFloatVector}). That retires
 * the divergence this file used to record as a re-evaluation trigger ("coerce still DROPS
 * the element type"): {@code expandCoerce} collapses a compound spec to its head, so the
 * packed spelling built a GENERAL vector -- which is how every literal lookup table a
 * library spells as {@code (coerce '(...) '(vector (unsigned-byte 32)))} lost its element
 * type.
 *
 * <p>
 * It retired it for the integer widths ONLY until 2026-09-06 ({@code .todo/707}): the
 * float widths were never added, so {@code (coerce '(1.0) '(vector single-float))}
 * answered a general vector and {@code (coerce #f(1.0) '(array bfloat16))} answered its
 * ARGUMENT -- a silent wrong answer in both directions, at every float width, through
 * both operators. That is why the element type travels as a CODE from the closed space
 * rather than as a second width field beside the integer one: a hand-rolled list of the
 * packed families is exactly what went one family short.
 *
 * <p>
 * The CHARACTER code joined the packed arms the same day, through {@link #needsSeqString}
 * and the {@code %seq-string} helper the string family already builds elements into: a
 * {@code (vector character)} result answers a mutable {@code LispString} -- the same
 * value {@code make-array}'s {@code :element-type 'character} builds -- rather than a
 * general vector that merely holds characters, so {@code array-element-type},
 * {@code stringp} and the printer agree with every other door onto the same
 * representation.
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
	 * family, the UPGRADED element type the designator asks for, as one of the
	 * {@link ArrayElementTypes} codes.
	 *
	 * <p>
	 * The code and not a width: the packed representations are a closed code space
	 * already ({@code make-array} picks one from exactly it), and a second field shaped
	 * like the integer widths would be the next transcription of that space -- the defect
	 * {@code .todo/487} removed from four other sites on 2026-09-05. Which representation
	 * a code names is asked of the representations themselves
	 * ({@link #packedIntWidth(int)}, {@link #isPackedFloat(int)}), never of a list here.
	 *
	 * @param family the sequence family the result belongs to
	 * @param elementType the {@link ArrayElementTypes} code the designator's element type
	 * upgrades to, {@link ArrayElementTypes#T} for a general (element-type-free) result
	 */
	public record ResultSpec(ResultFamily family, int elementType) {

		/**
		 * The packed unsigned-integer element width this result asks for, or 0.
		 * @return 8, 16, 32, or 0
		 */
		public int intWidth() {
			return packedIntWidth(this.elementType);
		}

		/**
		 * Whether this result asks for a packed FLOAT array.
		 * @return true when the element type names one of the packed float widths
		 */
		public boolean packedFloat() {
			return isPackedFloat(this.elementType);
		}

	}

	/**
	 * The packed unsigned-integer element width an {@link ArrayElementTypes} code names,
	 * or 0 when the code names no packed integer vector. ASKED of the representation --
	 * {@code LispNames.unsignedByteWidth} over the specifier the code answers -- so the
	 * code space and the widths cannot drift apart.
	 * @param elementTypeCode one of the {@link ArrayElementTypes} codes
	 * @return 8, 16, 32, or 0
	 */
	public static int packedIntWidth(int elementTypeCode) {
		return LispNames.unsignedByteWidth(ArrayElementTypes.valueOf(elementTypeCode));
	}

	/**
	 * Whether an {@link ArrayElementTypes} code names a packed FLOAT width. ASKED of the
	 * representation -- {@link LispFloatArray#prototypeFor} over the specifier the code
	 * answers, i.e. of the sealed umbrella's own permits -- so a fourth width is
	 * reachable here the moment it is reachable from {@code make-array}, with nothing to
	 * add.
	 * @param elementTypeCode one of the {@link ArrayElementTypes} codes
	 * @return true when the code names one of the packed float widths
	 */
	public static boolean isPackedFloat(int elementTypeCode) {
		return LispFloatArray.prototypeFor(ArrayElementTypes.valueOf(elementTypeCode)) != null;
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
					return new ResultSpec(ResultFamily.STRING, ArrayElementTypes.T);
				}
				case "LIST", "CONS" -> {
					return new ResultSpec(ResultFamily.LIST, ArrayElementTypes.T);
				}
				case "VECTOR", "SIMPLE-VECTOR", "ARRAY", "SIMPLE-ARRAY", "BIT-VECTOR", "SIMPLE-BIT-VECTOR" -> {
					// An atomic 'bit-vector names no element type, but the family IS
					// the bit family: the packed-element reader answers null for a
					// bare symbol, so the code falls back to the stamp the atomic
					// spelling means (.todo/043).
					LispVal elementType = LispNames.packedVectorElementType(current);
					int code = (elementType == null
							&& ("BIT-VECTOR".equals(member) || "SIMPLE-BIT-VECTOR".equals(member)))
									? ArrayElementTypes.BIT : ArrayElementTypes.codeOf(elementType);
					return new ResultSpec(ResultFamily.VECTOR, code);
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
	 * The width of an {@code (unsigned-byte 8|16|32)} element-type specifier -- the three
	 * widths the packed representation supports -- or 0 for anything else (including
	 * {@code *}, {@code t}, {@code character} and the unsupported widths, which all mean
	 * a general vector here). The spelling itself lives in {@link LispNames}, below both
	 * this package and {@code macro}, so the fold can ask the same question.
	 * @param elementType the element-type specifier
	 * @return 8, 16, 32, or 0
	 */
	public static int unsignedByteWidth(LispVal elementType) {
		return LispNames.unsignedByteWidth(elementType);
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
			case VECTOR -> packedVectorCall(appendedElements(args), spec.elementType());
		};
	}

	/**
	 * The packed-vector lowering of a {@code coerce} call, or {@code null} when the call
	 * asks for anything else: {@code (coerce seq '(vector (unsigned-byte 8)))} is
	 * {@code (%seq-int-vector seq 8)}, {@code (coerce seq '(vector single-float))} is
	 * {@code (%seq-float-vector seq 5)}, and {@code (coerce seq '(vector character))} is
	 * {@code (%seq-string seq)} -- the same helpers and the same values
	 * {@code (concatenate '(vector (unsigned-byte 8)) seq)},
	 * {@code (concatenate '(vector single-float) seq)} and
	 * {@code (concatenate '(vector character) seq)} produce.
	 *
	 * <p>
	 * Every other designator -- including the general vector, and a width the packed
	 * representation does not support -- answers {@code null}, so the caller falls back
	 * to {@code LispMacroExpander.expandCoerce} and the program compiles exactly as
	 * before. The interpreter and both compilers consult this before their own coerce
	 * expansion, which is what keeps one answer on four backends.
	 * @param cons the coerce expression
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return the {@code %seq-int-vector} / {@code %seq-float-vector} /
	 * {@code %seq-string} call, or null when the result is not one of the packed vector
	 * representations
	 */
	public static @Nullable LispVal packedVectorCoerce(LispCons cons, @Nullable ClosRegistry closRegistry) {
		if (!cons.isProperList()) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			return null;
		}
		ResultSpec spec = literalResultSpec(parts.get(2), closRegistry);
		if (spec == null || spec.family() != ResultFamily.VECTOR) {
			return null;
		}
		boolean packed = spec.intWidth() != 0 || spec.packedFloat()
				|| spec.elementType() == ArrayElementTypes.CHARACTER;
		return packed ? packedVectorCall(parts.get(1), spec.elementType()) : null;
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
	 * already a string literal -- OR a {@code concatenate} / {@code coerce} whose result
	 * type asks for a {@code (vector character)}, which always lowers through a call to
	 * it ({@link #packedVectorCall}). The backends gate the helper's injection on this,
	 * so a program that only concatenates literals into a string (and never asks for a
	 * character vector) stays byte-identical.
	 * @param program the top-level forms
	 * @return {@code true} when at least one call needs the helper at run time
	 */
	public static boolean needsSeqString(List<LispVal> program) {
		return needsSeqString(program, null);
	}

	/**
	 * {@link #needsSeqString(List)} with a class registry, so a
	 * {@code (concatenate 'alias ...)} whose alias is a user {@code deftype} of the
	 * string family, or of a {@code (vector character)} shape, gates the helper in too
	 * (see {@link #resultFamily(LispVal, ClosRegistry)}).
	 * @param program the top-level forms
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return {@code true} when at least one call needs the helper at run time
	 */
	public static boolean needsSeqString(List<LispVal> program, @Nullable ClosRegistry closRegistry) {
		for (LispVal form : program) {
			if (needsSeqString(form, closRegistry)) {
				return true;
			}
		}
		return needsPackedVector(program, closRegistry, spec -> spec.elementType() == ArrayElementTypes.CHARACTER);
	}

	private static boolean needsSeqString(LispVal form, @Nullable ClosRegistry closRegistry) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol op && LispNames.CONCATENATE.equals(op.name())) {
				List<LispVal> parts = cons.toList();
				if (parts.size() >= 3 && literalResultFamily(parts.get(1), closRegistry) == ResultFamily.STRING) {
					for (LispVal arg : parts.subList(2, parts.size())) {
						if (!isKnownString(arg)) {
							return true;
						}
					}
				}
			}
			if (needsSeqString(cons.car(), closRegistry)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	/**
	 * Whether the program writes a {@code concatenate} or a {@code coerce} whose result
	 * type asks for a PACKED unsigned-integer vector, i.e. whose lowering will call
	 * {@code %seq-int-vector}. The backends gate the helper's injection on this (plus a
	 * {@code #'concatenate} reference, whose wrapper spells the same dispatch at run
	 * time), so a program that never asks for one stays byte-identical.
	 *
	 * <p>
	 * Unlike {@link #needsSeqString} this gate cannot be outrun by a codegen-time
	 * expansion: nothing this compiler generates builds a packed element type --
	 * {@code format}, {@code with-output-to-string} and the string-stream builders all
	 * emit the {@code 'string} family, and every {@code coerce} an expansion synthesizes
	 * asks for {@code 'list} / {@code 'string} / {@code 'vector}.
	 * @param program the top-level forms
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return {@code true} when at least one call builds a packed vector
	 */
	public static boolean needsSeqIntVector(List<LispVal> program, @Nullable ClosRegistry closRegistry) {
		return needsPackedVector(program, closRegistry, spec -> spec.intWidth() != 0);
	}

	/**
	 * {@link #needsSeqIntVector}'s float twin: whether the program writes a
	 * {@code concatenate} or a {@code coerce} whose result type asks for a PACKED FLOAT
	 * array, i.e. whose lowering will call {@code %seq-float-vector}. Gated separately
	 * from the integer helper so a program that asks for one family never carries the
	 * other family's allocations, and one that asks for neither stays byte-identical.
	 * @param program the top-level forms
	 * @param closRegistry the registry whose {@code deftype} expansions resolve alias
	 * designators, or null for the built-in members only
	 * @return {@code true} when at least one call builds a packed float array
	 */
	public static boolean needsSeqFloatVector(List<LispVal> program, @Nullable ClosRegistry closRegistry) {
		return needsPackedVector(program, closRegistry, ResultSpec::packedFloat);
	}

	private static boolean needsPackedVector(List<LispVal> program, @Nullable ClosRegistry closRegistry,
			java.util.function.Predicate<ResultSpec> wanted) {
		for (LispVal form : program) {
			if (needsPackedVector(form, closRegistry, wanted)) {
				return true;
			}
		}
		return false;
	}

	private static boolean needsPackedVector(LispVal form, @Nullable ClosRegistry closRegistry,
			java.util.function.Predicate<ResultSpec> wanted) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			// (concatenate 'TYPE ...) reads its designator at index 1, (coerce value
			// 'TYPE) at index 2; both lower through a packed builder when it spells a
			// packed element type.
			if (cons.car() instanceof LispSymbol op
					&& (LispNames.CONCATENATE.equals(op.name()) || LispNames.COERCE.equals(op.name()))) {
				List<LispVal> parts = cons.toList();
				int designator = LispNames.CONCATENATE.equals(op.name()) ? 1 : parts.size() == 3 ? 2 : -1;
				if (parts.size() >= 2 && designator > 0) {
					ResultSpec spec = literalResultSpec(parts.get(designator), closRegistry);
					if (spec != null && wanted.test(spec)) {
						return true;
					}
				}
			}
			if (needsPackedVector(cons.car(), closRegistry, wanted)) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
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

	// The vector family's element sequence as the representation its element type asks
	// for: one lowering per PACKED FAMILY -- (%seq-int-vector elements width) for the
	// packed unsigned-integer widths, (%seq-float-vector elements code) for the packed
	// float ones, (%seq-string elements) for CHARACTER -- and the general
	// (coerce elements 'vector) for every other code.
	//
	// The character arm answers exactly what make-array builds for the same element
	// type: a mutable LispString, not a general vector that merely holds characters. A
	// (vector character) result IS a string in CL (SBCL answers CHARACTER from
	// array-element-type and "ab" from the printer), and %seq-string is already the
	// STRING family's own element-to-string builder, so the two designators converge on
	// one call rather than diverging on what a result-type designator means.
	//
	// A CALL either way, never an inlined allocate-and-fill loop, for the reason the
	// string family calls %seq-string: one emitted body must not grow with the number of
	// concatenate sites (.kb/wasm-function-body-size.md). The helpers also walk the
	// element list linearly, which an inlined (make-array n :initial-contents list) would
	// not (that fill indexes with elt). Each packed family rides its own injection gate
	// (needsSeqIntVector / needsSeqFloatVector / needsSeqString) so a program that asks
	// for only one of the families carries only that family's allocations, and one that
	// asks for none is byte-identical.
	private static LispVal packedVectorCall(LispVal elements, int elementTypeCode) {
		int width = packedIntWidth(elementTypeCode);
		if (width != 0) {
			return listToCons(List.of(new LispSymbol(LispNames.SEQ_INT_VECTOR), elements, new LispInteger(width)));
		}
		if (isPackedFloat(elementTypeCode)) {
			return listToCons(
					List.of(new LispSymbol(LispNames.SEQ_FLOAT_VECTOR), elements, new LispInteger(elementTypeCode)));
		}
		if (elementTypeCode == ArrayElementTypes.CHARACTER) {
			return listToCons(List.of(new LispSymbol(LispNames.SEQ_STRING), elements));
		}
		if (elementTypeCode == ArrayElementTypes.BIT) {
			// A bit-vector result builds the stamped general array through coerce's
			// bit arm, not the T vector the fallthrough below answers (.todo/043).
			return coerceCall(elements, "BIT-VECTOR");
		}
		return coerceCall(elements, "VECTOR");
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
