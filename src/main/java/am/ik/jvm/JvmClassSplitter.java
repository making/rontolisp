package am.ik.jvm;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Writes a {@link ClassDefinition} through {@code java.lang.classfile}: as one class file
 * when its members' constant-pool entries fit one class, else as a MAIN class plus as
 * many PART classes as the rest needs, each under the 65534-entry limit.
 * <p>
 * Every class is built with a pool of its own, so it holds exactly the entries its
 * members reference (an entry several classes use is repeated in each), and every method
 * body is played into the builder ({@link CodeReplay}), which gives it its
 * {@code StackMapTable}, {@code max_stack} and {@code max_locals}, relaxes a branch whose
 * offset does not fit 16 bits into its {@code goto_w} form and widens an {@code ldc}
 * whose constant landed past index 255. References merge through the same class hierarchy
 * {@link StackMapFrames} uses.
 * <p>
 * The main class keeps the definition's name, header, interfaces and every field, plus
 * each method that must stay where a caller can find it: a {@code pinned} one (named by
 * the caller -- its entry points and the methods something looks up by name), every
 * instance method and initializer, a {@code synchronized} method (its monitor is its
 * class) and one that asks {@code MethodHandles.lookup()} (whose answer is its class).
 * Every other method is placed in declaration order, into the main class while it has
 * room and then into {@code <name>$Part1}, {@code <name>$Part2}, ... A method lands
 * wherever it fits and every call to it follows it: a {@code Methodref} naming the
 * definition's own class and a method that went to a part is re-pointed to that part as
 * the call is written. Fields stay in the main class, so a {@code Fieldref} never
 * changes. The classes lose {@code ACC_PRIVATE} on every member once there is more than
 * one, since a part calls the main class's helpers and reads its fields: they share one
 * package, and package access is what that needs.
 * <p>
 * The same walk answers {@link #unresolvedSelfMethods} and, when asked, tree-shakes the
 * definition before placing anything, by its own-call graph ({@code OwnCallGraph}): a
 * method unreachable from the roots, and a field no surviving method references, are not
 * written at all. Dynamically-reached methods stay alive the way they do on WASM:
 * first-class calls go through dispatch methods whose bodies contain real
 * {@code invokestatic}s to every registered function. The one edge invisible to the code
 * is a call by name (reflection, an interface the JVM dispatches through); the caller
 * lists such methods as extra roots.
 */
public final class JvmClassSplitter {

	/**
	 * Entries every class keeps free below the format limit, once a class is split, for
	 * the frames' own entries (the {@code StackMapTable} name and a Class entry per frame
	 * type the pool lacks) and the part classes' own Class entries. Generous on purpose:
	 * an overestimate costs at most one more part class, an underestimate a failed
	 * compile.
	 */
	public static final int RESERVED_ENTRIES = 4096;

	private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";

	private static final String POOL_TOO_LARGE = "Constant pool is too large";

	private JvmClassSplitter() {
	}

	/**
	 * The written classes: the main class and the parts.
	 *
	 * @param mainClass the class under the definition's own name
	 * @param parts each part class's internal name mapped to its bytes, in part order;
	 * empty when everything fit the main class
	 */
	public record Split(byte[] mainClass, Map<String, byte[]> parts) {
	}

	/**
	 * How the written classes are stamped and framed.
	 *
	 * @param majorVersion the class-file major version (51 or later: every method gets
	 * its frames)
	 * @param classes the declared shape of a class the frames' merges meet, by internal
	 * name, or {@code null} when unknown (see {@link StackMapFrames})
	 */
	public record Target(int majorVersion, Function<String, @Nullable ClassFileInfo> classes) {

		/**
		 * @param majorVersion the class-file major version
		 * @return a target whose merges know the fixed {@code java.lang} table alone
		 */
		public static Target of(int majorVersion) {
			return new Target(majorVersion, name -> null);
		}

	}

	/**
	 * An own-class method some emitted body invokes but the class never declares.
	 *
	 * @param name the called method's name
	 * @param descriptor its descriptor
	 * @param callers the names of the declared methods whose bodies reference it, in
	 * declaration order
	 */
	public record UnresolvedSelfMethod(String name, String descriptor, List<String> callers) {

		@Override
		public String toString() {
			return this.name + this.descriptor + " (called from " + String.join(", ", this.callers) + ")";
		}
	}

	/**
	 * Every own-class method the definition's code invokes but does not declare, in
	 * first-reference order. JVM method resolution is lazy, so such a reference survives
	 * verification and class loading and throws {@link NoSuchMethodError} only if the
	 * branch containing it is ever taken -- the failure mode of a generator whose helper
	 * emission is decided by a prediction rather than by what the bodies turned out to
	 * reference. The scan reads only what the code references, so a constant-pool entry
	 * minted speculatively and never emitted is not reported.
	 * @param definition the class
	 * @return the unresolved calls, in first-reference order
	 */
	public static List<UnresolvedSelfMethod> unresolvedSelfMethods(ClassDefinition definition) {
		List<UnresolvedSelfMethod> result = new ArrayList<>();
		new Scan(definition).graph.unresolved()
			.forEach((call, callers) -> result
				.add(new UnresolvedSelfMethod(call.name(), call.descriptor(), List.copyOf(callers))));
		return List.copyOf(result);
	}

	/**
	 * Writes the definition as one class when what it keeps fits {@code limit} entries,
	 * else as a main class and its parts: filled to {@code limit} entries each, or to
	 * {@link ConstantPool#MAX_INDEX} less {@link #RESERVED_ENTRIES} when {@code limit} is
	 * the format limit itself. A class that fit by its members' count but not with its
	 * frames' own entries is split the same way.
	 * @param definition the class, whose pool may exceed {@link ConstantPool#MAX_INDEX}
	 * @param shakeRoots the entry points to tree-shake from (names, with
	 * {@code <init>}/{@code <clinit>} always kept), or {@code null} to keep every method
	 * and field
	 * @param pinned the methods that must stay in the main class beyond the ones this
	 * class pins by itself (see the class comment)
	 * @param limit the entries one class may be filled to -- the format limit, or less to
	 * force the split onto a small class in a test
	 * @param target the version and class hierarchy to write against
	 * @return the classes
	 * @throws IllegalStateException when the definition cannot be written: a method's
	 * code has no consistent frames, what must stay in the main class does not fit one,
	 * or a single method needs more entries than one class holds
	 * @throws IllegalArgumentException when a method's code exceeds 65535 bytes
	 */
	public static Split write(ClassDefinition definition, @Nullable Set<String> shakeRoots,
			Predicate<ClassDefinition.Method> pinned, int limit, Target target) {
		Scan scan = new Scan(definition);
		boolean[] keptMethod = scan.graph.reachable(shakeRoots);
		boolean[] keptField = scan.usedFields(keptMethod, shakeRoots != null);
		if (scan.oneClassSize(keptMethod, keptField) <= limit) {
			try {
				return oneClass(scan, keptMethod, keptField).write(definition, target);
			}
			catch (ConstantPoolOverflowException fullPool) {
				// The frames' own entries were the ones that did not fit: a pool within a
				// few hundred entries of the limit. The split reserves room for them.
			}
		}
		int budget = limit >= ConstantPool.MAX_INDEX ? ConstantPool.MAX_INDEX - RESERVED_ENTRIES : limit;
		return place(scan, keptMethod, keptField, pinned, budget).write(definition, target);
	}

	/** Everything kept, in the main class. */
	private static Placement oneClass(Scan scan, boolean[] keptMethod, boolean[] keptField) {
		Part main = new Part(scan, scan.cp.utf8At(scan.cp.firstComponentAt(scan.thisClass)));
		for (int m = 0; m < scan.methods.size(); m++) {
			if (keptMethod[m]) {
				main.methods.add(m);
			}
		}
		return new Placement(scan, List.of(main), new int[scan.methods.size()], keptMethod, keptField);
	}

	/**
	 * Places the kept methods: the main class's fixed part first, then every other method
	 * in declaration order, into the last class while it has room.
	 */
	private static Placement place(Scan scan, boolean[] keptMethod, boolean[] keptField,
			Predicate<ClassDefinition.Method> pinned, int budget) {
		ClassDefinition definition = scan.definition;
		List<Part> parts = new ArrayList<>();
		Part main = new Part(scan, scan.cp.utf8At(scan.cp.firstComponentAt(definition.thisClass().index())));
		parts.add(main);
		main.include(scan.closure(definition.thisClass().index()));
		for (ConstantPool.ClassConstant iface : definition.interfaces()) {
			main.include(scan.closure(iface.index()));
		}
		for (int f = 0; f < scan.fields.size(); f++) {
			if (keptField[f]) {
				main.include(scan.closure(scan.fields.get(f).name().index()));
				main.include(scan.closure(scan.fields.get(f).descriptor().index()));
			}
		}
		int[] owner = new int[scan.methods.size()];
		int fixedMethods = 0;
		for (int m = 0; m < scan.methods.size(); m++) {
			if (keptMethod[m] && (pinned.test(scan.methods.get(m)) || scan.staysInMain(m))) {
				main.include(scan.methodClosure(m));
				fixedMethods++;
			}
		}
		if (main.size() > budget) {
			throw new IllegalStateException("the class's fixed part -- its fields and the " + fixedMethods
					+ " methods it must keep -- needs " + main.size() + " constant pool entries, past the " + budget
					+ " one class can give it; no split can make it fit");
		}
		for (int m = 0; m < scan.methods.size(); m++) {
			if (!keptMethod[m] || pinned.test(scan.methods.get(m)) || scan.staysInMain(m)) {
				continue;
			}
			BitSet closure = scan.methodClosure(m);
			Part last = parts.getLast();
			if (last.sizeWith(closure) > budget) {
				last = new Part(scan, main.name + "$Part" + parts.size());
				parts.add(last);
				if (last.sizeWith(closure) > budget) {
					throw new IllegalStateException(
							"method " + scan.cp.utf8At(scan.methods.get(m).name().index()) + " alone needs "
									+ last.sizeWith(closure) + " constant pool entries, past the limit of one class");
				}
			}
			last.include(closure);
			owner[m] = parts.size() - 1;
		}
		for (int m = 0; m < scan.methods.size(); m++) {
			if (keptMethod[m]) {
				parts.get(owner[m]).methods.add(m);
			}
		}
		return new Placement(scan, parts, owner, keptMethod, keptField);
	}

	/**
	 * Where every kept member went: the classes in part order (the main class first) and
	 * each method's class.
	 */
	private record Placement(Scan scan, List<Part> parts, int[] owner, boolean[] keptMethod, boolean[] keptField) {

		Split write(ClassDefinition definition, Target target) {
			boolean split = this.parts.size() > 1;
			// Per Methodref of the definition naming a moved own method, the part (1..)
			// that declares it; 0 for every other entry.
			int[] movedTo = new int[definition.cp().size() + 1];
			if (split) {
				Map<OwnCallGraph.Member, Integer> ownerByMember = new HashMap<>();
				for (int m = 0; m < this.scan.methods.size(); m++) {
					if (this.keptMethod[m] && this.owner[m] > 0) {
						ClassDefinition.Method method = this.scan.methods.get(m);
						ownerByMember.put(this.scan.member(method.name().index(), method.descriptor().index()),
								this.owner[m]);
					}
				}
				for (int m = 0; m < this.scan.methods.size(); m++) {
					for (int index : this.scan.sites.get(m)) {
						OwnCallGraph.Member call = this.scan.ownMethod(index);
						Integer part = call == null ? null : ownerByMember.get(call);
						if (part != null) {
							movedTo[index] = part;
						}
					}
				}
			}
			// The replay writes every branch that does not reach in its long form itself
			// (CodeReplay.Layout); a short jump the writer still finds out of range is a
			// replay bug, reported rather than papered over by rewriting every forward
			// branch of the method long.
			ClassFile classFile = ClassFile.of(
					ClassFile.ClassHierarchyResolverOption.of(StackMapFrames.resolver(target.classes())),
					ClassFile.StackMapsOption.GENERATE_STACK_MAPS, ClassFile.ShortJumpsOption.FAIL_ON_SHORT_JUMPS);
			byte[] mainBytes = this.writeClass(classFile, definition, target, 0, movedTo, split);
			Map<String, byte[]> written = new LinkedHashMap<>();
			for (int p = 1; p < this.parts.size(); p++) {
				written.put(this.parts.get(p).name, this.writeClass(classFile, definition, target, p, movedTo, true));
			}
			return new Split(mainBytes, written);
		}

		private byte[] writeClass(ClassFile classFile, ClassDefinition definition, Target target, int self,
				int[] movedTo, boolean split) {
			Part part = this.parts.get(self);
			ConstantPool master = definition.cp();
			ConstantPoolBuilder pool = ConstantPoolBuilder.of();
			ClassEntry thisClass = pool.classEntry(pool.utf8Entry(part.name));
			ClassEntry[] partClasses = new ClassEntry[this.parts.size()];
			IntFunction<PoolEntry> operand = index -> {
				int moved = movedTo[index];
				if (moved == 0) {
					return master.entryAt(index);
				}
				ClassEntry owner = partClasses[moved];
				if (owner == null) {
					owner = pool.classEntry(pool.utf8Entry(this.parts.get(moved).name));
					partClasses[moved] = owner;
				}
				return pool.methodRefEntry(owner, ((MethodRefEntry) master.entryAt(index)).nameAndType());
			};
			try {
				return classFile.build(thisClass, pool, clb -> {
					clb.withVersion(target.majorVersion(), 0);
					if (self == 0) {
						this.writeHeader(clb, definition, split);
					}
					else {
						clb.withFlags(AccessFlag.ACC_SUPER | AccessFlag.ACC_FINAL | AccessFlag.ACC_SYNTHETIC);
						clb.withSuperclass(definition.superClass().entry());
					}
					for (int m : part.methods) {
						ClassDefinition.Method method = this.scan.methods.get(m);
						if (method.code().size() > 0xFFFF) {
							// A longer body produces a class every JVM rejects with a
							// message that no longer names the culprit; fail here
							// instead.
							throw new IllegalArgumentException("method " + master.utf8At(method.name().index())
									+ ": method code exceeds the JVM's 65535-byte limit: " + method.code().size());
						}
						int access = split ? method.access() & ~AccessFlag.ACC_PRIVATE : method.access();
						clb.withMethod(method.name().entry(), method.descriptor().entry(), access,
								mb -> mb.withCode(cb -> CodeReplay.replay(method, cb, operand)));
					}
				});
			}
			catch (IllegalArgumentException ex) {
				String message = String.valueOf(ex.getMessage());
				if (message.startsWith(POOL_TOO_LARGE)) {
					throw new ConstantPoolOverflowException("class " + part.name + ": " + message);
				}
				if (message.startsWith("method ")) {
					throw ex;
				}
				// The generator appends a dump of the whole method to its first line,
				// which
				// already names the offset and the method.
				int end = message.indexOf('\n');
				throw new IllegalStateException(
						"writing class " + part.name + ": " + (end < 0 ? message : message.substring(0, end)), ex);
			}
		}

		private void writeHeader(ClassBuilder clb, ClassDefinition definition, boolean split) {
			clb.withFlags(definition.accessFlags());
			clb.withSuperclass(definition.superClass().entry());
			List<ClassEntry> interfaces = new ArrayList<>();
			for (ConstantPool.ClassConstant iface : definition.interfaces()) {
				interfaces.add(iface.entry());
			}
			clb.withInterfaces(interfaces);
			for (int f = 0; f < definition.fields().size(); f++) {
				if (!this.keptField[f]) {
					continue;
				}
				ClassDefinition.Field field = definition.fields().get(f);
				clb.withField(field.name().entry(), field.descriptor().entry(),
						split ? field.access() & ~AccessFlag.ACC_PRIVATE : field.access());
			}
		}

	}

	/**
	 * The definition walked once: every method's constant-pool operands at full width,
	 * and the lookups the shake, the placement and the writing share.
	 */
	private static final class Scan {

		final ConstantPool cp;

		final ClassDefinition definition;

		final List<ClassDefinition.Method> methods;

		final List<ClassDefinition.Field> fields;

		/** Per method, the master-pool index of every constant-pool operand. */
		final List<int[]> sites = new ArrayList<>();

		final OwnCallGraph graph = new OwnCallGraph();

		final int thisClass;

		private final @Nullable BitSet[] closures;

		Scan(ClassDefinition definition) {
			this.definition = definition;
			this.cp = definition.cp();
			this.methods = definition.methods();
			this.fields = definition.fields();
			this.thisClass = definition.thisClass().index();
			for (ClassDefinition.Method method : this.methods) {
				int[] indexes = operands(method.code());
				this.sites.add(indexes);
				List<OwnCallGraph.Member> calls = new ArrayList<>();
				List<OwnCallGraph.Member> fieldUses = new ArrayList<>();
				for (int index : indexes) {
					OwnCallGraph.Member call = this.ownMethod(index);
					if (call != null) {
						calls.add(call);
					}
					OwnCallGraph.Member field = this.ownField(index);
					if (field != null) {
						fieldUses.add(field);
					}
				}
				this.graph.method(this.member(method.name().index(), method.descriptor().index()), calls, fieldUses);
			}
			this.closures = new BitSet[this.methods.size()];
		}

		/** The constant-pool operand of every instruction of a body, in order. */
		private static int[] operands(List<Integer> code) {
			int[] found = new int[16];
			int count = 0;
			int pc = 0;
			while (pc < code.size()) {
				int op = code.get(pc) & 0xFF;
				int index = switch (op) {
					case 0x12 -> CodeReplay.oneByteIndex(code.get(pc + 1));
					case 0x13, 0x14, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBB, 0xBD, 0xC0, 0xC1, 0xC5 ->
						CodeReplay.index(code, pc + 1);
					default -> 0;
				};
				if (index != 0) {
					if (count == found.length) {
						found = java.util.Arrays.copyOf(found, count * 2);
					}
					found[count++] = index;
				}
				pc += CodeReplay.length(code, pc);
			}
			return java.util.Arrays.copyOf(found, count);
		}

		OwnCallGraph.Member member(int nameIndex, int descriptorIndex) {
			return new OwnCallGraph.Member(this.cp.utf8At(nameIndex), this.cp.utf8At(descriptorIndex));
		}

		/**
		 * The own-class method a site's entry invokes, or null when the entry is not a
		 * method reference to the definition's own class.
		 */
		OwnCallGraph.@Nullable Member ownMethod(int index) {
			ConstantType type = this.cp.typeAt(index);
			if (type != ConstantType.METHODREF && type != ConstantType.INTERFACE_METHODREF) {
				return null;
			}
			if (!this.isThisClass(this.cp.firstComponentAt(index))) {
				return null;
			}
			int nameAndType = this.cp.secondComponentAt(index);
			return this.member(this.cp.firstComponentAt(nameAndType), this.cp.secondComponentAt(nameAndType));
		}

		OwnCallGraph.@Nullable Member ownField(int index) {
			if (this.cp.typeAt(index) != ConstantType.FIELDREF || !this.isThisClass(this.cp.firstComponentAt(index))) {
				return null;
			}
			int nameAndType = this.cp.secondComponentAt(index);
			return this.member(this.cp.firstComponentAt(nameAndType), this.cp.secondComponentAt(nameAndType));
		}

		// Identity by NAME, never by index: the pool may hold two Class entries that
		// spell the same class, and a method is the same method whichever one its call
		// site used.
		private boolean isThisClass(int classIndex) {
			return classIndex == this.thisClass || this.cp.utf8At(this.cp.firstComponentAt(classIndex))
				.equals(this.cp.utf8At(this.cp.firstComponentAt(this.thisClass)));
		}

		/**
		 * A field survives when a surviving method references it (or nothing is shaken).
		 */
		boolean[] usedFields(boolean[] keptMethod, boolean shaking) {
			boolean[] kept = new boolean[this.fields.size()];
			Set<OwnCallGraph.Member> used = this.graph.usedFields(keptMethod);
			for (int f = 0; f < this.fields.size(); f++) {
				ClassDefinition.Field field = this.fields.get(f);
				kept[f] = !shaking || used.contains(this.member(field.name().index(), field.descriptor().index()));
			}
			return kept;
		}

		/**
		 * Whether a method has to stay in the class it was generated for, whatever the
		 * caller pins: an instance method or initializer (it is the object's), a
		 * {@code synchronized} one (its monitor is its class object, which other methods
		 * may share), and one that calls {@code MethodHandles.lookup()} (the lookup's
		 * class is the caller's).
		 */
		boolean staysInMain(int m) {
			ClassDefinition.Method method = this.methods.get(m);
			if ((method.access() & AccessFlag.ACC_STATIC) == 0
					|| (method.access() & AccessFlag.ACC_SYNCHRONIZED) != 0) {
				return true;
			}
			String name = this.cp.utf8At(method.name().index());
			if (name.startsWith("<")) {
				return true;
			}
			for (int index : this.sites.get(m)) {
				ConstantType type = this.cp.typeAt(index);
				if ((type == ConstantType.METHODREF || type == ConstantType.INTERFACE_METHODREF)
						&& this.cp.utf8At(this.cp.firstComponentAt(this.cp.firstComponentAt(index)))
							.startsWith(METHOD_HANDLES)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The pool size one class holding everything kept would need.
		 */
		int oneClassSize(boolean[] keptMethod, boolean[] keptField) {
			BitSet all = new BitSet();
			this.close(this.definition.thisClass().index(), all);
			this.close(this.definition.superClass().index(), all);
			this.close(this.definition.codeName().index(), all);
			for (ConstantPool.ClassConstant iface : this.definition.interfaces()) {
				this.close(iface.index(), all);
			}
			for (int f = 0; f < this.fields.size(); f++) {
				if (keptField[f]) {
					this.close(this.fields.get(f).name().index(), all);
					this.close(this.fields.get(f).descriptor().index(), all);
				}
			}
			for (int m = 0; m < this.methods.size(); m++) {
				if (keptMethod[m]) {
					this.closeMethod(m, all);
				}
			}
			int size = 0;
			for (int i = all.nextSetBit(0); i >= 0; i = all.nextSetBit(i + 1)) {
				size += slots(this.cp.typeAt(i));
			}
			return size;
		}

		/**
		 * The entries a method's presence puts in a class's pool, components included.
		 */
		BitSet methodClosure(int m) {
			BitSet closure = this.closures[m];
			if (closure == null) {
				closure = new BitSet();
				this.closeMethod(m, closure);
				this.closures[m] = closure;
			}
			return closure;
		}

		private void closeMethod(int m, BitSet into) {
			ClassDefinition.Method method = this.methods.get(m);
			this.close(method.name().index(), into);
			this.close(method.descriptor().index(), into);
			for (int index : this.sites.get(m)) {
				this.close(index, into);
			}
			for (ClassDefinition.Handler entry : method.exceptionTable()) {
				if (entry.catchType() != 0) {
					this.close(entry.catchType(), into);
				}
			}
			if (!method.lineNumbers().isEmpty()) {
				this.close(java.util.Objects.requireNonNull(this.definition.lineNumberTableName()).index(), into);
			}
		}

		BitSet closure(int index) {
			BitSet closure = new BitSet();
			this.close(index, closure);
			return closure;
		}

		private void close(int index, BitSet into) {
			if (into.get(index)) {
				return;
			}
			into.set(index);
			switch (this.cp.typeAt(index)) {
				case CLASS, STRING -> this.close(this.cp.firstComponentAt(index), into);
				case NAME_AND_TYPE, FIELDREF, METHODREF, INTERFACE_METHODREF -> {
					this.close(this.cp.firstComponentAt(index), into);
					this.close(this.cp.secondComponentAt(index), into);
				}
				default -> {
				}
			}
		}

	}

	/** One class being filled: its name, its entries and its methods. */
	private static final class Part {

		private final Scan scan;

		final String name;

		private final BitSet entries = new BitSet();

		private int size;

		final List<Integer> methods = new ArrayList<>();

		Part(Scan scan, String name) {
			this.scan = scan;
			this.name = name;
			// Every class names its superclass and its methods' Code attribute; a part
			// also names itself, a Class entry and its Utf8.
			this.include(scan.closure(scan.definition.superClass().index()));
			this.include(scan.closure(scan.definition.codeName().index()));
			if (!name.equals(scan.cp.utf8At(scan.cp.firstComponentAt(scan.thisClass)))) {
				this.size += 2;
			}
		}

		int size() {
			return this.size;
		}

		/** The pool size this class would have with {@code closure} added. */
		int sizeWith(BitSet closure) {
			BitSet added = (BitSet) closure.clone();
			added.andNot(this.entries);
			int size = this.size;
			for (int i = added.nextSetBit(0); i >= 0; i = added.nextSetBit(i + 1)) {
				size += slots(this.scan.cp.typeAt(i));
			}
			return size;
		}

		void include(BitSet closure) {
			this.size = this.sizeWith(closure);
			this.entries.or(closure);
		}

	}

	private static int slots(ConstantType type) {
		return type == ConstantType.LONG || type == ConstantType.DOUBLE ? 2 : 1;
	}

}
