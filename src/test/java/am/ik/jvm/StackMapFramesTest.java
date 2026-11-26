package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frame pass over frame-free version-50 input: the hierarchy its merges see, its
 * failures, and the backedge query that reads the frames back.
 */
class StackMapFramesTest {

	private static final ClassDesc THIS = ClassDesc.of("Frames");

	private static final ClassDesc INTEGER = ConstantDescs.CD_Integer;

	private static final ClassDesc LONG = ConstantDescs.CD_Long;

	@Test
	void boxedNumericsMergeToNumberFromTheFixedTable() {
		byte[] out = StackMapFrames.generate(frameFree(joinOf(INTEGER, LONG)), 61);
		assertThat(ClassFile.of().parse(out).majorVersion()).isEqualTo(61);
		assertThat(joinStack(out)).containsExactly("java/lang/Number");
	}

	@Test
	void unknownClassesMergeToObject() {
		byte[] out = StackMapFrames.generate(frameFree(joinOf(ClassDesc.of("p.A"), ClassDesc.of("p.B"))), 61);
		assertThat(joinStack(out)).containsExactly("java/lang/Object");
	}

	@Test
	void aLookupSuppliesTheSuperclassesTheTableLacks() {
		ClassFileInfo a = info("p/A", "p/Base");
		ClassFileInfo b = info("p/B", "p/Base");
		ClassFileInfo base = info("p/Base", "java/lang/Object");
		byte[] out = StackMapFrames.generate(frameFree(joinOf(ClassDesc.of("p.A"), ClassDesc.of("p.B"))), 61,
				name -> switch (name) {
					case "p/A" -> a;
					case "p/B" -> b;
					case "p/Base" -> base;
					default -> null;
				});
		assertThat(joinStack(out)).containsExactly("p/Base");
	}

	@Test
	void anInconsistentStackFailsNamingTheMethodWithoutTheDump() {
		// One arm pushes an int, the other a reference, both into the same join.
		byte[] bad = frameFree(code -> {
			Label other = code.newLabel();
			Label join = code.newLabel();
			code.iload(0).ifeq(other).iconst_1().goto_(join);
			code.labelBinding(other).aconst_null();
			code.labelBinding(join).pop().return_();
		});
		assertThatThrownBy(() -> StackMapFrames.generate(bad, 61)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("stack map frames of Frames: ")
			.hasMessageContaining("of method m(int)")
			.hasMessageNotContaining("\n");
	}

	@Test
	void framesThatDoNotFitThePoolAreAnOverflow() {
		// A pool a few entries short of full: the frames' attribute name and their
		// java/lang/Number Class entry do not fit.
		byte[] full = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(THIS, cb -> {
			cb.withVersion(50, 0);
			cb.withMethodBody("m", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int), ClassFile.ACC_STATIC,
					joinOf(INTEGER, LONG));
			while (cb.constantPool().size() < 65532) {
				cb.constantPool().utf8Entry("u" + cb.constantPool().size());
			}
		});
		assertThatThrownBy(() -> StackMapFrames.generate(full, 61)).isInstanceOf(ConstantPoolOverflowException.class);
	}

	@Test
	void aLoopEnteredWithAPendingOperandIsAnOsrHostileBackedge() {
		byte[] loop = frameFree(code -> {
			Label head = code.newLabel();
			code.iconst_0();
			code.labelBinding(head).iload(0).ifne(head).pop().return_();
		});
		List<StackMapFrames.Backedge> found = StackMapFrames.osrHostileBackedges(StackMapFrames.generate(loop, 61));
		assertThat(found).singleElement().satisfies(edge -> {
			assertThat(edge.method()).isEqualTo("m");
			assertThat(edge.targetPc()).isEqualTo(1);
			assertThat(edge.stackDepth()).isEqualTo(1);
		});
		assertThat(StackMapFrames.osrHostileBackedges(loop)).as("frame-free input is given its frames first")
			.isEqualTo(found);
	}

	@Test
	void aLoopEnteredWithAnEmptyStackIsNot() {
		byte[] loop = frameFree(code -> {
			Label head = code.newLabel();
			code.labelBinding(head).iload(0).ifne(head).return_();
		});
		assertThat(StackMapFrames.osrHostileBackedges(StackMapFrames.generate(loop, 61))).isEmpty();
	}

	/** {@code static void m(int)}: push one of two types by the argument, then join. */
	private static Consumer<CodeBuilder> joinOf(ClassDesc left, ClassDesc right) {
		MethodTypeDesc make = MethodTypeDesc.of(ConstantDescs.CD_Object);
		return code -> {
			Label other = code.newLabel();
			Label join = code.newLabel();
			code.iload(0).ifeq(other);
			code.invokestatic(THIS, "make", make).checkcast(left).goto_(join);
			code.labelBinding(other).invokestatic(THIS, "make", make).checkcast(right);
			code.labelBinding(join).pop().return_();
		};
	}

	private static byte[] frameFree(Consumer<CodeBuilder> body) {
		return ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(THIS, cb -> {
			cb.withVersion(50, 0);
			cb.withMethodBody("m", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int), ClassFile.ACC_STATIC,
					body);
		});
	}

	/** The operand stack of the frame at the join, the last frame of {@code m}. */
	private static List<String> joinStack(byte[] classFile) {
		ClassModel model = ClassFile.of().parse(classFile);
		MethodModel m = model.methods()
			.stream()
			.filter(method -> method.methodName().equalsString("m"))
			.findFirst()
			.orElseThrow();
		List<StackMapFrameInfo> frames = m.code()
			.orElseThrow()
			.findAttribute(Attributes.stackMapTable())
			.map(StackMapTableAttribute::entries)
			.orElseThrow();
		return frames.getLast()
			.stack()
			.stream()
			.map(type -> ((StackMapFrameInfo.ObjectVerificationTypeInfo) type).className().asInternalName())
			.toList();
	}

	private static ClassFileInfo info(String name, String superName) {
		return new ClassFileInfo(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, name, superName, List.of(), List.of(),
				List.of(), List.of());
	}

}
