package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFileVersion;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent post-pass that gives every method of a finished, frame-free class
 * file its {@code StackMapTable} and stamps the class-file version, so the output passes
 * the type-checking verifier that class version 51+ makes mandatory. The frames come from
 * {@code java.lang.classfile}'s stack-map generator (in {@code java.base}): the class is
 * parsed, its method bodies are passed through unchanged, and the writer derives the
 * frames, {@code max_stack} and {@code max_locals} from the code.
 * <p>
 * Dead code (emitted after an unconditional transfer, never jumped to) is overwritten
 * with {@code nop}s ending in {@code athrow} under a {@code [Throwable]}-stack frame and
 * carved out of the exception table. An inconsistent operand stack at a merge point is a
 * compile-time {@link IllegalStateException} naming the method.
 * <p>
 * Reference types merge through a class hierarchy read without loading a class, so the
 * pass runs in a native image and in the browser build: a fixed table of the
 * {@code java.lang} relations the code generators rely on (the boxed numerics under
 * {@code Number}), then an optional caller-supplied lookup of class files, and any other
 * type counts as a class directly under {@code Object} -- an unequal pair of those merges
 * to {@code Object}.
 * <p>
 * The pass must run after {@link JvmClassShaker} when both apply: the shaker drops a
 * {@code StackMapTable}, whose frames reference constant-pool entries this pass appends
 * and the shaker's compaction would not know how to rewrite. A {@code LineNumberTable}
 * passes through both untouched.
 * <p>
 * {@link #osrHostileBackedges} reads the frames back: which backward branches target a
 * position whose operand stack is non-empty -- the one loop shape HotSpot refuses to
 * compile.
 */
public final class StackMapFrames {

	private static final String OBJECT = "java/lang/Object";

	/**
	 * Boxed numerics share {@code java/lang/Number}: the one non-trivial superclass
	 * relation a merge in generated code can need.
	 */
	private static final Map<String, String> SUPERCLASS = Map.of( //
			"java/lang/Long", "java/lang/Number", //
			"java/lang/Integer", "java/lang/Number", //
			"java/lang/Double", "java/lang/Number", //
			"java/lang/Float", "java/lang/Number", //
			"java/lang/Short", "java/lang/Number", //
			"java/lang/Byte", "java/lang/Number", //
			"java/lang/Number", OBJECT);

	private static final ClassHierarchyInfo UNDER_OBJECT = ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object);

	private static final String POOL_TOO_LARGE = "Constant pool is too large";

	private StackMapFrames() {
	}

	/**
	 * A backward branch whose target has a non-empty operand stack.
	 *
	 * @param method the method's name
	 * @param descriptor the method's descriptor
	 * @param branchPc the offset of the backward branch instruction
	 * @param targetPc the offset it jumps to (the loop head)
	 * @param stackDepth the number of operand stack entries at {@code targetPc}
	 */
	public record Backedge(String method, String descriptor, int branchPc, int targetPc, int stackDepth) {

		@Override
		public String toString() {
			return this.method + this.descriptor + ": " + this.branchPc + " -> " + this.targetPc + " (stack depth "
					+ this.stackDepth + ")";
		}
	}

	/**
	 * Computes the frames of every method with the fixed hierarchy table alone.
	 * @param classFile a frame-free class file (as {@link ByteCodeWriter} writes it)
	 * @param majorVersion the class-file major version to stamp (e.g. 61 for Java 17)
	 * @return the class file with its frames
	 * @throws ConstantPoolOverflowException when the frames' own constant-pool entries do
	 * not fit
	 * @throws IllegalStateException when a method's code has no consistent frames
	 */
	public static byte[] generate(byte[] classFile, int majorVersion) {
		return generate(classFile, majorVersion, name -> null);
	}

	/**
	 * Computes the frames of every method, resolving the types the fixed table does not
	 * know through {@code classes}.
	 * @param classFile a frame-free class file (as {@link ByteCodeWriter} writes it)
	 * @param majorVersion the class-file major version to stamp (e.g. 61 for Java 17)
	 * @param classes the declared shape of a class by internal name, or {@code null} when
	 * unknown
	 * @return the class file with its frames
	 * @throws ConstantPoolOverflowException when the frames' own constant-pool entries do
	 * not fit
	 * @throws IllegalStateException when a method's code has no consistent frames
	 */
	public static byte[] generate(byte[] classFile, int majorVersion,
			Function<String, @Nullable ClassFileInfo> classes) {
		ClassHierarchyResolver resolver = resolver(classes);
		ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver),
				ClassFile.StackMapsOption.GENERATE_STACK_MAPS);
		ClassModel model = cf.parse(classFile);
		ClassTransform transform = ClassTransform.dropping(e -> e instanceof ClassFileVersion)
			.andThen(ClassTransform.endHandler(cb -> cb.withVersion(majorVersion, 0)))
			.andThen(ClassTransform.transformingMethodBodies(CodeTransform.ACCEPT_ALL));
		try {
			return cf.transformClass(model, transform);
		}
		catch (IllegalArgumentException ex) {
			String message = String.valueOf(ex.getMessage());
			if (message.startsWith(POOL_TOO_LARGE)) {
				throw new ConstantPoolOverflowException("stack map frames: " + message);
			}
			// The generator appends a dump of the whole method to its first line, which
			// already names the offset and the method.
			int end = message.indexOf('\n');
			throw new IllegalStateException("stack map frames of " + model.thisClass().asInternalName() + ": "
					+ (end < 0 ? message : message.substring(0, end)), ex);
		}
	}

	private static ClassHierarchyResolver resolver(Function<String, @Nullable ClassFileInfo> classes) {
		Map<ClassDesc, ClassHierarchyInfo> cache = new HashMap<>();
		return desc -> cache.computeIfAbsent(desc, d -> {
			String name = internalName(d);
			if (OBJECT.equals(name)) {
				return ClassHierarchyInfo.ofClass(null);
			}
			String fixed = SUPERCLASS.get(name);
			if (fixed != null) {
				return ClassHierarchyInfo.ofClass(ClassDesc.ofInternalName(fixed));
			}
			ClassFileInfo info = classes.apply(name);
			if (info == null) {
				return UNDER_OBJECT;
			}
			if (info.isInterface()) {
				return ClassHierarchyInfo.ofInterface();
			}
			String superName = info.superName();
			return ClassHierarchyInfo.ofClass(superName == null ? null : ClassDesc.ofInternalName(superName));
		});
	}

	private static String internalName(ClassDesc desc) {
		String descriptor = desc.descriptorString();
		return descriptor.substring(1, descriptor.length() - 1);
	}

	/**
	 * Lists the backward branches that target a position with a non-empty operand stack.
	 * HotSpot can only enter an on-stack-replacement compilation at a backedge whose
	 * operand stack is empty; a loop head with pending operands is refused at every tier
	 * ({@code COMPILE SKIPPED: stack not empty at OSR entry point}), so a method entered
	 * once with such a loop inside runs in the bytecode interpreter forever.
	 * @param classFile a class file with its frames (as {@link #generate} writes it); a
	 * class below version 51 is given its frames first
	 * @return the offending backedges, empty when the class has none
	 */
	public static List<Backedge> osrHostileBackedges(byte[] classFile) {
		ClassModel model = ClassFile.of().parse(classFile);
		if (model.majorVersion() < ClassFile.JAVA_7_VERSION) {
			model = ClassFile.of().parse(generate(classFile, ClassFile.JAVA_7_VERSION));
		}
		List<Backedge> found = new ArrayList<>();
		for (MethodModel method : model.methods()) {
			CodeAttribute code = method.findAttribute(Attributes.code()).orElse(null);
			if (code == null) {
				continue;
			}
			Map<Integer, Integer> stackAt = new HashMap<>();
			code.findAttribute(Attributes.stackMapTable())
				.map(StackMapTableAttribute::entries)
				.orElse(List.of())
				.forEach(frame -> stackAt.put(code.labelToBci(frame.target()), frame.stack().size()));
			int pc = 0;
			for (CodeElement element : code.elementList()) {
				if (!(element instanceof Instruction instruction)) {
					continue;
				}
				for (Label target : targets(instruction)) {
					int targetPc = code.labelToBci(target);
					Integer depth = stackAt.get(targetPc);
					if (targetPc <= pc && depth != null && depth > 0) {
						found.add(new Backedge(method.methodName().stringValue(), method.methodType().stringValue(), pc,
								targetPc, depth));
					}
				}
				pc += instruction.sizeInBytes();
			}
		}
		return found;
	}

	private static List<Label> targets(Instruction instruction) {
		return switch (instruction) {
			case BranchInstruction branch -> List.of(branch.target());
			case TableSwitchInstruction table -> switchTargets(table.defaultTarget(), table.cases());
			case LookupSwitchInstruction lookup -> switchTargets(lookup.defaultTarget(), lookup.cases());
			default -> List.of();
		};
	}

	private static List<Label> switchTargets(Label defaultTarget, List<SwitchCase> cases) {
		List<Label> targets = new ArrayList<>(cases.size() + 1);
		targets.add(defaultTarget);
		cases.forEach(c -> targets.add(c.target()));
		return targets;
	}

}
