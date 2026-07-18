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
		seedConditionClass("condition", null);
		seedConditionClass("serious-condition", "condition");
		seedConditionClass("error", "serious-condition");
		seedConditionClass("simple-error", "error", "format-control", "format-arguments");
		seedConditionClass("simple-condition", "condition", "format-control", "format-arguments");
		seedConditionClass("warning", "condition");
		seedConditionClass("simple-warning", "warning", "format-control", "format-arguments");
		seedConditionClass("style-warning", "warning");
		seedConditionClass("parse-error", "error");
		seedConditionClass("type-error", "error", "datum", "expected-type");
		seedConditionClass("stream-error", "error");
		seedConditionClass("end-of-file", "stream-error");
		seedConditionClass("file-error", "error");
		seedConditionClass("arithmetic-error", "error");
		seedConditionClass("division-by-zero", "arithmetic-error");
		seedConditionClass("control-error", "error");
		seedConditionClass("program-error", "error");
		seedConditionClass("package-error", "error");
		seedConditionClass("cell-error", "error");
		seedConditionClass("unbound-variable", "cell-error");
		seedConditionClass("undefined-function", "cell-error");
	}

	private void seedConditionClass(String name, @Nullable String parent, String... slotNames) {
		ClassInfo parentInfo = parent == null ? null : this.classes.get(parent);
		java.util.List<SlotSpec> slots = new java.util.ArrayList<>(parentInfo == null ? List.of() : parentInfo.slots());
		for (String slotName : slotNames) {
			slots.add(new SlotSpec(slotName, slotName, LispNil.INSTANCE, ":" + slotName, List.of(), List.of()));
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
	 * {@code :initarg} keyword (defaults to the slot-name keyword), and the
	 * {@code :reader}/{@code :accessor} function names declared on it.
	 *
	 * @param name the canonical slot symbol name
	 * @param baseName the package-stripped slot name (constructor keywords use it)
	 * @param initform the default value expression ({@code nil} when omitted)
	 * @param initargKeyword the keyword accepted by the constructor, with the colon
	 * @param readers the {@code :reader} function names
	 * @param accessors the {@code :accessor} function names (also setf places)
	 */
	public record SlotSpec(String name, String baseName, LispVal initform, String initargKeyword, List<String> readers,
			List<String> accessors) {
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
				case DEFAULT -> "t";
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
	 * {@code ":before"}/{@code ":after"}/{@code ":around"})
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
		 * Whether any method dispatches on a {@code defclass} class (such dispatchers
		 * must be regenerated when a new subclass appears).
		 * @return true when a CLASS-specialized method exists
		 */
		public boolean hasClassMethod() {
			return this.methods.values()
				.stream()
				.anyMatch(m -> m.specializers().stream().anyMatch(s -> s.kind() == SpecializerKind.CLASS));
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
	 * Class name (normalized) to the extra parent types beyond the first -- the lite
	 * multiple-inheritance support of {@code define-condition}: the first parent provides
	 * the slot layout (single inheritance), the remaining parents contribute to the
	 * ancestor set only, so {@code typep}/{@code handler-case} match through them while
	 * their slots are not inherited. Merged into the class's ancestors when the class is
	 * registered.
	 */
	private final Map<String, Set<String>> pendingExtraAncestors = new LinkedHashMap<>();

	/**
	 * The classes by normalized name, in definition order.
	 * @return the class registry
	 */
	public Map<String, ClassInfo> classes() {
		return this.classes;
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
			return this.classes.get(qn.member());
		}
		ClassInfo found = null;
		for (ClassInfo candidate : this.classes.values()) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(candidate.name());
			if (qn != null && qn.member().equals(name)) {
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
		return this.slotPositions.get(baseName);
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

}
