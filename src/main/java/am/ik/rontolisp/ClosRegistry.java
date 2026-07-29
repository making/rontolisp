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
 * populated by the {@link LispMacroExpander} expansions.
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
		seedConditionClass("CONDITION", null);
		seedConditionClass("SERIOUS-CONDITION", "CONDITION");
		seedConditionClass("ERROR", "SERIOUS-CONDITION");
		seedConditionClass("SIMPLE-ERROR", "ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedConditionClass("SIMPLE-CONDITION", "CONDITION", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedConditionClass("WARNING", "CONDITION");
		seedConditionClass("SIMPLE-WARNING", "WARNING", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedConditionClass("STYLE-WARNING", "WARNING");
		seedConditionClass("PARSE-ERROR", "ERROR");
		seedConditionClass("TYPE-ERROR", "ERROR", "DATUM", "EXPECTED-TYPE");
		// simple-type-error carries BOTH the type-error slots and the simple-condition
		// report slots -- CL's multiple inheritance flattened onto the type-error
		// branch, which is the branch a handler-case clause tests (alexandria's
		// sequence bounds checks signal it).
		seedConditionClass("SIMPLE-TYPE-ERROR", "TYPE-ERROR", "FORMAT-CONTROL", "FORMAT-ARGUMENTS");
		seedConditionClass("STREAM-ERROR", "ERROR");
		seedConditionClass(END_OF_FILE_CLASS_NAME, "STREAM-ERROR");
		// The read family signals this class when it runs out of input, so its report is
		// the message every backend prints for an uncaught end of file. It is a plain
		// string (no stream slot to name) so that the interpreter can raise the same
		// message from Java without evaluating a report lambda.
		registerConditionReport(END_OF_FILE_CLASS_NAME, new LispString(END_OF_FILE_MESSAGE));
		seedConditionClass("FILE-ERROR", "ERROR");
		seedConditionClass("ARITHMETIC-ERROR", "ERROR");
		seedConditionClass("DIVISION-BY-ZERO", "ARITHMETIC-ERROR");
		seedConditionClass("CONTROL-ERROR", "ERROR");
		seedConditionClass("PROGRAM-ERROR", "ERROR");
		seedConditionClass("PACKAGE-ERROR", "ERROR");
		seedConditionClass("CELL-ERROR", "ERROR", "NAME");
		seedConditionClass("UNBOUND-VARIABLE", "CELL-ERROR");
		seedConditionClass("UNDEFINED-FUNCTION", "CELL-ERROR");
		// The condition a read of an unbound slot signals (CLHS 7.7.2): name = the slot,
		// instance = the object it was read from.
		seedConditionClass("UNBOUND-SLOT", "CELL-ERROR", "INSTANCE");
		registerConditionReport("UNBOUND-SLOT", unboundSlotReport());
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

	private void seedConditionClass(String name, @Nullable String parent, String... slotNames) {
		ClassInfo parentInfo = parent == null ? null : this.classes.get(parent);
		java.util.List<SlotSpec> slots = new java.util.ArrayList<>(parentInfo == null ? List.of() : parentInfo.slots());
		for (String slotName : slotNames) {
			slots.add(new SlotSpec(slotName, slotName, LispNil.INSTANCE, true, ":" + slotName, List.of(), List.of(),
					"T"));
		}
		java.util.Set<String> ancestors = new java.util.LinkedHashSet<>();
		if (parentInfo != null) {
			ancestors.addAll(parentInfo.ancestors());
		}
		ancestors.add(name);
		registerClass(new ClassInfo(name, parent, List.copyOf(slots), java.util.Set.copyOf(ancestors)));
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
	 * @param readers the {@code :reader} function names
	 * @param accessors the {@code :accessor} function names (also setf places)
	 * @param type the package-stripped {@code :type} option name ({@code "t"} if none)
	 */
	public record SlotSpec(String name, String baseName, LispVal initform, boolean initformSupplied,
			String initargKeyword, List<String> readers, List<String> accessors, String type) {
	}

	/**
	 * One class: its canonical name, optional single superclass, full ordered slot list
	 * (inherited slots first), and the ancestor set including the class itself.
	 *
	 * @param name the canonical class name
	 * @param superclass the canonical superclass name, or null
	 * @param slots the full slot list, inherited slots first
	 * @param ancestors the normalized names of the class and all its ancestors
	 */
	public record ClassInfo(String name, @Nullable String superclass, List<SlotSpec> slots, Set<String> ancestors) {
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

		GenericInfo(String name, List<String> paramNames) {
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

		void paramNames(List<String> paramNames) {
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

		void markVariadic() {
			this.variadic = true;
		}

		/**
		 * The {@code :documentation} string, or null.
		 * @return the documentation
		 */
		@Nullable public String documentation() {
			return this.documentation;
		}

		void documentation(@Nullable String documentation) {
			this.documentation = documentation;
		}

		/**
		 * The methods keyed by canonical specializer key, in definition order.
		 * @return the methods
		 */
		public Map<String, MethodInfo> methods() {
			return this.methods;
		}

		int nextMethodIndex() {
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
	 * User {@code deftype} name (normalized) to its expansion -- the literal type
	 * specifier a zero-parameter {@code (deftype name () 'spec)} defines. Consulted by
	 * the shared type-test builder so {@code typep}/{@code typecase} resolve a
	 * user-defined type name (single- and double-colon spellings match, like
	 * {@link #findStructTag}).
	 */
	private final Map<String, LispVal> deftypes = new LinkedHashMap<>();

	/**
	 * The classes by normalized name, in definition order.
	 * @return the class registry
	 */
	public Map<String, ClassInfo> classes() {
		return this.classes;
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

	void registerClass(ClassInfo info) {
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
			info = new ClassInfo(info.name(), info.superclass(), info.slots(), Set.copyOf(merged));
		}
		this.classes.put(key, info);
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

	void registerGeneric(GenericInfo info) {
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

	void registerSlotPosition(String baseName, int position) {
		Integer existing = this.slotPositions.get(baseName);
		if (existing == null) {
			this.slotPositions.put(baseName, position);
		}
		else if (existing != position) {
			this.slotPositions.put(baseName, -1);
		}
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
