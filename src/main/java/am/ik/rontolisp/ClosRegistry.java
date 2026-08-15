package am.ik.rontolisp;

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
		seedClass("CONDITION", null);
		seedClass("SERIOUS-CONDITION", "CONDITION");
		seedClass("ERROR", "SERIOUS-CONDITION");
		seedClass("SIMPLE-ERROR", "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedClass("SIMPLE-CONDITION", "CONDITION", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedClass("WARNING", "CONDITION");
		seedClass("SIMPLE-WARNING", "WARNING", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedClass("STYLE-WARNING", "WARNING");
		seedClass("PARSE-ERROR", "ERROR");
		seedClass("TYPE-ERROR", "ERROR", "DATUM", "EXPECTED-TYPE");
		// simple-type-error carries BOTH the type-error slots and the simple-condition
		// report slots -- CL's multiple inheritance flattened onto the type-error
		// branch, which is the branch a handler-case clause tests (alexandria's
		// sequence bounds checks signal it).
		seedClass("SIMPLE-TYPE-ERROR", "TYPE-ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedClass("STREAM-ERROR", "ERROR");
		seedClass(END_OF_FILE_CLASS_NAME, "STREAM-ERROR");
		// The read family signals this class when it runs out of input, so its report is
		// the message every backend prints for an uncaught end of file. It is a plain
		// string (no stream slot to name) so that the interpreter can raise the same
		// message from Java without evaluating a report lambda.
		registerConditionReport(END_OF_FILE_CLASS_NAME, new LispString(END_OF_FILE_MESSAGE));
		seedClass("FILE-ERROR", "ERROR");
		seedClass("ARITHMETIC-ERROR", "ERROR");
		seedClass("DIVISION-BY-ZERO", "ARITHMETIC-ERROR");
		seedClass("CONTROL-ERROR", "ERROR");
		seedClass("PROGRAM-ERROR", "ERROR");
		seedClass("PACKAGE-ERROR", "ERROR");
		seedClass("CELL-ERROR", "ERROR", "NAME");
		seedClass("UNBOUND-VARIABLE", "CELL-ERROR");
		seedClass("UNDEFINED-FUNCTION", "CELL-ERROR");
		// The condition a read of an unbound slot signals (CLHS 7.7.2): name = the slot,
		// instance = the object it was read from.
		seedClass("UNBOUND-SLOT", "CELL-ERROR", "INSTANCE");
		registerConditionReport("UNBOUND-SLOT", unboundSlotReport());
	}

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
		// CLASS is slot-less and never instantiated: it exists so that (typep x 'class)
		// -- the metaobject predicate mito's contains-class-or-subclasses rides -- is an
		// ordinary registry ancestor test on every backend, and so that standard-class
		// keeps its own slots at index 0 (a slot-less parent contributes nothing to the
		// layout, so the %obj-ref index contract below is unchanged).
		seedClass(CLASS_NAME, null);
		seedClass(STANDARD_CLASS_NAME, CLASS_NAME, "NAME", "DIRECT-SUPERCLASSES", "DIRECT-SLOTS", "EFFECTIVE-SLOTS",
				"FINALIZED-P");
		seedClass(STANDARD_DIRECT_SLOT_DEFINITION_NAME, null, "NAME", "INITARGS", "INITFORM", "TYPE", "READERS",
				"INITFUNCTION");
		seedClass(STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME, null, "NAME", "INITARGS", "INITFORM", "TYPE", "READERS",
				"INITFUNCTION");
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
		if (this.mopClassesSeeded) {
			return;
		}
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(typeName);
		String member = qualified == null ? typeName : qualified.member();
		if (MOP_BASE_CLASS_NAMES.contains(member.toUpperCase(java.util.Locale.ROOT))) {
			ensureMopClassesSeeded();
		}
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
	 * {@code (lambda (c s) (format s "No applicable method: ~a~a" (%obj-ref c 0) (%obj-ref c 1)))}
	 * -- the exact text the defun used to render eagerly into its signal message, now
	 * produced only when the condition is reported. Baked {@code %obj-ref} indexes for
	 * the same reason as {@link #unboundSlotReport}.
	 */
	private static LispVal noApplicableErrorReport() {
		LispSymbol condition = new LispSymbol("__c");
		LispSymbol stream = new LispSymbol("__s");
		LispVal format = list(new LispSymbol("FORMAT"), stream, new LispString("No applicable method: ~a~a"),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(0)),
				list(new LispSymbol(LispNames.OBJ_REF), condition, new LispInteger(1)));
		return list(new LispSymbol(LispNames.LAMBDA), list(condition, stream), format);
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
	 * The condition class the read family signals at end of input
	 * ({@code read-char}/{@code read-byte}, and {@code read-line} with an explicit
	 * eof-error-p). Seeded under {@code stream-error}, so an {@code (error () ...)}
	 * handler-case clause catches it too.
	 */
	public static final String END_OF_FILE_CLASS_NAME = "END-OF-FILE";

	/**
	 * The registered {@code :report} of {@link #END_OF_FILE_CLASS_NAME} -- the message an
	 * uncaught end of file prints on every backend.
	 */
	public static final String END_OF_FILE_MESSAGE = "end of file";

	/**
	 * A fresh {@code end-of-file} condition instance, for the interpreter's read family
	 * -- which runs inside {@code Environment}, where no registry is in scope. The class
	 * is SEEDED, so its layout is the same slot-less shape in every registry and can be
	 * built without one; {@code handler-case} dispatches on the instance TAG, not on
	 * layout identity, so the instance is indistinguishable from one
	 * {@code (error 'end-of-file)} would have constructed.
	 * @return the condition instance
	 */
	public static LispVal newEndOfFileCondition() {
		return new LispInstance(LispLayout.ofClass(END_OF_FILE_CLASS_NAME, List.of(), List.of()), new LispVal[0]);
	}

	/** The instance tag of the slot-unbound marker ({@link #UNBOUND_CLASS_NAME}). */
	public static final String UNBOUND_TAG = LispLayout.CLASS_TAG_PREFIX + UNBOUND_CLASS_NAME;

	/**
	 * The seeded metaclass every plain class metaobject is an instance of (see
	 * {@link #classMetaobject}). Slot order (an index contract, see
	 * {@code seedMopClasses}): name, direct-superclasses, direct-slots, effective-slots,
	 * finalized-p.
	 */
	public static final String STANDARD_CLASS_NAME = "STANDARD-CLASS";

	/**
	 * The seeded root of the metaclass hierarchy -- CL's {@code class}, the superclass of
	 * {@link #STANDARD_CLASS_NAME} and hence of every user metaclass. Slot-less and never
	 * instantiated: its only job is to make {@code (typep x 'class)} / {@code (subtypep m
	 * 'class)} answer for a class metaobject through the ordinary ancestor machinery,
	 * identically on all four backends.
	 */
	public static final String CLASS_NAME = "CLASS";

	/**
	 * The seeded direct-slot-definition base class a user metaclass protocol subclasses
	 * (postmodern's {@code direct-column-slot}). Slot order: name, initargs, initform,
	 * type, readers, initfunction (appended 2026-08-03; nil except on driver-built
	 * definitions).
	 */
	public static final String STANDARD_DIRECT_SLOT_DEFINITION_NAME = "STANDARD-DIRECT-SLOT-DEFINITION";

	/**
	 * The seeded effective-slot-definition class {@link #classMetaobject} builds the
	 * {@code class-slots} entries from. Slot order: name, initargs, initform, type,
	 * readers, initfunction.
	 */
	public static final String STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME = "STANDARD-EFFECTIVE-SLOT-DEFINITION";

	/** The classes {@link #ensureMopClassesSeeded()} registers, in seeding order. */
	private static final List<String> MOP_BASE_CLASS_NAMES = List.of(CLASS_NAME, STANDARD_CLASS_NAME,
			STANDARD_DIRECT_SLOT_DEFINITION_NAME, STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME);

	/**
	 * The built-in class names {@code class-of} can answer for a non-instance value --
	 * exactly the result set of the {@code %class-designator} dispatch
	 * ({@code LispMacroExpander.expandClassDesignator} and the interpreter's
	 * {@code builtinTypeName} agree on it), so the metaobject view and the designator
	 * view name the same classes on every backend. {@code find-class} resolves these
	 * names too ({@link #builtinClassMetaobject}).
	 */
	public static final List<String> BUILTIN_CLASS_NAMES = List.of("T", "NULL", "BOOLEAN", "INTEGER", "RATIO", "FLOAT",
			"STRING", "CHARACTER", "KEYWORD", "SYMBOL", "HASH-TABLE", "FUNCTION", "CONS");

	/**
	 * Names {@code find-class} resolves to a memoized slot-less metaobject but that are
	 * deliberately NOT in {@link #BUILTIN_CLASS_NAMES}: {@code class-of} never answers
	 * them, so the designator view stays untouched, and the typep/subtypep special-casing
	 * of {@code standard-object} ("every CLOS instance") keeps winning over any registry
	 * ancestor test. {@code standard-object} exists as a metaobject because AMOP walks
	 * eq-compare superclass metaobjects against {@code (find-class 'standard-object)}
	 * (mito's {@code map-all-superclasses}), and the metaclass protocol defaults a
	 * driver-built class's empty {@code :direct-superclasses} to it
	 * ({@code mop-protocol.lisp}), per AMOP.
	 */
	public static final List<String> FIND_CLASS_ONLY_CLASS_NAMES = List.of("STANDARD-OBJECT");

	/**
	 * Registers a built-in class (a condition of the seeded hierarchy, or one of the MOP
	 * base classes) with nil-defaulted slots: parent slots first, then the given ones,
	 * each accepting its {@code :slot-name} initarg.
	 */
	private void seedClass(String name, @Nullable String parent, String... slotNames) {
		this.seededClassNames.add(name);
		ClassInfo parentInfo = parent == null ? null : this.classes.get(parent);
		java.util.List<SlotSpec> slots = new java.util.ArrayList<>(parentInfo == null ? List.of() : parentInfo.slots());
		java.util.List<SlotSpec> directSlots = new java.util.ArrayList<>();
		for (String slotName : slotNames) {
			SlotSpec spec = new SlotSpec(slotName, slotName, LispNil.INSTANCE, true, ":" + slotName, true, List.of(),
					List.of(), "T");
			slots.add(spec);
			directSlots.add(spec);
		}
		java.util.Set<String> ancestors = new java.util.LinkedHashSet<>();
		if (parentInfo != null) {
			ancestors.addAll(parentInfo.ancestors());
		}
		ancestors.add(name);
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
	 * {@code :initarg} keyword (defaults to the slot-name keyword), the
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
	 * @param initargKeyword the keyword accepted by the constructor, with the colon
	 * @param initargSupplied whether the slot specification wrote an {@code :initarg};
	 * false means {@code initargKeyword} is the slot-name default that only the generated
	 * constructor honors -- CL's initialization protocol (the {@code shared-initialize}
	 * initarg fill, and therefore the metaclass-protocol re-fill after a {@code :before}
	 * hook) only ever fills DECLARED initargs
	 * @param readers the {@code :reader} function names
	 * @param accessors the {@code :accessor} function names (also setf places)
	 * @param type the package-stripped {@code :type} option name ({@code "t"} if none)
	 */
	public record SlotSpec(String name, String baseName, LispVal initform, boolean initformSupplied,
			String initargKeyword, boolean initargSupplied, List<String> readers, List<String> accessors, String type) {
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
	 * One generic function: its canonical name, required parameter names (from the
	 * {@code defgeneric} or the first {@code defmethod}), optional documentation, and its
	 * methods keyed by a canonical specializer key so redefining the same specializer
	 * replaces the method (CL semantics).
	 */
	public static final class GenericInfo {

		private final String name;

		private List<String> paramNames;

		private boolean variadic;

		@Nullable private String documentation;

		private final LinkedHashMap<String, MethodInfo> methods = new LinkedHashMap<>();

		private int methodCounter = 0;

		@Nullable private String methodCombination;

		private boolean mostSpecificLast;

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
		 * Whether any method's dispatch test enumerates class tags, so the dispatcher
		 * must be regenerated when a new class appears: a CLASS specializer (the new
		 * class may extend its descendant set) or a TYPE specializer whose test carries
		 * the any-class-instance enumeration ({@code standard-object} matches it,
		 * {@code cons}/{@code list}/{@code sequence} exclude it).
		 * @return true when such a method exists
		 */
		public boolean hasClassMethod() {
			return this.methods.values().stream().anyMatch(m -> m.specializers().stream().anyMatch(s -> {
				if (s.kind() == SpecializerKind.CLASS) {
					return true;
				}
				if (s.kind() != SpecializerKind.TYPE || s.name() == null) {
					return false;
				}
				String plain = plainNameOf(s.name());
				return "STANDARD-OBJECT".equals(plain) || "CONS".equals(plain) || "LIST".equals(plain)
						|| "SEQUENCE".equals(plain);
			}));
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

	/** Whether {@link #ensureMopClassesSeeded()} has run. */
	private boolean mopClassesSeeded;

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
	 * {@code defstruct} accessor name (normalized) to the declared {@code :type} of the
	 * slot it reads, and struct name (normalized) to its per-slot {@code :type} table
	 * (what an {@code :include} child inherits). Registered by
	 * {@code LispMacroExpander.expandDefstruct}; consulted by declaration-driven array
	 * emission ({@code am.ik.rontolisp.compiler.DeclaredArrayTypes}). Purely advisory
	 * side tables -- the {@link LispLayout} itself stays type-free.
	 */
	private final Map<String, LispVal> structAccessorTypes = new LinkedHashMap<>();

	private final Map<String, Map<String, LispVal>> structSlotTypes = new LinkedHashMap<>();

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
	 * Whether the compile paths have already emitted the class metaobject table from this
	 * registry (see {@code LispMacroExpander.classMetaTableForms}). A registration after
	 * that point can no longer reach the emitted program, so {@link #registerClassAlias}
	 * refuses instead of registering an alias the compiled program would never see.
	 */
	private boolean classMetaTableEmitted = false;

	/**
	 * The classes by normalized name, in definition order.
	 * @return the class registry
	 */
	public Map<String, ClassInfo> classes() {
		return this.classes;
	}

	/**
	 * The registered {@code (setf find-class)} aliases: alias name (normalized) to the
	 * canonical class name it resolves to. The compile paths add each alias as an extra
	 * SPELLING of its target's metaobject-table entry, so a runtime
	 * {@code (find-class 'alias)} answers the same object there as in the interpreter.
	 * @return the alias table, possibly empty
	 */
	public Map<String, String> classAliases() {
		return this.classAliases;
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
		String key = normalize(structName);
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
		Set<String> ancestors = this.structAncestors.get(normalize(structName));
		if (ancestors == null
				&& PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn) {
			ancestors = this.structAncestors.get(qn.member());
		}
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
		String key = normalize(structName);
		if (!this.structAncestors.containsKey(key)
				&& PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn
				&& this.structAncestors.containsKey(qn.member())) {
			key = qn.member();
		}
		List<String> tags = new java.util.ArrayList<>();
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
		Set<String> ancestors = this.structAncestors.get(normalize(structName));
		if (ancestors == null
				&& PackageRegistry.splitQualified(structName) instanceof PackageRegistry.QualifiedName qn) {
			ancestors = this.structAncestors.get(qn.member());
		}
		return ancestors == null ? 1 : ancestors.size();
	}

	/**
	 * The instance tag of a registered struct type, or null. Single- and double-colon
	 * spellings match, like {@link #findClass}.
	 * @param name the specializer name as spelled
	 * @return the {@code %struct-<name>} tag, or null when no such struct is registered
	 */
	@Nullable public String findStructTag(String name) {
		String exact = this.structTags.get(normalize(name));
		if (exact != null) {
			return exact;
		}
		if (PackageRegistry.splitQualified(name) instanceof PackageRegistry.QualifiedName qn) {
			return this.structTags.get(qn.member());
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
	 * Records that the generated {@code %condition-report-str} renderer is available; see
	 * {@link #routesConditionReports()}.
	 * @param routes whether the renderer is in the artifact
	 */
	public void setRoutesConditionReports(boolean routes) {
		this.routesConditionReports = routes;
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
	 * spelling unresolvable -- qualify it).
	 * @param name the class name as spelled
	 * @return the class, or null
	 */
	@Nullable public ClassInfo findClass(String name) {
		ClassInfo exact = this.classes.get(normalize(name));
		if (exact != null) {
			return exact;
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
		String found = null;
		for (Map.Entry<String, String> entry : this.classAliases.entrySet()) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(entry.getKey());
			if (qn != null && qn.member().equals(member)) {
				if (found != null) {
					return null;
				}
				found = entry.getValue();
			}
		}
		return found;
	}

	// The one registered class whose member name matches, or null when none does (or
	// when two packages define the name -- an ambiguous match must stay unresolved).
	@Nullable private ClassInfo uniqueByMember(String member) {
		ClassInfo found = null;
		for (ClassInfo candidate : this.classes.values()) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(candidate.name());
			if (qn != null && qn.member().equals(member)) {
				if (found != null) {
					return null;
				}
				found = candidate;
			}
		}
		return found;
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
			SlotSpec slot = slots.get(i);
			List<String> readerNames = new java.util.ArrayList<>(slot.readers());
			readerNames.addAll(slot.accessors());
			LispVal readers = LispNil.INSTANCE;
			for (int r = readerNames.size() - 1; r >= 0; r--) {
				readers = new LispCons(new LispSymbol(readerNames.get(r)), readers);
			}
			LispInstance slotDefinition = newSeededInstance(STANDARD_EFFECTIVE_SLOT_DEFINITION_NAME,
					new LispSymbol(slot.baseName()),
					new LispCons(new LispSymbol(slot.initargKeyword()), LispNil.INSTANCE),
					slot.initformSupplied() ? slot.initform() : LispNil.INSTANCE, new LispSymbol(slot.type()), readers);
			effectiveSlots = new LispCons(slotDefinition, effectiveSlots);
		}
		LispInstance metaobject = newSeededInstance(STANDARD_CLASS_NAME, new LispSymbol(info.name()), supers,
				LispNil.INSTANCE, effectiveSlots, LispTrue.INSTANCE);
		this.classMetaobjects.put(key, metaobject);
		return metaobject;
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
	 * back to when no registered class matches. A {@code standard-class} instance with no
	 * slots and no superclasses ({@code built-in-class} does not exist, a documented
	 * divergence); the {@code T} class's name slot holds the boolean {@code t} value,
	 * matching what the compiled table's quoted {@code T} reads as.
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
		LispInstance metaobject = newSeededInstance(STANDARD_CLASS_NAME, nameVal, LispNil.INSTANCE, LispNil.INSTANCE,
				LispNil.INSTANCE, LispTrue.INSTANCE);
		this.classMetaobjects.put(name, metaobject);
		return metaobject;
	}

	/**
	 * Whether a value is a class metaobject -- an instance of
	 * {@link #STANDARD_CLASS_NAME} or of a class derived from it (a user metaclass). This
	 * is what {@code closer-mop:classp} tests.
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
		return info != null && info.ancestors().contains(STANDARD_CLASS_NAME);
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
	 * Registers a class definition.
	 * @param info the class record
	 */
	public void registerClass(ClassInfo info) {
		String key = normalize(info.name());
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
		// A redefinition invalidates the memoized metaobject; descendants keep theirs
		// (their slot lists are unchanged -- redefinition does not propagate in the
		// static subset).
		this.classMetaobjects.remove(key);
		// Every class -- seeded condition or user defclass -- gets its layout here, so
		// the instance shape can never disagree with the slot list it was built from.
		LispLayout layout = LispLayout.ofClass(info.name(), info.slots().stream().map(SlotSpec::baseName).toList(),
				info.slots().stream().map(SlotSpec::initform).toList());
		this.layoutsByTag.put(layout.tag(), layout);
	}

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
	 * Applies every recorded {@link #registerChangeClassTarget} reservation: each
	 * target's ancestors (and the target itself) widen to the target's slot count.
	 * Idempotent, and a no-op for a program with no {@code change-class}.
	 */
	public void applyChangeClassCapacities() {
		for (String target : this.changeClassTargets) {
			ClassInfo info = findClass(target);
			if (info == null) {
				continue;
			}
			int reserved = info.slots().size();
			for (String ancestor : info.ancestors()) {
				ClassInfo owner = findClass(ancestor);
				if (owner == null) {
					continue;
				}
				String tag = LispLayout.CLASS_TAG_PREFIX + owner.name();
				LispLayout layout = this.layoutsByTag.get(tag);
				if (layout != null) {
					this.layoutsByTag.put(tag, layout.withCapacity(reserved));
				}
			}
		}
	}

	/**
	 * Registers a generic function definition.
	 * @param info the generic record
	 */
	public void registerGeneric(GenericInfo info) {
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
