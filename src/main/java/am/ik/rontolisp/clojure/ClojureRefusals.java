package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The refusal carriers: one {@code clojure.lisp} function per class the oracle throws
 * where the run-time library or the lowering refuses, each signalling its message as a
 * {@code %clojure-refusal} carrying that class's chain ({@code clojure.lisp},
 * "Refusals"). A catch, {@code class} and {@code instance?} read the chain; a program
 * with none of them has every carrier call folded back to the plain {@code error} with
 * the same message ({@link ClojureArms.Family#REFUSAL}). A carrier takes the message;
 * where the oracle casts a value it was handed, the {@code -of} carrier takes that value
 * too, nil there being the oracle's {@code NullPointerException}.
 */
final class ClojureRefusals {

	/** The condition class a carrier signals. */
	static final String CONDITION = "RONTOLISP::%CLOJURE-REFUSAL";

	/** {@code java.lang.IllegalArgumentException}. */
	static final String ILLEGAL_ARGUMENT = "RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION";

	/** {@code java.lang.IllegalStateException}. */
	static final String ILLEGAL_STATE = "RONTOLISP::%CLOJURE-ILLEGAL-STATE-EXCEPTION";

	/** {@code java.lang.ClassCastException}. */
	static final String CLASS_CAST = "RONTOLISP::%CLOJURE-CLASS-CAST-EXCEPTION";

	/** {@code java.lang.NullPointerException}. */
	static final String NULL_POINTER = "RONTOLISP::%CLOJURE-NULL-POINTER-EXCEPTION";

	/** {@code java.lang.IndexOutOfBoundsException}. */
	static final String INDEX_OUT_OF_BOUNDS = "RONTOLISP::%CLOJURE-INDEX-OUT-OF-BOUNDS-EXCEPTION";

	/** {@code java.lang.StringIndexOutOfBoundsException}. */
	static final String STRING_INDEX_OUT_OF_BOUNDS = "RONTOLISP::%CLOJURE-STRING-INDEX-OUT-OF-BOUNDS-EXCEPTION";

	/** {@code java.lang.UnsupportedOperationException}. */
	static final String UNSUPPORTED_OPERATION = "RONTOLISP::%CLOJURE-UNSUPPORTED-OPERATION-EXCEPTION";

	/** {@code java.lang.NumberFormatException}. */
	static final String NUMBER_FORMAT = "RONTOLISP::%CLOJURE-NUMBER-FORMAT-EXCEPTION";

	/** {@code java.lang.ArithmeticException}. */
	static final String ARITHMETIC = "RONTOLISP::%CLOJURE-ARITHMETIC-EXCEPTION";

	/** {@code clojure.lang.ArityException}: a wrong argument count. */
	static final String ARITY = "RONTOLISP::%CLOJURE-ARITY-EXCEPTION";

	/** {@code java.lang.RuntimeException}. */
	static final String RUNTIME = "RONTOLISP::%CLOJURE-RUNTIME-EXCEPTION";

	/** {@code java.lang.Exception}. */
	static final String EXCEPTION = "RONTOLISP::%CLOJURE-EXCEPTION";

	/** {@code java.lang.ClassNotFoundException}. */
	static final String CLASS_NOT_FOUND = "RONTOLISP::%CLOJURE-CLASS-NOT-FOUND-EXCEPTION";

	/** {@code java.util.regex.PatternSyntaxException}: a malformed regular expression. */
	static final String PATTERN_SYNTAX = "RONTOLISP::%CLOJURE-PATTERN-SYNTAX-EXCEPTION";

	/**
	 * {@code java.util.IllegalFormatConversionException}: a format argument of the wrong
	 * kind for its directive.
	 */
	static final String ILLEGAL_FORMAT_CONVERSION = "RONTOLISP::%CLOJURE-ILLEGAL-FORMAT-CONVERSION-EXCEPTION";

	/** {@code java.lang.AssertionError}. */
	static final String ASSERTION_ERROR = "RONTOLISP::%CLOJURE-ASSERTION-ERROR";

	/**
	 * {@code java.lang.ClassCastException} casting a value, its
	 * {@code NullPointerException} when the value is nil.
	 */
	static final String CLASS_CAST_OF = "RONTOLISP::%CLOJURE-CLASS-CAST-EXCEPTION-OF";

	/**
	 * {@code java.lang.IllegalArgumentException} over a value, its
	 * {@code NullPointerException} when the value is nil.
	 */
	static final String ILLEGAL_ARGUMENT_OF = "RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION-OF";

	/**
	 * A collection whose members the oracle reads as map entries: its
	 * {@code IllegalArgumentException} for one it cannot seq (or a vector, no pair), its
	 * {@code ClassCastException} of a member otherwise.
	 */
	static final String MAP_ENTRY = "RONTOLISP::%CLOJURE-MAP-ENTRY-REFUSAL";

	/**
	 * {@code subs}: {@code subseq} refusing a bound outside a string as the oracle's
	 * {@code StringIndexOutOfBoundsException}, in {@code subseq}'s words; the plain
	 * {@code subseq} where no class is read (the family's alias).
	 */
	static final String SUBS = "RONTOLISP::%CLOJURE-SUBS";

	/**
	 * {@code .charAt}: {@code char} refusing an index outside a string as the oracle's
	 * {@code StringIndexOutOfBoundsException}, in {@code char}'s words; the plain
	 * {@code char} where no class is read (the family's alias).
	 */
	static final String CHAR_AT = "RONTOLISP::%CLOJURE-CHAR-AT";

	/**
	 * {@link #SUBS} for a {@code .substring} whose receiver the lowering does not know to
	 * be a {@code String}: the oracle calls it by reflection, so a non-number bound is an
	 * {@code IllegalArgumentException} where a typed call's is a
	 * {@code ClassCastException}.
	 */
	static final String SUBS_BY_REFLECTION = "RONTOLISP::%CLOJURE-SUBS-BY-REFLECTION";

	/**
	 * {@link #CHAR_AT} for a receiver not known to be a {@code String}
	 * ({@link #SUBS_BY_REFLECTION}).
	 */
	static final String CHAR_AT_BY_REFLECTION = "RONTOLISP::%CLOJURE-CHAR-AT-BY-REFLECTION";

	/**
	 * {@code vec}'s argument: itself when the oracle's {@code vec} takes it, else its
	 * {@code RuntimeException} (it casts a non-collection to an array before it seqs it);
	 * the family's view of its argument where no class is read.
	 */
	static final String VEC_ARG = "RONTOLISP::%CLOJURE-VEC-ARG";

	/** Each carrier of one class, to the class whose chain it signals. */
	static final Map<String, String> CLASSES = Map.ofEntries(
			Map.entry(ILLEGAL_ARGUMENT, "java.lang.IllegalArgumentException"),
			Map.entry(ILLEGAL_STATE, "java.lang.IllegalStateException"),
			Map.entry(CLASS_CAST, "java.lang.ClassCastException"),
			Map.entry(NULL_POINTER, "java.lang.NullPointerException"),
			Map.entry(INDEX_OUT_OF_BOUNDS, "java.lang.IndexOutOfBoundsException"),
			Map.entry(STRING_INDEX_OUT_OF_BOUNDS, "java.lang.StringIndexOutOfBoundsException"),
			Map.entry(UNSUPPORTED_OPERATION, "java.lang.UnsupportedOperationException"),
			Map.entry(NUMBER_FORMAT, "java.lang.NumberFormatException"),
			Map.entry(ARITHMETIC, "java.lang.ArithmeticException"), Map.entry(ARITY, "clojure.lang.ArityException"),
			Map.entry(RUNTIME, "java.lang.RuntimeException"), Map.entry(EXCEPTION, "java.lang.Exception"),
			Map.entry(CLASS_NOT_FOUND, "java.lang.ClassNotFoundException"),
			Map.entry(PATTERN_SYNTAX, "java.util.regex.PatternSyntaxException"),
			Map.entry(ILLEGAL_FORMAT_CONVERSION, "java.util.IllegalFormatConversionException"),
			Map.entry(ASSERTION_ERROR, "java.lang.AssertionError"));

	/** Every carrier: a call to one is a refusal the strip folds. */
	static final Set<String> CARRIERS = carriers();

	private ClojureRefusals() {
	}

	private static Set<String> carriers() {
		java.util.Set<String> all = new java.util.HashSet<>(CLASSES.keySet());
		all.addAll(List.of(CLASS_CAST_OF, ILLEGAL_ARGUMENT_OF, MAP_ENTRY));
		return Set.copyOf(all);
	}

	/**
	 * The refusal of a message as a class: {@code (carrier message)}.
	 * @param carrier the carrier of the class the oracle throws there
	 * @param message the message: a literal, {@code (format nil control args...)}, or a
	 * form answering the text
	 * @return the call
	 */
	static LispVal refusal(String carrier, LispVal message) {
		return ClojureLowerUtil.list(new LispSymbol(carrier), message);
	}

	/**
	 * The refusal of a formatted message as a class:
	 * {@code (carrier (format nil control args...))}, which folds to
	 * {@code (error control args...)}.
	 * @param carrier the carrier of the class the oracle throws there
	 * @param control the literal format control
	 * @param args the forms the control renders
	 * @return the call
	 */
	static LispVal formatted(String carrier, String control, LispVal... args) {
		List<LispVal> format = new ArrayList<>();
		format.add(ClojureLowerUtil.sym("format"));
		format.add(ClojureLowering.NIL_CONST);
		format.add(LispString.literal(control));
		format.addAll(List.of(args));
		return refusal(carrier, ClojureLowerUtil.list(format));
	}

	/**
	 * The refusal of a message over a value the oracle was handed:
	 * {@code (carrier message value)}, the carrier ({@link #CLASS_CAST_OF},
	 * {@link #ILLEGAL_ARGUMENT_OF}, {@link #MAP_ENTRY}) picking the class by the value.
	 * @param carrier the carrier
	 * @param message the message, as for {@link #refusal(String, LispVal)}
	 * @param culprit the value, a variable (the strip drops it)
	 * @return the call
	 */
	static LispVal refusal(String carrier, LispVal message, LispVal culprit) {
		return ClojureLowerUtil.list(new LispSymbol(carrier), message, culprit);
	}

}
