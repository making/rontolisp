package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Writes a {@link ClassDefinition} whose constant pool outgrew one class file as a MAIN
 * class plus as many PART classes as the rest needs, each under the 65534-entry limit.
 * <p>
 * The main class keeps the definition's name, header, interfaces and every field, plus
 * each method that must stay where a caller can find it: a {@code pinned} one (named by
 * the caller -- its entry points and the methods something looks up by name), every
 * instance method and initializer, a {@code synchronized} method (its monitor is its
 * class) and one that asks {@code MethodHandles.lookup()} (whose answer is its class).
 * Every other method is placed in declaration order, into the main class while it has
 * room and then into {@code <name>$Part1}, {@code <name>$Part2}, ... Each class gets its
 * own pool, holding exactly the entries its members reference, so an entry several
 * classes use is repeated in each.
 * <p>
 * A method lands wherever it fits and every reference to it follows it: a
 * {@code Methodref} naming the definition's own class and a method that went to a part is
 * rewritten to name that part. Fields stay in the main class, so a {@code Fieldref} never
 * changes. The classes lose {@code ACC_PRIVATE} on every member, since a part calls the
 * main class's helpers and reads its fields: they share one package, and package access
 * is what that needs.
 * <p>
 * The same walk answers {@link #unresolvedSelfMethods} and, when asked, tree-shakes the
 * definition with exactly {@link JvmClassShaker}'s rules before placing anything: a
 * method unreachable from the roots, and a field no surviving method references, are not
 * written at all.
 * <p>
 * Every class keeps the definition's instruction bytes: an operand is re-pointed in place
 * (a u2 stays a u2), and an {@code ldc}'s one-byte operand stays in range because each
 * class's pool keeps the definition's entry order and appends its new entries last.
 */
public final class JvmClassSplitter {

	/**
	 * Entries every class keeps free below the format limit for {@link StackMapAugmenter}
	 * (the {@code StackMapTable} name and a Class entry per frame type it lacks) and for
	 * the part classes' own Class entries. Generous on purpose: an overestimate costs at
	 * most one more part class, an underestimate a failed compile.
	 */
	public static final int RESERVED_ENTRIES = 4096;

	private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";

	private JvmClassSplitter() {
	}

	/**
	 * The written classes: the main class and the parts, each ready for
	 * {@link StackMapAugmenter}.
	 *
	 * @param mainClass the class under the definition's own name
	 * @param parts each part class's internal name mapped to its bytes, in part order;
	 * empty when everything fit the main class
	 */
	public record Split(byte[] mainClass, Map<String, byte[]> parts) {
	}

	/**
	 * Every own-class method the definition's code invokes but does not declare -- the
	 * definition-level twin of {@link JvmClassShaker#unresolvedSelfMethods(byte[])}.
	 * @param definition the class
	 * @return the unresolved calls, in first-reference order
	 */
	public static List<JvmClassShaker.UnresolvedSelfMethod> unresolvedSelfMethods(ClassDefinition definition) {
		Scan scan = new Scan(definition);
		Map<String, Set<String>> missing = new LinkedHashMap<>();
		for (int m = 0; m < scan.methods.size(); m++) {
			for (int index : scan.sites.get(m).indexes) {
				String key = scan.ownMethodKey(index);
				if (key != null && !scan.methodByKey.containsKey(key)) {
					missing.computeIfAbsent(key, k -> new LinkedHashSet<>())
						.add(scan.cp.utf8At(scan.methods.get(m).name().index()));
				}
			}
		}
		List<JvmClassShaker.UnresolvedSelfMethod> result = new ArrayList<>(missing.size());
		missing.forEach((key, callers) -> {
			int colon = key.indexOf(':');
			result.add(new JvmClassShaker.UnresolvedSelfMethod(key.substring(0, colon), key.substring(colon + 1),
					List.copyOf(callers)));
		});
		return List.copyOf(result);
	}

	/**
	 * Writes the definition as one main class plus the parts its pool needs.
	 * @param definition the class, whose pool may exceed {@link ConstantPool#MAX_INDEX}
	 * @param shakeRoots the entry points to tree-shake from ({@link JvmClassShaker}'s
	 * roots: names, with {@code <init>}/{@code <clinit>} always kept), or {@code null} to
	 * keep every method and field
	 * @param pinned the methods that must stay in the main class beyond the ones this
	 * class pins by itself (see the class comment)
	 * @return the classes
	 */
	public static Split split(ClassDefinition definition, @Nullable Set<String> shakeRoots,
			Predicate<ClassDefinition.Method> pinned) {
		return split(definition, shakeRoots, pinned, ConstantPool.MAX_INDEX - RESERVED_ENTRIES);
	}

	/**
	 * Writes the definition as one main class plus the parts its pool needs, filling each
	 * class to at most {@code budget} entries.
	 * @param definition the class, whose pool may exceed {@link ConstantPool#MAX_INDEX}
	 * @param shakeRoots the entry points to tree-shake from, or {@code null} to keep
	 * every method and field
	 * @param pinned the methods that must stay in the main class beyond the ones this
	 * class pins by itself
	 * @param budget the entries a class may be filled to before the next part starts --
	 * {@link ConstantPool#MAX_INDEX} less {@link #RESERVED_ENTRIES}, or less than that to
	 * force the split onto a small class in a test
	 * @return the classes
	 */
	public static Split split(ClassDefinition definition, @Nullable Set<String> shakeRoots,
			Predicate<ClassDefinition.Method> pinned, int budget) {
		Scan scan = new Scan(definition);
		boolean[] keptMethod = scan.reachable(shakeRoots);
		boolean[] keptField = scan.usedFields(keptMethod, shakeRoots != null);

		// The main class's fixed part: its header, every kept field, every pinned method.
		List<Part> parts = new ArrayList<>();
		Part main = new Part(scan, definition.cp().utf8At(scan.cp.firstComponentAt(definition.thisClass().index())));
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
			ClassDefinition.Method method = scan.methods.get(m);
			if (keptMethod[m] && (pinned.test(method) || scan.staysInMain(m))) {
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
			Part target = parts.getLast();
			if (target.sizeWith(closure) > budget) {
				target = new Part(scan, main.name + "$Part" + parts.size());
				parts.add(target);
				if (target.sizeWith(closure) > budget) {
					throw new IllegalStateException(
							"method " + scan.cp.utf8At(scan.methods.get(m).name().index()) + " alone needs "
									+ target.sizeWith(closure) + " constant pool entries, past the limit of one class");
				}
			}
			target.include(closure);
			owner[m] = parts.size() - 1;
		}
		for (int m = 0; m < scan.methods.size(); m++) {
			if (keptMethod[m]) {
				parts.get(owner[m]).methods.add(m);
			}
		}

		Map<String, Integer> ownerByKey = new HashMap<>();
		for (int m = 0; m < scan.methods.size(); m++) {
			if (keptMethod[m]) {
				ownerByKey.put(
						scan.memberKey(scan.methods.get(m).name().index(), scan.methods.get(m).descriptor().index()),
						owner[m]);
			}
		}
		boolean split = parts.size() > 1;
		byte[] mainBytes = main.write(definition, parts, ownerByKey, keptField, split);
		Map<String, byte[]> written = new LinkedHashMap<>();
		for (int p = 1; p < parts.size(); p++) {
			Part part = parts.get(p);
			written.put(part.name, part.write(definition, parts, ownerByKey, keptField, true));
		}
		return new Split(mainBytes, written);
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

		final List<Sites> sites = new ArrayList<>();

		final Map<String, Integer> methodByKey = new HashMap<>();

		final int thisClass;

		private final @Nullable BitSet[] closures;

		Scan(ClassDefinition definition) {
			this.definition = definition;
			this.cp = definition.cp();
			this.methods = definition.methods();
			this.fields = definition.fields();
			this.thisClass = definition.thisClass().index();
			for (int m = 0; m < this.methods.size(); m++) {
				ClassDefinition.Method method = this.methods.get(m);
				this.sites.add(Sites.of(method.code()));
				this.methodByKey.putIfAbsent(this.memberKey(method.name().index(), method.descriptor().index()), m);
			}
			this.closures = new BitSet[this.methods.size()];
		}

		String memberKey(int nameIndex, int descriptorIndex) {
			return this.cp.utf8At(nameIndex) + ":" + this.cp.utf8At(descriptorIndex);
		}

		/**
		 * The {@code name:descriptor} of the own-class method a site's entry invokes, or
		 * null when the entry is not a method reference to the definition's own class.
		 */
		@Nullable String ownMethodKey(int index) {
			ConstantType type = this.cp.typeAt(index);
			if (type != ConstantType.METHODREF && type != ConstantType.INTERFACE_METHODREF) {
				return null;
			}
			if (!this.isThisClass(this.cp.firstComponentAt(index))) {
				return null;
			}
			int nameAndType = this.cp.secondComponentAt(index);
			return this.memberKey(this.cp.firstComponentAt(nameAndType), this.cp.secondComponentAt(nameAndType));
		}

		@Nullable String ownFieldKey(int index) {
			if (this.cp.typeAt(index) != ConstantType.FIELDREF || !this.isThisClass(this.cp.firstComponentAt(index))) {
				return null;
			}
			int nameAndType = this.cp.secondComponentAt(index);
			return this.memberKey(this.cp.firstComponentAt(nameAndType), this.cp.secondComponentAt(nameAndType));
		}

		// Identity by NAME, never by index: the pool may hold two Class entries that
		// spell
		// the same class, and a method is the same method whichever one its call site
		// used.
		private boolean isThisClass(int classIndex) {
			return classIndex == this.thisClass || this.cp.utf8At(this.cp.firstComponentAt(classIndex))
				.equals(this.cp.utf8At(this.cp.firstComponentAt(this.thisClass)));
		}

		/** {@link JvmClassShaker#shake}'s reachability; every method when not shaking. */
		boolean[] reachable(@Nullable Set<String> roots) {
			boolean[] kept = new boolean[this.methods.size()];
			if (roots == null) {
				java.util.Arrays.fill(kept, true);
				return kept;
			}
			Deque<Integer> work = new ArrayDeque<>();
			for (int m = 0; m < this.methods.size(); m++) {
				String name = this.cp.utf8At(this.methods.get(m).name().index());
				if (roots.contains(name) || "<init>".equals(name) || "<clinit>".equals(name)) {
					kept[m] = true;
					work.push(m);
				}
			}
			while (!work.isEmpty()) {
				int m = work.pop();
				for (int index : this.sites.get(m).indexes) {
					String key = this.ownMethodKey(index);
					Integer target = key == null ? null : this.methodByKey.get(key);
					if (target != null && !kept[target]) {
						kept[target] = true;
						work.push(target);
					}
				}
			}
			return kept;
		}

		/**
		 * A field survives when a surviving method references it (or nothing is shaken).
		 */
		boolean[] usedFields(boolean[] keptMethod, boolean shaking) {
			boolean[] kept = new boolean[this.fields.size()];
			if (!shaking) {
				java.util.Arrays.fill(kept, true);
				return kept;
			}
			Set<String> used = new java.util.HashSet<>();
			for (int m = 0; m < this.methods.size(); m++) {
				if (keptMethod[m]) {
					for (int index : this.sites.get(m).indexes) {
						String key = this.ownFieldKey(index);
						if (key != null) {
							used.add(key);
						}
					}
				}
			}
			for (int f = 0; f < this.fields.size(); f++) {
				ClassDefinition.Field field = this.fields.get(f);
				kept[f] = used.contains(this.memberKey(field.name().index(), field.descriptor().index()));
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
			for (int index : this.sites.get(m).indexes) {
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
		 * The entries a method's presence puts in a class's pool, components included.
		 */
		BitSet methodClosure(int m) {
			BitSet closure = this.closures[m];
			if (closure == null) {
				ClassDefinition.Method method = this.methods.get(m);
				closure = new BitSet();
				this.close(method.name().index(), closure);
				this.close(method.descriptor().index(), closure);
				for (int index : this.sites.get(m).indexes) {
					this.close(index, closure);
				}
				for (ByteCodeWriter.ExceptionTableEntry entry : method.exceptionTable()) {
					if (entry.catchType() != 0) {
						this.close(entry.catchType(), closure);
					}
				}
				this.closures[m] = closure;
			}
			return closure;
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
			// also names itself, a Class entry and its Utf8 appended when it is written.
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

		/**
		 * Writes the class (major version 50): its pool is the definition's entries it
		 * uses, in the definition's order, then the Class entries of the parts it calls
		 * into (and, for a part, its own) appended last.
		 */
		byte[] write(ClassDefinition definition, List<Part> parts, Map<String, Integer> ownerByKey, boolean[] keptField,
				boolean split) {
			ConstantPool cp = this.scan.cp;
			boolean isMain = parts.getFirst() == this;
			int self = parts.indexOf(this);
			// Old index -> new index for every entry this class carries.
			Remap remap = new Remap(cp.size());
			int next = 1;
			for (int i = this.entries.nextSetBit(0); i >= 0; i = this.entries.nextSetBit(i + 1)) {
				remap.put(i, next);
				next += slots(cp.typeAt(i));
			}
			// The Class entries of the other parts this class's Methodrefs name, and its
			// own when it is a part: each a Class entry followed by its Utf8 name.
			int[] partClass = new int[parts.size()];
			List<Integer> partOrder = new ArrayList<>();
			if (!isMain) {
				partClass[self] = next;
				partOrder.add(self);
				next += 2;
			}
			for (int i = this.entries.nextSetBit(0); i >= 0; i = this.entries.nextSetBit(i + 1)) {
				int target = this.methodOwner(i, ownerByKey);
				if (target > 0 && partClass[target] == 0) {
					partClass[target] = next;
					partOrder.add(target);
					next += 2;
				}
			}
			int count = next - 1;
			if (count > ConstantPool.MAX_INDEX) {
				throw new IllegalStateException("class " + this.name + " needs " + count
						+ " constant pool entries, past the format limit of " + ConstantPool.MAX_INDEX);
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeU4(out, 0xCAFEBABE);
			writeU2(out, 0);
			writeU2(out, 50);
			writeU2(out, count + 1);
			for (int i = this.entries.nextSetBit(0); i >= 0; i = this.entries.nextSetBit(i + 1)) {
				ConstantType type = cp.typeAt(i);
				out.write(type.value());
				switch (type) {
					case CLASS, STRING -> writeU2(out, remap.get(cp.firstComponentAt(i)));
					case NAME_AND_TYPE, FIELDREF, INTERFACE_METHODREF -> {
						writeU2(out, remap.get(cp.firstComponentAt(i)));
						writeU2(out, remap.get(cp.secondComponentAt(i)));
					}
					case METHODREF -> {
						int target = this.methodOwner(i, ownerByKey);
						writeU2(out, target > 0 ? partClass[target] : remap.get(cp.firstComponentAt(i)));
						writeU2(out, remap.get(cp.secondComponentAt(i)));
					}
					default -> {
						byte[] data = cp.dataAt(i);
						out.write(data, 0, data.length);
					}
				}
			}
			for (int part : partOrder) {
				out.write(ConstantType.CLASS.value());
				writeU2(out, partClass[part] + 1);
				out.write(ConstantType.UTF8.value());
				byte[] utf8 = utf8Info(parts.get(part).name);
				out.write(utf8, 0, utf8.length);
			}

			writeU2(out, isMain ? definition.accessFlags()
					: AccessFlag.ACC_SUPER | AccessFlag.ACC_FINAL | AccessFlag.ACC_SYNTHETIC);
			writeU2(out, isMain ? remap.get(definition.thisClass().index()) : partClass[self]);
			writeU2(out, remap.get(definition.superClass().index()));
			if (isMain) {
				writeU2(out, definition.interfaces().size());
				for (ConstantPool.ClassConstant iface : definition.interfaces()) {
					writeU2(out, remap.get(iface.index()));
				}
				int fieldCount = 0;
				for (boolean kept : keptField) {
					fieldCount += kept ? 1 : 0;
				}
				writeU2(out, fieldCount);
				for (int f = 0; f < definition.fields().size(); f++) {
					if (!keptField[f]) {
						continue;
					}
					ClassDefinition.Field field = definition.fields().get(f);
					writeU2(out, split ? field.access() & ~AccessFlag.ACC_PRIVATE : field.access());
					writeU2(out, remap.get(field.name().index()));
					writeU2(out, remap.get(field.descriptor().index()));
					writeU2(out, 0);
				}
			}
			else {
				writeU2(out, 0);
				writeU2(out, 0);
			}
			writeU2(out, this.methods.size());
			int codeName = remap.get(definition.codeName().index());
			for (int m : this.methods) {
				ClassDefinition.Method method = this.scan.methods.get(m);
				byte[] code = this.scan.sites.get(m).rewrite(method, remap);
				writeU2(out, split ? method.access() & ~AccessFlag.ACC_PRIVATE : method.access());
				writeU2(out, remap.get(method.name().index()));
				writeU2(out, remap.get(method.descriptor().index()));
				writeU2(out, 1);
				writeU2(out, codeName);
				writeU4(out, 2 + 2 + 4 + code.length + 2 + 8 * method.exceptionTable().size() + 2);
				writeU2(out, method.maxStack());
				writeU2(out, method.maxLocals());
				writeU4(out, code.length);
				out.write(code, 0, code.length);
				writeU2(out, method.exceptionTable().size());
				for (ByteCodeWriter.ExceptionTableEntry entry : method.exceptionTable()) {
					writeU2(out, entry.startPc());
					writeU2(out, entry.endPc());
					writeU2(out, entry.handlerPc());
					writeU2(out, entry.catchType() == 0 ? 0 : remap.get(entry.catchType()));
				}
				writeU2(out, 0);
			}
			writeU2(out, 0);
			return out.toByteArray();
		}

		/**
		 * The part (1..) that declares the own-class method a Methodref entry names, or 0
		 * when the entry is something else or the method stayed in the main class.
		 */
		private int methodOwner(int index, Map<String, Integer> ownerByKey) {
			if (this.scan.cp.typeAt(index) != ConstantType.METHODREF) {
				return 0;
			}
			String key = this.scan.ownMethodKey(index);
			Integer owner = key == null ? null : ownerByKey.get(key);
			return owner == null ? 0 : owner;
		}

	}

	/**
	 * Every constant-pool operand of one method body: where it sits, how wide it is, and
	 * the full index it names.
	 */
	private record Sites(int[] offsets, int[] widths, int[] indexes) {

		static Sites of(List<Integer> code) {
			List<int[]> found = new ArrayList<>();
			int pc = 0;
			while (pc < code.size()) {
				int op = code.get(pc) & 0xFF;
				int operand = pc + 1;
				switch (op) {
					case Opcode.LDC -> {
						found.add(new int[] { operand, 1, oneByteIndex(code.get(operand)) });
						pc = operand + 1;
					}
					case Opcode.LDC_W, Opcode.LDC2_W, Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.GETFIELD,
							Opcode.PUTFIELD, Opcode.INVOKEVIRTUAL, Opcode.INVOKESPECIAL, Opcode.INVOKESTATIC,
							Opcode.NEW, Opcode.ANEWARRAY, Opcode.CHECKCAST, Opcode.INSTANCEOF -> {
						found.add(new int[] { operand, 2, twoByteIndex(code, operand) });
						pc = operand + 2;
					}
					case Opcode.INVOKEINTERFACE -> {
						found.add(new int[] { operand, 2, twoByteIndex(code, operand) });
						pc = operand + 4;
					}
					case Opcode.MULTIANEWARRAY -> {
						found.add(new int[] { operand, 2, twoByteIndex(code, operand) });
						pc = operand + 3;
					}
					default -> pc = operand + operandLength(code, op, operand);
				}
			}
			if (pc != code.size()) {
				throw new IllegalStateException("JvmClassSplitter: bytecode overruns the Code attribute");
			}
			int[] offsets = new int[found.size()];
			int[] widths = new int[found.size()];
			int[] indexes = new int[found.size()];
			for (int i = 0; i < found.size(); i++) {
				offsets[i] = found.get(i)[0];
				widths[i] = found.get(i)[1];
				indexes[i] = found.get(i)[2];
			}
			return new Sites(offsets, widths, indexes);
		}

		// The high part arrives whole from a writer that kept a past-65535 index; a
		// negative one is a sign-extended byte, which only a value that fit 16 bits
		// produces.
		private static int twoByteIndex(List<Integer> code, int at) {
			int high = code.get(at);
			return ((high < 0 ? high & 0xFF : high) << 8) | (code.get(at + 1) & 0xFF);
		}

		private static int oneByteIndex(int element) {
			return element < 0 ? element & 0xFF : element;
		}

		/**
		 * The method's code bytes with every operand re-pointed through {@code remap}.
		 */
		byte[] rewrite(ClassDefinition.Method method, Remap remap) {
			List<Integer> code = method.code();
			if (code.size() > 0xFFFF) {
				throw new IllegalArgumentException("method code exceeds the JVM's 65535-byte limit: " + code.size());
			}
			byte[] bytes = new byte[code.size()];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) (int) code.get(i);
			}
			for (int s = 0; s < this.offsets.length; s++) {
				int mapped = remap.get(this.indexes[s]);
				if (this.widths[s] == 1) {
					if (mapped > 0xFF) {
						throw new IllegalStateException(
								"JvmClassSplitter: an ldc operand moved past one byte: " + mapped);
					}
					bytes[this.offsets[s]] = (byte) mapped;
				}
				else {
					bytes[this.offsets[s]] = (byte) (mapped >>> 8);
					bytes[this.offsets[s] + 1] = (byte) mapped;
				}
			}
			return bytes;
		}

		// Operand byte count of an instruction that carries no pool index -- the set the
		// generators emit, as JvmClassShaker walks it.
		private static int operandLength(List<Integer> code, int op, int operand) {
			if (op <= 0x0F || (op >= 0x1A && op <= 0x35) || (op >= 0x3B && op <= 0x83) || (op >= 0x85 && op <= 0x98)
					|| (op >= 0xAC && op <= 0xB1) || op == 0xBE || op == 0xBF || op == 0xC2 || op == 0xC3) {
				return 0;
			}
			if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3A)) {
				return 1;
			}
			if ((op >= 0x99 && op <= 0xA8) || op == 0xC6 || op == 0xC7) {
				return 2;
			}
			return switch (op) {
				case 0x10, 0xA9, 0xBC -> 1;
				case 0x11, 0x84 -> 2;
				case 0xC8, 0xC9 -> 4;
				case 0xC4 -> (code.get(operand) & 0xFF) == 0x84 ? 5 : 3;
				case 0xAA -> {
					int base = operand + pad(operand);
					int low = s4(code, base + 4);
					int high = s4(code, base + 8);
					yield base - operand + 12 + 4 * (high - low + 1);
				}
				case 0xAB -> {
					int base = operand + pad(operand);
					yield base - operand + 8 + 8 * s4(code, base + 4);
				}
				default ->
					throw new IllegalStateException(String.format("JvmClassSplitter: unhandled opcode 0x%02X", op));
			};
		}

		private static int pad(int operand) {
			return (4 - (operand & 3)) & 3;
		}

		private static int s4(List<Integer> code, int at) {
			return (code.get(at) & 0xFF) << 24 | (code.get(at + 1) & 0xFF) << 16 | (code.get(at + 2) & 0xFF) << 8
					| (code.get(at + 3) & 0xFF);
		}

	}

	/**
	 * Each constant-pool index of the definition to the index its entry has in one
	 * written class.
	 */
	private static final class Remap {

		private final int[] local;

		Remap(int size) {
			this.local = new int[size + 1];
		}

		void put(int index, int localIndex) {
			this.local[index] = localIndex;
		}

		int get(int index) {
			int mapped = index > 0 && index < this.local.length ? this.local[index] : 0;
			if (mapped == 0) {
				throw new IllegalStateException(
						"JvmClassSplitter: constant " + index + " is referenced but not in the class being written");
			}
			return mapped;
		}

	}

	private static int slots(ConstantType type) {
		return type == ConstantType.LONG || type == ConstantType.DOUBLE ? 2 : 1;
	}

	private static byte[] utf8Info(String s) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		new ByteCodeWriter(stream).writeUtf8Info(s);
		return stream.toByteArray();
	}

	private static void writeU2(ByteArrayOutputStream out, int v) {
		out.write((v >>> 8) & 0xFF);
		out.write(v & 0xFF);
	}

	private static void writeU4(ByteArrayOutputStream out, int v) {
		out.write((v >>> 24) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 8) & 0xFF);
		out.write(v & 0xFF);
	}

}
