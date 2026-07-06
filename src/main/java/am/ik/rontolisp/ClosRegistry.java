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

	/** The kind of the (first-parameter) specializer of a method. */
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
	 * One method of a generic function.
	 *
	 * @param kind the specializer kind
	 * @param eqlValue the literal compared against for {@link SpecializerKind#EQL}
	 * @param specializerName the normalized class/type name for CLASS/TYPE
	 * @param functionName the name of the generated method-body defun
	 */
	public record MethodInfo(SpecializerKind kind, @Nullable LispVal eqlValue, @Nullable String specializerName,
			String functionName) {
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
			return this.methods.values().stream().anyMatch(m -> m.kind() == SpecializerKind.CLASS);
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
		if (exact != null || PackageRegistry.splitQualified(name) != null) {
			return exact;
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

	void registerClass(ClassInfo info) {
		this.classes.put(normalize(info.name()), info);
	}

	void registerGeneric(GenericInfo info) {
		this.generics.put(normalize(info.name()), info);
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
