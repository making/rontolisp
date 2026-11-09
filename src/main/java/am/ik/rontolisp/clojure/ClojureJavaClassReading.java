package am.ik.rontolisp.clojure;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ConstantValueEntry;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * A dependency's Java class read as Clojure source, for a target where no host is (wasm):
 * the namespace {@link #NAMESPACE_PREFIX}{@code <binary name>} holding a {@code deftype*}
 * of the class's own name over its instance fields, a {@code definterface} of its
 * instance methods and constructors, a function per constructor arity ({@value #INIT})
 * and per static method, a var per static field ({@code .kb/clojure-frontend.md},
 * "Portable reading of a dependency's Java class"). Specter ships
 * {@code com.rpl.specter.MutableCell} (a field, its getter and setter) and
 * {@code com.rpl.specter.Util} ({@code new Object[size]}) in its jar: plain Java no wasm
 * module can run, read here as the Clojure it amounts to.
 *
 * <p>
 * What is read: a concrete class extending {@code Object} and implementing nothing whose
 * every member is within the subset -- fields of any type but {@code float} (an instance
 * field private to the type, so none public), methods without exception handlers over
 * locals, constants, the class's own fields, methods and constructors, arrays, integer,
 * long and double arithmetic, comparisons and every branch and switch. A method's blocks
 * become one {@code loop} over a {@code case} of the block's index where it branches, a
 * {@code let} where it does not. Values keep their Clojure spelling at every boundary --
 * a parameter, a result, a field, an array element -- and the JVM's on the operand stack,
 * so a {@code boolean} is {@code true}/{@code false} to Clojure and 0/1 to the code, a
 * {@code char} a character and an int. Anything else -- another class's member, an
 * {@code invokedynamic}, a handler, a {@code float} -- leaves the class unread: it stays
 * the host's, refused when called where no host is, as before.
 */
final class ClojureJavaClassReading {

	/** The prefix of the namespace a reading defines; the binary name follows it. */
	static final String NAMESPACE_PREFIX = "rontolisp.java.";

	/** The interface of the instance methods and constructors. */
	static final String MEMBERS = "Members";

	/**
	 * The name of a constructor: the interface method running its body on a fresh
	 * instance, and the namespace's function making one.
	 */
	static final String INIT = "<init>";

	/** The internal name of {@code java.lang.Object}. */
	private static final String OBJECT = "java/lang/Object";

	/**
	 * Names a member cannot take: a special form's (a field in scope or a method spelled
	 * like one would read as the form), and the type's own {@code this}.
	 */
	private static final Set<String> SPECIALS = Set.of("def", "if", "do", "let", "let*", "quote", "var", "fn", "fn*",
			"loop", "loop*", "recur", "throw", "try", "catch", "finally", "monitor-enter", "monitor-exit", "new",
			"set!", "case", "case*", "letfn", "letfn*", "deftype*", "reify*", "import*", "this");

	/**
	 * The reading of one class.
	 *
	 * @param binaryName the class's binary name ({@code com.rpl.specter.MutableCell})
	 * @param namespace the namespace the source defines
	 * @param source the namespace's Clojure source
	 * @param constructors the constructors' parameter counts
	 * @param statics the static methods' parameter counts by name
	 * @param fields the static fields by name: whether each is a constant (a value), else
	 * a cell its readers deref
	 */
	record Reading(String binaryName, String namespace, String source, Set<Integer> constructors,
			Map<String, Set<Integer>> statics, Map<String, Boolean> fields) {
	}

	/** Why a class is not read: a member or an instruction outside the subset. */
	static final class Unreadable extends RuntimeException {

		Unreadable(String why) {
			super(why, null, false, false);
		}

	}

	private ClojureJavaClassReading() {
	}

	/**
	 * Reads a class file.
	 * @param binaryName the class's binary name
	 * @param classFile its bytes
	 * @return the reading
	 * @throws Unreadable when the class is outside the subset
	 */
	static Reading read(String binaryName, byte[] classFile) {
		ClassModel model;
		try {
			model = ClassFile.of().parse(classFile);
		}
		catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
			throw new Unreadable("no class file: " + ex.getMessage());
		}
		return new ClassReader(binaryName, model).read();
	}

	/** One class's translation. */
	private static final class ClassReader {

		private final String binaryName;

		private final ClassModel model;

		private final String internalName;

		private final String simpleName;

		private final String namespace;

		/** The instance fields' descriptors by name, in declaration order. */
		private final Map<String, String> instanceFields = new LinkedHashMap<>();

		/** The static fields' descriptors by name. */
		private final Map<String, String> staticFields = new LinkedHashMap<>();

		/** The static fields holding a constant, to its literal. */
		private final Map<String, String> constants = new HashMap<>();

		/** The helper functions a method body called, by name, to their definitions. */
		private final Map<String, String> helpers = new TreeMap<>();

		ClassReader(String binaryName, ClassModel model) {
			this.binaryName = binaryName;
			this.model = model;
			this.internalName = model.thisClass().asInternalName();
			int dot = binaryName.lastIndexOf('.');
			this.simpleName = binaryName.substring(dot + 1);
			this.namespace = NAMESPACE_PREFIX + binaryName;
		}

		Reading read() {
			checkClass();
			readFields();
			Map<String, List<MethodModel>> instance = new TreeMap<>();
			Map<String, List<MethodModel>> statics = new TreeMap<>();
			List<MethodModel> constructors = new ArrayList<>();
			MethodModel clinit = null;
			for (MethodModel method : this.model.methods()) {
				String name = method.methodName().stringValue();
				Set<AccessFlag> flags = method.flags().flags();
				if (flags.contains(AccessFlag.NATIVE) || flags.contains(AccessFlag.ABSTRACT)) {
					throw new Unreadable(name + " has no code");
				}
				if (name.equals("<clinit>")) {
					clinit = method;
				}
				else if (name.equals(INIT)) {
					constructors.add(method);
				}
				else {
					checkName(name);
					(flags.contains(AccessFlag.STATIC) ? statics : instance)
						.computeIfAbsent(name, k -> new ArrayList<>())
						.add(method);
				}
			}
			checkOverloads(instance);
			checkOverloads(statics);
			checkOverloads(Map.of(INIT, constructors));
			StringBuilder out = new StringBuilder();
			out.append("(ns ").append(this.namespace).append("\n  \"The Java class ").append(this.binaryName);
			out.append(" read as Clojure where no host is (generated from its class file).\"");
			if (statics.containsKey(this.simpleName) || statics.containsKey(MEMBERS)) {
				throw new Unreadable("a static method named like its class or its interface");
			}
			out.append("\n  (:refer-clojure :exclude [").append(String.join(" ", statics.keySet())).append("]))\n");
			// the interface the instance methods and constructors are reached through
			out.append("(clojure.core/definterface ").append(MEMBERS);
			for (Map.Entry<String, List<MethodModel>> entry : instance.entrySet()) {
				if (isObjectMethod(entry.getKey(), entry.getValue())) {
					continue;
				}
				for (MethodModel method : entry.getValue()) {
					out.append("\n  (").append(entry.getKey()).append(" [").append(params(method, false)).append("])");
				}
			}
			for (MethodModel constructor : constructors) {
				out.append("\n  (").append(INIT).append(" [").append(params(constructor, false)).append("])");
			}
			out.append(")\n");
			if (!statics.isEmpty()) {
				out.append("(clojure.core/declare ").append(String.join(" ", statics.keySet())).append(")\n");
			}
			Set<String> constantNames = new TreeSet<>();
			for (Map.Entry<String, String> field : this.staticFields.entrySet()) {
				String constant = this.constants.get(field.getKey());
				if (constant != null) {
					constantNames.add(field.getKey());
					out.append("(def ").append(fieldVar(field.getKey())).append(' ').append(constant).append(")\n");
				}
				else {
					out.append("(def ")
						.append(fieldVar(field.getKey()))
						.append(" (clojure.core/atom ")
						.append(defaultValue(field.getValue()))
						.append("))\n");
				}
			}
			StringBuilder type = new StringBuilder();
			type.append("(deftype* ").append(this.simpleName).append(' ').append(this.binaryName).append(" [");
			List<String> fieldNames = new ArrayList<>(this.instanceFields.keySet());
			for (int i = 0; i < fieldNames.size(); i++) {
				type.append(i > 0 ? " " : "").append("^:unsynchronized-mutable ").append(fieldNames.get(i));
			}
			type.append("]\n  :implements [").append(this.namespace).append('.').append(MEMBERS).append(']');
			for (Map.Entry<String, List<MethodModel>> entry : instance.entrySet()) {
				for (MethodModel method : entry.getValue()) {
					type.append("\n  (")
						.append(entry.getKey())
						.append(" [this")
						.append(params(method, true))
						.append("]\n    ")
						.append(new MethodReader(this, method).body())
						.append(')');
				}
			}
			for (MethodModel constructor : constructors) {
				type.append("\n  (")
					.append(INIT)
					.append(" [this")
					.append(params(constructor, true))
					.append("]\n    ")
					.append(new MethodReader(this, constructor).body())
					.append(')');
			}
			type.append(")\n");
			StringBuilder functions = new StringBuilder();
			Set<Integer> constructorArities = new TreeSet<>();
			if (!constructors.isEmpty()) {
				functions.append("(clojure.core/defn ").append(INIT);
				for (MethodModel constructor : constructors) {
					int n = parameterCount(constructor);
					constructorArities.add(n);
					String args = params(constructor, false);
					functions.append("\n  ([")
						.append(args)
						.append("] (.")
						.append(INIT)
						.append(" (")
						.append(this.simpleName)
						.append('.');
					for (String desc : this.instanceFields.values()) {
						functions.append(' ').append(defaultValue(desc));
					}
					functions.append(')').append(args.isEmpty() ? "" : " " + args).append("))");
				}
				functions.append(")\n");
			}
			Map<String, Set<Integer>> staticArities = new TreeMap<>();
			for (Map.Entry<String, List<MethodModel>> entry : statics.entrySet()) {
				functions.append("(clojure.core/defn ").append(entry.getKey());
				for (MethodModel method : entry.getValue()) {
					staticArities.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).add(parameterCount(method));
					functions.append("\n  ([")
						.append(params(method, false))
						.append("]\n    ")
						.append(new MethodReader(this, method).body())
						.append(')');
				}
				functions.append(")\n");
			}
			String initializer = clinit == null ? null : new MethodReader(this, clinit).body();
			for (String helper : this.helpers.values()) {
				out.append(helper).append('\n');
			}
			out.append(type).append(functions);
			if (initializer != null) {
				out.append(initializer).append('\n');
			}
			Map<String, Boolean> fieldKinds = new TreeMap<>();
			for (String name : this.staticFields.keySet()) {
				fieldKinds.put(name, constantNames.contains(name));
			}
			return new Reading(this.binaryName, this.namespace, out.toString(), Set.copyOf(constructorArities),
					Map.copyOf(staticArities), Map.copyOf(fieldKinds));
		}

		private void checkClass() {
			Set<AccessFlag> flags = this.model.flags().flags();
			if (flags.contains(AccessFlag.INTERFACE) || flags.contains(AccessFlag.ABSTRACT)
					|| flags.contains(AccessFlag.ENUM) || flags.contains(AccessFlag.ANNOTATION)) {
				throw new Unreadable("not a concrete class");
			}
			String superclass = this.model.superclass().map(e -> e.asInternalName()).orElse(null);
			if (!OBJECT.equals(superclass)) {
				throw new Unreadable("extends " + superclass);
			}
			if (!this.model.interfaces().isEmpty()) {
				throw new Unreadable("implements an interface");
			}
			checkName(this.simpleName);
		}

		private void readFields() {
			for (FieldModel field : this.model.fields()) {
				String name = field.fieldName().stringValue();
				String desc = field.fieldType().stringValue();
				checkName(name);
				checkType(desc);
				Set<AccessFlag> flags = field.flags().flags();
				if (flags.contains(AccessFlag.STATIC)) {
					this.staticFields.put(name, desc);
					if (flags.contains(AccessFlag.FINAL)) {
						field.findAttribute(Attributes.constantValue())
							.map(ConstantValueAttribute::constant)
							.ifPresent(constant -> this.constants.put(name, constantLiteral(constant, desc)));
					}
				}
				else {
					if (flags.contains(AccessFlag.PUBLIC)) {
						throw new Unreadable("public instance field " + name);
					}
					this.instanceFields.put(name, desc);
				}
			}
		}

		private static String constantLiteral(ConstantValueEntry constant, String desc) {
			Object value = constant.constantValue();
			return switch (desc) {
				case "Z" -> ((Integer) value) != 0 ? "true" : "false";
				case "C" -> charLiteral((Integer) value);
				default -> literal(value);
			};
		}

		private static void checkOverloads(Map<String, List<MethodModel>> methods) {
			for (Map.Entry<String, List<MethodModel>> entry : methods.entrySet()) {
				Set<Integer> counts = new HashSet<>();
				for (MethodModel method : entry.getValue()) {
					if (!counts.add(parameterCount(method))) {
						throw new Unreadable(entry.getKey() + " is overloaded by type");
					}
				}
			}
		}

		private static boolean isObjectMethod(String name, List<MethodModel> methods) {
			if (methods.size() != 1) {
				return false;
			}
			String desc = methods.get(0).methodType().stringValue();
			return name.equals("toString") && desc.equals("()Ljava/lang/String;")
					|| name.equals("hashCode") && desc.equals("()I")
					|| name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z");
		}

		private static void checkName(String name) {
			if (name.isEmpty() || SPECIALS.contains(name) || name.chars().anyMatch(c -> c == '.' || c == '/')) {
				throw new Unreadable("the name " + name);
			}
		}

		static void checkType(String desc) {
			if (desc.indexOf('F') >= 0 && !desc.startsWith("L") && !desc.startsWith("[L")) {
				throw new Unreadable("a float");
			}
		}

		private static int parameterCount(MethodModel method) {
			return method.methodTypeSymbol().parameterCount();
		}

		/**
		 * The parameter names of a method, {@code arg-0 arg-1 ...} (no Java name has a
		 * hyphen, so none is a field's), after a space when {@code leading}.
		 */
		private static String params(MethodModel method, boolean leading) {
			StringBuilder out = new StringBuilder();
			int n = parameterCount(method);
			for (int i = 0; i < n; i++) {
				if (leading || i > 0) {
					out.append(' ');
				}
				out.append("arg-").append(i);
			}
			return out.toString();
		}

		/** A helper the generated code calls, defined once ahead of the type. */
		String helper(String name, String definition) {
			this.helpers.putIfAbsent(name, definition);
			return name;
		}

	}

	/** One method's translation into a Clojure body. */
	private static final class MethodReader {

		private final ClassReader owner;

		private final MethodModel method;

		private final boolean isStatic;

		private final MethodTypeDesc type;

		private final List<Instruction> code = new ArrayList<>();

		/** The instruction index each label stands before. */
		private final Map<Label, Integer> labels = new HashMap<>();

		private final int maxLocals;

		private int temps;

		MethodReader(ClassReader owner, MethodModel method) {
			this.owner = owner;
			this.method = method;
			this.isStatic = method.flags().has(AccessFlag.STATIC);
			this.type = method.methodTypeSymbol();
			CodeModel model = method.code().orElseThrow(() -> new Unreadable("no code"));
			if (!model.exceptionHandlers().isEmpty()) {
				throw new Unreadable(method.methodName().stringValue() + " handles an exception");
			}
			for (CodeElement element : model) {
				if (element instanceof LabelTarget target) {
					this.labels.put(target.label(), this.code.size());
				}
				else if (element instanceof Instruction instruction) {
					this.code.add(instruction);
				}
			}
			this.maxLocals = model instanceof CodeAttribute attribute ? attribute.maxLocals() : 256;
			for (ClassDesc param : this.type.parameterList()) {
				ClassReader.checkType(param.descriptorString());
			}
			ClassReader.checkType(this.type.returnType().descriptorString());
		}

		/** A value on the operand stack or in a local. */
		private record Value(String expr, TypeKind kind, @Nullable String desc, int uninitialized) {

			/**
			 * A value of its computational kind: a {@code boolean}, {@code byte},
			 * {@code char} or {@code short} is an {@code int} on the stack, so two paths
			 * meeting at a branch target agree on it.
			 */
			Value {
				kind = kind.asLoadable();
			}

			Value(String expr, TypeKind kind, @Nullable String desc) {
				this(expr, kind, desc, 0);
			}

			int slots() {
				return this.kind.slotSize();
			}

		}

		/**
		 * A block's entry state: its stack's kinds and descriptors and its locals'
		 * descriptors, a descriptor null where the paths into it disagree.
		 */
		private record Entry(List<TypeKind> kinds, List<@Nullable String> descs, List<@Nullable String> locals) {

			Entry(List<TypeKind> kinds, List<@Nullable String> locals) {
				this(kinds, List.of(), locals);
			}

			Entry merge(Entry other) {
				if (!this.kinds.equals(other.kinds)) {
					throw new Unreadable("a stack of two shapes at one branch target");
				}
				return new Entry(this.kinds, agreed(this.descs, other.descs), agreed(this.locals, other.locals));
			}

			private static List<@Nullable String> agreed(List<@Nullable String> a, List<@Nullable String> b) {
				List<@Nullable String> out = new ArrayList<>();
				for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
					String x = i < a.size() ? a.get(i) : null;
					String y = i < b.size() ? b.get(i) : null;
					out.add(x != null && x.equals(y) ? x : null);
				}
				return Collections.unmodifiableList(out);
			}

		}

		String body() {
			List<Integer> starts = blockStarts();
			// the shapes first: every block's entry stack and local descriptors, merged
			// over its predecessors, and the deepest stack a jump carries
			Map<Integer, Entry> entries = new LinkedHashMap<>();
			entries.put(0, new Entry(List.of(), entryLocals()));
			Deque<Integer> work = new ArrayDeque<>(List.of(0));
			int maxStack = 0;
			while (!work.isEmpty()) {
				int start = work.pop();
				Entry entry = Objects.requireNonNull(entries.get(start));
				Block block = new Block(starts, start, end(starts, start), entry);
				block.translate();
				for (Map.Entry<Integer, Entry> next : block.successors.entrySet()) {
					maxStack = Math.max(maxStack, next.getValue().kinds().size());
					Entry known = entries.get(next.getKey());
					Entry merged = known == null ? next.getValue() : known.merge(next.getValue());
					if (!merged.equals(known)) {
						entries.put(next.getKey(), merged);
						work.push(next.getKey());
					}
				}
			}
			this.loopStack = maxStack;
			String entryBindings = entryBindings();
			if (starts.size() == 1) {
				return wrapLet(entryBindings,
						new Block(starts, 0, end(starts, 0), Objects.requireNonNull(entries.get(0))).translate());
			}
			StringBuilder loop = new StringBuilder("(clojure.core/loop [pc- 0");
			for (int slot = firstLocal(); slot < this.maxLocals; slot++) {
				loop.append(' ').append(localName(slot)).append(' ').append(initialLocal(slot));
			}
			for (int i = 0; i < maxStack; i++) {
				loop.append(" stack-").append(i).append(" nil");
			}
			loop.append("]\n      (clojure.core/case pc-");
			for (int i = 0; i < starts.size(); i++) {
				Entry entry = entries.get(starts.get(i));
				if (entry == null) {
					continue; // no path reaches it
				}
				loop.append("\n        ")
					.append(i)
					.append(' ')
					.append(new Block(starts, starts.get(i), end(starts, starts.get(i)), entry).translate());
			}
			loop.append("))");
			return wrapLet(entryBindings, loop.toString());
		}

		/** The stack depth every {@code recur} passes, once the blocks are known. */
		private int loopStack;

		private static String wrapLet(String bindings, String body) {
			return bindings.isEmpty() ? body : "(clojure.core/let [" + bindings + "] " + body + ")";
		}

		/** The descriptors of the locals where the method starts: its parameters'. */
		private List<@Nullable String> entryLocals() {
			List<@Nullable String> descs = new ArrayList<>();
			for (int i = 0; i < this.maxLocals; i++) {
				descs.add(null);
			}
			int slot = this.isStatic ? 0 : 1;
			for (ClassDesc param : this.type.parameterList()) {
				if (slot < descs.size()) {
					descs.set(slot, param.descriptorString());
				}
				slot += TypeKind.from(param).slotSize();
			}
			return Collections.unmodifiableList(descs);
		}

		/**
		 * The parameters bound to their locals, converted from their Clojure spelling.
		 */
		private String entryBindings() {
			StringBuilder out = new StringBuilder();
			int slot = this.isStatic ? 0 : 1;
			List<ClassDesc> params = this.type.parameterList();
			for (int i = 0; i < params.size(); i++) {
				String desc = params.get(i).descriptorString();
				if (!out.isEmpty()) {
					out.append(' ');
				}
				out.append(localName(slot)).append(' ').append(fromClojure("arg-" + i, desc));
				slot += TypeKind.from(params.get(i)).slotSize();
			}
			return out.toString();
		}

		/** A local's value where the method starts: its parameter, else nil. */
		private String initialLocal(int slot) {
			int at = this.isStatic ? 0 : 1;
			for (ClassDesc param : this.type.parameterList()) {
				if (at == slot) {
					return localName(slot);
				}
				at += TypeKind.from(param).slotSize();
			}
			return "nil";
		}

		private int firstLocal() {
			return this.isStatic ? 0 : 1;
		}

		private static String localName(int slot) {
			return "local-" + slot;
		}

		private List<Integer> blockStarts() {
			Set<Integer> starts = new TreeSet<>();
			starts.add(0);
			for (int i = 0; i < this.code.size(); i++) {
				Instruction instruction = this.code.get(i);
				switch (instruction) {
					case BranchInstruction branch -> {
						starts.add(target(branch.target()));
						starts.add(i + 1);
					}
					case TableSwitchInstruction table -> {
						starts.add(target(table.defaultTarget()));
						for (SwitchCase c : table.cases()) {
							starts.add(target(c.target()));
						}
						starts.add(i + 1);
					}
					case LookupSwitchInstruction lookup -> {
						starts.add(target(lookup.defaultTarget()));
						for (SwitchCase c : lookup.cases()) {
							starts.add(target(c.target()));
						}
						starts.add(i + 1);
					}
					case ReturnInstruction ignored -> starts.add(i + 1);
					case ThrowInstruction ignored -> starts.add(i + 1);
					default -> {
					}
				}
			}
			starts.removeIf(start -> start >= this.code.size());
			return new ArrayList<>(starts);
		}

		private int end(List<Integer> starts, int start) {
			int at = starts.indexOf(start);
			return at + 1 < starts.size() ? starts.get(at + 1) : this.code.size();
		}

		private int target(Label label) {
			Integer at = this.labels.get(label);
			if (at == null) {
				throw new Unreadable("a branch to no instruction");
			}
			return at;
		}

		/** One basic block's translation. */
		private final class Block {

			private final int start;

			private final int end;

			private final Deque<Value> stack = new ArrayDeque<>();

			private final @Nullable Value[] locals;

			private final List<String> bindings = new ArrayList<>();

			/** Each successor block's start to the stack it is entered with. */
			final Map<Integer, Entry> successors = new LinkedHashMap<>();

			private final List<Integer> blockStarts;

			Block(List<Integer> blockStarts, int start, int end, Entry entry) {
				this.start = start;
				this.end = end;
				this.locals = new Value[MethodReader.this.maxLocals];
				this.blockStarts = blockStarts;
				if (!MethodReader.this.isStatic) {
					this.locals[0] = new Value("this", TypeKind.REFERENCE,
							"L" + MethodReader.this.owner.internalName + ";");
				}
				for (int slot = firstLocal(); slot < MethodReader.this.maxLocals; slot++) {
					this.locals[slot] = new Value(localName(slot), TypeKind.REFERENCE,
							slot < entry.locals().size() ? entry.locals().get(slot) : null);
				}
				for (int i = 0; i < entry.kinds().size(); i++) {
					this.stack.addLast(new Value("stack-" + i, entry.kinds().get(i),
							i < entry.descs().size() ? entry.descs().get(i) : null));
				}
			}

			String translate() {
				String terminal = null;
				for (int i = this.start; i < this.end && terminal == null; i++) {
					terminal = step(MethodReader.this.code.get(i), i);
				}
				if (terminal == null) {
					// falls through into the next block
					terminal = jump(this.end);
				}
				return this.bindings.isEmpty() ? terminal
						: "(clojure.core/let [" + String.join(" ", this.bindings) + "] " + terminal + ")";
			}

			private String bind(String expr) {
				String name = "t-" + (++MethodReader.this.temps);
				this.bindings.add(name + " " + expr);
				return name;
			}

			private void statement(String expr) {
				this.bindings.add("_ " + expr);
			}

			private void push(String expr, TypeKind kind, @Nullable String desc) {
				this.stack.push(new Value(expr, kind, desc));
			}

			private void pushBound(String expr, TypeKind kind, @Nullable String desc) {
				push(bind(expr), kind, desc);
			}

			private Value pop() {
				Value value = this.stack.poll();
				if (value == null) {
					throw new Unreadable("an empty stack");
				}
				if (value.uninitialized() != 0) {
					throw new Unreadable("an object used before its constructor ran");
				}
				return value;
			}

			private Value popAny() {
				Value value = this.stack.poll();
				if (value == null) {
					throw new Unreadable("an empty stack");
				}
				return value;
			}

			/**
			 * The terminal form of a jump to the block starting at an instruction: the
			 * loop's next round with every local and the stack, which the target is
			 * entered with.
			 */
			private String jump(int to) {
				List<Value> carried = new ArrayList<>(this.stack);
				Collections.reverse(carried);
				List<TypeKind> kinds = new ArrayList<>();
				List<@Nullable String> descs = new ArrayList<>();
				for (Value value : carried) {
					if (value.uninitialized() != 0) {
						throw new Unreadable("an object before its constructor ran across a branch");
					}
					kinds.add(value.kind());
					descs.add(value.desc());
				}
				List<@Nullable String> localDescs = new ArrayList<>();
				for (Value local : this.locals) {
					localDescs.add(local == null ? null : local.desc());
				}
				this.successors.put(to, new Entry(List.copyOf(kinds), Collections.unmodifiableList(descs),
						Collections.unmodifiableList(localDescs)));
				StringBuilder out = new StringBuilder("(recur ").append(this.blockStarts.indexOf(to));
				for (int slot = firstLocal(); slot < MethodReader.this.maxLocals; slot++) {
					Value local = this.locals[slot];
					out.append(' ').append(local == null ? "nil" : local.expr());
				}
				for (Value value : carried) {
					out.append(' ').append(value.expr());
				}
				for (int i = carried.size(); i < MethodReader.this.loopStack; i++) {
					out.append(" nil");
				}
				return out.append(')').toString();
			}

			/**
			 * Translates one instruction; answers the block's terminal form when it ends
			 * the block.
			 */
			private @Nullable String step(Instruction instruction, int index) {
				switch (instruction) {
					case LoadInstruction load -> {
						Value local = this.locals[load.slot()];
						if (local == null) {
							throw new Unreadable("a local read before it is written");
						}
						this.stack.push(new Value(local.expr(), load.typeKind(), local.desc()));
					}
					case StoreInstruction store -> {
						Value value = popAny();
						if (value.uninitialized() != 0) {
							throw new Unreadable("an object stored before its constructor ran");
						}
						String name = localName(store.slot()) + "-" + (++MethodReader.this.temps);
						this.bindings.add(name + " " + value.expr());
						this.locals[store.slot()] = new Value(name, store.typeKind(), value.desc());
					}
					case IncrementInstruction increment -> {
						Value local = this.locals[increment.slot()];
						if (local == null) {
							throw new Unreadable("a local read before it is written");
						}
						String name = localName(increment.slot()) + "-" + (++MethodReader.this.temps);
						this.bindings.add(name + " (clojure.core/unchecked-add-int " + local.expr() + " "
								+ increment.constant() + ")");
						this.locals[increment.slot()] = new Value(name, TypeKind.INT, null);
					}
					case ConstantInstruction constant -> constant(constant);
					case FieldInstruction field -> field(field);
					case InvokeInstruction invoke -> invoke(invoke);
					case NewObjectInstruction created -> {
						if (!created.className().asInternalName().equals(MethodReader.this.owner.internalName)) {
							throw new Unreadable("a new " + created.className().asInternalName());
						}
						this.stack.push(new Value("nil", TypeKind.REFERENCE, null, index + 1));
					}
					case NewReferenceArrayInstruction array ->
						pushBound("(clojure.core/object-array " + pop().expr() + ")", TypeKind.REFERENCE,
								"[" + array.componentType().asSymbol().descriptorString());
					case NewPrimitiveArrayInstruction array -> {
						String maker = switch (array.typeKind()) {
							case BOOLEAN -> "boolean-array";
							case BYTE -> "byte-array";
							case CHAR -> "char-array";
							case SHORT -> "short-array";
							case INT -> "int-array";
							case LONG -> "long-array";
							case DOUBLE -> "double-array";
							default -> throw new Unreadable("an array of " + array.typeKind());
						};
						pushBound("(clojure.core/" + maker + " " + pop().expr() + ")", TypeKind.REFERENCE,
								"[" + array.typeKind().upperBound().descriptorString());
					}
					case ArrayLoadInstruction load -> {
						Value at = pop();
						Value array = pop();
						String element = elementDesc(load.typeKind(), array);
						pushBound(fromClojure("(clojure.core/aget " + array.expr() + " " + at.expr() + ")", element),
								load.typeKind(), element.startsWith("[") || element.startsWith("L") ? element : null);
					}
					case ArrayStoreInstruction store -> {
						Value value = pop();
						Value at = pop();
						Value array = pop();
						String element = elementDesc(store.typeKind(), array);
						statement("(clojure.core/aset " + array.expr() + " " + at.expr() + " "
								+ toClojure(narrowed(value.expr(), element), element) + ")");
					}
					case OperatorInstruction operator -> operator(operator);
					case ConvertInstruction convert -> convert(convert);
					case StackInstruction op -> stackOp(op.opcode());
					case TypeCheckInstruction check -> {
						Value value = pop();
						String target = check.type().asInternalName();
						if (check.opcode() == Opcode.CHECKCAST) {
							this.stack.push(new Value(value.expr(), TypeKind.REFERENCE,
									target.startsWith("[") ? target : "L" + target + ";"));
						}
						else {
							pushBound(
									"(if (clojure.core/instance? " + className(target) + " " + value.expr() + ") 1 0)",
									TypeKind.INT, null);
						}
					}
					case MonitorInstruction ignored -> pop();
					case NopInstruction ignored -> {
					}
					case ReturnInstruction ret -> {
						return ret(ret.typeKind());
					}
					case ThrowInstruction ignored -> {
						return "(throw " + pop().expr() + ")";
					}
					case BranchInstruction branch -> {
						return branch(branch, index);
					}
					case TableSwitchInstruction table -> {
						Value key = pop();
						StringBuilder out = new StringBuilder(
								"(clojure.core/case (clojure.core/long " + key.expr() + ")");
						for (SwitchCase c : table.cases()) {
							out.append(' ').append(c.caseValue()).append(' ').append(jump(target(c.target())));
						}
						return out.append(' ').append(jump(target(table.defaultTarget()))).append(')').toString();
					}
					case LookupSwitchInstruction lookup -> {
						Value key = pop();
						StringBuilder out = new StringBuilder(
								"(clojure.core/case (clojure.core/long " + key.expr() + ")");
						for (SwitchCase c : lookup.cases()) {
							out.append(' ').append(c.caseValue()).append(' ').append(jump(target(c.target())));
						}
						return out.append(' ').append(jump(target(lookup.defaultTarget()))).append(')').toString();
					}
					default -> throw new Unreadable("the instruction " + instruction.opcode());
				}
				return null;
			}

			private void constant(ConstantInstruction constant) {
				Object value = constant.constantValue();
				TypeKind kind = constant.typeKind();
				if (value == null) {
					push("nil", TypeKind.REFERENCE, null);
				}
				else if (value instanceof String string) {
					push(stringLiteral(string), TypeKind.REFERENCE, "Ljava/lang/String;");
				}
				else if (value instanceof Integer || value instanceof Long || value instanceof Double) {
					push(literal(value), kind, null);
				}
				else {
					throw new Unreadable("the constant " + value);
				}
			}

			private void field(FieldInstruction field) {
				if (!field.owner().asInternalName().equals(MethodReader.this.owner.internalName)) {
					throw new Unreadable("a field of " + field.owner().asInternalName());
				}
				String name = field.name().stringValue();
				String desc = field.type().stringValue();
				TypeKind kind = TypeKind.fromDescriptor(desc);
				switch (field.opcode()) {
					case GETFIELD -> {
						Value target = pop();
						requireSelf(target, name);
						pushBound(fromClojure(name, desc), kind, desc);
					}
					case PUTFIELD -> {
						Value value = pop();
						Value target = pop();
						requireSelf(target, name);
						statement("(set! " + name + " " + toClojure(value.expr(), desc) + ")");
					}
					case GETSTATIC -> {
						String constant = MethodReader.this.owner.constants.get(name);
						if (constant != null) {
							push(fromClojure(constant, desc), kind, desc);
						}
						else {
							pushBound(fromClojure("(clojure.core/deref " + fieldVar(name) + ")", desc), kind, desc);
						}
					}
					case PUTSTATIC -> {
						if (MethodReader.this.owner.constants.containsKey(name)) {
							pop();
						}
						else {
							statement("(clojure.core/reset! " + fieldVar(name) + " " + toClojure(pop().expr(), desc)
									+ ")");
						}
					}
					default -> throw new Unreadable("the field instruction " + field.opcode());
				}
			}

			private void requireSelf(Value target, String field) {
				if (!target.expr().equals("this")) {
					throw new Unreadable("the field " + field + " of another instance");
				}
			}

			private void invoke(InvokeInstruction invoke) {
				String ownerName = invoke.owner().asInternalName();
				String name = invoke.name().stringValue();
				MethodTypeDesc desc = invoke.typeSymbol();
				List<Value> args = new ArrayList<>();
				for (int i = 0; i < desc.parameterCount(); i++) {
					args.add(0, pop());
				}
				StringBuilder call = new StringBuilder();
				if (invoke.opcode() == Opcode.INVOKESPECIAL && name.equals(INIT)) {
					Value target = popAny();
					if (ownerName.equals(OBJECT) && args.isEmpty() && target.expr().equals("this")) {
						return; // the super constructor, which does nothing
					}
					if (!ownerName.equals(MethodReader.this.owner.internalName)) {
						throw new Unreadable("a constructor of " + ownerName);
					}
					if (target.expr().equals("this")) {
						statement(callOf("(." + INIT + " this", args, desc));
						return;
					}
					if (target.uninitialized() == 0) {
						throw new Unreadable("a constructor of a made object");
					}
					String made = bind(callOf("(" + INIT, args, desc));
					replaceUninitialized(target.uninitialized(), made);
					return;
				}
				if (!ownerName.equals(MethodReader.this.owner.internalName)) {
					throw new Unreadable("a method of " + ownerName);
				}
				if (invoke.opcode() == Opcode.INVOKESTATIC) {
					call.append('(').append(name);
				}
				else if (invoke.opcode() == Opcode.INVOKEVIRTUAL || invoke.opcode() == Opcode.INVOKESPECIAL) {
					call.append("(.").append(name).append(' ').append(pop().expr());
				}
				else {
					throw new Unreadable("the invoke " + invoke.opcode());
				}
				String form = callOf(call.toString(), args, desc);
				ClassDesc result = desc.returnType();
				if (result.descriptorString().equals("V")) {
					statement(form);
				}
				else {
					pushBound(fromClojure(form, result.descriptorString()), TypeKind.from(result),
							result.descriptorString());
				}
			}

			private String callOf(String head, List<Value> args, MethodTypeDesc desc) {
				StringBuilder out = new StringBuilder(head);
				for (int i = 0; i < args.size(); i++) {
					out.append(' ').append(toClojure(args.get(i).expr(), desc.parameterType(i).descriptorString()));
				}
				return out.append(')').toString();
			}

			private void replaceUninitialized(int id, String made) {
				List<Value> rebuilt = new ArrayList<>();
				for (Value value : this.stack) {
					rebuilt.add(value.uninitialized() == id
							? new Value(made, TypeKind.REFERENCE, "L" + MethodReader.this.owner.internalName + ";")
							: value);
				}
				this.stack.clear();
				this.stack.addAll(rebuilt);
			}

			private void operator(OperatorInstruction operator) {
				Opcode op = operator.opcode();
				if (op == Opcode.ARRAYLENGTH) {
					pushBound("(clojure.core/alength " + pop().expr() + ")", TypeKind.INT, null);
					return;
				}
				if (op == Opcode.INEG || op == Opcode.LNEG || op == Opcode.DNEG) {
					String fn = op == Opcode.INEG ? "unchecked-negate-int"
							: op == Opcode.LNEG ? "unchecked-negate" : "-";
					pushBound("(clojure.core/" + fn + " " + pop().expr() + ")", operator.typeKind(), null);
					return;
				}
				Value b = pop();
				Value a = pop();
				String x = a.expr();
				String y = b.expr();
				String form = switch (op) {
					case IADD -> "(clojure.core/unchecked-add-int " + x + " " + y + ")";
					case ISUB -> "(clojure.core/unchecked-subtract-int " + x + " " + y + ")";
					case IMUL -> "(clojure.core/unchecked-multiply-int " + x + " " + y + ")";
					case IDIV -> "(clojure.core/unchecked-divide-int " + x + " " + y + ")";
					case IREM -> "(clojure.core/unchecked-remainder-int " + x + " " + y + ")";
					case LADD -> "(clojure.core/unchecked-add " + x + " " + y + ")";
					case LSUB -> "(clojure.core/unchecked-subtract " + x + " " + y + ")";
					case LMUL -> "(clojure.core/unchecked-multiply " + x + " " + y + ")";
					case LDIV -> "(clojure.core/quot " + x + " " + y + ")";
					case LREM -> "(clojure.core/rem " + x + " " + y + ")";
					case DADD -> "(clojure.core/+ " + x + " " + y + ")";
					case DSUB -> "(clojure.core/- " + x + " " + y + ")";
					case DMUL -> "(clojure.core/* " + x + " " + y + ")";
					case DDIV -> "(clojure.core// " + x + " " + y + ")";
					case DREM -> "(clojure.core/rem " + x + " " + y + ")";
					case ISHL -> "(clojure.core/unchecked-int (clojure.core/bit-shift-left " + x
							+ " (clojure.core/bit-and " + y + " 31)))";
					case ISHR -> "(clojure.core/bit-shift-right " + x + " (clojure.core/bit-and " + y + " 31))";
					case IUSHR -> "(clojure.core/unchecked-int (clojure.core/unsigned-bit-shift-right "
							+ "(clojure.core/bit-and " + x + " 4294967295) (clojure.core/bit-and " + y + " 31)))";
					case LSHL -> "(clojure.core/bit-shift-left " + x + " (clojure.core/bit-and " + y + " 63))";
					case LSHR -> "(clojure.core/bit-shift-right " + x + " (clojure.core/bit-and " + y + " 63))";
					case LUSHR ->
						"(clojure.core/unsigned-bit-shift-right " + x + " (clojure.core/bit-and " + y + " 63))";
					case IAND, LAND -> "(clojure.core/bit-and " + x + " " + y + ")";
					case IOR, LOR -> "(clojure.core/bit-or " + x + " " + y + ")";
					case IXOR, LXOR -> "(clojure.core/bit-xor " + x + " " + y + ")";
					case LCMP -> "(clojure.core/compare " + x + " " + y + ")";
					case DCMPL, DCMPG ->
						"(if (clojure.core/or (clojure.core/NaN? " + x + ") (clojure.core/NaN? " + y + ")) "
								+ (op == Opcode.DCMPG ? "1" : "-1") + " (clojure.core/compare " + x + " " + y + "))";
					default -> throw new Unreadable("the operator " + op);
				};
				TypeKind kind = switch (op) {
					case LCMP, DCMPL, DCMPG -> TypeKind.INT;
					default -> operator.typeKind();
				};
				pushBound(form, kind, null);
			}

			private void convert(ConvertInstruction convert) {
				Value value = pop();
				String x = value.expr();
				String form = switch (convert.opcode()) {
					case I2L -> x;
					case L2I -> "(clojure.core/unchecked-int " + x + ")";
					case I2D, L2D -> "(clojure.core/double " + x + ")";
					case D2I -> "(" + MethodReader.this.owner.helper("d2i-",
							"(clojure.core/defn- d2i- [x] (clojure.core/cond (clojure.core/NaN? x) 0"
									+ " (clojure.core/>= x 2147483647.0) 2147483647"
									+ " (clojure.core/<= x -2147483648.0) -2147483648"
									+ " :else (clojure.core/long x)))")
							+ " " + x + ")";
					case D2L -> "(" + MethodReader.this.owner.helper("d2l-",
							"(clojure.core/defn- d2l- [x] (clojure.core/cond (clojure.core/NaN? x) 0"
									+ " (clojure.core/>= x 9.223372036854775807E18) 9223372036854775807"
									+ " (clojure.core/<= x -9.223372036854775808E18) -9223372036854775808"
									+ " :else (clojure.core/long x)))")
							+ " " + x + ")";
					case I2B -> "(clojure.core/unchecked-byte " + x + ")";
					case I2S -> "(clojure.core/unchecked-short " + x + ")";
					case I2C -> "(clojure.core/bit-and " + x + " 65535)";
					default -> throw new Unreadable("the conversion " + convert.opcode());
				};
				if (form.equals(x)) {
					this.stack.push(new Value(x, convert.toType(), null));
				}
				else {
					pushBound(form, convert.toType(), null);
				}
			}

			private void stackOp(Opcode op) {
				switch (op) {
					case POP -> popAny();
					case POP2 -> {
						Value top = popAny();
						if (top.slots() == 1) {
							popAny();
						}
					}
					case DUP -> {
						Value top = popAny();
						this.stack.push(top);
						this.stack.push(top);
					}
					case DUP_X1 -> {
						Value v1 = popAny();
						Value v2 = popAny();
						this.stack.push(v1);
						this.stack.push(v2);
						this.stack.push(v1);
					}
					case DUP_X2 -> {
						Value v1 = popAny();
						Value v2 = popAny();
						if (v2.slots() == 2) {
							this.stack.push(v1);
							this.stack.push(v2);
							this.stack.push(v1);
						}
						else {
							Value v3 = popAny();
							this.stack.push(v1);
							this.stack.push(v3);
							this.stack.push(v2);
							this.stack.push(v1);
						}
					}
					case DUP2 -> {
						Value v1 = popAny();
						if (v1.slots() == 2) {
							this.stack.push(v1);
							this.stack.push(v1);
						}
						else {
							Value v2 = popAny();
							this.stack.push(v2);
							this.stack.push(v1);
							this.stack.push(v2);
							this.stack.push(v1);
						}
					}
					case DUP2_X1 -> {
						Value v1 = popAny();
						if (v1.slots() == 2) {
							Value v2 = popAny();
							this.stack.push(v1);
							this.stack.push(v2);
							this.stack.push(v1);
						}
						else {
							Value v2 = popAny();
							Value v3 = popAny();
							this.stack.push(v2);
							this.stack.push(v1);
							this.stack.push(v3);
							this.stack.push(v2);
							this.stack.push(v1);
						}
					}
					case SWAP -> {
						Value v1 = popAny();
						Value v2 = popAny();
						this.stack.push(v1);
						this.stack.push(v2);
					}
					default -> throw new Unreadable("the stack instruction " + op);
				}
			}

			private String ret(TypeKind kind) {
				if (kind == TypeKind.VOID) {
					return MethodReader.this.method.methodName().stringValue().equals(INIT) ? "this" : "nil";
				}
				Value value = pop();
				return toClojure(value.expr(), MethodReader.this.type.returnType().descriptorString());
			}

			private String branch(BranchInstruction branch, int index) {
				Opcode op = branch.opcode();
				int to = target(branch.target());
				if (op == Opcode.GOTO || op == Opcode.GOTO_W) {
					return jump(to);
				}
				String test;
				switch (op) {
					case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> test = compare(op, pop().expr(), "0");
					case IFNULL -> test = "(clojure.core/nil? " + pop().expr() + ")";
					case IFNONNULL -> test = "(clojure.core/some? " + pop().expr() + ")";
					case IF_ACMPEQ, IF_ACMPNE -> {
						Value b = pop();
						Value a = pop();
						test = (op == Opcode.IF_ACMPEQ ? "" : "(clojure.core/not ") + "(clojure.core/identical? "
								+ a.expr() + " " + b.expr() + ")" + (op == Opcode.IF_ACMPEQ ? "" : ")");
					}
					default -> {
						Value b = pop();
						Value a = pop();
						test = compare(op, a.expr(), b.expr());
					}
				}
				String taken = jump(to);
				String fallen = jump(index + 1);
				return "(if " + test + " " + taken + " " + fallen + ")";
			}

			private static String compare(Opcode op, String a, String b) {
				String fn = switch (op) {
					case IFEQ, IF_ICMPEQ -> "==";
					case IFNE, IF_ICMPNE -> "not==";
					case IFLT, IF_ICMPLT -> "<";
					case IFGE, IF_ICMPGE -> ">=";
					case IFGT, IF_ICMPGT -> ">";
					case IFLE, IF_ICMPLE -> "<=";
					default -> throw new Unreadable("the branch " + op);
				};
				if (fn.equals("not==")) {
					return "(clojure.core/not (clojure.core/== " + a + " " + b + "))";
				}
				return "(clojure.core/" + fn + " " + a + " " + b + ")";
			}

			/**
			 * The descriptor of an array's element an array instruction reads or writes:
			 * a {@code baload}/{@code bastore} serves both {@code byte[]} and
			 * {@code boolean[]}, which only the array's own type tells apart.
			 */
			private String elementDesc(TypeKind kind, Value array) {
				String desc = array.desc();
				if (desc != null && desc.startsWith("[")) {
					return desc.substring(1);
				}
				return switch (kind) {
					case BYTE -> throw new Unreadable("a byte or boolean array of no known type");
					case CHAR -> "C";
					case SHORT -> "S";
					case INT -> "I";
					case LONG -> "J";
					case DOUBLE -> "D";
					case REFERENCE -> "Ljava/lang/Object;";
					default -> throw new Unreadable("an array of " + kind);
				};
			}

			/** A value an array store narrows to the element's width, as the JVM's. */
			private static String narrowed(String expr, String element) {
				return switch (element) {
					case "B" -> "(clojure.core/unchecked-byte " + expr + ")";
					case "S" -> "(clojure.core/unchecked-short " + expr + ")";
					case "C" -> "(clojure.core/bit-and " + expr + " 65535)";
					case "Z" -> "(clojure.core/bit-and " + expr + " 1)";
					default -> expr;
				};
			}

		}

	}

	/**
	 * A value of a descriptor's type in its Clojure spelling, from the operand stack's: a
	 * {@code boolean} is {@code true}/{@code false}, a {@code char} a character.
	 */
	static String toClojure(String expr, String desc) {
		return switch (desc) {
			case "Z" -> "(clojure.core/not (clojure.core/== 0 " + expr + "))";
			case "C" -> "(clojure.core/char " + expr + ")";
			default -> expr;
		};
	}

	/**
	 * A value of a descriptor's type on the operand stack, from its Clojure spelling: a
	 * {@code boolean} 0 or 1, a {@code char} its code, a {@code double} a double.
	 */
	static String fromClojure(String expr, String desc) {
		return switch (desc) {
			case "Z" -> "(if " + expr + " 1 0)";
			case "C" -> "(clojure.core/int " + expr + ")";
			case "D" -> "(clojure.core/double " + expr + ")";
			default -> expr;
		};
	}

	/**
	 * The var of a static field: its name after {@code field-}, which no Java name spells
	 * (no hyphen), so a static method of the same name keeps its own.
	 * @param name the field's name
	 * @return the var's name
	 */
	static String fieldVar(String name) {
		return "field-" + name;
	}

	/** A field's default value in its Clojure spelling. */
	static String defaultValue(String desc) {
		return switch (desc) {
			case "Z" -> "false";
			case "C" -> "\\u0000";
			case "B", "S", "I", "J" -> "0";
			case "D" -> "0.0";
			default -> "nil";
		};
	}

	/** The class a binary internal name spells, for {@code instance?}. */
	private static String className(String internalName) {
		return internalName.replace('/', '.');
	}

	/** A numeric constant as Clojure reads it. */
	static String literal(Object value) {
		if (value instanceof Double d) {
			if (d.isNaN()) {
				return "##NaN";
			}
			if (d.isInfinite()) {
				return d > 0 ? "##Inf" : "##-Inf";
			}
			return Double.toString(d);
		}
		if (value instanceof String s) {
			return stringLiteral(s);
		}
		if (value instanceof Integer || value instanceof Long) {
			return value.toString();
		}
		throw new Unreadable("the constant " + value);
	}

	/** A character as Clojure reads it. */
	static String charLiteral(int code) {
		return String.format("\\u%04x", code);
	}

	/** A string as Clojure reads it. */
	static String stringLiteral(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\t' -> out.append("\\t");
				case '\r' -> out.append("\\r");
				default -> {
					if (c < 0x20 || c >= 0x7f) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

}
