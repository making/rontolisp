package am.ik.rontolisp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The mutable registry behind the static CLOS subset ({@code defclass} /
 * {@code defgeneric} / {@code defmethod} / {@code make-instance} / {@code slot-value}).
 * Mirrors the {@code defstruct} accessor registry: one instance lives per evaluator
 * ({@code LispEvaluator}) and one per compilation ({@code Jvm/WasmLispCompiler.Ctx}),
 * populated by the {@link am.ik.rontolisp.macro.LispMacroExpander} expansions.
 *
 * <p>
 * An instance is a tagged proper list {@code (%class-<name> v1 v2 ...)} like a defstruct
 * instance; the slot layout is the superclass slots (in inheritance order) followed by
 * the class's own slots, so single inheritance keeps every inherited slot at the same
 * 1-based position in all descendants. The ancestor sets recorded here give
 * {@code defmethod} class specializers their statically-known descendant-tag membership
 * tests.
 */
public final class ClosRegistry {

	/**
	 * The CLHS short-form {@code :method-combination} operators. Each names ONE uniform
	 * mechanism -- "call every applicable method carrying this name as its qualifier, in
	 * specificity order, and combine the results with the operator" -- so the family is
	 * implemented once rather than per operator. The long form
	 * ({@code define-method-combination}) is out of scope; a {@code :method-combination}
	 * naming anything else is rejected at {@code defgeneric} time.
	 */
	public static final Set<String> SHORT_FORM_COMBINATIONS = Set.of(LispNames.PROGN, LispNames.AND, LispNames.OR,
			LispNames.ADD, LispNames.LIST, LispNames.NCONC, LispNames.APPEND, LispNames.MAX, LispNames.MIN);

	/**
	 * Creates a registry pre-seeded with the built-in condition hierarchy:
	 * {@code condition} &gt; {@code serious-condition} &gt; {@code error} (&gt;
	 * {@code simple-error} and the standard error subtypes) plus {@code warning}/
	 * {@code simple-warning}. Conditions are ordinary CLOS-subset classes, so
	 * {@code define-condition} extends the hierarchy through {@code defclass} and
	 * {@code typep}/{@code typecase}/{@code handler-case} test instances by tag
	 * membership.
	 */
	public ClosRegistry() {
		// The slot-unbound marker is a LAYOUT ONLY -- never a class. It must be
		// distinguishable from every user value with one layout-identity compare (that is
		// what makes the per-read boundness check cheap on all four backends), but it
		// must
		// NOT appear in classes(), or it would join every typep tag table,
		// class-slot-defs
		// answer and standard-object descendant set.
		this.layoutsByTag.put(UNBOUND_TAG, LispLayout.ofClass(UNBOUND_CLASS_NAME, List.of(), List.of()));
		// The pathname layout is a LAYOUT ONLY too: a pathname value is an instance
		// carrying its namestring (LispLayout.PATHNAME), and registering it
		// here -- never as a class or struct -- is what resolves the %PATHNAME tag for
		// %obj-new/%obj-is on every backend while keeping it out of every typep tag
		// table, structure-object enumeration and %class-slot-defs answer.
		this.layoutsByTag.put(LispLayout.PATHNAME_TAG, LispLayout.PATHNAME);
		// Same treatment for the synonym-stream layout, and for the same reason: a
		// synonym stream is a VALUE (LispLayout.SYNONYM_STREAM), and this registration
		// is what resolves the %SYNONYM-STREAM tag for %obj-new/%obj-is on every
		// backend without making it a class anything can specialize on or enumerate.
		this.layoutsByTag.put(LispLayout.SYNONYM_STREAM_TAG, LispLayout.SYNONYM_STREAM);
		// And for the OPEN stream layout, which is what made a stream self-describing:
		// the value carries the backend handle plus the kind keyword, so streamp can
		// tell a stream from a file descriptor and file-stream from string-stream.
		this.layoutsByTag.put(LispLayout.STREAM_TAG, LispLayout.STREAM);
		// And for the readtable layout: a readtable is a VALUE (LispLayout.READTABLE)
		// built and read by prelude Lisp on every backend.
		this.layoutsByTag.put(LispLayout.READTABLE_TAG, LispLayout.READTABLE);
		this.layoutsByTag.put(LispLayout.RANDOM_STATE_TAG, LispLayout.RANDOM_STATE);
		// reader-error inherits from BOTH parse-error and stream-error (CLHS 9.1.2):
		// parse-error provides the slot layout, stream-error joins the ancestor set
		// (the define-condition multiple-parents rule).
		registerExtraAncestors(READER_ERROR_CLASS_NAME, List.of("STREAM-ERROR"));
		// simple-type-error is both a simple-condition and a type-error (CLHS):
		// type-error
		// provides the layout (it carries the format pair), simple-condition joins the
		// ancestor set.
		registerExtraAncestors("SIMPLE-TYPE-ERROR", List.of("SIMPLE-CONDITION"));
		// simple-error and simple-warning too (CLHS): each carries the format pair
		// itself.
		registerExtraAncestors("SIMPLE-ERROR", List.of("SIMPLE-CONDITION"));
		registerExtraAncestors("SIMPLE-WARNING", List.of("SIMPLE-CONDITION"));
		for (ConditionSeed seed : CONDITION_SEEDS) {
			seedClass(seed.name(), seed.parent(), seed.slots().toArray(new String[0]));
		}
		// The read family signals this class when it runs out of input, so its report is
		// the message every backend prints for an uncaught end of file. It is a plain
		// string (no stream slot to name) so that the interpreter can raise the same
		// message from Java without evaluating a report lambda.
		registerConditionReport(END_OF_FILE_CLASS_NAME, new LispString(END_OF_FILE_MESSAGE));
		registerConditionReport("UNBOUND-SLOT", unboundSlotReport());
		registerConditionReport("PRINT-NOT-READABLE", printNotReadableReport());
	}

	/**
	 * The condition class the read family signals at end of input
	 * ({@code read-char}/{@code read-byte}, and {@code read-line} with an explicit
	 * eof-error-p). Seeded under {@code stream-error}, so an {@code (error () ...)}
	 * handler-case clause catches it too.
	 */
	public static final String END_OF_FILE_CLASS_NAME = "END-OF-FILE";

	/**
	 * The condition class a malformed datum is signaled as: a bad token ({@code .},
	 * {@code 1/0}, {@code #:a:b}, {@code #<}) at runtime {@code read} time. Seeded under
	 * {@code parse-error}, with {@code stream-error} as the second ancestor (CLHS 9.1.2
	 * gives it both parents), so a {@code stream-error} clause catches it too. Its layout
	 * is {@code [FORMAT-CONTROL, FORMAT-ARGUMENTS, STREAM]}: the message-carrying pair
	 * {@code parse-error} provides, plus the stream slot {@code stream-error-stream}
	 * reads back.
	 */
	public static final String READER_ERROR_CLASS_NAME = "READER-ERROR";

	/**
	 * The condition class a string that is no integer syntax is signaled as by
	 * {@code parse-integer} without {@code :junk-allowed} (CLHS {@code parse-integer}).
	 * Its layout is {@code [FORMAT-CONTROL, FORMAT-ARGUMENTS]}, the message-carrying pair
	 * every built-in-signaled class has.
	 */
	public static final String PARSE_ERROR_CLASS_NAME = "PARSE-ERROR";

	/**
	 * The condition class a failed file operation signals ({@code open},
	 * {@code delete-file}, {@code rename-file}, {@code truename}). Its layout is
	 * {@code [PATHNAME, FORMAT-CONTROL, FORMAT-ARGUMENTS]}: the designator
	 * {@code file-error-pathname} reads back, plus the message-carrying pair every
	 * built-in-signaled class has.
	 */
	public static final String FILE_ERROR_CLASS_NAME = "FILE-ERROR";

	/**
	 * The registered {@code :report} of {@link #END_OF_FILE_CLASS_NAME} -- the message an
	 * uncaught end of file prints on every backend.
	 */
	public static final String END_OF_FILE_MESSAGE = "end of file";

	/**
	 * The class a built-in error over a value of the wrong type is signaled as -- a bad
	 * {@code car}, an arithmetic operation on a non-number, and (per CLHS {@code aref})
	 * an index outside its bounds.
	 */
	public static final String TYPE_ERROR_CLASS_NAME = "TYPE-ERROR";

	/** The class an arithmetic failure that is not a division by zero is signaled as. */
	public static final String ARITHMETIC_ERROR_CLASS_NAME = "ARITHMETIC-ERROR";

	/**
	 * The class an argument-SHAPE error is signaled as (CLHS 3.5.1): a keyword the
	 * operator does not accept, an odd keyword tail, a non-symbol in keyword position, a
	 * wrong argument count. Named by the {@code %program-error} lowering of an
	 * expansion-time rejection and by the interpreter's runtime validators and arity
	 * checks; the interpreter also classifies a raw {@code IllegalArgumentException} /
	 * {@code IndexOutOfBoundsException} escaping an evaluation as this class, since those
	 * are how the expander reports a malformed form.
	 */
	public static final String PROGRAM_ERROR_CLASS_NAME = "PROGRAM-ERROR";

	/** The class a division by zero is signaled as. */
	public static final String DIVISION_BY_ZERO_CLASS_NAME = "DIVISION-BY-ZERO";

	/**
	 * What a division by an exact zero reports, on every backend and through every
	 * division path -- never the host's own text ({@code / by zero},
	 * {@code BigInteger divide by zero}).
	 */
	public static final String DIVISION_BY_ZERO_MESSAGE = "Division by zero";

	/**
	 * What a left {@code ash} whose result the backend cannot build reports, followed by
	 * the count as {@code prin1} prints it -- a {@code simple-error} on every backend,
	 * never the host's own text ({@code BigInteger would overflow supported range}) or a
	 * trap.
	 */
	public static final String ASH_COUNT_TOO_LARGE_MESSAGE_PREFIX = "ASH: shift count too large: ";

	/** The class every datum {@code warn} signals descends from. */
	public static final String WARNING_CLASS_NAME = "WARNING";

	/**
	 * The warning class a compile never fails on: a macro-time {@code warn} of one is
	 * reported and not counted by {@code --warnings-as-errors}, as SBCL's
	 * {@code compile-file} leaves {@code failure-p} alone for it.
	 */
	public static final String STYLE_WARNING_CLASS_NAME = "STYLE-WARNING";

	/**
	 * The substring that tells a {@code division-by-zero} from any other arithmetic
	 * failure, in the host exception's message ({@code "/ by zero"},
	 * {@code "BigInteger divide by zero"}, rontolisp's own {@code "Division by zero"}).
	 * Matched case-SENSITIVELY, because the backends that classify at their landing pad
	 * emit the comparison as bytecode and a case fold there would cost a locale-aware
	 * call for a distinction no host message makes.
	 */
	public static final String DIVISION_BY_ZERO_MESSAGE_TOKEN = "zero";

	/** The class a read of an unbound variable is signaled as. */
	public static final String UNBOUND_VARIABLE_CLASS_NAME = "UNBOUND-VARIABLE";

	/** The class a call to an undefined function is signaled as. */
	public static final String UNDEFINED_FUNCTION_CLASS_NAME = "UNDEFINED-FUNCTION";

	/**
	 * The class a runtime package failure is signaled as: {@code make-package} over an
	 * existing name, {@code delete-package} / {@code rename-package} over an unknown or
	 * read/compile-time package, {@code package-nicknames} over an unknown designator.
	 * Carries the offending designator in its {@code package} slot (read by
	 * {@code package-error-package}) and the reason in {@code format-control}, like the
	 * other built-in-signaled classes.
	 */
	public static final String PACKAGE_ERROR_CLASS_NAME = "PACKAGE-ERROR";

	/**
	 * The message a call to an undefined function reports, as a prefix around the
	 * function name (the suffix is {@link #UNDEFINED_FUNCTION_MESSAGE_SUFFIX}). Every
	 * backend spells it identically -- the interpreter's late binding, the compile paths'
	 * call-time stub -- and that is what lets a backend which only sees the MESSAGE of a
	 * failure (the JVM landing pad, whose bytecode-emitted throws carry no condition)
	 * recover the class from the text it generated itself.
	 */
	public static final String UNDEFINED_FUNCTION_MESSAGE_PREFIX = "The function ";

	/** The suffix of {@link #UNDEFINED_FUNCTION_MESSAGE_PREFIX}'s message. */
	public static final String UNDEFINED_FUNCTION_MESSAGE_SUFFIX = " is undefined";

	/**
	 * The prefix of the internal function-namespace name a {@code (setf place)} writer
	 * function is stored under: {@code (defun (setf name) ...)} installs a plain defun
	 * named {@code %setf-name}.
	 */
	public static final String SETF_FUNCTION_PREFIX = "%setf-";

	/** What opens a {@code (setf place)} function name as a report spells it. */
	public static final String SETF_FUNCTION_NAME_OPEN = "(SETF ";

	/**
	 * A function name as a report spells it: the {@code (setf place)} the program wrote
	 * for a writer function stored under its {@link #SETF_FUNCTION_PREFIX} name, any
	 * other name as it is. An undefined-function's message and warning use it, so the
	 * internal name never reaches the user.
	 * @param name the function-namespace name
	 * @return the name to report
	 */
	public static String functionNameForReport(String name) {
		String place = setfPlaceOfFunctionName(name);
		return place != null ? SETF_FUNCTION_NAME_OPEN + place + ")" : name;
	}

	/**
	 * The place of a function-namespace name that stores a {@code (setf place)} writer
	 * function, or {@code null} for any other name.
	 * @param name the function-namespace name
	 * @return the place's spelling, or null
	 */
	public static @Nullable String setfPlaceOfFunctionName(String name) {
		return name.startsWith(SETF_FUNCTION_PREFIX) ? name.substring(SETF_FUNCTION_PREFIX.length()) : null;
	}

	/**
	 * The place of a {@code (setf place)} function name as {@link #functionNameForReport}
	 * spells it, or {@code null} for a name that is not one: what a backend that only
	 * sees the message of an undefined-function reads the condition's {@code name} back
	 * from.
	 * @param reported the name as reported
	 * @return the place's spelling, or null
	 */
	public static @Nullable String setfPlaceOfReportedName(String reported) {
		return reported.startsWith(SETF_FUNCTION_NAME_OPEN) && reported.endsWith(")")
				? reported.substring(SETF_FUNCTION_NAME_OPEN.length(), reported.length() - 1) : null;
	}

	/**
	 * The message a raw CAST failure reports. Both compiled and interpreted, the host's
	 * own text for one names Java classes ({@code "class java.lang.Long cannot be cast to
	 * class [Ljava.lang.Object;"}), which is not an answer rontolisp should print for a
	 * Lisp program; the built-ins that detect the type themselves report their own,
	 * better text ({@code "car expects a cons cell, got: 1"}) and never reach this.
	 */
	public static final String TYPE_ERROR_MESSAGE = "the value is not of the expected type";

	/**
	 * The message an out-of-range index reports on a backend that only has the host's
	 * text for it. The JVM's ({@code "Index 10 out of bounds for length 3"}) counts the
	 * layout cell an instance keeps in slot 0, so it names a length the Lisp program does
	 * not have.
	 */
	public static final String INDEX_OUT_OF_BOUNDS_MESSAGE = "index out of bounds";

	/**
	 * What {@code floor}/{@code ceiling}/{@code round}/{@code truncate} (and so their
	 * float twins) signal for a NaN or an infinity, which has no integer to round to --
	 * one text on every backend, constant so a WASM build carries no irritant printer for
	 * it.
	 */
	public static final String NON_FINITE_ROUNDING_MESSAGE = "rounding a non-finite float to an integer is undefined";

	/**
	 * The prefix of the message applying a value that is not a function designator
	 * reports -- {@code (funcall 3 1)}, a Scheme {@code (h 1)} over a non-procedure --
	 * signaled as a {@code type-error}; the printed value follows. A SYMBOL designator
	 * naming no function (NIL included) is an {@code undefined-function} instead. Every
	 * backend detects it where it already dispatches on the callee's representation, and
	 * the JVM landing pad recovers the class from this prefix (the arithmetic precedent).
	 */
	public static final String NOT_A_FUNCTION_MESSAGE_PREFIX = "Not a function: ";

	/**
	 * The {@code simple-error} an {@code apply} whose last argument is no proper list
	 * signals -- {@code (apply f 1 2)}, {@code (apply f '(1 . 2))} -- before the callee
	 * is called. The interpreter's spread, the {@code #'apply} wrapper and the compiled
	 * backends' argument-list walk all say it.
	 */
	public static final String APPLY_IMPROPER_LIST_MESSAGE = "APPLY: last argument must be a list";

	/**
	 * What an arity message calls a callee that is not a built-in operator: a program's
	 * own function, an anonymous lambda, a destructured list.
	 */
	public static final String ARITY_ANONYMOUS_OPERATOR = "Function";

	/** What an arity message puts between the operator and the expectation. */
	public static final String ARITY_VERB = " expects ";

	/**
	 * The prefix of the message a call with the wrong number of arguments reports when
	 * the callee is not a built-in operator ({@link #arityMessage}).
	 */
	public static final String ARITY_MESSAGE_PREFIX = ARITY_ANONYMOUS_OPERATOR + ARITY_VERB;

	/**
	 * The message a call with the wrong number of arguments reports, the one spelling
	 * every backend uses, for a callee that is not a built-in operator.
	 * @param required the callee's required parameter count
	 * @param variadic whether the callee takes a {@code &rest} tail
	 * @param got the number of arguments the call passed
	 * @return the message
	 * @see #arityMessage(String, int, boolean, int)
	 */
	public static String arityMessage(int required, boolean variadic, int got) {
		return arityMessage(null, required, variadic, got);
	}

	/**
	 * The message a call with the wrong number of arguments reports, the one spelling
	 * every backend uses: {@code CONS expects 2 arguments, got 1}. A built-in operator
	 * names itself; any other callee is {@link #ARITY_ANONYMOUS_OPERATOR}. A variadic
	 * callee can only be called with too FEW arguments, so its count is reported as a
	 * lower bound.
	 * @param operator the built-in operator's name, or {@code null} for any other callee
	 * @param required the callee's required parameter count
	 * @param variadic whether the callee takes a {@code &rest} tail
	 * @param got the number of arguments the call passed
	 * @return the message
	 */
	public static String arityMessage(@Nullable String operator, int required, boolean variadic, int got) {
		return (operator == null ? ARITY_ANONYMOUS_OPERATOR : operator) + ARITY_VERB
				+ arityExpectation(required, variadic) + ARITY_MESSAGE_INFIX + got;
	}

	/**
	 * The part of {@link #arityMessage} that depends on the CALLEE alone. A compiled
	 * backend bakes this half as a constant beside the callee it describes and joins the
	 * two halves at the throw site, where the count it was called with is what varies.
	 * @param required the callee's required parameter count
	 * @param variadic whether the callee takes a {@code &rest} tail
	 * @return the expectation, e.g. {@code "1 argument"} or
	 * {@code "at least 2 arguments"}
	 */
	public static String arityExpectation(int required, boolean variadic) {
		return (variadic ? ARITY_AT_LEAST : "") + required + ARITY_ARGUMENT + (required == 1 ? "" : ARITY_PLURAL);
	}

	/**
	 * The message of a call past an {@code &optional} tail ({@code LambdaLists}): the
	 * {@link #arityMessage} shape with the count as an upper bound.
	 * @param max the callee's required plus optional parameter count
	 * @param got the number of arguments the call passed
	 * @return the message
	 */
	public static String aritySurplusMessage(int max, int got) {
		return aritySurplusMessage(null, max, got);
	}

	/**
	 * {@link #aritySurplusMessage(int, int)} naming the operator, as
	 * {@link #arityMessage} does: a built-in's function value reports its surplus under
	 * the built-in's name.
	 * @param operator the operator's name, or {@code null} for
	 * {@link #ARITY_ANONYMOUS_OPERATOR}
	 * @param max the callee's required plus optional parameter count
	 * @param got the number of arguments the call passed
	 * @return the message
	 */
	public static String aritySurplusMessage(@Nullable String operator, int max, int got) {
		return (operator == null ? ARITY_ANONYMOUS_OPERATOR : operator) + ARITY_VERB + ARITY_AT_MOST
				+ arityExpectation(max, false) + ARITY_MESSAGE_INFIX + got;
	}

	/** What {@link #arityMessage} puts between the expectation and the actual count. */
	public static final String ARITY_MESSAGE_INFIX = ", got ";

	/**
	 * How the {@code &optional} too-many-arguments check marks its bound
	 * ({@code LambdaLists}).
	 */
	public static final String ARITY_AT_MOST = "at most ";

	/** How {@link #arityExpectation} marks a callee with a {@code &rest} tail. */
	public static final String ARITY_AT_LEAST = "at least ";

	/** The noun {@link #arityExpectation} counts, and its plural suffix. */
	public static final String ARITY_ARGUMENT = " argument";

	/** @see #ARITY_ARGUMENT */
	public static final String ARITY_PLURAL = "s";

	/** The prefix of the message a read of an unbound variable reports. */
	public static final String UNBOUND_VARIABLE_MESSAGE_PREFIX = "The variable ";

	/** The suffix of {@link #UNBOUND_VARIABLE_MESSAGE_PREFIX}'s message. */
	public static final String UNBOUND_VARIABLE_MESSAGE_SUFFIX = " is unbound";

	/**
	 * One entry of {@link #CONDITION_SEEDS}: the class name, its single parent (null at
	 * the root) and the slots it adds on top of the parent's.
	 *
	 * @param name the class name
	 * @param parent the superclass name, or null for {@code condition}
	 * @param slots the slot names this class adds
	 */
	private record ConditionSeed(String name, @Nullable String parent, List<String> slots) {
	}

	private static ConditionSeed seed(String name, @Nullable String parent, String... slots) {
		return new ConditionSeed(name, parent, List.of(slots));
	}

	/**
	 * The built-in condition hierarchy, as ONE list: the constructor seeds it and
	 * {@link #CONDITION_CLASS_NAMES} -- the names {@code PackageRegistry} owns as
	 * {@code cl} symbols -- is derived from it, so a class added here is a {@code cl}
	 * symbol by construction rather than by a second list somebody has to remember.
	 *
	 * <p>
	 * Eight classes carry {@code format-control}/{@code format-arguments} beyond CLHS's
	 * slot lists ({@code type-error}, {@code arithmetic-error}, {@code program-error},
	 * {@code package-error}, {@code parse-error} -- so {@code reader-error} --,
	 * {@code file-error} and the two {@code cell-error} leaves): those are the classes a
	 * BUILT-IN error is synthesized as, and the two slots are how the synthesized
	 * instance carries the message it reports -- the same {@code simple-condition} report
	 * path every other message- bearing condition uses, rather than a second message
	 * channel. {@code type-error} gaining them is what leaves {@code simple-type-error}
	 * with the identical layout (it adds nothing now), so the {@code %obj-ref} indexes of
	 * both are unchanged.
	 */
	private static final List<ConditionSeed> CONDITION_SEEDS = List.of(seed("CONDITION", null),
			seed("SERIOUS-CONDITION", "CONDITION"), seed("ERROR", "SERIOUS-CONDITION"),
			seed("SIMPLE-ERROR", "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed("SIMPLE-CONDITION", "CONDITION", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"), seed("WARNING", "CONDITION"),
			seed("SIMPLE-WARNING", "WARNING", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"), seed("STYLE-WARNING", "WARNING"),
			// parse-error carries the message-carrying pair because a built-in signals
			// it (parse-integer); reader-error inherits the pair and adds its stream.
			seed(PARSE_ERROR_CLASS_NAME, "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed(TYPE_ERROR_CLASS_NAME, "ERROR", "DATUM", "EXPECTED-TYPE", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			// simple-type-error carries BOTH the type-error slots and the
			// simple-condition report slots -- CL's multiple inheritance flattened onto
			// the type-error branch, which is the branch a handler-case clause tests
			// (alexandria's sequence bounds checks signal it). Since type-error itself
			// now carries the report pair, it inherits the whole layout.
			seed("SIMPLE-TYPE-ERROR", TYPE_ERROR_CLASS_NAME),
			// stream-error carries the offending stream (CLHS 9.1.2): end-of-file and
			// reader-error inherit it, and stream-error-stream reads it back.
			seed("STREAM-ERROR", "ERROR", "STREAM"), seed(END_OF_FILE_CLASS_NAME, "STREAM-ERROR"),
			seed(READER_ERROR_CLASS_NAME, PARSE_ERROR_CLASS_NAME, "STREAM"),
			// file-error carries the pathname the failed operation was given
			// (file-error-pathname) plus the message-carrying pair: a built-in signals it
			// (open, delete-file, rename-file, truename), so it reports like the other
			// built-in-signaled classes.
			seed(FILE_ERROR_CLASS_NAME, "ERROR", "PATHNAME", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed(ARITHMETIC_ERROR_CLASS_NAME, "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS", "OPERATION", "OPERANDS"),
			seed(DIVISION_BY_ZERO_CLASS_NAME, ARITHMETIC_ERROR_CLASS_NAME),
			seed("FLOATING-POINT-INEXACT", ARITHMETIC_ERROR_CLASS_NAME),
			seed("FLOATING-POINT-INVALID-OPERATION", ARITHMETIC_ERROR_CLASS_NAME),
			seed("FLOATING-POINT-OVERFLOW", ARITHMETIC_ERROR_CLASS_NAME),
			seed("FLOATING-POINT-UNDERFLOW", ARITHMETIC_ERROR_CLASS_NAME),
			seed("STORAGE-CONDITION", "SERIOUS-CONDITION"), seed("CONTROL-ERROR", "ERROR"),
			seed(PROGRAM_ERROR_CLASS_NAME, "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed(PACKAGE_ERROR_CLASS_NAME, "ERROR", "PACKAGE", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed("CELL-ERROR", "ERROR", "NAME"),
			seed(UNBOUND_VARIABLE_CLASS_NAME, "CELL-ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			seed(UNDEFINED_FUNCTION_CLASS_NAME, "CELL-ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
			// The condition a read of an unbound slot signals (CLHS 7.7.2): name = the
			// slot, instance = the object it was read from.
			seed("UNBOUND-SLOT", "CELL-ERROR", "INSTANCE"),
			// object = what a print-object method or a program refuses to print readably.
			seed("PRINT-NOT-READABLE", "ERROR", "OBJECT"));

	/**
	 * The names of the built-in condition classes the constructor seeds, in seeding
	 * order. {@code PackageRegistry} owns exactly these as {@code cl} symbols: without
	 * that, {@code 'type-error} inside a {@code (:use #:cl)} package reads, prints and
	 * compares as {@code MY-PKG::TYPE-ERROR}, two packages naming the same condition hold
	 * two different symbols, and a RUNTIME type specifier carrying the qualified spelling
	 * matches no registered class.
	 */
	public static final List<String> CONDITION_CLASS_NAMES = CONDITION_SEEDS.stream().map(ConditionSeed::name).toList();

	/**
	 * Seeds the MOP base classes the static metaobject layer rests on: {@code find-class}
	 * answers with an INSTANCE of {@code standard-class} (or of a user metaclass derived
	 * from it), and a user metaclass protocol (postmodern's {@code dao-class}) subclasses
	 * the two slot-definition classes. Idempotent, and deliberately NOT run from the
	 * constructor: a registered class joins every {@code typep} tag table, runtime
	 * slot-name dispatch and {@code %class-slot-defs} answer a compilation emits, so
	 * unconditional seeding grows every program that uses runtime dispatch (the ci-spec
	 * corpus sits close enough to the JVM's 64 KB method ceiling that three extra classes
	 * pushed it over). Triggered instead where the MOP surface actually appears:
	 * {@link #classMetaobject} (the interpreter's {@code find-class}), the closer-mop
	 * shim load on both loaders, and (later) a {@code defclass} naming a
	 * {@code :metaclass}.
	 *
	 * <p>
	 * The slot ORDER here is a contract: readers over these metaobjects (the closer-mop
	 * library, {@link #classMetaobject}) bake {@code %obj-ref} indexes, exactly like the
	 * seeded condition hierarchy's report lambda does. Append new slots, never reorder.
	 */
	public void ensureMopClassesSeeded() {
		if (this.mopClassesSeeded) {
			return;
		}
		this.mopClassesSeeded = true;
		// CLASS is never instantiated and holds the slots of every class metaobject, so
		// (typep x 'class) -- the metaobject predicate mito's
		// contains-class-or-subclasses rides -- is an ordinary registry ancestor test on
		// every backend, and every metaclass, standard-class and built-in-class alike,
		// has the %obj-ref index contract below.
		seedClass(CLASS_NAME, null, "NAME", "DIRECT-SUPERCLASSES", "DIRECT-SLOTS", "EFFECTIVE-SLOTS", "FINALIZED-P");
		seedClass(STANDARD_CLASS_NAME, CLASS_NAME);
		seedClass(BUILT_IN_CLASS_NAME, CLASS_NAME);
		// The slot-definition hierarchy (AMOP 5.4): the root holds every slot, so both
		// standard classes keep the same layout.
		seedClass(SLOT_DEFINITION_NAME, null, "NAME", "INITARGS", "INITFORM", "TYPE", "READERS", "INITFUNCTION",
				"WRITERS", "ALLOCATION", "%LOCATION");
		seedClass(DIRECT_SLOT_DEFINITION_NAME, SLOT_DEFINITION_NAME);
		seedClass(EFFECTIVE_SLOT_DEFINITION_NAME, SLOT_DEFINITION_NAME);
		seedClass(STANDARD_SLOT_DEFINITION_NAME, SLOT_DEFINITION_NAME);
		seedStandardSlotDefinitionClass(STANDARD_DIRECT_SLOT_DEFINITION_NAME, DIRECT_SLOT_DEFINITION_NAME);
		seedStandardSlotDefinitionClass(STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME, EFFECTIVE_SLOT_DEFINITION_NAME);
	}

	/**
	 * Seeds a slot-less class under {@link #STANDARD_SLOT_DEFINITION_NAME} and the given
	 * parent, in that precedence (AMOP 5.4). Both descend from the one root holding every
	 * slot, so the layout is the root's.
	 */
	private void seedStandardSlotDefinitionClass(String name, String parent) {
		this.seededClassNames.add(name);
		ClassInfo parentInfo = java.util.Objects.requireNonNull(this.classes.get(parent));
		java.util.Set<String> ancestors = new java.util.LinkedHashSet<>(parentInfo.ancestors());
		ancestors.add(STANDARD_SLOT_DEFINITION_NAME);
		ancestors.add(name);
		registerClass(new ClassInfo(name, List.of(STANDARD_SLOT_DEFINITION_NAME, parent),
				List.of(name, STANDARD_SLOT_DEFINITION_NAME, parent, SLOT_DEFINITION_NAME), List.of(),
				parentInfo.slots(), java.util.Set.copyOf(ancestors)));
	}

	/**
	 * Seeds the MOP base classes when the given type-specifier name is one of them --
	 * {@code (typep x 'class)}, {@code (subtypep m 'standard-class)} and friends. A type
	 * TEST is as good a MOP-surface trigger as the closer-mop load or a
	 * {@code :metaclass}: the interpreter expands such a test against the live registry,
	 * and without the seeding the test would compile to a constant nil BEFORE the
	 * {@code find-class} call that produces the very metaobject it is about had a chance
	 * to seed. The name may be package-qualified ({@code closer-mop:standard-class}); the
	 * member spelling decides.
	 * @param typeName the type-specifier name as spelled
	 */
	public void ensureMopClassesSeededFor(String typeName) {
		if (this.mopClassesSeeded && this.funcallableClassesSeeded
				&& this.seededMethodMetaobjectFamilies.size() == METHOD_METAOBJECT_FAMILIES.size()) {
			return;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(typeName);
		String member = (qualified == null ? typeName : qualified.member()).toUpperCase(java.util.Locale.ROOT);
		if (FUNCALLABLE_CLASS_NAMES.contains(member)) {
			ensureFuncallableClassesSeeded();
		}
		else if (MOP_BASE_CLASS_NAMES.contains(member)) {
			ensureMopClassesSeeded();
		}
		else {
			ensureMethodMetaobjectClassesSeeded(member);
		}
	}

	/**
	 * Seeds the method-metaobject family the member name belongs to (see
	 * {@link #METHOD_METAOBJECT_FAMILIES}): the whole family, parents first, each class
	 * slot-less. Idempotent, and as lazy as the funcallable pair: run where a program
	 * names a member -- a specializer, a type, a superclass, a {@code find-class} name
	 * ({@link #ensureMopClassesSeededFor}) or, on the compile paths, anywhere in the
	 * program before the walk -- so no other program's tables change. A class a program
	 * already registered under one of the names is left alone.
	 * @param member the upcased, package-stripped class name
	 * @return whether the name belongs to a family
	 */
	public boolean ensureMethodMetaobjectClassesSeeded(String member) {
		List<MopSeed> family = METHOD_METAOBJECT_FAMILY_BY_MEMBER.get(member);
		if (family == null) {
			return false;
		}
		if (this.seededMethodMetaobjectFamilies.add(family.get(0).name())) {
			for (MopSeed seed : family) {
				if (FUNCALLABLE_STANDARD_OBJECT_NAME.equals(seed.parent())) {
					// generic-function is a funcallable-standard-object (AMOP 5.2).
					ensureFuncallableClassesSeeded();
				}
				if (CLASS_NAME.equals(seed.parent())) {
					ensureMopClassesSeeded();
				}
				if (!this.classes.containsKey(normalize(seed.name()))) {
					seedClass(seed.name(), seed.parent(), seed.slots().toArray(String[]::new));
				}
			}
		}
		return true;
	}

	/**
	 * Whether the name (a package-stripped member spelling) is a class of a
	 * method-metaobject family ({@link #METHOD_METAOBJECT_FAMILIES}).
	 * @param member the upcased member name
	 * @return true for {@code standard-method}, {@code generic-function}, ...
	 */
	public static boolean isMethodMetaobjectClassName(String member) {
		return METHOD_METAOBJECT_FAMILY_BY_MEMBER.containsKey(member);
	}

	/**
	 * Seeds the two funcallable MOP classes over the MOP base classes:
	 * {@code funcallable-standard-object}, whose descendants are FUNCALLABLE instances
	 * (their layout reserves {@link LispLayout#FUNCALLABLE_CELL}, see
	 * {@link #registerClass}), and the metaclass {@code funcallable-standard-class} under
	 * {@code standard-class}, which makes a {@code defclass} default its superclass list
	 * to the former. Idempotent, and lazier still than {@link #ensureMopClassesSeeded}:
	 * run only where a program names one of the two ({@link #ensureMopClassesSeededFor},
	 * a {@code defclass} naming either), so no other MOP program's tables change.
	 */
	public void ensureFuncallableClassesSeeded() {
		if (this.funcallableClassesSeeded) {
			return;
		}
		ensureMopClassesSeeded();
		this.funcallableClassesSeeded = true;
		seedClass(FUNCALLABLE_STANDARD_OBJECT_NAME, null);
		seedClass(FUNCALLABLE_STANDARD_CLASS_NAME, STANDARD_CLASS_NAME);
	}

	/**
	 * Whether {@link #ensureFuncallableClassesSeeded()} has run.
	 * @return true once the funcallable classes are registered
	 */
	public boolean funcallableClassesSeeded() {
		return this.funcallableClassesSeeded;
	}

	/**
	 * The instance tags of every registered funcallable layout, in registration order:
	 * what a compiled call through a value and {@code functionp} test an instance
	 * against. Empty for every program defining no funcallable class.
	 * @return the tags
	 */
	public List<String> funcallableTags() {
		if (!this.funcallableClassesSeeded) {
			return List.of();
		}
		List<String> tags = new java.util.ArrayList<>();
		boolean instantiable = this.gfProtocol;
		for (LispLayout layout : this.layoutsByTag.values()) {
			if (layout.funcallable()) {
				tags.add(layout.tag());
				String name = LispLayout.printNameOfTag(layout.tag());
				instantiable = instantiable || name == null || !this.seededClassNames.contains(name);
			}
		}
		// Only SEEDED funcallable classes (the funcallable-standard-object a program
		// names,
		// the generic-function classes it specializes on) and no generic-function
		// protocol: nothing can make an instance of one, so no call through a value needs
		// the arm.
		return instantiable ? tags : List.of();
	}

	/**
	 * Whether the named class was SEEDED by this registry (the built-in condition
	 * hierarchy, the MOP base classes) rather than registered by a program
	 * {@code defclass}/{@code define-condition}. Seeded classes have no generated keyword
	 * constructor -- the only exception, the MOP base trio, gets its constructors emitted
	 * separately by the metaclass-protocol runtime.
	 * @param name the canonical class name
	 * @return {@code true} for a seeded class
	 */
	public boolean isSeededClass(String name) {
		return this.seededClassNames.contains(name);
	}

	/**
	 * Seeds the condition class the shared {@code %no-applicable-method} defun signals:
	 * {@code no-applicable-method-error} under {@code error}, carrying the message
	 * fragment ({@code "GENERIC-NAME on "}) and the failing argument's class designator
	 * as VALUES, with a {@code :report} that renders them only when something reports the
	 * condition. Idempotent, and deliberately NOT run from the constructor (the
	 * {@link #ensureMopClassesSeeded} lesson: a registered class joins every typep tag
	 * table, runtime slot-name dispatch and {@code %class-slot-defs} answer) -- triggered
	 * exactly where the defun is installed: the {@code expandTopLevelDefinitions}
	 * injection and the interpreter's {@code defineDispatcher}.
	 */
	public void ensureNoApplicableErrorSeeded() {
		if (this.classes.containsKey(NO_APPLICABLE_ERROR_CLASS_NAME)) {
			return;
		}
		// The slot names are %-fenced: seedClass registers every slot name's position
		// globally (the ambiguous-slot-name machinery), so a plain name here could turn
		// a same-named user slot ambiguous and grow that program's runtime dispatch.
		// Nothing but the generated defun ever spells these.
		seedClass(NO_APPLICABLE_ERROR_CLASS_NAME, "ERROR", NO_APPLICABLE_ERROR_OPERATION_SLOT,
				NO_APPLICABLE_ERROR_DATUM_CLASS_SLOT);
		registerConditionReport(NO_APPLICABLE_ERROR_CLASS_NAME, noApplicableErrorReport());
	}

	/**
	 * Seeds {@code java:java-exception}, the condition a {@code java:} member that throws
	 * signals: a {@code simple-error} reporting the member and the throwable, carrying
	 * the throwable itself ({@code java:java-exception-cause}). Idempotent, and
	 * deliberately NOT run from the constructor (the {@link #ensureMopClassesSeeded}
	 * lesson) -- a program that can make no host call never registers it: the interpreter
	 * seeds it with the {@code java:} functions, the compile path for a program naming a
	 * {@code java:} operator or the class.
	 */
	public void ensureJavaExceptionSeeded() {
		if (this.classes.containsKey(JAVA_EXCEPTION_CLASS_NAME)) {
			return;
		}
		// %-fenced like the no-applicable-method slots: a plain CAUSE would turn a
		// user's slot of that name ambiguous in every java: program.
		seedClass(JAVA_EXCEPTION_CLASS_NAME, "SIMPLE-ERROR", JAVA_EXCEPTION_CAUSE_SLOT);
	}

	/**
	 * Seeds {@code rontolisp:interactive-interrupt}, the condition a program's own
	 * handler of it asks SIGINT to be signalled as: a {@code serious-condition} and no
	 * {@code error}, as SBCL's {@code sb-sys:interactive-interrupt}. Idempotent, and like
	 * {@link #ensureJavaExceptionSeeded} not run from the constructor: the interpreter
	 * seeds it for every program, the compile path for a program naming it.
	 */
	public void ensureInteractiveInterruptSeeded() {
		if (!this.classes.containsKey(normalize(LispNames.INTERACTIVE_INTERRUPT_QUALIFIED))) {
			seedClass(LispNames.INTERACTIVE_INTERRUPT_QUALIFIED, "SERIOUS-CONDITION");
		}
	}

	/**
	 * The condition class {@link #ensureJavaExceptionSeeded} registers, as the registry
	 * spells {@code java:java-exception}.
	 */
	public static final String JAVA_EXCEPTION_CLASS_NAME = "JAVA::JAVA-EXCEPTION";

	/**
	 * The slot of {@link #JAVA_EXCEPTION_CLASS_NAME} holding the throwable, after the
	 * {@code simple-error} pair: index {@link #JAVA_EXCEPTION_CAUSE_INDEX}.
	 */
	public static final String JAVA_EXCEPTION_CAUSE_SLOT = LispNames.JAVA_EXCEPTION_CAUSE_SLOT;

	/** The index of {@link #JAVA_EXCEPTION_CAUSE_SLOT} in the class's layout. */
	public static final int JAVA_EXCEPTION_CAUSE_INDEX = 2;

	/**
	 * The condition class {@link #ensureNoApplicableErrorSeeded} registers. Not a CL
	 * standard name (CLHS leaves the type of a no-applicable-method error
	 * implementation-defined below {@code error}), so an {@code (error () ...)} clause is
	 * the portable way to catch it.
	 */
	public static final String NO_APPLICABLE_ERROR_CLASS_NAME = "NO-APPLICABLE-METHOD-ERROR";

	/** Slot 0 of the seeded class: the {@code "GENERIC-NAME on "} message fragment. */
	public static final String NO_APPLICABLE_ERROR_OPERATION_SLOT = "%NAM-OPERATION";

	/** Slot 1 of the seeded class: the failing argument's class designator symbol. */
	public static final String NO_APPLICABLE_ERROR_DATUM_CLASS_SLOT = "%NAM-DATUM-CLASS";

	/**
	 * The {@code :report} of the seeded {@code no-applicable-method-error}:
	 * {@code (lambda (c s) (format s "No applicable method: ~a~a" (%obj-ref c 0) NAME))},
	 * NAME being slot 1's class designator with an instance's tag taken off, so the
	 * message names the class the program wrote. The strip is here and not in the defun
	 * so a signal nothing reports pays for no string operation. Baked {@code %obj-ref}
	 * indexes for the same reason as {@link #unboundSlotReport}.
	 */
	private static LispVal noApplicableErrorReport() {
		LispSymbol condition = new LispSymbol("__c");
		LispSymbol stream = new LispSymbol("__s");
		LispSymbol text = new LispSymbol("__t");
		LispSymbol size = new LispSymbol("__n");
		LispVal bindings = list(
				list(text,
						list(new LispSymbol(LispNames.PRINC_PIECE_INTERNAL),
								list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(1)))),
				list(size, list(new LispSymbol(LispNames.LENGTH), text)));
		LispVal format = list(new LispSymbol("FORMAT"), stream, new LispString("No applicable method: ~a~a"),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(0)), tagStrippedText(text, size));
		return list(new LispSymbol(LispNames.LAMBDA), list(condition, stream),
				list(new LispSymbol(LispNames.LET_STAR), bindings, format));
	}

	/**
	 * The form answering a printed class designator without an instance's
	 * {@code %struct-}/{@code %class-} tag: a type's name as the program wrote it. A
	 * package-qualified type needs no strip, the tag being on its package half.
	 * @param text the variable holding the designator's text
	 * @param size the variable holding its length
	 * @return the {@code cond} form
	 */
	public static LispVal tagStrippedText(LispSymbol text, LispSymbol size) {
		LispVal clauses = list(list(LispTrue.INSTANCE, text));
		for (String tagPrefix : List.of(LispLayout.CLASS_TAG_PREFIX, LispLayout.STRUCT_TAG_PREFIX)) {
			LispInteger length = new LispInteger(tagPrefix.length());
			LispVal test = list(new LispSymbol(LispNames.AND), list(new LispSymbol(LispNames.GT), size, length),
					list(new LispSymbol(LispNames.STRING_EQ),
							list(new LispSymbol(LispNames.SUBSEQ), text, new LispInteger(0), length),
							new LispString(tagPrefix)));
			clauses = new LispCons(list(test, list(new LispSymbol(LispNames.SUBSEQ), text, length)), clauses);
		}
		return new LispCons(new LispSymbol(LispNames.COND), clauses);
	}

	/**
	 * What a condition of a class whose built-in signals carry their message as a text
	 * control reports when a program signals it with no control, as
	 * {@code (error 'undefined-function :name 'f)}: the text the built-in signal writes,
	 * from the slots the program supplied. A class with no control and no entry here, or
	 * in its precedence list, reports nothing.
	 * @param className the class name
	 * @return the report (a literal string or a lambda expression), or null
	 */
	public static @Nullable LispVal controlFreeReport(String className) {
		return switch (className) {
			case UNDEFINED_FUNCTION_CLASS_NAME -> slotsReport("The function ~S is undefined", 1);
			case UNBOUND_VARIABLE_CLASS_NAME -> slotsReport("The variable ~S is unbound", 1);
			case TYPE_ERROR_CLASS_NAME -> slotsReport("The value ~S is not of type ~S", 2);
			case DIVISION_BY_ZERO_CLASS_NAME -> new LispString(DIVISION_BY_ZERO_MESSAGE);
			default -> null;
		};
	}

	/**
	 * {@code (lambda (c s) (format s control (%obj-ref c 0) ...))}, over leading slots.
	 */
	private static LispVal slotsReport(String control, int slotCount) {
		LispSymbol condition = new LispSymbol("__c");
		LispSymbol stream = new LispSymbol("__s");
		List<LispVal> format = new java.util.ArrayList<>(
				List.of(new LispSymbol("FORMAT"), stream, new LispString(control)));
		for (int i = 0; i < slotCount; i++) {
			format.add(list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(i)));
		}
		return list(new LispSymbol(LispNames.LAMBDA), list(condition, stream), list(format.toArray(new LispVal[0])));
	}

	/**
	 * The {@code :report} of the seeded {@code unbound-slot}, as the AST a
	 * {@code define-condition} would have registered:
	 * {@code (lambda (c s) (format s "The slot ~S is unbound in ~S" (%obj-ref c 0) (%obj-ref c 1)))}.
	 * The slot indexes are baked rather than read through {@code slot-value} because they
	 * are fixed by the seeding above ({@code name} from {@code cell-error}, then
	 * {@code instance}) and a {@code slot-value} would drag the whole ambiguous-name
	 * dispatch into every program that mentions the condition.
	 */
	private static LispVal unboundSlotReport() {
		LispSymbol condition = new LispSymbol("__c");
		LispSymbol stream = new LispSymbol("__s");
		LispVal format = list(new LispSymbol("FORMAT"), stream, new LispString("The slot ~S is unbound in ~S"),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(0)),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(1)));
		return list(new LispSymbol(LispNames.LAMBDA), list(condition, stream), format);
	}

	/**
	 * The {@code :report} of the seeded {@code print-not-readable}, built as
	 * {@link #unboundSlotReport} is: the text SBCL prints.
	 */
	private static LispVal printNotReadableReport() {
		LispSymbol condition = new LispSymbol("__c");
		LispSymbol stream = new LispSymbol("__s");
		LispVal format = list(new LispSymbol("FORMAT"), stream, new LispString("~S cannot be printed readably."),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(0)));
		return list(new LispSymbol(LispNames.LAMBDA), list(condition, stream), format);
	}

	/** Builds a proper list of the given elements. */
	private static LispVal list(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

	/**
	 * The type name of the internal slot-unbound marker. A slot holding an instance of it
	 * is UNBOUND: reading it signals {@code unbound-slot}, {@code slot-boundp} is nil,
	 * and {@code slot-makunbound} stores a fresh one. It is spelled with the {@code %}
	 * fences of an internal name so no source symbol collides with it.
	 */
	public static final String UNBOUND_CLASS_NAME = "%UNBOUND%";

	/**
	 * A fresh {@code end-of-file} condition instance, for the interpreter's read family
	 * -- which runs inside {@code Environment}, where no registry is in scope. The class
	 * is SEEDED, so its layout is the same single-{@code STREAM}-slot shape in every
	 * registry and can be built without one; {@code handler-case} dispatches on the
	 * instance TAG, not on layout identity, so the instance is indistinguishable from one
	 * {@code (error 'end-of-file)} would have constructed. The layout here must keep
	 * mirroring the seed (a unit test pins it).
	 * @return the condition instance, carrying nil as its stream
	 */
	public static LispVal newEndOfFileCondition() {
		return newEndOfFileCondition(LispNil.INSTANCE);
	}

	/**
	 * A fresh {@code end-of-file} condition instance carrying the offending stream --
	 * what {@code stream-error-stream} reads back, and what the ANSI suite's
	 * {@code signals-error} checks {@code streamp} of.
	 * @param stream the stream the read ran out on
	 * @return the condition instance
	 */
	public static LispVal newEndOfFileCondition(LispVal stream) {
		return new LispInstance(
				LispLayout.ofClass(END_OF_FILE_CLASS_NAME, List.of("STREAM"), List.of(LispNil.INSTANCE)),
				new LispVal[] { stream });
	}

	/**
	 * A fresh {@code reader-error} condition instance carrying the message and the
	 * offending stream, for the interpreter's runtime {@code read} family -- which runs
	 * inside {@code Environment}, where no registry is in scope. The class is SEEDED, so
	 * its layout is the same {@code [FORMAT-CONTROL, FORMAT-ARGUMENTS, STREAM]} shape in
	 * every registry and can be built without one (a unit test pins it); the message
	 * rides {@code format-control}, so the instance reports like the
	 * {@code simple-condition} family.
	 * @param message the reported message
	 * @param stream the stream (or string-input stream over the text) the bad datum came
	 * from
	 * @return the condition instance
	 */
	public static LispVal newReaderErrorCondition(LispVal message, LispVal stream) {
		return new LispInstance(
				LispLayout.ofClass(READER_ERROR_CLASS_NAME, List.of("FORMAT-CONTROL", "FORMAT-ARGUMENTS", "STREAM"),
						List.of(LispNil.INSTANCE, LispNil.INSTANCE, LispNil.INSTANCE)),
				new LispVal[] { textControl(message), LispNil.INSTANCE, stream });
	}

	/**
	 * The {@code format-control} whose rendering is {@code text} verbatim: every
	 * {@code ~} doubled. A condition carrying already-rendered text stores it this way,
	 * because its report renders the slot as a control ({@code %text-control}).
	 * @param text the rendered text
	 * @return the control
	 */
	public static String textControl(String text) {
		return text.replace("~", "~~");
	}

	/**
	 * {@link #textControl(String)} over a value: a string becomes its control (the same
	 * object when it holds no {@code ~}), anything else passes through.
	 * @param value the rendered text, or any other value
	 * @return the control, or the value
	 */
	public static LispVal textControl(LispVal value) {
		if (value instanceof LispString text) {
			String s = text.value();
			return s.indexOf('~') < 0 ? value : new LispString(textControl(s));
		}
		return value;
	}

	/**
	 * The inverse of {@link #textControl(String)}: the rendering of a control whose only
	 * directive is {@code ~~} ({@code %control-text}).
	 * @param control the control
	 * @return the text it renders
	 */
	public static String controlText(String control) {
		return control.replace("~~", "~");
	}

	/**
	 * {@link #controlText(String)} over a value: a string becomes its text (the same
	 * object when it holds no {@code ~}), anything else passes through.
	 * @param value the control, or any other value
	 * @return the text, or the value
	 */
	public static LispVal controlText(LispVal value) {
		if (value instanceof LispString control) {
			String s = control.value();
			return s.indexOf('~') < 0 ? value : new LispString(controlText(s));
		}
		return value;
	}

	/**
	 * A fresh {@code file-error} condition instance carrying the pathname and the
	 * message, for the interpreter's file operations. The class is SEEDED, so its layout
	 * is the same {@code [PATHNAME, FORMAT-CONTROL, FORMAT-ARGUMENTS]} shape in every
	 * registry and can be built without one (a unit test pins it); the message rides
	 * {@code format-control}, so the instance reports like the {@code simple-condition}
	 * family.
	 * @param pathname the designator the failed operation was given
	 * @param message the reported message
	 * @return the condition instance
	 */
	public static LispVal newFileErrorCondition(LispVal pathname, LispVal message) {
		return new LispInstance(
				LispLayout.ofClass(FILE_ERROR_CLASS_NAME, List.of("PATHNAME", "FORMAT-CONTROL", "FORMAT-ARGUMENTS"),
						List.of(LispNil.INSTANCE, LispNil.INSTANCE, LispNil.INSTANCE)),
				new LispVal[] { pathname, textControl(message), LispNil.INSTANCE });
	}

	/**
	 * A fresh instance of a SEEDED condition class reporting the given message: every
	 * slot nil except {@code format-control}, which is why the four classes a built-in
	 * error is synthesized as carry that slot (see {@link #CONDITION_SEEDS}). This is the
	 * one place the interpreter turns a raw failure into a condition, so the class a
	 * built-in named at its throw site and the message it reports cannot come apart.
	 * @param className the seeded condition class name
	 * @param message the reported message, or null for no report
	 * @return the condition instance, or null when the class is not registered here
	 */
	public @Nullable LispVal newReportingCondition(String className, @Nullable LispVal message) {
		return newReportingCondition(className, message, java.util.Map.of());
	}

	/**
	 * {@link #newReportingCondition(String, LispVal)} with named slots filled as well --
	 * a {@code type-error}'s {@code DATUM} and {@code EXPECTED-TYPE}. A name the layout
	 * does not carry is ignored.
	 * @param className the seeded condition class name
	 * @param message the reported message, or null for no report
	 * @param slotValues slot name to value
	 * @return the condition instance, or null when the class is not registered here
	 */
	public @Nullable LispVal newReportingCondition(String className, @Nullable LispVal message,
			java.util.Map<String, LispVal> slotValues) {
		LispLayout layout = findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + normalize(className));
		if (layout == null) {
			return null;
		}
		LispVal[] slots = new LispVal[Math.max(layout.capacity(), layout.slotNames().size())];
		java.util.Arrays.fill(slots, LispNil.INSTANCE);
		int control = layout.slotNames().indexOf("FORMAT-CONTROL");
		if (control >= 0 && message != null) {
			slots[control] = textControl(message);
		}
		slotValues.forEach((name, value) -> {
			int index = layout.slotNames().indexOf(name);
			if (index >= 0) {
				slots[index] = value;
			}
		});
		return new LispInstance(layout, slots);
	}

	/** The instance tag of the slot-unbound marker ({@link #UNBOUND_CLASS_NAME}). */
	public static final String UNBOUND_TAG = LispLayout.CLASS_TAG_PREFIX + UNBOUND_CLASS_NAME;

	/**
	 * The seeded metaclass every plain class metaobject is an instance of (see
	 * {@link #classMetaobject}). Slot-less: its layout is {@link #CLASS_NAME}'s.
	 */
	public static final String STANDARD_CLASS_NAME = "STANDARD-CLASS";

	/**
	 * The seeded metaclass of the built-in classes (see {@link #builtinClassMetaobject}),
	 * beside {@link #STANDARD_CLASS_NAME} under {@link #CLASS_NAME}: McCLIM's
	 * {@code (typep (find-class name nil) 'standard-class)} is how a program tells a CLOS
	 * class from a built-in one. Slot-less, and never instantiated by a program.
	 */
	public static final String BUILT_IN_CLASS_NAME = "BUILT-IN-CLASS";

	/**
	 * CL's root class: every class's last ancestor before {@code t}, never registered
	 * ({@link #FIND_CLASS_ONLY_CLASS_NAMES}) -- a {@code typep} of it is "any class
	 * instance", and a {@code defclass} writing it as a superclass adds nothing.
	 */
	public static final String STANDARD_OBJECT_NAME = "STANDARD-OBJECT";

	/**
	 * The seeded root of the metaclass hierarchy -- CL's {@code class}, the superclass of
	 * {@link #STANDARD_CLASS_NAME}, {@link #BUILT_IN_CLASS_NAME},
	 * {@link #FORWARD_REFERENCED_CLASS_NAME} and hence of every user metaclass. Never
	 * instantiated: it makes {@code (typep x 'class)} / {@code (subtypep m 'class)}
	 * answer for a class metaobject through the ordinary ancestor machinery, identically
	 * on all four backends, and holds the slots of every class metaobject. Slot order (an
	 * index contract, see {@link #ensureMopClassesSeeded}): name, direct-superclasses,
	 * direct-slots, effective-slots, finalized-p.
	 */
	public static final String CLASS_NAME = "CLASS";

	/**
	 * The seeded root of the slot-definition metaobjects; it declares every slot the two
	 * standard classes carry. Slot order: name, initargs, initform, type, readers,
	 * initfunction (appended 2026-08-03; nil except on driver-built definitions), writers
	 * (appended 2026-10-30; filled on direct slot definitions only), allocation (appended
	 * 2026-11-01), %location (appended 2026-11-05; the slot's index in an instance,
	 * filled on effective slot definitions only).
	 */
	public static final String SLOT_DEFINITION_NAME = "SLOT-DEFINITION";

	/** The slot-less class every direct slot definition is a {@code typep} of. */
	public static final String DIRECT_SLOT_DEFINITION_NAME = "DIRECT-SLOT-DEFINITION";

	/** The slot-less class every effective slot definition is a {@code typep} of. */
	public static final String EFFECTIVE_SLOT_DEFINITION_NAME = "EFFECTIVE-SLOT-DEFINITION";

	/** The slot-less class both standard slot definitions are a {@code typep} of. */
	public static final String STANDARD_SLOT_DEFINITION_NAME = "STANDARD-SLOT-DEFINITION";

	/**
	 * The seeded direct-slot-definition class a user metaclass protocol subclasses
	 * (postmodern's {@code direct-column-slot}), and what {@code class-direct-slots}
	 * answers. Layout: {@link #SLOT_DEFINITION_NAME}'s.
	 */
	public static final String STANDARD_DIRECT_SLOT_DEFINITION_NAME = "STANDARD-DIRECT-SLOT-DEFINITION";

	/**
	 * The seeded effective-slot-definition class {@link #classMetaobject} builds the
	 * {@code class-slots} entries from. Layout: {@link #SLOT_DEFINITION_NAME}'s.
	 */
	public static final String STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME = "STANDARD-EFFECTIVE-SLOT-DEFINITION";

	/** The {@code %obj-ref} index of a slot definition's {@code %location}. */
	private static final int SLOT_DEFINITION_LOCATION_INDEX = 8;

	/** The {@code :allocation :class} keyword, as a slot definition's allocation. */
	public static final String CLASS_ALLOCATION = ":CLASS";

	/** The {@code closer-mop} symbol naming {@link #FUNCALLABLE_STANDARD_OBJECT_NAME}. */
	public static final String FUNCALLABLE_STANDARD_OBJECT = "FUNCALLABLE-STANDARD-OBJECT";

	/** The {@code closer-mop} symbol naming {@link #FUNCALLABLE_STANDARD_CLASS_NAME}. */
	public static final String FUNCALLABLE_STANDARD_CLASS = "FUNCALLABLE-STANDARD-CLASS";

	/**
	 * The seeded root of the funcallable instances (AMOP's
	 * {@code funcallable-standard-object}): slot-less, and every class descending from it
	 * gets a funcallable layout ({@link LispLayout#funcallable()}). Registered under its
	 * {@code closer-mop} name, like {@link #JAVA_EXCEPTION_CLASS_NAME} under its
	 * package's, so a compiled {@code find-class} of the symbol a program spells matches
	 * one of the class's spellings.
	 */
	public static final String FUNCALLABLE_STANDARD_OBJECT_NAME = LispNames.CLOSER_MOP_PKG + "::"
			+ FUNCALLABLE_STANDARD_OBJECT;

	/**
	 * The seeded metaclass of funcallable classes, a slot-less child of
	 * {@link #STANDARD_CLASS_NAME}: a {@code defclass} naming it (or a metaclass
	 * descending from it) as its {@code :metaclass} gets
	 * {@link #FUNCALLABLE_STANDARD_OBJECT_NAME} among its superclasses.
	 */
	public static final String FUNCALLABLE_STANDARD_CLASS_NAME = LispNames.CLOSER_MOP_PKG + "::"
			+ FUNCALLABLE_STANDARD_CLASS;

	/**
	 * The member names of the classes {@link #ensureFuncallableClassesSeeded()}
	 * registers.
	 */
	private static final List<String> FUNCALLABLE_CLASS_NAMES = List.of(FUNCALLABLE_STANDARD_OBJECT,
			FUNCALLABLE_STANDARD_CLASS);

	/** The {@code closer-mop} symbol naming {@link #STANDARD_ACCESSOR_METHOD_NAME}. */
	public static final String STANDARD_ACCESSOR_METHOD = "STANDARD-ACCESSOR-METHOD";

	/** The {@code closer-mop} symbol naming {@link #STANDARD_READER_METHOD_NAME}. */
	public static final String STANDARD_READER_METHOD = "STANDARD-READER-METHOD";

	/** The {@code closer-mop} symbol naming {@link #STANDARD_WRITER_METHOD_NAME}. */
	public static final String STANDARD_WRITER_METHOD = "STANDARD-WRITER-METHOD";

	/** The {@code closer-mop} symbol naming {@link #EQL_SPECIALIZER_NAME}. */
	public static final String EQL_SPECIALIZER = "EQL-SPECIALIZER";

	/**
	 * AMOP's {@code standard-accessor-method}, registered under its {@code closer-mop}
	 * name in the EXTERNAL spelling the reader gives it, so {@code class-name} answers
	 * the symbol a program reads ({@link #FUNCALLABLE_STANDARD_OBJECT_NAME}'s internal
	 * spelling does not).
	 */
	public static final String STANDARD_ACCESSOR_METHOD_NAME = LispNames.CLOSER_MOP_PKG + ":"
			+ STANDARD_ACCESSOR_METHOD;

	/** AMOP's {@code standard-reader-method}, under its {@code closer-mop} name. */
	public static final String STANDARD_READER_METHOD_NAME = LispNames.CLOSER_MOP_PKG + ":" + STANDARD_READER_METHOD;

	/** AMOP's {@code standard-writer-method}, under its {@code closer-mop} name. */
	public static final String STANDARD_WRITER_METHOD_NAME = LispNames.CLOSER_MOP_PKG + ":" + STANDARD_WRITER_METHOD;

	/** AMOP's {@code eql-specializer}, under its {@code closer-mop} name. */
	public static final String EQL_SPECIALIZER_NAME = LispNames.CLOSER_MOP_PKG + ":" + EQL_SPECIALIZER;

	/** The {@code closer-mop} symbol naming {@link #FORWARD_REFERENCED_CLASS_NAME}. */
	public static final String FORWARD_REFERENCED_CLASS = "FORWARD-REFERENCED-CLASS";

	/**
	 * AMOP's {@code forward-referenced-class}, under its {@code closer-mop} name, beside
	 * {@link #STANDARD_CLASS_NAME} under {@link #CLASS_NAME}.
	 */
	public static final String FORWARD_REFERENCED_CLASS_NAME = LispNames.CLOSER_MOP_PKG + ":"
			+ FORWARD_REFERENCED_CLASS;

	/**
	 * One class a seeding family registers: its canonical name, its parent and its own
	 * slots.
	 */
	private record MopSeed(String name, @Nullable String parent, List<String> slots) {

		MopSeed(String name, @Nullable String parent) {
			this(name, parent, List.of());
		}

		/**
		 * The package-stripped name. Plain string work: the index below is built during
		 * this class's static initialization, which must not start PackageRegistry's (it
		 * reads this class's statics).
		 */
		String member() {
			return this.name.substring(this.name.lastIndexOf(':') + 1);
		}
	}

	/**
	 * The slots of a {@code standard-generic-function} metaobject, AMOP 5.3's initargs
	 * plus the methods and {@code %STATIC}, the compiled dispatcher of a generic the
	 * program defines statically (nil for one made only at run time). Read and written by
	 * name ({@code macro/generic-functions.lisp}).
	 */
	public static final List<String> GENERIC_FUNCTION_SLOTS = List.of("NAME", "LAMBDA-LIST", "METHODS", "METHOD-CLASS",
			"METHOD-COMBINATION", "ARGUMENT-PRECEDENCE-ORDER", "DECLARATIONS", "DOCUMENTATION", "%STATIC");

	/**
	 * The slots of a {@code standard-method} metaobject, AMOP 5.4's initargs plus its
	 * generic function and {@code %STATIC-KEY}, the registry key of a method a
	 * {@code defmethod} defined (nil for one made at run time, which the dispatchers find
	 * in the run-time method table instead).
	 */
	public static final List<String> METHOD_SLOTS = List.of("QUALIFIERS", "SPECIALIZERS", "LAMBDA-LIST", "FUNCTION",
			"GENERIC-FUNCTION", "DOCUMENTATION", "%STATIC-KEY");

	/**
	 * The metaobject classes of generic functions and methods (AMOP 5.2), as four
	 * families, each seeded whole the first time a program names a member
	 * ({@link #ensureMethodMetaobjectClassesSeeded}): {@code generic-function} /
	 * {@code standard-generic-function}; {@code method} / {@code standard-method} and
	 * closer-mop's accessor-method trio; {@code method-combination}; closer-mop's
	 * {@code eql-specializer}. The CL names are registered bare, the closer-mop ones
	 * under their external {@code closer-mop:} names. Single-parent roots (AMOP's
	 * abstract {@code metaobject}/{@code specializer} roots are left out);
	 * {@code generic-function} descends from {@code funcallable-standard-object}, so a
	 * generic function metaobject is a FUNCALLABLE instance. Instances exist only under
	 * the generic-function protocol ({@link #enableGfProtocol},
	 * {@code macro/generic-functions.lisp}): without it a generic function's value is a
	 * plain function and a method a registry row plus a defun, and the classes exist so a
	 * program can SPECIALIZE on them, test them as types, {@code find-class} them and
	 * SUBCLASS them -- moptilities' {@code (:method ((method standard-method)) ...)},
	 * contextl's {@code (defclass layered-method (standard-method) ())}.
	 */
	private static final List<List<MopSeed>> METHOD_METAOBJECT_FAMILIES = List.of(
			List.of(new MopSeed("GENERIC-FUNCTION", FUNCALLABLE_STANDARD_OBJECT_NAME),
					new MopSeed("STANDARD-GENERIC-FUNCTION", "GENERIC-FUNCTION", GENERIC_FUNCTION_SLOTS)),
			List.of(new MopSeed("METHOD", null), new MopSeed("STANDARD-METHOD", "METHOD", METHOD_SLOTS),
					new MopSeed(STANDARD_ACCESSOR_METHOD_NAME, "STANDARD-METHOD"),
					new MopSeed(STANDARD_READER_METHOD_NAME, STANDARD_ACCESSOR_METHOD_NAME),
					new MopSeed(STANDARD_WRITER_METHOD_NAME, STANDARD_ACCESSOR_METHOD_NAME)),
			List.of(new MopSeed("METHOD-COMBINATION", null)),
			List.of(new MopSeed(EQL_SPECIALIZER_NAME, null, List.of("OBJECT"))),
			List.of(new MopSeed(FORWARD_REFERENCED_CLASS_NAME, CLASS_NAME)));

	/** Each member name of {@link #METHOD_METAOBJECT_FAMILIES} to its family. */
	private static final Map<String, List<MopSeed>> METHOD_METAOBJECT_FAMILY_BY_MEMBER = methodMetaobjectFamilyIndex();

	private static Map<String, List<MopSeed>> methodMetaobjectFamilyIndex() {
		Map<String, List<MopSeed>> index = new java.util.HashMap<>();
		for (List<MopSeed> family : METHOD_METAOBJECT_FAMILIES) {
			for (MopSeed seed : family) {
				index.put(seed.member(), family);
			}
		}
		return Map.copyOf(index);
	}

	/** The classes {@link #ensureMopClassesSeeded()} registers, in seeding order. */
	private static final List<String> MOP_BASE_CLASS_NAMES = List.of(CLASS_NAME, STANDARD_CLASS_NAME,
			BUILT_IN_CLASS_NAME, SLOT_DEFINITION_NAME, DIRECT_SLOT_DEFINITION_NAME, EFFECTIVE_SLOT_DEFINITION_NAME,
			STANDARD_SLOT_DEFINITION_NAME, STANDARD_DIRECT_SLOT_DEFINITION_NAME,
			STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME);

	/**
	 * Whether the name (a package-stripped member spelling) is one of the seeded MOP base
	 * classes -- a {@code defclass} naming one as a superclass needs them seeded.
	 * @param member the upcased member name
	 * @return true for a MOP base class name
	 */
	public static boolean isMopBaseClassName(String member) {
		return MOP_BASE_CLASS_NAMES.contains(member);
	}

	/**
	 * The built-in class names {@code class-of} can answer for a non-instance value --
	 * exactly the result set of the {@code class-of} designator dispatch
	 * ({@code LispMacroExpander.expandClassOf} and the interpreter's
	 * {@code classOfTypeName} agree on it), so the metaobject view and the designator
	 * view name the same classes on every backend. That set is the
	 * {@code %class-designator} result set plus the array narrowing {@code class-of}
	 * alone performs: the lite designator answers {@code T} for every array, while
	 * {@code class-of} answers {@code VECTOR} / {@code ARRAY}, and an instance tag for a
	 * value of a fixed layout, while {@code class-of} answers the name
	 * {@link #VALUE_LAYOUT_CLASS_NAMES} gives it. {@code find-class} resolves these names
	 * too ({@link #builtinClassMetaobject}).
	 */
	public static final List<String> BUILTIN_CLASS_NAMES = List.of("T", "NULL", "BOOLEAN", "INTEGER", "RATIO", "FLOAT",
			"COMPLEX", "STRING", "CHARACTER", "KEYWORD", "SYMBOL", "HASH-TABLE", "FUNCTION", "CONS", "ARRAY", "VECTOR",
			"BIT-VECTOR", "QUANTIZED-MATRIX", "PATHNAME", "READTABLE", "RANDOM-STATE", "STREAM", "FILE-STREAM",
			"STRING-STREAM", "SYNONYM-STREAM", "TWO-WAY-STREAM", "BROADCAST-STREAM", "ECHO-STREAM",
			"CONCATENATED-STREAM");

	/**
	 * The standard stream classes, {@code stream} first and then the subtypes ANSI places
	 * directly below it -- the names {@link #streamClassName} answers, and part of
	 * {@link #BUILTIN_CLASS_NAMES}.
	 */
	public static final List<String> STREAM_CLASS_NAMES = BUILTIN_CLASS_NAMES
		.subList(BUILTIN_CLASS_NAMES.indexOf("STREAM"), BUILTIN_CLASS_NAMES.size());

	/**
	 * The composite stream classes, each built by a prelude Gray class named
	 * {@code %<NAME>} (tag {@code %class-%<NAME>}).
	 */
	public static final List<String> COMPOSITE_STREAM_CLASS_NAMES = List.of("TWO-WAY-STREAM", "BROADCAST-STREAM",
			"ECHO-STREAM", "CONCATENATED-STREAM");

	/**
	 * The standard class of a built-in stream VALUE -- what {@code type-of} and
	 * {@code class-of} name: an open stream by its {@code KIND} ({@code file-stream},
	 * {@code string-stream}, else {@code stream}), a synonym stream, or a composite
	 * stream by its prelude class. The compile paths emit the same dispatch
	 * ({@code LispMacroExpander.expandClassDesignator}).
	 * @param instance the instance
	 * @return the class name, or null for an instance that is no built-in stream
	 */
	public static @Nullable String streamClassName(LispInstance instance) {
		String tag = instance.layout().tag();
		if (LispLayout.STREAM_TAG.equals(tag)) {
			return streamKindClassName(instance.slot(1) instanceof LispSymbol kind ? kind.name() : "");
		}
		if (LispLayout.SYNONYM_STREAM_TAG.equals(tag)) {
			return "SYNONYM-STREAM";
		}
		for (String name : COMPOSITE_STREAM_CLASS_NAMES) {
			if (compositeStreamTag(name).equals(tag)) {
				return name;
			}
		}
		return null;
	}

	/**
	 * The standard class of an open stream of one {@link LispLayout.Kinds} kind.
	 * @param kind the kind keyword's name
	 * @return {@code FILE-STREAM}, {@code STRING-STREAM} or {@code STREAM}
	 */
	public static String streamKindClassName(String kind) {
		return switch (kind) {
			case LispLayout.Kinds.FILE -> "FILE-STREAM";
			case LispLayout.Kinds.STRING_INPUT, LispLayout.Kinds.STRING_OUTPUT -> "STRING-STREAM";
			default -> "STREAM";
		};
	}

	/**
	 * The instance tag of a composite stream class's prelude Gray class.
	 * @param name one of {@link #COMPOSITE_STREAM_CLASS_NAMES}
	 * @return the tag
	 */
	public static String compositeStreamTag(String name) {
		return LispLayout.CLASS_TAG_PREFIX + "%" + name;
	}

	/**
	 * Names {@code find-class} resolves to a memoized slot-less metaobject but that are
	 * deliberately NOT in {@link #BUILTIN_CLASS_NAMES}: {@code class-of} never answers
	 * them, so the designator view stays untouched, and the typep/subtypep special-casing
	 * of {@code standard-object} ("every CLOS instance") keeps winning over any registry
	 * ancestor test. {@code standard-object} exists as a metaobject because AMOP walks
	 * eq-compare superclass metaobjects against {@code (find-class 'standard-object)}
	 * (mito's {@code map-all-superclasses}), and the metaclass protocol defaults a
	 * driver-built class's empty {@code :direct-superclasses} to it
	 * ({@code mop-protocol.lisp}), per AMOP. It and {@code structure-object}
	 * ({@link #STANDARD_METACLASS_ROOT_NAMES}) are {@code standard-class} instances.
	 *
	 * <p>
	 * The rest are the INTERIOR of the built-in class lattice: every value they cover is
	 * covered by a narrower name {@code class-of} already answers ({@code number} by
	 * {@code integer}/{@code ratio}/{@code float}/{@code complex}, {@code list} by
	 * {@code cons}/{@code null}), so they are reachable only by NAME. The lattice ITSELF
	 * is not duplicated into their superclass slots -- it lives once in
	 * {@code typep}/{@code subtypep}, which a class metaobject standing as a type
	 * specifier resolves through by name (`.kb/clos.md`).
	 */
	public static final List<String> FIND_CLASS_ONLY_CLASS_NAMES = List.of("STANDARD-OBJECT", "SEQUENCE", "LIST",
			"NUMBER", "REAL", "RATIONAL", "STRUCTURE-OBJECT");

	/**
	 * The {@link #FIND_CLASS_ONLY_CLASS_NAMES} that are no built-in classes: the roots of
	 * the instances, whose metaobjects are {@link #STANDARD_CLASS_NAME} instances as a
	 * class's and a structure's are.
	 */
	public static final List<String> STANDARD_METACLASS_ROOT_NAMES = List.of(STANDARD_OBJECT_NAME, "STRUCTURE-OBJECT");

	/**
	 * The fixed layouts whose instances are values of a built-in class rather than of a
	 * registered one: the instance tag to the {@link #BUILTIN_CLASS_NAMES} name
	 * {@code class-of} answers.
	 */
	public static final Map<String, String> VALUE_LAYOUT_CLASS_NAMES = Map.of(LispLayout.PATHNAME_TAG, "PATHNAME",
			LispLayout.READTABLE_TAG, "READTABLE", LispLayout.RANDOM_STATE_TAG, "RANDOM-STATE");

	/**
	 * The {@code define-method-combination} functions, normalized name to what computes
	 * an effective method form ({@code macro/MethodCombinations.java}).
	 */
	private final Map<String, LispVal> methodCombinations = new java.util.HashMap<>();

	/**
	 * Records a {@code define-method-combination}.
	 * @param name the combination's name
	 * @param expander the function computing an effective method form
	 */
	public void registerMethodCombination(String name, LispVal expander) {
		this.methodCombinations.put(normalize(name), expander);
	}

	/**
	 * The function of a {@code define-method-combination}.
	 * @param name the combination's name
	 * @return the function, or null when the name was not defined
	 */
	@Nullable public LispVal findMethodCombination(String name) {
		return this.methodCombinations.get(normalize(name));
	}

	/**
	 * What a {@code define-method-combination} body handles as a method: a
	 * {@code standard-method} with the method's qualifiers, which is all the static
	 * subset has of it there.
	 * @param qualifiers the method's qualifiers
	 * @return the method object
	 */
	public LispInstance methodObject(LispVal qualifiers) {
		ensureMethodMetaobjectClassesSeeded("STANDARD-METHOD");
		return newSeededInstance("STANDARD-METHOD", qualifiers);
	}

	/**
	 * Registers a built-in class (a condition of the seeded hierarchy, or one of the MOP
	 * base classes) with nil-defaulted slots: parent slots first, then the given ones,
	 * each accepting its {@code :slot-name} initarg.
	 */
	private void seedClass(String name, @Nullable String parent, String... slotNames) {
		this.seededClassNames.add(name);
		ClassInfo parentInfo = parent == null ? null : this.classes.get(normalize(parent));
		java.util.List<SlotSpec> slots = new java.util.ArrayList<>(parentInfo == null ? List.of() : parentInfo.slots());
		java.util.List<SlotSpec> directSlots = new java.util.ArrayList<>();
		for (String slotName : slotNames) {
			// A slot definition no :type reached has type t (AMOP 5.4.2).
			LispVal initform = SLOT_DEFINITION_NAME.equals(name) && "TYPE".equals(slotName) ? LispTrue.INSTANCE
					: LispNil.INSTANCE;
			SlotSpec spec = new SlotSpec(slotName, slotName, initform, true, List.of(":" + slotName), true, List.of(),
					List.of(), "T");
			slots.add(spec);
			directSlots.add(spec);
		}
		java.util.Set<String> ancestors = new java.util.LinkedHashSet<>();
		if (parentInfo != null) {
			ancestors.addAll(parentInfo.ancestors());
		}
		// The ancestor set holds registry KEYS (descendantTags, a specializer's
		// applicability), as a defclass's does: an externally spelled name normalizes.
		ancestors.add(normalize(name));
		java.util.List<String> cpl = new java.util.ArrayList<>();
		cpl.add(name);
		if (parentInfo != null) {
			cpl.addAll(parentInfo.cpl());
		}
		registerClass(new ClassInfo(name, parent == null ? List.of() : List.of(parent), List.copyOf(cpl),
				List.copyOf(directSlots), List.copyOf(slots), java.util.Set.copyOf(ancestors)));
		for (int i = 0; i < slots.size(); i++) {
			registerSlotPosition(slots.get(i).baseName(), i + 1);
		}
	}

	/**
	 * One slot of a class: the canonical (package-resolved) slot symbol name, its
	 * package-stripped base name, the {@code :initform} expression AST, the
	 * {@code :initarg} names (default: the slot-name keyword), the
	 * {@code :reader}/{@code :accessor} function names declared on it, and the declared
	 * {@code :type} (plain name; {@code "t"} when omitted -- still a no-op for checking,
	 * but introspectable so serializers can disambiguate a nil value by declared type).
	 *
	 * @param name the canonical slot symbol name
	 * @param baseName the package-stripped slot name (constructor keywords use it)
	 * @param initform the default value expression -- the unbound-marker construction
	 * when no {@code :initform} was written (CLHS: such a slot starts UNBOUND)
	 * @param initformSupplied whether the slot specification wrote an {@code :initform};
	 * false means {@code initform} is the unbound marker, which is also what makes a
	 * shadowing subclass slot inherit the superclass initform instead of overriding it
	 * @param initargs the initarg names (a keyword keeps its colon), in declaration order
	 * and never empty: a slot may declare several and ANY of them fills it, the leftmost
	 * supplied one winning (CLHS 7.1.1, 7.1.4)
	 * @param initargSupplied whether the slot specification wrote an {@code :initarg};
	 * false means {@code initargs} is the slot-name default alone, which only the
	 * generated constructor honors -- CL's initialization protocol (the
	 * {@code shared-initialize} initarg fill, and therefore the metaclass-protocol
	 * re-fill after a {@code :before} hook) only ever fills DECLARED initargs
	 * @param readers the {@code :reader} function names
	 * @param accessors the {@code :accessor} function names (also setf places)
	 * @param type the package-stripped {@code :type} option name ({@code "t"} if none)
	 * @param writers the {@code :writer} generic names, internal form: a
	 * {@code (setf place)} writer is stored as its mangled {@code %setf-place} name (and
	 * registers the place as a setf function), a symbol writer as itself -- both take the
	 * new value first, like an accessor's write half
	 * @param sharedCellVar for a {@code :allocation :class} slot, the name of the global
	 * variable holding the shared value (the class that DECLARED the slot owns the cell;
	 * a subclass re-declaring with {@code :allocation :class} gets a cell of its own, per
	 * CLHS 7.5.3); null for an ordinary {@code :instance} slot
	 */
	public record SlotSpec(String name, String baseName, LispVal initform, boolean initformSupplied,
			List<String> initargs, boolean initargSupplied, List<String> readers, List<String> accessors, String type,
			List<String> writers, @Nullable String sharedCellVar) {

		/**
		 * Whether the slot accepts the initarg.
		 * @param initarg the initarg name as written (a keyword keeps its colon)
		 * @return true when it is one of {@link #initargs()}
		 */
		public boolean acceptsInitarg(String initarg) {
			return this.initargs.contains(initarg);
		}

		/**
		 * The pre-{@code :writer}/{@code :allocation} shape: no writer generics, instance
		 * allocation.
		 * @param name the slot name as spelled
		 * @param baseName the package-stripped base name
		 * @param initform the effective initform expression
		 * @param initformSupplied whether the source wrote an {@code :initform}
		 * @param initargs the initarg names, never empty
		 * @param initargSupplied whether the source wrote an {@code :initarg}
		 * @param readers the {@code :reader} function names
		 * @param accessors the {@code :accessor} function names
		 * @param type the package-stripped {@code :type} option name
		 */
		public SlotSpec(String name, String baseName, LispVal initform, boolean initformSupplied, List<String> initargs,
				boolean initargSupplied, List<String> readers, List<String> accessors, String type) {
			this(name, baseName, initform, initformSupplied, initargs, initargSupplied, readers, accessors, type,
					List.of(), null);
		}
	}

	/**
	 * One class: its canonical name, direct superclasses (in local precedence order),
	 * class precedence list, direct (own) slot specifications, full ordered effective
	 * slot list (inherited slots first), and the ancestor set including the class itself.
	 *
	 * @param name the canonical class name
	 * @param superclasses the canonical direct superclass names, in definition order
	 * (empty at a root)
	 * @param cpl the class precedence list (canonical names, the class itself first, most
	 * specific first -- CLHS 4.3.5 topological order over the registered classes)
	 * @param directSlots the slot specifications this class wrote itself (shadowing
	 * re-declarations included), used for CPL-ordered effective-slot option merging
	 * @param slots the full effective slot list, inherited slots first (the first
	 * superclass's effective slots keep their indexes -- the layout prefix rule)
	 * @param ancestors the normalized names of the class and all its ancestors
	 */
	public record ClassInfo(String name, List<String> superclasses, List<String> cpl, List<SlotSpec> directSlots,
			List<SlotSpec> slots, Set<String> ancestors) {
	}

	/** The kind of one parameter specializer of a method. */
	public enum SpecializerKind {

		/** No specializer (or {@code t}): the default method. */
		DEFAULT,
		/** {@code (eql literal)}: matches by object identity/content. */
		EQL,
		/** A {@code defclass} class name: matches the class and its descendants. */
		CLASS,
		/** A built-in type name ({@code integer}, {@code string}, ...). */
		TYPE

	}

	/**
	 * The specializer of one required parameter of a method.
	 *
	 * @param kind the specializer kind
	 * @param eqlValue the literal compared against for {@link SpecializerKind#EQL}
	 * @param name the normalized class/type name for CLASS/TYPE
	 */
	public record Specializer(SpecializerKind kind, @Nullable LispVal eqlValue, @Nullable String name) {

		/** The unspecialized ({@code t}) parameter specializer. */
		public static final Specializer DEFAULT = new Specializer(SpecializerKind.DEFAULT, null, null);

		/**
		 * The canonical key text of this specializer (used to build the method key).
		 * @return the key text
		 */
		public String keyText() {
			return switch (this.kind) {
				case DEFAULT -> "T";
				case EQL -> "eql " + java.util.Objects.requireNonNull(this.eqlValue).print();
				case CLASS -> "class " + this.name;
				case TYPE -> "type " + this.name;
			};
		}
	}

	/**
	 * One method of a generic function.
	 *
	 * @param specializers one specializer per required parameter (in parameter order)
	 * @param functionName the name of the generated method-body defun
	 * @param qualifier the method qualifier ({@code ""} for a primary method, or
	 * {@code ":BEFORE"}/{@code ":AFTER"}/{@code ":AROUND"} -- the upcased canonical,
	 * which the reader upcases every source qualifier to)
	 * @param usesNext whether the method body calls {@code call-next-method} or
	 * {@code next-method-p} (forces the combined dispatcher even without a qualifier)
	 */
	public record MethodInfo(List<Specializer> specializers, String functionName, String qualifier, boolean usesNext) {

		/**
		 * Whether this is a primary (unqualified) method.
		 * @return true when the qualifier is empty
		 */
		public boolean isPrimary() {
			return this.qualifier.isEmpty();
		}

		/**
		 * Whether every parameter is unspecialized (the default method).
		 * @return true when no parameter carries a specializer
		 */
		public boolean isDefault() {
			return this.specializers.stream().allMatch(s -> s.kind() == SpecializerKind.DEFAULT);
		}
	}

	/**
	 * What CLHS 7.6.4 compares between a generic function's lambda list and a method's:
	 * the required and optional counts, whether the list takes a {@code &rest} or
	 * {@code &key} tail, the keyword names after {@code &key} and
	 * {@code &allow-other-keys}. A method's specializers and {@code &aux} take no part.
	 *
	 * @param required the number of required parameters
	 * @param optional the number of {@code &optional} parameters
	 * @param rest whether the list has {@code &rest}/{@code &body} (or a dotted tail)
	 * @param key whether the list mentions {@code &key}
	 * @param keywords the keyword names {@code &key} accepts, spelled {@code :NAME}
	 * @param allowOtherKeys whether the list mentions {@code &allow-other-keys}
	 */
	public record LambdaListShape(int required, int optional, boolean rest, boolean key, List<String> keywords,
			boolean allowOtherKeys) {

		/**
		 * The shape of an ordinary or specialized lambda list as written.
		 * @param lambdaList the lambda list ({@code nil} or a cons)
		 * @return its shape
		 */
		public static LambdaListShape of(LispVal lambdaList) {
			int required = 0;
			int optional = 0;
			boolean rest = false;
			boolean key = false;
			boolean allowOtherKeys = false;
			List<String> keywords = new java.util.ArrayList<>();
			String section = "";
			LispVal cursor = lambdaList;
			while (cursor instanceof LispCons cell) {
				LispVal param = cell.car();
				cursor = cell.cdr();
				if (param instanceof LispSymbol sym && sym.isLambdaListKeyword()) {
					switch (sym.name()) {
						case LispNames.LAMBDA_REST, LispNames.LAMBDA_BODY -> rest = true;
						case LispNames.LAMBDA_KEY -> key = true;
						case LispNames.LAMBDA_ALLOW_OTHER_KEYS -> allowOtherKeys = true;
						default -> {
						}
					}
					section = sym.name();
					continue;
				}
				switch (section) {
					case "" -> required++;
					case LispNames.LAMBDA_OPTIONAL -> optional++;
					case LispNames.LAMBDA_KEY -> {
						String keyword = keywordOf(param);
						if (keyword != null) {
							keywords.add(keyword);
						}
					}
					default -> {
					}
				}
			}
			if (!(cursor instanceof LispNil)) {
				rest = true;
			}
			return new LambdaListShape(required, optional, rest, key, List.copyOf(keywords), allowOtherKeys);
		}

		/**
		 * The keyword a {@code &key} parameter specifier accepts: {@code var},
		 * {@code (var init [supplied-p])} or {@code ((keyword var) init [supplied-p])}.
		 */
		private static @Nullable String keywordOf(LispVal param) {
			LispVal named = param instanceof LispCons spec ? spec.car() : param;
			if (named instanceof LispCons explicit && explicit.car() instanceof LispSymbol keyword) {
				return keyword.name();
			}
			return named instanceof LispSymbol var ? ":" + plainNameOf(var.name()) : null;
		}

		/**
		 * Why a method of this shape cannot join a generic function of the
		 * {@code generic} shape (CLHS 7.6.4), as the end of SBCL's sentence, or null when
		 * the two are congruent. The {@code &key} names are compared only when the
		 * generic's lambda list was written by a {@code defgeneric}: one a
		 * {@code defmethod} created mentions {@code &key} but no keyword names (CLHS
		 * 7.6.5).
		 * @param generic the generic function's shape
		 * @param keywordsDeclared whether the generic's keyword names are its own
		 * @return the reason, or null
		 */
		public @Nullable String incongruenceWith(LambdaListShape generic, boolean keywordsDeclared) {
			if (this.required != generic.required) {
				return "the method has " + (this.required < generic.required ? "fewer" : "more")
						+ " required arguments than the generic function";
			}
			if (this.optional != generic.optional) {
				return "the method has " + (this.optional < generic.optional ? "fewer" : "more")
						+ " optional arguments than the generic function";
			}
			if ((this.rest || this.key) != (generic.rest || generic.key)) {
				return "the method and generic function differ in whether they accept &REST or &KEY arguments";
			}
			if (keywordsDeclared && generic.key && !this.allowOtherKeys && !(this.rest && !this.key)) {
				List<String> missing = generic.keywords.stream().filter(k -> !this.keywords.contains(k)).toList();
				if (!missing.isEmpty()) {
					return "the method does not accept each of the &KEY arguments (" + String.join(" ", missing) + ")";
				}
			}
			return null;
		}
	}

	/**
	 * The lambda-list shape CL gives a standard generic function the static subset
	 * models, or null for any other name: the instance-initialization trio,
	 * {@code print-object}, {@code slot-unbound} and {@code slot-missing}.
	 * @param plainName the generic's name without a package qualifier
	 * @return the standard shape, or null
	 */
	public static @Nullable LambdaListShape standardGenericShape(String plainName) {
		return switch (plainName) {
			case "INITIALIZE-INSTANCE", "REINITIALIZE-INSTANCE" ->
				new LambdaListShape(1, 0, true, true, List.of(), true);
			case "SHARED-INITIALIZE" -> new LambdaListShape(2, 0, true, true, List.of(), true);
			case LispNames.PRINT_OBJECT -> new LambdaListShape(2, 0, false, false, List.of(), false);
			case LispNames.SLOT_UNBOUND -> new LambdaListShape(3, 0, false, false, List.of(), false);
			case LispNames.SLOT_MISSING -> new LambdaListShape(4, 1, false, false, List.of(), false);
			default -> null;
		};
	}

	/**
	 * One step of a generic function's history on a compile path: the top-level form at
	 * {@code position} set a method, removed one, or made the generic exist.
	 *
	 * @param position the form's index in the compile walk's output
	 * @param key the method's key, or null for a {@code defgeneric} with no method yet
	 * @param method the method, or null for a removal
	 */
	public record MethodChange(int position, @Nullable String key, @Nullable MethodInfo method) {
	}

	/**
	 * The method table a compiled generic dispatches over until a flag is set: its
	 * dispatcher reads the flag and picks the table's body while the flag is nil.
	 *
	 * @param flag the global the defining form sets
	 * @param position the defining form's index in the compile walk's output
	 * @param methods the methods defined before that form
	 * @param declared whether the generic exists by then, with no method if need be
	 */
	public record MethodGeneration(String flag, int position, Map<String, MethodInfo> methods, boolean declared) {
	}

	/**
	 * One generic function: its canonical name, required parameter names (from the
	 * {@code defgeneric} or the first {@code defmethod}), optional documentation, and its
	 * methods keyed by a canonical specializer key so redefining the same specializer
	 * replaces the method (CL semantics).
	 */
	public static final class GenericInfo {

		private final String name;

		private List<String> paramNames;

		@Nullable private LambdaListShape lambdaListShape;

		private boolean keywordsDeclared;

		private final Map<String, LambdaListShape> methodShapes = new java.util.HashMap<>();

		private final Set<String> inlineMethodKeys = new java.util.HashSet<>();

		private boolean variadic;

		@Nullable private String documentation;

		private final LinkedHashMap<String, MethodInfo> methods = new LinkedHashMap<>();

		private int methodCounter = 0;

		private final LinkedHashMap<String, String> eqlSpecializerCells = new LinkedHashMap<>();

		@Nullable private String methodCombination;

		/** The {@code define-method-combination} function, or null. */
		@Nullable private LispVal methodCombinationExpander;

		/** The {@code :method-combination} arguments for that function. */
		private LispVal methodCombinationOptions = LispNil.INSTANCE;

		/** A method's qualifiers under that function, by its defun's name. */
		private final Map<String, LispVal> methodQualifiers = new java.util.HashMap<>();

		private boolean mostSpecificLast;

		@Nullable private LispVal genericFunctionClass;

		@Nullable private LispVal methodClass;

		@Nullable private LispVal declaredLambdaList;

		private final List<MethodChange> methodChanges = new java.util.ArrayList<>();

		@Nullable private LinkedHashMap<String, MethodInfo> methodsBeforeChanges;

		private final LinkedHashMap<String, MethodInfo> systemDefaults = new LinkedHashMap<>();

		private List<MethodGeneration> generations = List.of();

		/**
		 * Sets or removes a method, recording the change when a compile walk is at a
		 * top-level form.
		 * @param position the form's index in the walk's output, negative off the walk
		 * @param key the method's key
		 * @param method the method, or null to remove it
		 */
		void changeMethod(int position, String key, @Nullable MethodInfo method) {
			if (position >= 0) {
				if (this.methodsBeforeChanges == null) {
					this.methodsBeforeChanges = new LinkedHashMap<>(this.methods);
				}
				this.methodChanges.add(new MethodChange(position, key, method));
			}
			if (method == null) {
				this.methods.remove(key);
			}
			else {
				this.methods.put(key, method);
			}
		}

		/**
		 * Records that a {@code defgeneric} made this generic exist with no method yet.
		 * @param position the form's index in the walk's output, negative off the walk
		 */
		void declaredAt(int position) {
			if (position >= 0) {
				if (this.methodsBeforeChanges == null) {
					this.methodsBeforeChanges = new LinkedHashMap<>(this.methods);
				}
				this.methodChanges.add(new MethodChange(position, null, null));
			}
		}

		/**
		 * Sets the method the system supplies. It is no definition of the program: it
		 * applies from the start, wherever the form that synthesized it stands.
		 * @param key the method's key
		 * @param method the method
		 */
		public void putSystemDefault(String key, MethodInfo method) {
			this.systemDefaults.put(key, method);
			this.methods.put(key, method);
		}

		/**
		 * The recorded changes, in definition order.
		 * @return the changes
		 */
		public List<MethodChange> methodChanges() {
			return this.methodChanges;
		}

		/**
		 * The method table as the top-level forms before {@code position} left it.
		 * @param position an index in the compile walk's output
		 * @return the methods, in definition order
		 */
		public Map<String, MethodInfo> methodsBefore(int position) {
			LinkedHashMap<String, MethodInfo> table = this.methodsBeforeChanges == null ? new LinkedHashMap<>()
					: new LinkedHashMap<>(this.methodsBeforeChanges);
			table.putAll(this.systemDefaults);
			for (MethodChange change : this.methodChanges) {
				if (change.position() >= position) {
					break;
				}
				if (change.key() != null) {
					if (change.method() == null) {
						table.remove(change.key());
					}
					else {
						table.put(change.key(), change.method());
					}
				}
			}
			return table;
		}

		/**
		 * Whether a {@code defgeneric} or a method made this generic exist before
		 * {@code position}.
		 * @param position an index in the compile walk's output
		 * @return true when it existed by then
		 */
		public boolean existsBefore(int position) {
			return this.methodsBeforeChanges != null && !this.methodsBeforeChanges.isEmpty()
					|| this.methodChanges.stream().anyMatch(change -> change.position() < position);
		}

		/**
		 * The earlier method tables calls can observe, oldest first; empty when every
		 * call of the program sees the last one.
		 * @return the generations
		 */
		public List<MethodGeneration> generations() {
			return this.generations;
		}

		/**
		 * Records the earlier method tables calls can observe.
		 * @param generations the generations, oldest first
		 */
		public void generations(List<MethodGeneration> generations) {
			this.generations = List.copyOf(generations);
		}

		/**
		 * The global a generation's flag lives in: {@code %NAME--genN}, the {@code %}
		 * after the package qualifier when there is one.
		 * @param index the generation's index
		 * @return the variable's name
		 */
		public String generationFlag(int index) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(this.name);
			return (qn == null ? "%" + this.name : qn.pkg() + "::%" + qn.member()) + "--gen" + index;
		}

		/**
		 * This generic function with another method table, which is what a dispatcher
		 * body generated for one generation reads.
		 * @param table the methods
		 * @return the view
		 */
		public GenericInfo withMethods(Map<String, MethodInfo> table) {
			GenericInfo view = new GenericInfo(this.name, this.paramNames);
			view.methods.putAll(table);
			view.variadic = this.variadic;
			view.methodCombination = this.methodCombination;
			view.methodCombinationExpander = this.methodCombinationExpander;
			view.methodCombinationOptions = this.methodCombinationOptions;
			view.methodQualifiers.putAll(this.methodQualifiers);
			view.mostSpecificLast = this.mostSpecificLast;
			view.precedenceOrder = this.precedenceOrder;
			view.lambdaListShape = this.lambdaListShape;
			view.keywordsDeclared = this.keywordsDeclared;
			view.genericFunctionClass = this.genericFunctionClass;
			view.methodClass = this.methodClass;
			view.declaredLambdaList = this.declaredLambdaList;
			return view;
		}

		/**
		 * The {@code (:generic-function-class C)} option of the generic's
		 * {@code defgeneric}, or null for {@code standard-generic-function}.
		 * @return the class name, or null
		 */
		@Nullable public LispVal genericFunctionClass() {
			return this.genericFunctionClass;
		}

		/**
		 * The {@code (:method-class C)} option of the generic's {@code defgeneric}, or
		 * null for the generic-function class's default.
		 * @return the class name, or null
		 */
		@Nullable public LispVal methodClass() {
			return this.methodClass;
		}

		/**
		 * The lambda list the generic's {@code defgeneric} wrote, or null.
		 * @return the lambda list, or null
		 */
		@Nullable public LispVal declaredLambdaList() {
			return this.declaredLambdaList;
		}

		private final Map<String, LispVal> methodLambdaLists = new java.util.HashMap<>();

		@Nullable private String lastMethodKey;

		private List<String> precedenceOrder = List.of();

		/**
		 * The {@code :argument-precedence-order} of the generic's {@code defgeneric} as
		 * required-parameter indexes, or null when it wrote none (or not a permutation of
		 * the required parameters): leftmost-first.
		 * @return the indexes in precedence order, or null
		 */
		public int @Nullable [] precedenceIndexes() {
			if (this.precedenceOrder.isEmpty() || this.precedenceOrder.size() != this.paramNames.size()) {
				return null;
			}
			int[] order = new int[this.precedenceOrder.size()];
			for (int k = 0; k < order.length; k++) {
				order[k] = this.paramNames.indexOf(this.precedenceOrder.get(k));
				if (order[k] < 0) {
					return null;
				}
			}
			return order;
		}

		/**
		 * Records the {@code :argument-precedence-order} of the generic's
		 * {@code defgeneric}.
		 * @param order the parameter names in precedence order (empty for none)
		 */
		public void precedenceOrder(List<String> order) {
			this.precedenceOrder = List.copyOf(order);
		}

		/**
		 * Each method's lambda list without its specializers (a method metaobject's
		 * {@code method-lambda-list}), keyed like {@link #methods()}.
		 * @return the lambda lists
		 */
		public Map<String, LispVal> methodLambdaLists() {
			return this.methodLambdaLists;
		}

		/**
		 * The key of the method a {@code defmethod} defined last, or null.
		 * @return the key, or null
		 */
		@Nullable public String lastMethodKey() {
			return this.lastMethodKey;
		}

		/**
		 * Records the method a {@code defmethod} just defined; a null key forgets the
		 * last one (a {@code defmethod} about to be expanded may be refused).
		 * @param key its key in {@link #methods()}, or null
		 * @param lambdaList its lambda list without specializers
		 */
		public void methodDefined(@Nullable String key, LispVal lambdaList) {
			this.lastMethodKey = key;
			if (key != null) {
				this.methodLambdaLists.put(key, lambdaList);
			}
		}

		/**
		 * Records the metaobject options of the generic's {@code defgeneric}.
		 * @param genericFunctionClass the {@code :generic-function-class}, or null
		 * @param methodClass the {@code :method-class}, or null
		 * @param lambdaList the written lambda list
		 */
		public void metaobjectOptions(@Nullable LispVal genericFunctionClass, @Nullable LispVal methodClass,
				LispVal lambdaList) {
			this.genericFunctionClass = genericFunctionClass;
			this.methodClass = methodClass;
			this.declaredLambdaList = lambdaList;
		}

		/**
		 * Creates a generic-function record.
		 * @param name the generic function name
		 * @param paramNames the parameter names of its lambda list
		 */
		public GenericInfo(String name, List<String> paramNames) {
			this.name = name;
			this.paramNames = List.copyOf(paramNames);
		}

		/**
		 * The canonical generic-function name (the dispatcher defun's name).
		 * @return the name
		 */
		public String name() {
			return this.name;
		}

		/**
		 * The required parameter names of the dispatcher.
		 * @return the parameter names
		 */
		public List<String> paramNames() {
			return this.paramNames;
		}

		/**
		 * Replaces the recorded parameter names.
		 * @param paramNames the parameter names
		 */
		public void paramNames(List<String> paramNames) {
			this.paramNames = List.copyOf(paramNames);
		}

		/**
		 * The generic function's lambda-list shape: its {@code defgeneric}'s, else the
		 * one its first method established, else null (a synthesized system generic whose
		 * name has no standard shape).
		 * @return the shape, or null
		 */
		@Nullable public LambdaListShape lambdaListShape() {
			return this.lambdaListShape;
		}

		/**
		 * Whether the shape's {@code &key} names were written by a {@code defgeneric}
		 * (and so bind every method), see {@link LambdaListShape#incongruenceWith}.
		 * @return true for a declared shape
		 */
		public boolean keywordsDeclared() {
			return this.keywordsDeclared;
		}

		/**
		 * Records the generic function's lambda-list shape.
		 * @param shape the shape
		 * @param declared whether a {@code defgeneric} wrote it
		 */
		public void lambdaListShape(LambdaListShape shape, boolean declared) {
			this.lambdaListShape = shape;
			this.keywordsDeclared = declared;
		}

		/**
		 * The lambda-list shape of each method a {@code defmethod} added, keyed like
		 * {@link #methods()}; a synthesized system default has none.
		 * @return the method shapes
		 */
		public Map<String, LambdaListShape> methodShapes() {
			return this.methodShapes;
		}

		/**
		 * The keys of the methods the generic's {@code defgeneric} defined through
		 * {@code (:method ...)} options, which a redefinition of the generic removes
		 * (CLHS defgeneric).
		 * @return the method keys
		 */
		public Set<String> inlineMethodKeys() {
			return this.inlineMethodKeys;
		}

		/**
		 * Whether the generic's or any method's lambda list continues past the required
		 * parameters ({@code &optional}/{@code &rest}/{@code &key}). A variadic
		 * dispatcher takes a {@code &rest} tail and forwards it to the method-body defuns
		 * with {@code apply}, so each method's own defaults apply.
		 * @return true when the dispatcher must forward a rest tail
		 */
		public boolean variadic() {
			return this.variadic;
		}

		/** Records that at least one method of this generic takes a rest parameter. */
		public void markVariadic() {
			this.variadic = true;
		}

		/**
		 * The {@code :documentation} string, or null.
		 * @return the documentation
		 */
		@Nullable public String documentation() {
			return this.documentation;
		}

		/**
		 * Records the generic's documentation string.
		 * @param documentation the documentation string, or {@code null}
		 */
		public void documentation(@Nullable String documentation) {
			this.documentation = documentation;
		}

		/**
		 * The {@code (:method-combination NAME [order])} operator, or null for the
		 * STANDARD combination. One of {@link #SHORT_FORM_COMBINATIONS}: the effective
		 * method is then "that operator over EVERY applicable method carrying the
		 * combination name as its qualifier", not "the most specific primary plus a next
		 * chain".
		 * @return the combination operator name, or null
		 */
		@Nullable public String methodCombination() {
			return this.methodCombination;
		}

		/**
		 * Whether the combination's optional order argument was
		 * {@code :most-specific-last} (the operator sees the LEAST specific method
		 * first). Meaningless for the standard combination.
		 * @return true for {@code :most-specific-last}
		 */
		public boolean mostSpecificLast() {
			return this.mostSpecificLast;
		}

		/**
		 * Records the short-form method combination.
		 * @param name the combination operator name, or null for the standard one
		 * @param mostSpecificLast whether the order argument was
		 * {@code :most-specific-last}
		 */
		public void methodCombination(@Nullable String name, boolean mostSpecificLast) {
			this.methodCombination = name;
			this.mostSpecificLast = mostSpecificLast;
			this.methodCombinationExpander = null;
			this.methodCombinationOptions = LispNil.INSTANCE;
		}

		/**
		 * Records a combination {@code define-method-combination} defined.
		 * @param name the combination's name
		 * @param expander its function
		 * @param options the arguments {@code :method-combination} gives it
		 */
		public void methodCombination(String name, LispVal expander, LispVal options) {
			this.methodCombination = name;
			this.mostSpecificLast = false;
			this.methodCombinationExpander = expander;
			this.methodCombinationOptions = options;
		}

		/**
		 * The function of a combination {@code define-method-combination} defined.
		 * @return the function, or null
		 */
		@Nullable public LispVal methodCombinationExpander() {
			return this.methodCombinationExpander;
		}

		/**
		 * The arguments {@code :method-combination} gives
		 * {@link #methodCombinationExpander()}.
		 * @return the list
		 */
		public LispVal methodCombinationOptions() {
			return this.methodCombinationOptions;
		}

		/**
		 * Each method's qualifiers under {@link #methodCombinationExpander()}, by the
		 * name of its method-body defun.
		 * @return the mutable table
		 */
		public Map<String, LispVal> methodQualifiers() {
			return this.methodQualifiers;
		}

		/**
		 * The methods keyed by canonical specializer key, in definition order.
		 * @return the methods
		 */
		public Map<String, MethodInfo> methods() {
			return this.methods;
		}

		/**
		 * Allocates the index of the next method defined on this generic.
		 * @return the method index
		 */
		public int nextMethodIndex() {
			return this.methodCounter++;
		}

		/**
		 * The global cell holding the value of a COMPUTED {@code (eql form)} specializer,
		 * minted on first request: {@code %NAME--eqlN}, the {@code %} after the package
		 * qualifier when there is one. One cell per form TEXT, so a method defined again
		 * with the same form replaces the earlier one (its specializer key is the cell)
		 * while the form is evaluated anew.
		 * @param formText the printed specializer form
		 * @return the cell's variable name
		 */
		public String eqlSpecializerCell(String formText) {
			return this.eqlSpecializerCells.computeIfAbsent(formText, text -> {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(this.name);
				return (qn == null ? "%" + this.name : qn.pkg() + "::%" + qn.member()) + "--eql"
						+ this.eqlSpecializerCells.size();
			});
		}

		/**
		 * Every computed-{@code eql}-specializer cell of this generic, in minting order.
		 * @return the cell names
		 */
		public java.util.Collection<String> eqlSpecializerCells() {
			return this.eqlSpecializerCells.values();
		}

		/**
		 * Whether any method's dispatch test enumerates class tags, so the dispatcher
		 * must be regenerated when a new class appears: a CLASS specializer (the new
		 * class may extend its descendant set) or a TYPE specializer whose test carries
		 * the any-class-instance enumeration ({@code standard-object} matches it,
		 * {@code cons}/{@code list}/{@code sequence} exclude it).
		 * @return true when such a method exists
		 */
		public boolean hasClassMethod() {
			return this.methods.values()
				.stream()
				.anyMatch(m -> m.specializers()
					.stream()
					.anyMatch(s -> s.kind() == SpecializerKind.CLASS || enumeratesClassInstances(s)));
		}

		/**
		 * {@link #hasClassMethod()} for one class never defined before: the dispatcher
		 * changes only where a class specializer is among its ancestors, so a big system
		 * does not regenerate each of its generics per {@code defclass}.
		 * @param defined the new class
		 * @return true when the dispatcher has to be regenerated
		 */
		public boolean dispatchesOverNewClass(ClassInfo defined) {
			return this.methods.values()
				.stream()
				.anyMatch(m -> m.specializers()
					.stream()
					.anyMatch(s -> (s.kind() == SpecializerKind.CLASS && defined.ancestors().contains(s.name()))
							|| enumeratesClassInstances(s)));
		}

		private static boolean enumeratesClassInstances(Specializer specializer) {
			if (specializer.kind() != SpecializerKind.TYPE || specializer.name() == null) {
				return false;
			}
			String plain = plainNameOf(specializer.name());
			return "STANDARD-OBJECT".equals(plain) || "CONS".equals(plain) || "LIST".equals(plain)
					|| "SEQUENCE".equals(plain);
		}

		/**
		 * Whether some method dispatches on a struct type: a specializer naming a
		 * registered {@code defstruct} type, or {@code structure-object}. A later
		 * {@code defstruct} widens both (a new {@code :include} descendant joins the
		 * specializer's tag set; any new struct joins the {@code structure-object}
		 * enumeration), so the interpreter regenerates these dispatchers per defstruct --
		 * the struct-side twin of {@link #hasClassMethod}.
		 * @param registry the registry that knows the struct types
		 * @return true when such a method exists
		 */
		public boolean hasStructMethod(ClosRegistry registry) {
			return this.methods.values()
				.stream()
				.anyMatch(m -> m.specializers()
					.stream()
					.anyMatch(s -> s.kind() == SpecializerKind.TYPE && s.name() != null
							&& ("STRUCTURE-OBJECT".equals(plainNameOf(s.name()))
									|| registry.findStructTag(s.name()) != null)));
		}

	}

	private final LinkedHashMap<String, ClassInfo> classes = new LinkedHashMap<>();

	private final LinkedHashMap<String, GenericInfo> generics = new LinkedHashMap<>();

	/**
	 * The index in the compile walk's output of the top-level form being walked; negative
	 * off the walk.
	 */
	private int definitionPosition = -1;

	/**
	 * The index in the compile walk's output of the form that first defined a class or
	 * structure type, by normalized name.
	 */
	private final Map<String, Integer> typeFirstDefined = new java.util.HashMap<>();

	/**
	 * Tells the registry which top-level form the compile walk is at.
	 * @param position the form's index in the walk's output, negative off the walk
	 */
	public void definitionPosition(int position) {
		this.definitionPosition = position;
	}

	private void noteTypeDefined(String key) {
		if (this.definitionPosition >= 0) {
			this.typeFirstDefined.putIfAbsent(key, this.definitionPosition);
		}
	}

	/**
	 * Where the compile walk first defined a class or structure type.
	 * @param name the type's name
	 * @return the form's index in the walk's output, or -1 for a type the program did not
	 * define
	 */
	public int typeFirstDefined(String name) {
		return isSeededClass(name) ? -1 : this.typeFirstDefined.getOrDefault(normalize(name), -1);
	}

	/**
	 * Sets or removes a method of a generic function, in program order on a compile walk.
	 * @param generic the generic function
	 * @param key the method's key
	 * @param method the method, or null to remove it
	 */
	public void changeMethod(GenericInfo generic, String key, @Nullable MethodInfo method) {
		generic.changeMethod(this.definitionPosition, key, method);
	}

	/**
	 * Records that a {@code defgeneric} made a generic function exist with no method yet.
	 * @param generic the generic function
	 */
	public void genericDeclared(GenericInfo generic) {
		generic.declaredAt(this.definitionPosition);
	}

	/**
	 * Slot base name to its 1-based position, or {@code -1} when two classes disagree on
	 * the position ({@code slot-value} then rejects the name as ambiguous).
	 */
	private final Map<String, Integer> slotPositions = new LinkedHashMap<>();

	/**
	 * Condition class name (normalized) to its {@code (:report x)} form -- a literal
	 * string or a {@code (lambda (condition stream) ...)} expression AST -- registered by
	 * {@code define-condition}. The {@code error}/{@code signal}/{@code warn} expansions
	 * consult it to build the message of a typed signal.
	 */
	private final Map<String, LispVal> conditionReports = new LinkedHashMap<>();

	/**
	 * Whether this compilation routes condition printing through
	 * {@code %condition-report-str} (see {@link #routesConditionReports()}).
	 */
	private boolean routesConditionReports;

	/**
	 * Whether the printing operators route a condition through the renderer (see
	 * {@link #printsConditionReports()}).
	 */
	private boolean printsConditionReports;

	/** Whether {@link #ensureMopClassesSeeded()} has run. */
	private boolean mopClassesSeeded;

	/** Whether {@link #ensureFuncallableClassesSeeded()} has run. */
	private boolean funcallableClassesSeeded;

	/**
	 * The root names of the {@link #METHOD_METAOBJECT_FAMILIES} seeded so far (see
	 * {@link #ensureMethodMetaobjectClassesSeeded}).
	 */
	private final Set<String> seededMethodMetaobjectFamilies = new java.util.HashSet<>();

	/**
	 * Registers the class a {@link #findClass} lookup missed when a lazily loaded library
	 * defines it, answering whether the lookup is worth repeating; null when every class
	 * is registered up front (see
	 * {@link #classMissLoader(java.util.function.Predicate)}).
	 */
	private java.util.function.@Nullable Predicate<String> classMissLoader;

	/**
	 * Installs the loader {@link #findClass} consults on a miss, before answering null.
	 * The interpreter loads gray.lisp on first use, so a Gray base class NAMED as data
	 * ({@code (find-class 'rontolisp:fundamental-stream)}, a {@code subtypep} or
	 * {@code typep} specifier) before any write, {@code defclass} or protocol definition
	 * loaded it would otherwise be unknown; the compile paths splice the library ahead of
	 * expansion for any program naming one and install none.
	 * @param loader receives the name as spelled; loads what defines it and answers
	 * whether the lookup should be repeated
	 */
	public void classMissLoader(java.util.function.Predicate<String> loader) {
		this.classMissLoader = loader;
	}

	/**
	 * Whether the metaclass-protocol runtime ({@code macro/mop-protocol.lisp}) is part of
	 * this evaluation/compilation -- set by the interpreter's protocol load and by the
	 * compile paths when the forms are prepended. It gates the CHAIN-FILL construction of
	 * metaobject-class instances ({@code expandMakeInstance},
	 * {@code mopMakeInstanceDefuns}): allocate unbound, then run the initialization
	 * generic whose system {@code shared-initialize} primary performs the initarg fill --
	 * without the protocol those primaries do not exist and the static constructor shape
	 * must stay.
	 */
	private boolean mopProtocolActive;

	/**
	 * Whether the metaclass-protocol runtime is part of this evaluation/compilation --
	 * see {@link #setMopProtocolActive()}.
	 * @return {@code true} once the protocol forms are loaded or prepended
	 */
	public boolean isMopProtocolActive() {
		return this.mopProtocolActive;
	}

	/**
	 * Records that the metaclass-protocol forms are loaded (interpreter) or prepended
	 * (compile paths) into this registry's evaluation.
	 */
	public void setMopProtocolActive() {
		this.mopProtocolActive = true;
	}

	private boolean slotProtocol;

	/**
	 * Whether the slot-access protocol runtime ({@code macro/slot-protocol.lisp}) is part
	 * of this evaluation/compilation: {@code slot-value}, its {@code setf},
	 * {@code slot-boundp}, {@code slot-makunbound} and the accessors of a
	 * {@code :metaclass} class then route an instance of a class defined with a
	 * {@code :metaclass} through {@code slot-value-using-class} and its siblings.
	 * @return {@code true} once {@link #enableSlotProtocol} ran
	 */
	public boolean slotProtocol() {
		return this.slotProtocol;
	}

	/**
	 * Records that the slot-access protocol runtime is loaded (interpreter) or prepended
	 * (compile paths).
	 */
	public void enableSlotProtocol() {
		this.slotProtocol = true;
	}

	private boolean marksGenericDefinitions;

	/**
	 * Whether a compile path's walk marks where a generic function that has its
	 * dispatcher is defined again, which binds a name the program retired or replaced
	 * ({@code compiler.RedefinedDefuns}).
	 * @return {@code true} once {@link #markGenericDefinitions} ran
	 */
	public boolean marksGenericDefinitions() {
		return this.marksGenericDefinitions;
	}

	/** Has the walk mark each later definition of a generic function. */
	public void markGenericDefinitions() {
		this.marksGenericDefinitions = true;
	}

	private boolean gfProtocol;

	/**
	 * Whether the generic-function protocol runtime
	 * ({@code macro/generic-functions.lisp}) is part of this evaluation/compilation:
	 * generic function and method metaobjects exist ({@code ensure-generic-function},
	 * {@code add-method}, closer-mop's {@code ensure-method}), every dispatcher consults
	 * its generic's run-time methods, and a {@code defmethod} on a generic of a user
	 * generic-function class reaches {@code add-method}.
	 * @return {@code true} once {@link #enableGfProtocol} ran
	 */
	public boolean gfProtocol() {
		return this.gfProtocol;
	}

	/**
	 * Records that the generic-function protocol runtime is loaded (interpreter) or
	 * prepended (compile paths), seeding the method metaobject classes it instantiates.
	 */
	public void enableGfProtocol() {
		if (this.gfProtocol) {
			return;
		}
		this.gfProtocol = true;
		for (List<MopSeed> family : METHOD_METAOBJECT_FAMILIES) {
			ensureMethodMetaobjectClassesSeeded(family.get(0).member());
		}
	}

	/** The classes a {@code defclass} defined with a {@code :metaclass}, normalized. */
	private final Set<String> metaclassedClasses = new java.util.HashSet<>();

	/** Each of {@link #metaclassedClasses} to its metaclass's name. */
	private final Map<String, String> metaclassOf = new java.util.HashMap<>();

	/**
	 * Records that the class was defined with a {@code (:metaclass M)} option.
	 * @param className the class name as spelled
	 * @param metaclassName the metaclass's name
	 */
	public void markMetaclassed(String className, String metaclassName) {
		this.metaclassedClasses.add(normalize(className));
		this.metaclassOf.put(normalize(className), metaclassName);
	}

	/**
	 * Whether any class was defined with a {@code :metaclass} -- the instances the
	 * interpreter's class redefinition may have to update exist only then.
	 * @return true once one was
	 */
	public boolean hasMetaclassedClasses() {
		return !this.metaclassedClasses.isEmpty();
	}

	/**
	 * Whether the class was defined with a {@code (:metaclass M)} option.
	 * @param className the class name as spelled
	 * @return true for such a class
	 */
	public boolean isMetaclassed(String className) {
		return this.metaclassedClasses.contains(normalize(className));
	}

	/**
	 * The {@code :metaclass} classes a compiled program defines more than once at top
	 * level, normalized: the compile paths track their instances (and their subclasses')
	 * until the last definition has run, for the updating a redefinition does (CLHS
	 * 4.3.6).
	 */
	private final Set<String> instanceTrackedClasses = new java.util.HashSet<>();

	/**
	 * Records that the instances of the class are tracked for redefinition updating.
	 * @param className the class name as spelled
	 */
	public void trackInstancesOf(String className) {
		this.instanceTrackedClasses.add(normalize(className));
	}

	/**
	 * Whether the instances of the class are tracked: it, or one of its registered
	 * ancestors, is a class {@link #trackInstancesOf} named.
	 * @param className the class name as spelled
	 * @return true when an allocation of the class records the instance
	 */
	public boolean tracksInstancesOf(String className) {
		if (this.instanceTrackedClasses.isEmpty()) {
			return false;
		}
		ClassInfo info = findClass(className);
		if (info == null) {
			return this.instanceTrackedClasses.contains(normalize(className));
		}
		if (this.instanceTrackedClasses.contains(normalize(info.name()))) {
			return true;
		}
		for (String ancestor : info.ancestors()) {
			if (this.instanceTrackedClasses.contains(normalize(ancestor))) {
				return true;
			}
		}
		return false;
	}

	/** The classes a {@code defclass} gave {@code :default-initargs}, normalized. */
	private final Set<String> defaultInitargClasses = new java.util.HashSet<>();

	/**
	 * Records that the class was defined with {@code (:default-initargs ...)}.
	 * @param className the class name as spelled
	 */
	public void markDefaultInitargs(String className) {
		this.defaultInitargClasses.add(normalize(className));
	}

	/** Each class's own {@code :default-initargs}, initarg name to form, normalized. */
	private final Map<String, Map<String, LispVal>> directDefaultInitargs = new java.util.HashMap<>();

	/**
	 * Records a class's own {@code (:default-initargs ...)} (empty for none), replacing
	 * an earlier definition's.
	 * @param className the class name as spelled
	 * @param defaults initarg name to its default form, in written order
	 */
	public void directDefaultInitargs(String className, Map<String, LispVal> defaults) {
		if (defaults.isEmpty()) {
			this.directDefaultInitargs.remove(normalize(className));
		}
		else {
			this.directDefaultInitargs.put(normalize(className), new java.util.LinkedHashMap<>(defaults));
		}
	}

	/**
	 * A class's default initargs (CLHS 7.1.3): its own and its superclasses' along the
	 * class precedence list, the most specific default of an initarg winning, in that
	 * order.
	 * @param info the class
	 * @return initarg name to default form, empty when none
	 */
	public Map<String, LispVal> defaultInitargs(ClassInfo info) {
		if (this.directDefaultInitargs.isEmpty()) {
			return Map.of();
		}
		Map<String, LispVal> merged = new java.util.LinkedHashMap<>();
		for (String name : info.cpl()) {
			Map<String, LispVal> own = this.directDefaultInitargs.get(normalize(name));
			if (own != null) {
				own.forEach(merged::putIfAbsent);
			}
		}
		return merged;
	}

	/**
	 * Whether {@code make-instance} of the class calls {@code allocate-instance}: the
	 * class was defined with a {@code :metaclass} a method on the generic specializes.
	 * @param className the class name as spelled
	 * @return true for such a class
	 */
	public boolean allocatesThroughGeneric(String className) {
		return metaclassHasMethod(findGeneric(LispNames.ALLOCATE_INSTANCE), className);
	}

	/**
	 * Whether a literal {@code make-instance} of the class calls the generic: the class
	 * was defined with a {@code :metaclass} a method on {@code make-instance} specializes
	 * (ESA's {@code modual-class}).
	 * @param className the class name as spelled
	 * @return true for such a class
	 */
	public boolean constructsThroughGeneric(String className) {
		return metaclassHasMethod(makeInstanceGeneric(), className);
	}

	/**
	 * The generic a method on {@code make-instance} made, in whatever package.
	 * @return the generic, or null
	 */
	@Nullable public GenericInfo makeInstanceGeneric() {
		return findGeneric(LispNames.MAKE_INSTANCE);
	}

	private boolean metaclassHasMethod(@Nullable GenericInfo generic, String className) {
		String metaclass = this.metaclassOf.get(normalize(className));
		if (generic == null || metaclass == null) {
			return false;
		}
		ClassInfo info = findClass(metaclass);
		if (info == null) {
			return false;
		}
		return generic.methods().values().stream().anyMatch(method -> {
			Specializer first = method.specializers().isEmpty() ? Specializer.DEFAULT : method.specializers().get(0);
			return first.kind() == SpecializerKind.CLASS && info.ancestors().contains(first.name());
		});
	}

	/**
	 * Whether {@code make-instance} of the class fills its slots through the slot-access
	 * protocol: the protocol is on and the class was defined with a {@code :metaclass}
	 * (its instances are the ones the protocol routes). A class whose precedence list
	 * carries {@code :default-initargs} keeps the constructor fill, which honors them
	 * (through the effective initforms) where the protocol's fill would not.
	 * @param className the class name as spelled
	 * @return true for such a class
	 */
	public boolean fillsThroughSlotProtocol(String className) {
		if (!this.slotProtocol || !this.metaclassedClasses.contains(normalize(className))) {
			return false;
		}
		ClassInfo info = findClass(className);
		// A generic function's or method's slots are the system's, which initialize to
		// nil with no initfunction to say so.
		return info != null && info.ancestors().stream().noneMatch(this.defaultInitargClasses::contains)
				&& !info.ancestors().contains("GENERIC-FUNCTION") && !info.ancestors().contains("METHOD");
	}

	/**
	 * The internal type of an instance of a class made at RUN time
	 * ({@link #enableDynamicClasses}): slot 0 holds the class metaobject, slot 1 its
	 * class precedence list.
	 */
	public static final String DYNAMIC_OBJECT_CLASS_NAME = "%DYNAMIC-OBJECT%";

	/** The instance tag of {@link #DYNAMIC_OBJECT_CLASS_NAME}. */
	public static final String DYNAMIC_OBJECT_TAG = LispLayout.CLASS_TAG_PREFIX + DYNAMIC_OBJECT_CLASS_NAME;

	private boolean dynamicClasses;

	/**
	 * Whether instances of classes made at run time can exist in this registry's program:
	 * every dispatcher with a class specializer then carries the arm that orders methods
	 * by such an instance's class precedence list, and {@code class-of} reads its class
	 * out of it.
	 * @return {@code true} once {@link #enableDynamicClasses} ran
	 */
	public boolean dynamicClasses() {
		return this.dynamicClasses;
	}

	/**
	 * Registers the {@link #DYNAMIC_OBJECT_TAG} layout -- a LAYOUT ONLY, never a class,
	 * like the unbound marker -- and turns {@link #dynamicClasses} on. Lazy, so a program
	 * that makes no class at run time keeps its layout set (WASM bakes every layout).
	 */
	public void enableDynamicClasses() {
		if (this.dynamicClasses) {
			return;
		}
		this.dynamicClasses = true;
		this.layoutsByTag.put(DYNAMIC_OBJECT_TAG, LispLayout.ofClass(DYNAMIC_OBJECT_CLASS_NAME,
				List.of("CLASS", "PRECEDENCE-LIST"), List.of(LispNil.INSTANCE, LispNil.INSTANCE)));
	}

	/**
	 * Whether a value is a class metaobject made at RUN time: a class metaobject that
	 * {@code find-class} does not answer for its own name (an anonymous one included).
	 * @param value the value
	 * @return {@code true} for such a metaobject
	 */
	public boolean isDynamicClassMetaobject(LispVal value) {
		if (!(value instanceof LispInstance inst) || inst.slotCount() == 0 || !isClassMetaobject(inst)) {
			return false;
		}
		LispInstance registered = null;
		if (inst.slot(0) instanceof LispSymbol name) {
			registered = classMetaobject(name.name());
			if (registered == null) {
				registered = builtinClassMetaobject(name.name());
			}
		}
		else if (inst.slot(0) instanceof LispTrue) {
			registered = builtinClassMetaobject("T");
		}
		return registered != inst;
	}

	/** The names registered by {@code seedClass} (see {@link #isSeededClass}). */
	private final Set<String> seededClassNames = new java.util.HashSet<>();

	/**
	 * Class name (normalized) to the extra parent types beyond the first -- the lite
	 * multiple-inheritance support of {@code define-condition}: the first parent provides
	 * the slot layout (single inheritance), the remaining parents contribute to the
	 * ancestor set only, so {@code typep}/{@code handler-case} match through them while
	 * their slots are not inherited. Merged into the class's ancestors when the class is
	 * registered.
	 */
	private final Map<String, Set<String>> pendingExtraAncestors = new LinkedHashMap<>();

	/**
	 * Struct type name (normalized) to its instance tag ({@code %struct-<name>}) --
	 * registered by {@code defstruct} so a struct name is usable as a {@code defmethod}
	 * parameter specializer (the dispatcher tests the tag, exactly like the struct
	 * predicate).
	 */
	private final Map<String, String> structTags = new LinkedHashMap<>();

	/**
	 * Struct type name (normalized) to its ancestor set (the struct itself and its
	 * {@code :include} chain, all normalized) -- the struct-side twin of
	 * {@link ClassInfo#ancestors()}, giving {@code typep}/predicates/method dispatch the
	 * statically-known descendant tag sets.
	 */
	private final Map<String, Set<String>> structAncestors = new LinkedHashMap<>();

	/**
	 * {@link #descendantStructTags}'s answers by the name as spelled, cleared by every
	 * {@link #registerStruct} and {@link #registerClass} ({@link #structKey} reads the
	 * classes). Each answer is a scan of every struct, and the generic dispatch builders
	 * ask it once per (method specializer, dispatch branch) pair -- 15% of mito's JVM
	 * compile before it was kept.
	 */
	private final Map<String, List<String>> descendantStructTagsMemo = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Struct type name (normalized) to its DIRECT {@code :include} parent (normalized as
	 * given) -- what {@link #classMetaobject} builds the direct-superclass list of a
	 * struct metaobject from ({@link #structAncestors} flattens the chain into a set and
	 * cannot answer "the parent").
	 */
	private final Map<String, String> structParents = new LinkedHashMap<>();

	/**
	 * Struct type name (normalized) to the name of the predicate defun {@code defstruct}
	 * generated for it (absent when {@code (:predicate nil)} suppressed it, or for a
	 * {@code :type} struct, which has no instance tag to test). A predicate bakes the
	 * descendant tag set known when it is generated, so a struct defined LATER with
	 * {@code (:include this)} has to make the predicate regenerate -- this is the map
	 * that says which defuns to rebuild (see
	 * {@code LispMacroExpander.structPredicateDefun}).
	 */
	private final Map<String, String> structPredicates = new LinkedHashMap<>();

	/**
	 * Instance tag ({@code %struct-<name>} / {@code %class-<name>}) to the interned
	 * {@link LispLayout} of that type. Keyed by tag rather than by type name because the
	 * two prefixes keep struct and class entries apart even when a program defines a
	 * struct and a class of the same name; the name-based lookups
	 * ({@link #findStructLayout} / {@link #findClassLayout}) route through the existing
	 * {@link #findStructTag} / {@link #findClass} resolution rules first, so package
	 * spellings resolve exactly as they do everywhere else.
	 */
	private final Map<String, LispLayout> layoutsByTag = new LinkedHashMap<>();

	/**
	 * The classes a {@code change-class} in the program turns instances into
	 * (normalized). Their ancestors reserve their slot count; see
	 * {@link #applyChangeClassCapacities()}.
	 */
	private final Set<String> changeClassTargets = new java.util.LinkedHashSet<>();

	/**
	 * The generated method-body defun names ({@code %<generic>--m<i>}) whose defun sits
	 * inside a top-level {@code let} body rather than at top level -- the
	 * closure-over-let method idiom
	 * ({@code expandLetNestedDefmethods}/{@code rewriteNestedDefmethods}). Such a defun
	 * compiles to a {@code (setq name (lambda ...))} global-closure assignment, so its
	 * function value exists only once the {@code defmethod} FORM executes; a dispatcher
	 * branch calling it before that must be skipped (falling through to the default or to
	 * no-applicable-method), or the call reads an unassigned global. Only the compile
	 * paths mark entries: the interpreter evaluates the defmethod (registering the
	 * method) exactly when the form runs, so its dispatchers never reference an
	 * unassigned body.
	 */
	private final Set<String> nestedMethodFunctions = new java.util.LinkedHashSet<>();

	/**
	 * Class name (normalized) to its memoized class METAOBJECT -- the
	 * {@code standard-class} instance {@code find-class} answers with. Built on demand
	 * ({@link #classMetaobject}) so programs that never touch the MOP surface allocate
	 * none; invalidated when the class is re-registered. A metaclass'd class's metaobject
	 * (an instance of the user metaclass) will be registered here by the class-definition
	 * protocol driver instead of being built generically.
	 */
	private final Map<String, LispInstance> classMetaobjects = new LinkedHashMap<>();

	/**
	 * User {@code deftype} name (normalized) to its expansion -- the literal type
	 * specifier a zero-parameter {@code (deftype name () 'spec)} defines. Consulted by
	 * the shared type-test builder so {@code typep}/{@code typecase} resolve a
	 * user-defined type name (single- and double-colon spellings match, like
	 * {@link #findStructTag}).
	 */
	private final Map<String, LispVal> deftypes = new LinkedHashMap<>();

	/**
	 * The expansions of COMPOUND specifiers headed by a parameterized {@code deftype}
	 * ({@code (proper-list integer)}), keyed by the specifier's printed spelling. The
	 * compile paths fill it from the {@code %deftype-instance} declamations the macro
	 * pass emits (it is the pass with an evaluator); the interpreter expands through
	 * {@link #deftypeExpander} instead.
	 */
	private final Map<String, DeftypeInstance> deftypeInstances = new LinkedHashMap<>();

	/**
	 * The evaluator that runs a parameterized {@code deftype}'s body over a compound
	 * specifier's arguments, or null where none is installed (the compile paths).
	 */
	private java.util.function.@Nullable Function<LispCons, @Nullable LispVal> deftypeExpander;

	/**
	 * {@code defconstant} name (normalized) to the LITERAL value it was defined with --
	 * the table an {@code (eql +name+)} parameter specializer resolves through. CLHS
	 * 7.6.2 evaluates the form of an eql specializer when the method is defined, and a
	 * constant name is the one such form that carries a value a static walk can know.
	 * Populated in DEFINITION ORDER (the compile path's top-level walk, the interpreter's
	 * {@code defconstant} evaluation), so -- as CL requires -- only a constant defined
	 * BEFORE the {@code defmethod} resolves; a name with no entry keeps the historic
	 * lenience of standing for the symbol itself.
	 *
	 * <p>
	 * Constants are not a CLOS concept. The table lives here because this registry is the
	 * one definition-scoped object the specializer parse already carries (one per
	 * evaluator, one per compilation) and the {@code macro} package sits BELOW
	 * {@code eval}, so there is no macro-time evaluator to ask instead.
	 */
	private final Map<String, LispVal> constants = new LinkedHashMap<>();

	/**
	 * Every name a {@code defconstant} anywhere in a compiled program defines, as spelled
	 * there (canonical once packages are resolved): what {@code constantp} answers t for
	 * at run time on the compile paths ({@code LispMacroExpander.expandConstantp}).
	 * Scope-blind and order-free, unlike {@link #constants}: the value may be computed,
	 * and a name defined after a probe still names a constant when the probe runs later.
	 */
	private final Set<String> constantNames = new java.util.LinkedHashSet<>();

	/**
	 * {@code defstruct} accessor name (normalized) to the declared {@code :type} of the
	 * slot it reads, and struct name (normalized) to its per-slot {@code :type} table
	 * (what an {@code :include} child inherits). Registered by
	 * {@code LispMacroExpander.expandDefstruct}; consulted by declaration-driven array
	 * emission ({@code am.ik.rontolisp.compiler.DeclaredArrayTypes}). Purely advisory
	 * side tables -- the {@link LispLayout} itself stays type-free.
	 */
	private final Map<String, LispVal> structAccessorTypes = new LinkedHashMap<>();

	private final Map<String, String> structAccessorOwners = new LinkedHashMap<>();

	private final Map<String, Map<String, LispVal>> structSlotTypes = new LinkedHashMap<>();

	/** Struct name to the base names of its {@code :read-only} slots. */
	private final Map<String, Set<String>> structReadOnlySlots = new LinkedHashMap<>();

	/**
	 * Alias name (normalized) to the CANONICAL name of the class it names -- what
	 * {@code (setf (find-class 'alias) (find-class 'target))} registers. The target is
	 * resolved to its canonical spelling at registration time, so the map is one level
	 * deep by construction and {@link #findClass} needs no chain walk. An alias is a
	 * second NAME for one class, never a second class: the metaobject, the instance tag,
	 * the ancestor set and the layout all stay the target's, so
	 * {@code (eq (find-class 'alias) (find-class 'target))} holds and
	 * {@code make-instance}/{@code typep}/{@code handler-case} through the alias behave
	 * exactly as through the target.
	 */
	private final Map<String, String> classAliases = new LinkedHashMap<>();

	/**
	 * {@link #uniqueByMember}'s answers, keyed by member name: the one package-qualified
	 * class of that member, or an empty optional when two packages define it. Built on
	 * first use and dropped by every {@link #registerClass}; without it each miss of
	 * {@link #findClass} scanned every class, which the runtime-subtypep table (a lookup
	 * per pair of its universe) turned into minutes on a program the size of mito. An
	 * index is never mutated once published, so interpreter threads that only LOOK UP
	 * classes stay as safe as they were over the plain maps.
	 */
	private volatile @Nullable Map<String, java.util.Optional<ClassInfo>> classesByMember;

	/** {@link #classesByMember}'s twin over the alias table, dropped by every alias. */
	private volatile @Nullable Map<String, java.util.Optional<String>> aliasesByMember;

	/**
	 * Whether the compile paths have already emitted the class metaobject table from this
	 * registry (see {@code LispMacroExpander.classMetaTableForms}). A registration after
	 * that point can no longer reach the emitted program, so {@link #registerClassAlias}
	 * refuses instead of registering an alias the compiled program would never see.
	 */
	private boolean classMetaTableEmitted = false;

	/**
	 * Bumped by every registration that can move a class or struct's descendant set, or
	 * which generic is {@code print-object}: the validity stamp of
	 * {@link #printObjectTags}'s memo. {@link #classes()} and {@link #generics()} hand
	 * out the live maps, but nothing writes through them -- every write goes through
	 * {@link #registerClass}, {@link #registerStruct} or {@link #registerGeneric}.
	 */
	private int hierarchyVersion;

	/** The last {@link #printObjectTags} answer and the state it was computed from. */
	private @Nullable PrintObjectTags printObjectTagsMemo;

	private record PrintObjectTags(int hierarchyVersion, @Nullable GenericInfo generic, int methodCount,
			boolean dynamicClasses, List<String> tags) {
	}

	/**
	 * The classes by normalized name, in definition order.
	 * @return the class registry
	 */
	public Map<String, ClassInfo> classes() {
		return java.util.Collections.unmodifiableMap(this.classes);
	}

	/**
	 * The registered {@code (setf find-class)} aliases: alias name (normalized) to the
	 * canonical class name it resolves to. The compile paths add each alias as an extra
	 * SPELLING of its target's metaobject-table entry, so a runtime
	 * {@code (find-class 'alias)} answers the same object there as in the interpreter.
	 * @return the alias table, possibly empty
	 */
	public Map<String, String> classAliases() {
		return java.util.Collections.unmodifiableMap(this.classAliases);
	}

	/**
	 * Registers {@code alias} as an additional name of an already registered class -- the
	 * {@code (setf (find-class 'alias) (find-class 'target))} idiom cl-dbi's
	 * {@code defclass/a} uses to give every condition a bracket-spelled twin.
	 * @param alias the new name
	 * @param target the name of the class to alias; must already be registered
	 * @throws IllegalArgumentException when the target is unknown, when the alias already
	 * names a class of its own, or when the compile path has already emitted its
	 * metaobject table (a non-top-level registration)
	 */
	public void registerClassAlias(String alias, String target) {
		ClassInfo info = findClass(target);
		if (info == null) {
			throw new IllegalArgumentException("(setf (find-class '" + alias + ")): there is no class named " + target);
		}
		String key = normalize(alias);
		String canonical = normalize(info.name());
		if (canonical.equals(this.classAliases.get(key))) {
			return;
		}
		if (this.classes.containsKey(key)) {
			// An exact class name beats the alias table in findClass, so registering one
			// would be a silent no-op. Rebinding an existing class NAME is a different
			// (runtime-class) feature, not the aliasing this supports.
			throw new IllegalArgumentException("(setf (find-class '" + alias
					+ ")): a class of that name is already defined -- only naming an EXISTING class under a NEW "
					+ "name is supported");
		}
		if (this.classMetaTableEmitted) {
			throw new IllegalArgumentException("(setf (find-class '" + alias
					+ ")) is only supported at top level on the compiled backends: the class table is built at "
					+ "compile time, so an alias registered from inside a function body would not exist at run time");
		}
		this.classAliases.put(key, canonical);
		this.aliasesByMember = null;
	}

	/**
	 * Marks the class metaobject table as emitted; see {@link #classMetaTableEmitted}.
	 */
	public void markClassMetaTableEmitted() {
		this.classMetaTableEmitted = true;
	}

	/**
	 * Registers a zero-parameter {@code deftype} expansion so its name resolves as a type
	 * specifier in {@code typep}/{@code typecase}/{@code check-type}.
	 * @param name the type name as spelled in the deftype
	 * @param expansion the literal type specifier the name expands to
	 */
	public void registerDeftype(String name, LispVal expansion) {
		this.deftypes.put(normalize(name), expansion);
	}

	/**
	 * Records the value of a {@code defconstant} so an {@code (eql name)} parameter
	 * specializer naming it dispatches on the value, not on the symbol.
	 * @param name the constant name as spelled in the defconstant
	 * @param value the literal value
	 */
	public void registerConstant(String name, LispVal value) {
		this.constants.put(normalize(name), value);
	}

	/**
	 * The recorded value of a {@code defconstant} name, or null when the name names no
	 * constant this registry has seen. Single- and double-colon spellings match, like
	 * {@link #findDeftype}.
	 * @param name the constant name as spelled at the use site
	 * @return the constant's literal value, or null
	 */
	@Nullable public LispVal findConstant(String name) {
		LispVal exact = this.constants.get(normalize(name));
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			return this.constants.get(qn.member());
		}
		return null;
	}

	/**
	 * Records the names the program's {@code defconstant} forms define; see
	 * {@link #constantNames}.
	 * @param names the names as spelled in the program
	 */
	public void registerConstantNames(Collection<String> names) {
		this.constantNames.addAll(names);
	}

	/**
	 * The names the program's {@code defconstant} forms define, in first-seen order.
	 * @return the names, read-only
	 */
	public Set<String> constantNames() {
		return java.util.Collections.unmodifiableSet(this.constantNames);
	}

	/**
	 * The registered expansion of a user {@code deftype} name, or null. Single- and
	 * double-colon spellings match, like {@link #findStructTag}.
	 * @param name the type name as spelled
	 * @return the literal type specifier the name expands to, or null
	 */
	@Nullable public LispVal findDeftype(String name) {
		LispVal exact = this.deftypes.get(normalize(name));
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			return this.deftypes.get(qn.member());
		}
		return null;
	}

	/**
	 * The registered {@code deftype} names (normalized), in registration order -- the
	 * user-type extension of the runtime-subtypep universe.
	 * @return the deftype names
	 */
	public java.util.Set<String> deftypeNames() {
		return java.util.Collections.unmodifiableSet(this.deftypes.keySet());
	}

	/**
	 * A compound specifier headed by a parameterized {@code deftype}, with the type
	 * specifier the deftype's body computed for its arguments.
	 *
	 * @param specifier the compound specifier, as the program spells it
	 * @param expansion the type specifier it denotes
	 */
	public record DeftypeInstance(LispCons specifier, LispVal expansion) {
	}

	/**
	 * Records the expansion of one compound specifier headed by a parameterized
	 * {@code deftype}.
	 * @param specifier the compound specifier
	 * @param expansion the type specifier the deftype's body computed for it
	 */
	public void registerDeftypeInstance(LispCons specifier, LispVal expansion) {
		this.deftypeInstances.put(specifier.print(), new DeftypeInstance(specifier, expansion));
	}

	/**
	 * Installs the evaluator that expands a compound specifier headed by a parameterized
	 * {@code deftype} (the interpreter's, which runs the body over the arguments).
	 * @param expander the specifier-to-expansion function; it answers null for a head
	 * naming no parameterized deftype or a body that fails
	 */
	public void setDeftypeExpander(java.util.function.Function<LispCons, @Nullable LispVal> expander) {
		this.deftypeExpander = expander;
	}

	/**
	 * Whether a compound specifier can expand through an installed evaluator, which is
	 * what a run-time {@code typep} designator needs on the interpreter.
	 * @return true when an expander is installed
	 */
	public boolean expandsDeftypeSpecifiers() {
		return this.deftypeExpander != null;
	}

	/**
	 * The type specifier a compound specifier headed by a parameterized {@code deftype}
	 * denotes: a recorded instance first, else the installed expander.
	 * @param specifier the compound specifier
	 * @return its expansion, or null when neither knows it
	 */
	@Nullable public LispVal expandDeftypeSpecifier(LispCons specifier) {
		DeftypeInstance instance = this.deftypeInstances.get(specifier.print());
		if (instance != null) {
			return instance.expansion();
		}
		return this.deftypeExpander == null ? null : this.deftypeExpander.apply(specifier);
	}

	/**
	 * The recorded compound-specifier instances, in registration order -- the entries a
	 * run-time {@code typep} designator resolves through on the compile paths.
	 * @return the instances
	 */
	public java.util.Collection<DeftypeInstance> deftypeInstances() {
		return java.util.Collections.unmodifiableCollection(this.deftypeInstances.values());
	}

	/**
	 * Registers a {@code defstruct} slot's declared {@code :type} under its accessor
	 * name, and the same specifier in the per-struct slot-type table (which is what an
	 * {@code :include} child reads to inherit it). The specifier is stored as written --
	 * whether it proves a representation is the consumer's judgment
	 * ({@code am.ik.rontolisp.compiler.DeclaredArrayTypes}); a slot with no {@code :type}
	 * registers nothing.
	 * @param structName the struct name as spelled in the defstruct
	 * @param slotBaseName the package-stripped slot name
	 * @param accessorName the generated accessor's name, as the expansion spells it
	 * @param typeSpec the slot's declared {@code :type} specifier
	 */
	public void registerStructSlotType(String structName, String slotBaseName, String accessorName, LispVal typeSpec) {
		this.structSlotTypes.computeIfAbsent(normalize(structName), k -> new LinkedHashMap<>())
			.put(slotBaseName, typeSpec);
		this.structAccessorTypes.put(normalize(accessorName), typeSpec);
	}

	/**
	 * The declared {@code :type} specifiers of a struct's slots, by package-stripped slot
	 * name -- what an {@code :include} child inherits. Empty for a struct that declares
	 * none.
	 * @param structName the struct name as spelled
	 * @return slot base name to type specifier, possibly empty
	 */
	public Map<String, LispVal> structSlotTypes(String structName) {
		Map<String, LispVal> exact = this.structSlotTypes.get(normalize(structName));
		if (exact == null && PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn) {
			exact = this.structSlotTypes.get(qn.member());
		}
		return exact == null ? Map.of() : exact;
	}

	/**
	 * Records which of a {@code defstruct}'s slots are {@code :read-only} -- what an
	 * {@code :include} child inherits.
	 * @param structName the struct name as spelled in the defstruct
	 * @param slotBaseNames the package-stripped names of its read-only slots
	 */
	public void registerStructReadOnlySlots(String structName, Set<String> slotBaseNames) {
		this.structReadOnlySlots.put(normalize(structName), Set.copyOf(slotBaseNames));
	}

	/**
	 * The package-stripped names of a struct's {@code :read-only} slots, inherited ones
	 * included. Empty for a struct with none, or one never registered.
	 * @param structName the struct name as spelled
	 * @return the read-only slot base names
	 */
	public Set<String> structReadOnlySlots(String structName) {
		Set<String> exact = this.structReadOnlySlots.get(normalize(structName));
		if (exact == null && PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn) {
			exact = this.structReadOnlySlots.get(qn.member());
		}
		return exact == null ? Set.of() : exact;
	}

	/**
	 * Registers the struct a {@code defstruct} slot accessor belongs to -- the type its
	 * {@code setf} expansion names when the object is not an instance.
	 * @param accessorName the generated accessor's name, as the expansion spells it
	 * @param structName the struct name as spelled in the defstruct
	 */
	public void registerStructAccessor(String accessorName, String structName) {
		this.structAccessorOwners.put(normalize(accessorName), structName);
	}

	/**
	 * The struct a {@code defstruct} slot accessor belongs to, as spelled in its
	 * defstruct, or null. Single- and double-colon spellings match, like
	 * {@link #structAccessorType}.
	 * @param accessorName the accessor name as spelled at the call site
	 * @return the struct name, or null
	 */
	@Nullable public String structOfAccessor(String accessorName) {
		String exact = this.structAccessorOwners.get(normalize(accessorName));
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(accessorName) instanceof PackageRegistry.QualifiedName qn) {
			return this.structAccessorOwners.get(qn.member());
		}
		return null;
	}

	/**
	 * The declared {@code :type} specifier of the slot a {@code defstruct} accessor
	 * reads, or null. Single- and double-colon spellings match, like
	 * {@link #findDeftype}.
	 * @param accessorName the accessor name as spelled at the call site
	 * @return the slot's type specifier, or null
	 */
	@Nullable public LispVal structAccessorType(String accessorName) {
		LispVal exact = this.structAccessorTypes.get(normalize(accessorName));
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(accessorName) instanceof PackageRegistry.QualifiedName qn) {
			return this.structAccessorTypes.get(qn.member());
		}
		return null;
	}

	/**
	 * Registers a {@code defstruct} type so its name is usable as a method specializer
	 * and its {@link LispLayout} is available to instance construction, printing and
	 * {@code #S(...)} reading.
	 * @param structName the struct name as spelled in the defstruct
	 * @param slotBaseNames the package-stripped slot names, in declaration order
	 * @param initforms the slot initforms, in the same order
	 */
	public void registerStruct(String structName, List<String> slotBaseNames, List<LispVal> initforms) {
		registerStruct(structName, null, slotBaseNames, initforms);
	}

	/**
	 * Registers a {@code defstruct} type with an {@code :include} parent. The slot lists
	 * must already carry the parent's slots first (the caller prepends them), so the
	 * layout is complete; this overload additionally records the ancestor chain for
	 * {@code typep}/predicate/dispatch descendant enumeration.
	 * @param structName the struct name as spelled in the defstruct
	 * @param parentName the {@code :include} parent struct name, or null for none
	 * @param slotBaseNames the package-stripped slot names (parent slots first)
	 * @param initforms the slot initforms, in the same order
	 */
	public void registerStruct(String structName, @Nullable String parentName, List<String> slotBaseNames,
			List<LispVal> initforms) {
		this.hierarchyVersion++;
		String key = normalize(structName);
		noteTypeDefined(key);
		this.structTags.put(key, LispLayout.STRUCT_TAG_PREFIX + structName);
		// A redefinition invalidates the memoized metaobject, like registerClass.
		this.classMetaobjects.remove(key);
		if (parentName != null) {
			this.structParents.put(key, parentName);
		}
		Set<String> ancestors = new java.util.LinkedHashSet<>();
		if (parentName != null) {
			Set<String> parentAncestors = this.structAncestors.get(normalize(parentName));
			if (parentAncestors != null) {
				ancestors.addAll(parentAncestors);
			}
			else {
				ancestors.add(normalize(parentName));
			}
		}
		ancestors.add(key);
		this.structAncestors.put(key, Set.copyOf(ancestors));
		this.descendantStructTagsMemo.clear();
		LispLayout layout = LispLayout.ofStruct(structName, slotBaseNames, initforms);
		this.layoutsByTag.put(layout.tag(), layout);
	}

	/**
	 * Records the predicate defun {@code defstruct} generated for a struct type, so it
	 * can be regenerated once a later {@code (:include this)} struct widens the
	 * descendant tag set.
	 * @param structName the struct name as spelled in the defstruct
	 * @param predicateName the generated (or {@code (:predicate name)}-given) predicate
	 * name
	 */
	public void registerStructPredicate(String structName, String predicateName) {
		this.structPredicates.put(normalize(structName), predicateName);
	}

	/**
	 * The recorded struct predicates: normalized struct name to predicate defun name, in
	 * definition order.
	 * @return the struct predicate registry
	 */
	public Map<String, String> structPredicates() {
		return java.util.Collections.unmodifiableMap(this.structPredicates);
	}

	/**
	 * The ancestor set of a registered struct type -- the struct itself plus its
	 * {@code :include} chain, all normalized -- or an empty set when unknown.
	 * @param structName the struct name as spelled
	 * @return the ancestor names
	 */
	public Set<String> structAncestorNames(String structName) {
		Set<String> ancestors = structAncestorsOf(structName);
		return ancestors == null ? Set.of() : ancestors;
	}

	/**
	 * The tag symbols ({@code %struct-<name>}) of the given struct type and all its
	 * registered {@code :include} descendants, in definition order -- the struct-side
	 * twin of {@link #descendantTags}. A name with no registered struct yields an empty
	 * list.
	 * @param structName the struct name as spelled
	 * @return the descendant tags
	 */
	public List<String> descendantStructTags(String structName) {
		List<String> memo = this.descendantStructTagsMemo.get(structName);
		if (memo == null) {
			memo = java.util.Collections.unmodifiableList(computeDescendantStructTags(structName));
			this.descendantStructTagsMemo.putIfAbsent(structName, memo);
		}
		return memo;
	}

	private List<String> computeDescendantStructTags(String structName) {
		String key = structKey(structName);
		List<String> tags = new java.util.ArrayList<>();
		if (key == null) {
			return tags;
		}
		for (Map.Entry<String, Set<String>> entry : this.structAncestors.entrySet()) {
			if (entry.getValue().contains(key)) {
				tags.add(this.structTags.get(entry.getKey()));
			}
		}
		return tags;
	}

	/**
	 * The ancestor-set size of a registered struct type (the struct itself plus its
	 * {@code :include} chain), or 1 when unknown -- the dispatch-specificity input for a
	 * struct specializer (deeper included structs dispatch first).
	 * @param structName the struct name as spelled
	 * @return the ancestor count
	 */
	public int structAncestorCount(String structName) {
		Set<String> ancestors = structAncestorsOf(structName);
		return ancestors == null ? 1 : ancestors.size();
	}

	/**
	 * The instance tag of a registered struct type, or null. Single- and double-colon
	 * spellings match, like {@link #findClass}.
	 * @param name the specializer name as spelled
	 * @return the {@code %struct-<name>} tag, or null when no such struct is registered
	 */
	@Nullable public String findStructTag(String name) {
		String key = structKey(name);
		return key == null ? null : this.structTags.get(key);
	}

	@Nullable private Set<String> structAncestorsOf(String name) {
		String key = structKey(name);
		return key == null ? null : this.structAncestors.get(key);
	}

	// The registry key of the struct a name designates: its exact key, else the qualified
	// spelling's member when a struct holds that plain key and no class holds the exact
	// one. A class of exactly this name is the type the name designates, like the struct
	// lookupClass gives way to.
	@Nullable private String structKey(String name) {
		String key = normalize(name);
		if (this.structTags.containsKey(key)) {
			return key;
		}
		if (!this.classes.containsKey(key)
				&& PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn
				&& this.structTags.containsKey(qn.member())) {
			return qn.member();
		}
		return null;
	}

	/**
	 * One entry of a type's {@code %class-slot-defs} answer.
	 *
	 * @param name the slot base name
	 * @param type the declared type name ({@code T} when none was declared)
	 */
	public record SlotDef(String name, String type) {
	}

	/**
	 * The slot definitions of a type DESIGNATOR: an instance tag ({@code %class-<name>}
	 * or {@code %struct-<name>}, what {@code class-of} yields) or the plain type name. A
	 * {@code defstruct} answers too -- its slots have no declared type, so every entry
	 * reads {@code T} -- which is what lets a serializer walk a struct's slots the same
	 * way it walks a CLOS instance's.
	 * @param designator the instance tag or plain type name
	 * @return the slot definitions in layout order, or null when the designator names
	 * neither a registered class nor a registered struct
	 */
	@Nullable public List<SlotDef> slotDefs(String designator) {
		String name = LispLayout.printNameOfTag(designator);
		boolean structOnly = designator.startsWith(LispLayout.STRUCT_TAG_PREFIX);
		if (name == null) {
			name = designator;
		}
		if (!structOnly) {
			ClassInfo info = findClass(name);
			if (info != null) {
				return info.slots().stream().map(s -> new SlotDef(s.baseName(), s.type())).toList();
			}
			if (designator.startsWith(LispLayout.CLASS_TAG_PREFIX)) {
				return null;
			}
		}
		LispLayout layout = findStructLayout(name);
		return layout == null ? null : layout.slotNames().stream().map(s -> new SlotDef(s, "T")).toList();
	}

	/**
	 * The registered condition {@code :report} forms by normalized class name. The report
	 * ASTs live only here after {@code define-condition} is rewritten out of the program,
	 * so a scan for first-class function references (e.g. a report lambda applying
	 * {@code #'format}) must include them.
	 * @return the condition report registry
	 */
	public Map<String, LispVal> conditionReports() {
		return this.conditionReports;
	}

	/**
	 * Whether this compilation renders a printed condition through its {@code :report},
	 * i.e. whether the generated {@code %condition-report-str} renderer is available. It
	 * is a PROGRAM fact, not a class one -- a program that can never build a condition
	 * keeps every printing operator (and every signal message) in the shape it always had
	 * -- but it lives here because the registry is the one compile-scoped object already
	 * threaded through every expansion and both backends' {@code Ctx}. Set by
	 * {@code LispMacroExpander.expandTopLevelDefinitions} on the compile path and by the
	 * interpreter when it loads the same generated defuns.
	 * @return whether the condition-report renderer is in the artifact
	 */
	public boolean routesConditionReports() {
		return this.routesConditionReports;
	}

	/**
	 * Whether the printing operators ({@code princ}, {@code format ~A}, ...) render a
	 * condition VALUE through the {@code %condition-report-str} renderer. The same answer
	 * as {@link #routesConditionReports()} except on a wasm-GC module whose renderer
	 * exists only for the entry function's uncaught report ({@code --report-locations}
	 * outside exception-handling mode): no program code can hold a condition there, so no
	 * printing operator can be handed one.
	 * @return whether printing a condition goes through its report
	 */
	public boolean printsConditionReports() {
		return this.printsConditionReports;
	}

	/**
	 * Records that the generated {@code %condition-report-str} renderer is available, and
	 * that the printing operators route through it; see
	 * {@link #routesConditionReports()}.
	 * @param routes whether the renderer is in the artifact
	 */
	public void setRoutesConditionReports(boolean routes) {
		setRoutesConditionReports(routes, routes);
	}

	/**
	 * Records the two halves of {@link #setRoutesConditionReports(boolean)} apart.
	 * @param routes whether the renderer is in the artifact
	 * @param prints whether the printing operators route through it (implies
	 * {@code routes})
	 */
	public void setRoutesConditionReports(boolean routes, boolean prints) {
		this.routesConditionReports = routes;
		this.printsConditionReports = routes && prints;
	}

	/**
	 * The generic functions by normalized name, in definition order.
	 * @return the generic-function registry
	 */
	public Map<String, GenericInfo> generics() {
		return this.generics;
	}

	/**
	 * The class names carrying a {@code :before} method (through their first parameter
	 * specializer) on an instance-initialization generic ({@code initialize-instance} or
	 * {@code shared-initialize}). In CL such a {@code :before} runs BEFORE the initargs
	 * fill the slots; the static model's constructor fills first, so
	 * {@code make-instance} (literal and {@code %mop-make-instance} alike) re-fills the
	 * declared-initarg slots of these classes -- and only these -- after the
	 * initialization generic returns, restoring the observable CL order (postmodern's
	 * {@code dao-class} resets its {@code direct-keys} slot that way and relies on the
	 * {@code :keys} class option surviving).
	 * @return the specialized class names, possibly empty
	 */
	public Set<String> initRefillTargets() {
		Set<String> targets = new java.util.LinkedHashSet<>();
		for (GenericInfo generic : this.generics.values()) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(generic.name());
			String plain = qn == null ? generic.name() : qn.member();
			if (!"INITIALIZE-INSTANCE".equals(plain) && !"SHARED-INITIALIZE".equals(plain)) {
				continue;
			}
			for (MethodInfo method : generic.methods().values()) {
				if (":BEFORE".equals(method.qualifier()) && !method.specializers().isEmpty()
						&& method.specializers().get(0).kind() == SpecializerKind.CLASS) {
					targets.add(java.util.Objects.requireNonNull(method.specializers().get(0).name()));
				}
			}
		}
		return targets;
	}

	/**
	 * Whether {@code make-instance} of the class must re-fill its declared-initarg slots
	 * after the initialization generic returns -- see {@link #initRefillTargets()}.
	 * @param info the class
	 * @return true when a {@code :before} initialization method specializes the class or
	 * one of its ancestors
	 */
	public boolean needsInitRefill(ClassInfo info) {
		Set<String> targets = initRefillTargets();
		if (targets.isEmpty()) {
			return false;
		}
		return info.ancestors().stream().anyMatch(targets::contains);
	}

	/**
	 * Looks up a class by name. Single- and double-colon spellings match; an unqualified
	 * name additionally matches a UNIQUELY-named class of any package, because quoted
	 * class names ({@code (make-instance 'dog)}) are not package-resolved while
	 * {@code defclass} names are (two packages defining the same class name make the bare
	 * spelling unresolvable -- qualify it). A miss consults the
	 * {@link #classMissLoader(java.util.function.Predicate) class-miss loader} before
	 * answering null.
	 * @param name the class name as spelled
	 * @return the class, or null
	 */
	@Nullable public ClassInfo findClass(String name) {
		ClassInfo found = lookupClass(name);
		java.util.function.Predicate<String> loader = this.classMissLoader;
		if (found == null && loader != null && loader.test(name)) {
			// One repeat, never a recursion: a lookup made while the library loads
			// misses through the loader again and answers null by itself.
			found = lookupClass(name);
		}
		return found;
	}

	@Nullable private ClassInfo lookupClass(String name) {
		ClassInfo exact = this.classes.get(normalize(name));
		if (exact != null) {
			return exact;
		}
		// A defstruct of exactly this name is the type the name designates: the tolerant
		// fallbacks below are for a name nothing registered answers, and would hand a
		// struct name another package's class of the same member.
		String key = normalize(name);
		if (this.structTags.containsKey(key) && !this.classAliases.containsKey(key)) {
			return null;
		}
		// A (setf find-class) alias resolves to its target's class -- checked before the
		// package-tolerant fallbacks below, so an alias never loses to an unrelated
		// same-member class in another package.
		ClassInfo aliased = aliasTarget(name);
		if (aliased != null) {
			return aliased;
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			// A qualified spelling also matches a class registered under the plain name:
			// the built-in condition hierarchy is package-less, while the resolver
			// qualifies non-CL-symbol names (e.g. pkg::program-error) inside a package.
			ClassInfo plain = this.classes.get(qn.member());
			if (plain != null) {
				return plain;
			}
			// ... and, failing that, a class of the same MEMBER name registered in
			// another package, when that match is unique. A condition type named in one
			// package but defined in a sibling (cl-postgres names
			// cl-postgres-error's protocol-violation unqualified) would otherwise have
			// no layout at all, which is a COMPILE error on the JVM/WASM backends while
			// the interpreter resolves it at signal time.
			return uniqueByMember(qn.member());
		}
		return uniqueByMember(name);
	}

	// The class an alias names, with the same package tolerance findClass gives a class
	// name: an exact spelling first, then the qualified spelling's member, then a
	// UNIQUE member match over the alias table (a quoted alias name is not
	// package-resolved, exactly like a quoted class name).
	@Nullable private ClassInfo aliasTarget(String name) {
		if (this.classAliases.isEmpty()) {
			return null;
		}
		String canonical = this.classAliases.get(normalize(name));
		if (canonical == null) {
			canonical = this.classAliases.get(plainNameOf(name));
		}
		if (canonical == null) {
			canonical = uniqueAliasByMember(plainNameOf(name));
		}
		// The target was canonicalized at registration time, so this is a plain lookup --
		// never a recursion back through findClass.
		return canonical == null ? null : this.classes.get(canonical);
	}

	// The one alias whose member name matches, or null when none does (or when the match
	// is ambiguous) -- uniqueByMember's twin over the alias table.
	@Nullable private String uniqueAliasByMember(String member) {
		Map<String, java.util.Optional<String>> index = this.aliasesByMember;
		if (index == null) {
			index = new java.util.HashMap<>();
			for (Map.Entry<String, String> entry : this.classAliases.entrySet()) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(entry.getKey());
				if (qn != null) {
					index.merge(qn.member(), java.util.Optional.of(entry.getValue()),
							(first, second) -> java.util.Optional.empty());
				}
			}
			this.aliasesByMember = index;
		}
		java.util.Optional<String> found = index.get(member);
		return found == null ? null : found.orElse(null);
	}

	// The one registered class whose member name matches, or null when none does (or
	// when two packages define the name -- an ambiguous match must stay unresolved).
	@Nullable private ClassInfo uniqueByMember(String member) {
		Map<String, java.util.Optional<ClassInfo>> index = this.classesByMember;
		if (index == null) {
			index = new java.util.HashMap<>();
			for (ClassInfo candidate : this.classes.values()) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(candidate.name());
				if (qn != null) {
					index.merge(qn.member(), java.util.Optional.of(candidate),
							(first, second) -> java.util.Optional.empty());
				}
			}
			this.classesByMember = index;
		}
		java.util.Optional<ClassInfo> found = index.get(member);
		return found == null ? null : found.orElse(null);
	}

	/**
	 * The class METAOBJECT of a registered class or struct type: an instance of
	 * {@link #STANDARD_CLASS_NAME} holding the name, the direct-superclass metaobject
	 * list, the direct slots (nil until a metaclass protocol fills them), the effective
	 * slots as {@link #STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME} instances, and
	 * finalized-p (t -- a plain registered type is always complete). Memoized, so
	 * {@code eq} identity holds across calls ({@code (eq (find-class 'a) (find-class
	 * 'a))} is true) and {@code class-of} answers with the same object. The designator
	 * may be an instance TAG ({@code %class-<name>}/{@code %struct-<name>}, what
	 * {@code class-of} reads off a value) as well as a plain name, mirroring
	 * {@link #slotDefs}; a struct answers a {@code standard-class} instance too --
	 * rontolisp has no {@code structure-class}, a documented divergence.
	 * @param designator the type name as spelled, or an instance tag
	 * @return the metaobject, or null when no such class or struct is registered
	 */
	@Nullable public LispInstance classMetaobject(String designator) {
		ensureMopClassesSeeded();
		boolean structOnly = designator.startsWith(LispLayout.STRUCT_TAG_PREFIX);
		String name = LispLayout.printNameOfTag(designator);
		if (name == null) {
			name = designator;
		}
		if (structOnly) {
			return structMetaobject(name);
		}
		ClassInfo info = findClass(name);
		if (info == null) {
			return designator.startsWith(LispLayout.CLASS_TAG_PREFIX) ? null : structMetaobject(name);
		}
		String key = normalize(info.name());
		LispInstance cached = this.classMetaobjects.get(key);
		if (cached != null) {
			return cached;
		}
		LispVal supers = LispNil.INSTANCE;
		for (int s = info.superclasses().size() - 1; s >= 0; s--) {
			LispInstance superMetaobject = classMetaobject(info.superclasses().get(s));
			if (superMetaobject != null) {
				supers = new LispCons(superMetaobject, supers);
			}
		}
		LispVal effectiveSlots = LispNil.INSTANCE;
		List<SlotSpec> slots = info.slots();
		for (int i = slots.size() - 1; i >= 0; i--) {
			LispInstance effective = slotDefinitionInstance(slots.get(i), false);
			effective.setSlot(SLOT_DEFINITION_LOCATION_INDEX, new LispInteger(i));
			effectiveSlots = new LispCons(effective, effectiveSlots);
		}
		LispVal directSlots = LispNil.INSTANCE;
		for (int i = info.directSlots().size() - 1; i >= 0; i--) {
			directSlots = new LispCons(slotDefinitionInstance(info.directSlots().get(i), true), directSlots);
		}
		LispInstance metaobject = newSeededInstance(STANDARD_CLASS_NAME, new LispSymbol(info.name()), supers,
				directSlots, effectiveSlots, LispTrue.INSTANCE);
		this.classMetaobjects.put(key, metaobject);
		return metaobject;
	}

	// One slot-definition metaobject of a materialized plain view: a direct one carries
	// the slot's writers ((setf accessor) for an accessor, the :writer names), an
	// effective one none (AMOP 5.4.3) -- readers stay on both, which trivia's effective
	// slot walk reads. The compile paths build the same data from the class table
	// (LispMacroExpander.classMetaTableForms).
	private LispInstance slotDefinitionInstance(SlotSpec slot, boolean direct) {
		LispVal initargs = symbolList(slot.initargs());
		List<String> readerNames = new java.util.ArrayList<>(slot.readers());
		readerNames.addAll(slot.accessors());
		return newSeededInstance(
				direct ? STANDARD_DIRECT_SLOT_DEFINITION_NAME : STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME,
				new LispSymbol(slot.name()), initargs, slot.initformSupplied() ? slot.initform() : LispNil.INSTANCE,
				new LispSymbol(slot.type()), symbolList(readerNames), LispNil.INSTANCE,
				direct ? slotWriters(slot) : LispNil.INSTANCE, slotAllocation(slot));
	}

	/**
	 * The {@code allocation} slot of a slot-definition metaobject materialized from a
	 * spec: {@code :class} for a {@code :allocation :class} slot, nil otherwise --
	 * {@code closer-mop:slot-definition-allocation} answers {@code :instance} for nil.
	 * @param slot the slot
	 * @return the allocation value
	 */
	public static LispVal slotAllocation(SlotSpec slot) {
		return slot.sharedCellVar() != null ? new LispSymbol(CLASS_ALLOCATION) : LispNil.INSTANCE;
	}

	/**
	 * A proper list of the named symbols ({@code nil} when empty).
	 * @param names the symbol names
	 * @return the list
	 */
	public static LispVal symbolList(List<String> names) {
		LispVal out = LispNil.INSTANCE;
		for (int i = names.size() - 1; i >= 0; i--) {
			out = new LispCons(new LispSymbol(names.get(i)), out);
		}
		return out;
	}

	/**
	 * The {@code slot-definition-writers} answer of a direct slot: {@code (setf a)} per
	 * accessor, then each {@code :writer} name -- a {@code (setf place)} writer back in
	 * its list spelling (the registry keeps it mangled).
	 * @param slot the direct slot
	 * @return the writer function names
	 */
	public static LispVal slotWriters(SlotSpec slot) {
		List<LispVal> names = new java.util.ArrayList<>();
		for (String accessor : slot.accessors()) {
			names.add(setfName(accessor));
		}
		for (String writer : slot.writers()) {
			names.add(writer.startsWith(SETF_FUNCTION_PREFIX)
					? setfName(writer.substring(SETF_FUNCTION_PREFIX.length())) : new LispSymbol(writer));
		}
		LispVal out = LispNil.INSTANCE;
		for (int i = names.size() - 1; i >= 0; i--) {
			out = new LispCons(names.get(i), out);
		}
		return out;
	}

	private static LispVal setfName(String place) {
		return new LispCons(new LispSymbol(LispNames.SETF), new LispCons(new LispSymbol(place), LispNil.INSTANCE));
	}

	/**
	 * Primes the metaobject memo with a driver-built instance (the metaclass protocol's
	 * {@code %register-class-metaobject}): from then on {@code find-class} and
	 * {@code class-of} answer this instance -- an instance of the USER metaclass --
	 * instead of building the plain {@code standard-class} view. Keyed like
	 * {@link #classMetaobject} memoizes, so every spelling resolves to it.
	 * @param name the class name as the driver received it (the canonical spelling)
	 * @param metaobject the metaclass instance to answer
	 */
	public void registerClassMetaobject(String name, LispInstance metaobject) {
		ClassInfo info = findClass(name);
		this.classMetaobjects.put(normalize(info != null ? info.name() : name), metaobject);
	}

	/**
	 * The metaobject memoized for a class name, without materializing a plain view: what
	 * a redefinition starts from (the driver-built instance of an earlier
	 * {@code :metaclass} definition), or null when none is memoized.
	 * @param name the class name as spelled in its defclass
	 * @return the memoized metaobject, or null
	 */
	@Nullable public LispInstance memoizedClassMetaobject(String name) {
		return this.classMetaobjects.get(normalize(name));
	}

	/**
	 * The run-time {@code (setf find-class)} overrides, normalized name to the class the
	 * public {@code find-class} answers (nil: the name is forgotten).
	 */
	private final Map<String, LispVal> findClassOverrides = new java.util.HashMap<>();

	/**
	 * Makes the public {@code find-class} answer {@code value} for {@code name} from now
	 * on ({@code (setf (find-class name) value)}); nil forgets the name. Nothing else
	 * changes: the class, its instances, {@code class-of} and the static tables.
	 * @param name the class name
	 * @param value the class metaobject, or nil
	 */
	public void setFindClassOverride(String name, LispVal value) {
		this.findClassOverrides.put(normalize(name), value);
	}

	/**
	 * Ends a {@link #setFindClassOverride} of the name: a {@code defclass} of it makes
	 * {@code find-class} answer the class again.
	 * @param name the class name
	 */
	public void clearFindClassOverride(String name) {
		this.findClassOverrides.remove(normalize(name));
	}

	/**
	 * The name a class defined under {@code name} was defined as before: the memo key of
	 * the metaobject {@code (setf find-class)} moved to {@code name}, which keeps it
	 * through the definition (McCLIM's forward-referenced presentation types).
	 * @param name the class name being defined
	 * @return the earlier key, or null when no class moved to the name
	 */
	@Nullable public String classMovedTo(String name) {
		if (!(findClassOverride(name) instanceof LispInstance moved)) {
			return null;
		}
		for (Map.Entry<String, LispInstance> entry : this.classMetaobjects.entrySet()) {
			if (entry.getValue() == moved && !entry.getKey().equals(normalize(name))) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Makes the classes descending from {@code oldKey} descend from the class now defined
	 * as {@code newName}, the same class by {@link #classMovedTo}. Their layouts stay:
	 * redefinition does not propagate in the static subset.
	 * @param oldKey the class's earlier key
	 * @param newName the class's name
	 */
	public void moveDescendants(String oldKey, String newName) {
		ClassInfo target = java.util.Objects.requireNonNull(findClass(newName));
		for (ClassInfo info : List.copyOf(this.classes.values())) {
			if (info != target && info.ancestors().contains(oldKey)) {
				Set<String> ancestors = new java.util.LinkedHashSet<>(info.ancestors());
				ancestors.addAll(target.ancestors());
				java.util.function.UnaryOperator<String> moved = name -> normalize(name).equals(oldKey) ? target.name()
						: name;
				this.classes.put(normalize(info.name()),
						new ClassInfo(info.name(), info.superclasses().stream().map(moved).toList(),
								info.cpl().stream().map(moved).toList(), info.directSlots(), info.slots(),
								Set.copyOf(ancestors)));
			}
		}
		this.hierarchyVersion++;
		this.classesByMember = null;
		this.descendantStructTagsMemo.clear();
	}

	/**
	 * The {@link #setFindClassOverride} answer for a name, or null when none is in force
	 * (nil itself means forgotten).
	 * @param name the class name
	 * @return the overriding class, nil, or null
	 */
	@Nullable public LispVal findClassOverride(String name) {
		return this.findClassOverrides.isEmpty() ? null : this.findClassOverrides.get(normalize(name));
	}

	/**
	 * The canonical names of the classes whose DIRECT superclasses contain the given
	 * class -- the {@code class-direct-subclasses} answer. Two sources, merged: the
	 * memoized metaobjects' direct-superclass lists (a driver-built metaclass instance
	 * may carry superclasses a user {@code initialize-instance :around} INJECTED, which
	 * the static registry never sees -- mito's dao-class push), then the static
	 * registry's declared superclasses for classes never materialized at run time.
	 * @param targetName the canonical class name
	 * @return the subclass names, memoized-first then registration order
	 */
	public List<String> directSubclassNames(String targetName) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (java.util.Map.Entry<String, LispInstance> entry : this.classMetaobjects.entrySet()) {
			LispVal supers = entry.getValue().slotCount() > 1 ? entry.getValue().slot(1) : LispNil.INSTANCE;
			while (supers instanceof LispCons cons) {
				if (cons.car() instanceof LispInstance superMo && superMo.slotCount() > 0
						&& superMo.slot(0) instanceof LispSymbol superName && targetName.equals(superName.name())) {
					out.add(entry.getKey());
				}
				supers = cons.cdr();
			}
		}
		for (ClassInfo candidate : this.classes.values()) {
			if (candidate.superclasses().contains(targetName)) {
				out.add(normalize(candidate.name()));
			}
		}
		return List.copyOf(out);
	}

	// The struct half of classMetaobject: built from the layout (slot types all read T,
	// like slotDefs; readers stay nil -- the accessor names are conc-name spellings the
	// registry does not keep), superclasses from the direct :include parent.
	@Nullable private LispInstance structMetaobject(String name) {
		String tag = findStructTag(name);
		if (tag == null) {
			return null;
		}
		LispLayout layout = this.layoutsByTag.get(tag);
		if (layout == null) {
			return null;
		}
		String key = normalize(layout.printName());
		LispInstance cached = this.classMetaobjects.get(key);
		if (cached != null) {
			return cached;
		}
		LispVal supers = LispNil.INSTANCE;
		String parent = this.structParents.get(key);
		if (parent != null) {
			LispInstance superMetaobject = classMetaobject(parent);
			if (superMetaobject != null) {
				supers = new LispCons(superMetaobject, LispNil.INSTANCE);
			}
		}
		LispVal effectiveSlots = LispNil.INSTANCE;
		List<String> slotNames = layout.slotNames();
		for (int i = slotNames.size() - 1; i >= 0; i--) {
			LispInstance slotDefinition = newSeededInstance(STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME,
					new LispSymbol(slotNames.get(i)),
					new LispCons(new LispSymbol(":" + slotNames.get(i)), LispNil.INSTANCE), layout.initforms().get(i),
					new LispSymbol("T"), LispNil.INSTANCE);
			slotDefinition.setSlot(SLOT_DEFINITION_LOCATION_INDEX, new LispInteger(i));
			effectiveSlots = new LispCons(slotDefinition, effectiveSlots);
		}
		LispInstance metaobject = newSeededInstance(STANDARD_CLASS_NAME, new LispSymbol(layout.printName()), supers,
				LispNil.INSTANCE, effectiveSlots, LispTrue.INSTANCE);
		this.classMetaobjects.put(key, metaobject);
		return metaobject;
	}

	/**
	 * The memoized metaobject of a BUILT-IN class name ({@link #BUILTIN_CLASS_NAMES}) --
	 * what {@code class-of} answers for a non-instance value and {@code find-class} falls
	 * back to when no registered class matches. A {@link #BUILT_IN_CLASS_NAME} instance
	 * with no slots and no superclasses ({@link #STANDARD_METACLASS_ROOT_NAMES} a
	 * {@code standard-class} one); the {@code T} class's name slot holds the boolean
	 * {@code t} value, matching what the compiled table's quoted {@code T} reads as.
	 * @param name the built-in class name (upcased)
	 * @return the metaobject, or null when the name is not a built-in class
	 */
	@Nullable public LispInstance builtinClassMetaobject(String name) {
		if (!BUILTIN_CLASS_NAMES.contains(name) && !FIND_CLASS_ONLY_CLASS_NAMES.contains(name)) {
			return null;
		}
		ensureMopClassesSeeded();
		LispInstance cached = this.classMetaobjects.get(name);
		if (cached != null) {
			return cached;
		}
		LispVal nameVal = "T".equals(name) ? LispTrue.INSTANCE : new LispSymbol(name);
		LispInstance metaobject = newSeededInstance(
				STANDARD_METACLASS_ROOT_NAMES.contains(name) ? STANDARD_CLASS_NAME : BUILT_IN_CLASS_NAME, nameVal,
				LispNil.INSTANCE, LispNil.INSTANCE, LispNil.INSTANCE, LispTrue.INSTANCE);
		this.classMetaobjects.put(name, metaobject);
		return metaobject;
	}

	/**
	 * Whether a value is a class metaobject -- an instance of a class derived from
	 * {@link #CLASS_NAME}: {@link #STANDARD_CLASS_NAME}, {@link #BUILT_IN_CLASS_NAME}, a
	 * user metaclass. This is what {@code closer-mop:classp} tests.
	 * @param value the value to test
	 * @return true for a class metaobject
	 */
	public boolean isClassMetaobject(LispVal value) {
		if (!(value instanceof LispInstance inst)) {
			return false;
		}
		String typeName = LispLayout.printNameOfTag(inst.layout().tag());
		if (typeName == null) {
			return false;
		}
		ClassInfo info = findClass(typeName);
		return info != null && info.ancestors().contains(CLASS_NAME);
	}

	// A fresh instance of a seeded class, with the trailing slots nil-filled up to the
	// layout capacity.
	private LispInstance newSeededInstance(String className, LispVal... values) {
		LispLayout layout = java.util.Objects
			.requireNonNull(this.layoutsByTag.get(LispLayout.CLASS_TAG_PREFIX + className), className);
		LispVal[] slots = new LispVal[Math.max(layout.capacity(), values.length)];
		java.util.Arrays.fill(slots, LispNil.INSTANCE);
		System.arraycopy(values, 0, slots, 0, values.length);
		return new LispInstance(layout, slots);
	}

	/**
	 * Looks up a generic function by name (single- and double-colon spellings match).
	 * @param name the generic-function name as spelled
	 * @return the generic, or null
	 */
	@Nullable public GenericInfo findGeneric(String name) {
		return this.generics.get(normalize(name));
	}

	/**
	 * The 1-based position of a slot (by base name) shared by every class declaring it,
	 * {@code -1} when classes disagree, or null when unknown.
	 * @param baseName the package-stripped slot name
	 * @return the position, -1, or null
	 */
	@Nullable public Integer slotPosition(String baseName) {
		Integer exact = this.slotPositions.get(baseName);
		if (exact != null) {
			return exact;
		}
		// Case-flip retry: a Java-side reader asks with the canonical lowercase name
		// (simple-condition-format-control -> "format-control") while an upcase-read
		// user condition registered the slot upcased -- and vice versa for the seeded
		// lowercase hierarchy.
		String upper = baseName.toUpperCase(java.util.Locale.ROOT);
		if (!upper.equals(baseName)) {
			Integer flipped = this.slotPositions.get(upper);
			if (flipped != null) {
				return flipped;
			}
		}
		String lower = baseName.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(baseName)) {
			return this.slotPositions.get(lower);
		}
		return null;
	}

	/**
	 * Records the extra parent types (beyond the first) of a {@code define-condition}
	 * with multiple parents; they join the class's ancestor set when it is registered.
	 * @param className the condition class name as spelled
	 * @param parents the extra parent type names as spelled
	 */
	public void registerExtraAncestors(String className, List<String> parents) {
		Set<String> extras = this.pendingExtraAncestors.computeIfAbsent(normalize(className),
				k -> new java.util.LinkedHashSet<>());
		for (String parent : parents) {
			extras.add(normalize(parent));
		}
	}

	/**
	 * A class that cannot have instances yet: its {@code defclass} named a superclass no
	 * definition has finalized (ANSI allows it), or a program named it only as such a
	 * superclass. It stays registered, so methods and subclasses can name it, and its
	 * definition is expanded again once nothing it waits for is left.
	 *
	 * @param name the class name as defined
	 * @param form the {@code defclass} to expand then, null for a name no definition has
	 * given a form yet
	 * @param superclasses the direct superclasses it waits for
	 * @param primaryMethods the primary methods defined on it after its {@code defclass}
	 * ({@code <generic> <specializer key>}), which its generated readers must not replace
	 */
	public record ForwardClass(String name, @Nullable LispCons form, List<String> superclasses,
			Set<String> primaryMethods) {
	}

	/** The classes waiting to be finalized, by normalized name, oldest first. */
	private final Map<String, ForwardClass> forwardClasses = new LinkedHashMap<>();

	/** Every class that ever waited, in definition order, by normalized name. */
	private final Map<String, String> forwardReferencedClasses = new LinkedHashMap<>();

	/**
	 * Records that a class waits for its superclasses. A later {@code defclass} of the
	 * name replaces the record.
	 * @param name the class name as defined
	 * @param form the {@code defclass}, null when the class is only named so far
	 * @param superclasses the direct superclasses it waits for
	 */
	public void deferClass(String name, @Nullable LispCons form, List<String> superclasses) {
		String key = normalize(name);
		this.forwardReferencedClasses.put(key, name);
		this.forwardClasses.put(key,
				new ForwardClass(name, form, List.copyOf(superclasses), new java.util.HashSet<>()));
	}

	/**
	 * Takes the class off the waiting list: its definition is complete.
	 * @param name the class name
	 * @return what it waited as, or null when it was not waiting
	 */
	@Nullable public ForwardClass finalizeClass(String name) {
		return this.forwardClasses.isEmpty() ? null : this.forwardClasses.remove(normalize(name));
	}

	/**
	 * Whether the class waits for a superclass, so it cannot have instances.
	 * @param name the class name
	 * @return true while it waits
	 */
	public boolean isUnfinalized(String name) {
		return !this.forwardClasses.isEmpty() && this.forwardClasses.containsKey(normalize(name));
	}

	/**
	 * Whether the class ever waited for a superclass, which is what makes its allocations
	 * test for it.
	 * @param name the class name
	 * @return true when it did
	 */
	public boolean isForwardReferenced(String name) {
		return !this.forwardReferencedClasses.isEmpty() && this.forwardReferencedClasses.containsKey(normalize(name));
	}

	/**
	 * The classes that ever waited for a superclass.
	 * @return their names as defined, in definition order
	 */
	public Collection<String> forwardReferencedClasses() {
		return this.forwardReferencedClasses.values();
	}

	/**
	 * The classes still waiting.
	 * @return their records, oldest first
	 */
	public Collection<ForwardClass> unfinalizedClasses() {
		return this.forwardClasses.values();
	}

	/**
	 * The direct superclasses of a waiting class no {@code defclass} has defined.
	 * @param waiting the waiting class
	 * @return their names
	 */
	public List<String> undefinedSuperclasses(ForwardClass waiting) {
		return waiting.superclasses().stream().filter(name -> {
			ForwardClass superclass = this.forwardClasses.get(normalize(name));
			return superclass != null && superclass.form() == null;
		}).toList();
	}

	/**
	 * The oldest waiting {@code defclass} nothing holds back any more.
	 * @return the form to expand again, or null when there is none
	 */
	@Nullable public LispCons nextFinalizableClass() {
		for (ForwardClass waiting : this.forwardClasses.values()) {
			if (waiting.form() != null && waiting.superclasses().stream().noneMatch(this::isUnfinalized)) {
				return waiting.form();
			}
		}
		return null;
	}

	/**
	 * Records a primary method on a waiting class: it was defined after the class, so it
	 * replaces the class's generated reader of the same key, which is only emitted later.
	 * @param className the specializer's class
	 * @param generic the generic function's name
	 * @param specializerKey the method's specializer key
	 */
	public void noteMethodOnWaitingClass(String className, String generic, String specializerKey) {
		ForwardClass waiting = this.forwardClasses.isEmpty() ? null : this.forwardClasses.get(normalize(className));
		if (waiting != null) {
			waiting.primaryMethods().add(normalize(generic) + " " + specializerKey);
		}
	}

	/**
	 * The text of the refusal to allocate an instance of a waiting class.
	 * @param name the class name
	 * @return the condition's report
	 */
	public static String unfinalizedClassMessage(String name) {
		return "The class " + name + " is not finalized: it or a superclass is a forward referenced class";
	}

	/**
	 * Registers a class definition.
	 * @param info the class record
	 */
	public void registerClass(ClassInfo info) {
		this.hierarchyVersion++;
		String key = normalize(info.name());
		noteTypeDefined(key);
		Set<String> extras = this.pendingExtraAncestors.get(key);
		if (extras != null) {
			Set<String> merged = new java.util.LinkedHashSet<>(info.ancestors());
			for (String extra : extras) {
				ClassInfo parent = findClass(extra);
				if (parent != null) {
					merged.addAll(parent.ancestors());
				}
				else {
					merged.add(extra);
				}
			}
			info = new ClassInfo(info.name(), info.superclasses(), info.cpl(), info.directSlots(), info.slots(),
					Set.copyOf(merged));
		}
		this.classes.put(key, info);
		this.classesByMember = null;
		this.descendantStructTagsMemo.clear();
		// A redefinition invalidates the memoized metaobject; descendants keep theirs
		// (their slot lists are unchanged -- redefinition does not propagate in the
		// static subset).
		this.classMetaobjects.remove(key);
		// Every class -- seeded condition or user defclass -- gets its layout here, so
		// the instance shape can never disagree with the slot list it was built from.
		LispLayout layout = LispLayout.ofClass(info.name(), info.slots().stream().map(SlotSpec::baseName).toList(),
				info.slots().stream().map(SlotSpec::initform).toList());
		if (info.ancestors().contains(FUNCALLABLE_STANDARD_OBJECT_NAME)) {
			// The function cell (LispLayout.FUNCALLABLE_CELL) and the tail cell, past
			// the declared slots; a funcallable Gray input stream parks its pushback in
			// the tail one.
			layout = layout.asFuncallable();
		}
		else if (info.ancestors().contains(GRAY_INPUT_STREAM_KEY)) {
			// The unread-char pushback cell (LispLayout.TAIL_CELL), past the declared
			// slots so no printer, equal or slot listing sees it.
			layout = layout.withCapacity(layout.slotCount() + 1);
		}
		this.layoutsByTag.put(layout.tag(), layout);
	}

	/**
	 * The registry key of {@code rontolisp:fundamental-input-stream}: a class descending
	 * from it reserves the {@link LispLayout#TAIL_CELL} its default
	 * {@code stream-unread-char} parks a character in.
	 */
	private static final String GRAY_INPUT_STREAM_KEY = LispNames.RONTOLISP_PKG + "::" + LispNames.GRAY_INPUT_STREAM;

	/**
	 * The layout registered under an exact instance tag.
	 * @param tag the instance tag ({@code %struct-<name>} or {@code %class-<name>})
	 * @return the layout, or null when no such type is registered
	 */
	@Nullable public LispLayout findLayoutByTag(String tag) {
		return this.layoutsByTag.get(tag);
	}

	/**
	 * The layout of a {@code defstruct} type by name, resolved with the same single-/
	 * double-colon tolerance as {@link #findStructTag}.
	 * @param name the struct name as spelled
	 * @return the layout, or null when no such struct is registered
	 */
	@Nullable public LispLayout findStructLayout(String name) {
		String tag = findStructTag(name);
		return tag == null ? null : this.layoutsByTag.get(tag);
	}

	/**
	 * The layout of a CLOS class by name, resolved with the same package tolerance as
	 * {@link #findClass}.
	 * @param name the class name as spelled
	 * @return the layout, or null when no such class is registered
	 */
	@Nullable public LispLayout findClassLayout(String name) {
		ClassInfo info = findClass(name);
		return info == null ? null : this.layoutsByTag.get(LispLayout.CLASS_TAG_PREFIX + info.name());
	}

	/**
	 * Every registered layout, keyed by instance tag, in registration order. The
	 * compilers walk this to bake the layout constants into their artifacts.
	 * @return the layout registry
	 */
	public Map<String, LispLayout> layouts() {
		return this.layoutsByTag;
	}

	/**
	 * Whether the program defined an instance type of its own, a {@code defstruct},
	 * {@code defclass} or {@code define-condition}: without one the only instances are
	 * seeded conditions, whose slots the printer has no reason to walk.
	 * @return {@code true} when a type beyond the seeded ones is registered
	 */
	public boolean definesInstanceTypes() {
		return this.layoutsByTag.keySet().stream().anyMatch(tag -> tag.startsWith(LispLayout.STRUCT_TAG_PREFIX))
				|| !this.seededClassNames.containsAll(this.classes.keySet());
	}

	/**
	 * The direct {@code :include} parent of a registered struct type, or null -- what a
	 * struct metaobject's direct-superclass list is built from, on the interpreter
	 * ({@link #classMetaobject}) and in the compile paths' {@code %class-meta-table%}
	 * entries alike.
	 * @param structName the struct name as spelled
	 * @return the parent name as given at registration, or null
	 */
	@Nullable public String structParent(String structName) {
		String parent = this.structParents.get(normalize(structName));
		if (parent == null && PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn) {
			parent = this.structParents.get(qn.member());
		}
		return parent;
	}

	/**
	 * Records that a {@code change-class} in the program turns instances INTO this class,
	 * so every class it descends from must reserve room for its slots
	 * ({@link LispLayout#withCapacity}). Call before the layouts are consumed; the
	 * reservation is applied by {@link #applyChangeClassCapacities()} once the whole
	 * program is registered.
	 * @param className the target class name as spelled in the change-class
	 */
	public void registerChangeClassTarget(String className) {
		this.changeClassTargets.add(normalize(className));
	}

	/**
	 * Records that the generated method-body defun {@code functionName} sits inside a
	 * top-level {@code let} body (see {@link #nestedMethodFunctions}).
	 * @param functionName the generated method-body defun name as spelled
	 */
	public void markNestedMethodFunction(String functionName) {
		this.nestedMethodFunctions.add(functionName);
	}

	/**
	 * Whether the generated method-body defun {@code functionName} sits inside a
	 * top-level {@code let} body, so its function value is assigned only when the
	 * {@code defmethod} form executes.
	 * @param functionName the generated method-body defun name as spelled
	 * @return true when the dispatcher must guard calls to it
	 */
	public boolean isNestedMethodFunction(String functionName) {
		return this.nestedMethodFunctions.contains(functionName);
	}

	/**
	 * Applies every recorded {@link #registerChangeClassTarget} reservation: every class
	 * an instance can be changed FROM -- the target's ancestors (and the target itself)
	 * and every non-seeded class, since the instance's class is a runtime property --
	 * widens to the target's capacity: its slot count, plus the
	 * {@link LispLayout#TAIL_CELL} a Gray input stream keeps past them, so a changed
	 * instance's last cell is past every slot of its new class too. Idempotent, and a
	 * no-op for a program with no {@code change-class}.
	 */
	public void applyChangeClassCapacities() {
		for (String target : this.changeClassTargets) {
			ClassInfo info = findClass(target);
			if (info == null) {
				continue;
			}
			LispLayout targetLayout = this.layoutsByTag.get(LispLayout.CLASS_TAG_PREFIX + info.name());
			int reserved = targetLayout == null ? info.slots().size() : targetLayout.capacity();
			for (String ancestor : info.ancestors()) {
				ClassInfo owner = findClass(ancestor);
				if (owner != null) {
					reserveCapacity(owner, reserved);
				}
			}
			for (ClassInfo source : this.classes.values()) {
				if (!isSeededClass(source.name())) {
					reserveCapacity(source, reserved);
				}
			}
		}
	}

	private void reserveCapacity(ClassInfo owner, int reserved) {
		String tag = LispLayout.CLASS_TAG_PREFIX + owner.name();
		LispLayout layout = this.layoutsByTag.get(tag);
		if (layout != null) {
			this.layoutsByTag.put(tag, layout.withCapacity(reserved));
		}
	}

	/**
	 * Registers a generic function definition.
	 * @param info the generic record
	 */
	public void registerGeneric(GenericInfo info) {
		this.hierarchyVersion++;
		this.generics.put(normalize(info.name()), info);
	}

	/**
	 * Registers the {@code (:report x)} form of a condition class defined by
	 * {@code define-condition}.
	 * @param className the condition class name as spelled
	 * @param report the report form (a literal string or a lambda expression AST)
	 */
	public void registerConditionReport(String className, LispVal report) {
		this.conditionReports.put(normalize(className), report);
	}

	/**
	 * Looks up the {@code :report} form of a condition class (single- and double-colon
	 * spellings match).
	 * @param className the condition class name as spelled
	 * @return the report form, or null when the class has none
	 */
	@Nullable public LispVal findConditionReport(String className) {
		return this.conditionReports.get(normalize(className));
	}

	/**
	 * Records the instance-slot position a struct/class accessor reads.
	 * @param baseName the accessor name
	 * @param position the slot index
	 */
	public void registerSlotPosition(String baseName, int position) {
		Integer existing = this.slotPositions.get(baseName);
		if (existing == null) {
			this.slotPositions.put(baseName, position);
		}
		else if (existing != position) {
			this.slotPositions.put(baseName, -1);
		}
	}

	/**
	 * Whether any registered class has more than one direct superclass -- the gate for
	 * the multiple-inheritance dispatch refinement, so a single-inheritance program
	 * generates dispatchers byte-identical to the pre-MI shape.
	 * @return true when a multiply-inheriting class is registered
	 */
	public boolean hasMultipleInheritance() {
		return this.classes.values().stream().anyMatch(c -> c.superclasses().size() > 1);
	}

	/**
	 * The instance tags a user {@code print-object} method specializes on, in
	 * registration order -- the exact set the printer has to route through the generic.
	 * Empty when the program defines no {@code print-object} method, which is the gate:
	 * with no method, every printing operator keeps the shape (and the output bytes) it
	 * always had.
	 *
	 * <p>
	 * Every printing operator a compile meets asks this, several times, and the answer
	 * walks every class once per specializer -- measured 2026-09-24 at 10-14% of a
	 * ci-spec corpus compile on either backend when it was recomputed per call. It is
	 * memoized against {@link #hierarchyVersion} and the generic's method count: a method
	 * is only ever added (a redefinition replaces the entry under the same specializer
	 * key, so the specializer set cannot change without the count moving).
	 * @return the instance tags with a user print-object method, unmodifiable
	 */
	public List<String> printObjectTags() {
		PrintObjectTags memo = this.printObjectTagsMemo;
		if (memo != null && memo.hierarchyVersion() == this.hierarchyVersion
				&& memo.dynamicClasses() == this.dynamicClasses
				&& (memo.generic() == null || memo.generic().methods().size() == memo.methodCount())) {
			return memo.tags();
		}
		GenericInfo generic = null;
		for (GenericInfo candidate : this.generics.values()) {
			if (LispNames.PRINT_OBJECT.equals(plainNameOf(candidate.name()))) {
				generic = candidate;
				break;
			}
		}
		List<String> tags = new java.util.ArrayList<>();
		if (generic != null) {
			for (MethodInfo method : generic.methods().values()) {
				for (Specializer specializer : method.specializers()) {
					// A defclass name is a CLASS specializer, a defstruct name a TYPE one
					// carrying the struct name (parseSpecializer); both route the
					// printer,
					// so both descendant-tag families are collected.
					if (specializer.name() == null || (specializer.kind() != SpecializerKind.CLASS
							&& specializer.kind() != SpecializerKind.TYPE)) {
						continue;
					}
					// An instance of a class made at run time (contextl's layer-context
					// prototypes) may have the class in its precedence list: the
					// dispatcher's dynamic arm decides, and the system method answers
					// when no method applies.
					if (this.dynamicClasses && specializer.kind() == SpecializerKind.CLASS
							&& !tags.contains(DYNAMIC_OBJECT_TAG)) {
						tags.add(DYNAMIC_OBJECT_TAG);
					}
					List<String> descendants = new java.util.ArrayList<>(descendantTags(specializer.name()));
					descendants.addAll(descendantStructTags(specializer.name()));
					for (String tag : descendants) {
						if (tag != null && !tags.contains(tag)) {
							tags.add(tag);
						}
					}
				}
			}
		}
		List<String> answer = List.copyOf(tags);
		this.printObjectTagsMemo = new PrintObjectTags(this.hierarchyVersion, generic,
				generic == null ? 0 : generic.methods().size(), this.dynamicClasses, answer);
		return answer;
	}

	/**
	 * The tag symbols ({@code %class-<name>}) of the given class and all its descendants,
	 * in definition order -- the statically-known instance-of test set for a class
	 * specializer.
	 * @param className the class name as spelled
	 * @return the descendant tags
	 */
	public List<String> descendantTags(String className) {
		String key = normalize(className);
		return this.classes.values()
			.stream()
			.filter(c -> c.ancestors().contains(key))
			.map(c -> "%class-" + c.name())
			.toList();
	}

	/**
	 * The canonical registry key of a possibly package-qualified name: single- and
	 * double-colon spellings of the same symbol map to the same key.
	 * @param name the name as spelled
	 * @return the normalized key
	 */
	public static String normalize(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.pkg() + "::" + qn.member();
	}

	/** The package-stripped member of a possibly qualified name. */
	private static String plainNameOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
