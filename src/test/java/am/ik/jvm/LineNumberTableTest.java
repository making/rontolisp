package am.ik.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.LineNumberInfo;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A method's {@code LineNumberTable} through every stage a generated class passes:
 * written by {@link ClassDefinition}, moved by {@link BranchRelaxer}, kept by
 * {@link JvmClassShaker}, {@link JvmClassSplitter} and {@link StackMapAugmenter} -- each
 * checked where it matters, in the line a thrown exception's stack trace reports.
 */
class LineNumberTableTest {

	private static final String CLASS = "Lines";

	@Test
	void aThrowingMethodReportsTheLineItsInstructionMapsTo() throws Exception {
		Fixture f = new Fixture(true);
		f.throwing("boom", List.of(new ByteCodeWriter.LineNumberEntry(0, 7)));
		byte[] bytes = StackMapAugmenter.augment(f.build().toBytes(), 61);
		assertThat(lines(bytes, "boom")).containsExactly(0, 7);
		assertThat(thrownLine(Map.of(CLASS, bytes), "boom")).isEqualTo(7);
	}

	@Test
	void aMethodWithoutLineNumbersCarriesNoAttributeAndNoName() {
		Fixture f = new Fixture(false);
		f.throwing("boom", List.of());
		byte[] bytes = f.build().toBytes();
		assertThat(lines(bytes, "boom")).isEmpty();
		assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("LineNumberTable");
	}

	@Test
	void lineNumbersNeedTheAttributeNamed() {
		ConstantPool cp = ConstantPool.unbounded();
		ClassDefinition.Builder builder = ClassDefinition.builder(cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER,
				cp.addClass(cp.addUtf8(CLASS)), cp.addClass(cp.addUtf8("java/lang/Object")), cp.addUtf8("Code"));
		builder.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, cp.addUtf8("m"), cp.addUtf8("()V"), 1, 0,
				List.of(Opcode.RETURN), List.of(), List.of(new ByteCodeWriter.LineNumberEntry(0, 1)));
		assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("LineNumberTable");
	}

	@Test
	void relaxingABranchMovesTheLinesAfterIt() {
		// A goto over 40,000 nops to a return is past the signed 16-bit offset: widening
		// it to goto_w moves every later instruction two bytes on, and the line starting
		// at the return with them.
		int gap = 40_000;
		List<Integer> code = new ArrayList<>(List.of(Opcode.GOTO, 0, 0));
		for (int i = 0; i < gap; i++) {
			code.add(Opcode.NOP);
		}
		code.add(Opcode.RETURN);
		int returnPc = 3 + gap;
		List<int[]> deferred = new ArrayList<>();
		deferred.add(new int[] { 0, returnPc });
		List<ByteCodeWriter.LineNumberEntry> lines = new ArrayList<>(
				List.of(new ByteCodeWriter.LineNumberEntry(0, 1), new ByteCodeWriter.LineNumberEntry(returnPc, 2)));
		BranchRelaxer.relax(code, deferred, new ArrayList<>(), lines);
		assertThat(code.get(0)).isEqualTo(Opcode.GOTO_W);
		assertThat(code.get(returnPc + 2)).isEqualTo(Opcode.RETURN);
		assertThat(lines).containsExactly(new ByteCodeWriter.LineNumberEntry(0, 1),
				new ByteCodeWriter.LineNumberEntry(returnPc + 2, 2));
	}

	@Test
	void theShakerKeepsAKeptMethodsLinesAndDropsTheNameWithTheLastMethodCarryingThem() throws Exception {
		Fixture f = new Fixture(true);
		f.throwing("boom", List.of(new ByteCodeWriter.LineNumberEntry(0, 11)));
		f.throwing("gone", List.of(new ByteCodeWriter.LineNumberEntry(0, 12)));
		byte[] definition = f.build().toBytes();
		byte[] kept = StackMapAugmenter.augment(JvmClassShaker.shake(definition, Set.of("boom")), 61);
		assertThat(lines(kept, "boom")).containsExactly(0, 11);
		assertThat(thrownLine(Map.of(CLASS, kept), "boom")).isEqualTo(11);
		byte[] none = JvmClassShaker.shake(new Fixture(true).throwingAndBuild("boom", List.of()), Set.of("boom"));
		assertThat(new String(none, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("LineNumberTable");
	}

	@Test
	void aMethodTheSplitterMovesToAPartKeepsItsLines() throws Exception {
		Fixture f = new Fixture(true);
		// Enough strings that a tiny budget forces the later methods into parts.
		for (int k = 0; k < 12; k++) {
			f.throwing("boom" + k, List.of(new ByteCodeWriter.LineNumberEntry(0, 100 + k)));
		}
		ClassDefinition definition = f.build();
		JvmClassSplitter.Split split = JvmClassSplitter.split(definition, null, method -> false, 40);
		assertThat(split.parts()).isNotEmpty();
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(CLASS, StackMapAugmenter.augment(split.mainClass(), 61));
		split.parts().forEach((name, bytes) -> classes.put(name, StackMapAugmenter.augment(bytes, 61)));
		String part = split.parts().keySet().iterator().next();
		String moved = firstMethod(java.util.Objects.requireNonNull(classes.get(part)));
		int k = Integer.parseInt(moved.substring("boom".length()));
		assertThat(lines(java.util.Objects.requireNonNull(classes.get(part)), moved)).containsExactly(0, 100 + k);
		assertThat(thrownLine(classes, part, moved)).isEqualTo(100 + k);
	}

	private static List<Integer> lines(byte[] classFile, String method) {
		ClassModel model = ClassFile.of().parse(classFile);
		for (MethodModel m : model.methods()) {
			if (m.methodName().stringValue().equals(method)) {
				CodeModel code = m.code().orElseThrow();
				List<Integer> out = new ArrayList<>();
				code.findAttribute(Attributes.lineNumberTable()).ifPresent(table -> {
					for (LineNumberInfo info : table.lineNumbers()) {
						out.add(info.startPc());
						out.add(info.lineNumber());
					}
				});
				return out;
			}
		}
		throw new AssertionError("no method " + method);
	}

	private static String firstMethod(byte[] classFile) {
		return ClassFile.of().parse(classFile).methods().getFirst().methodName().stringValue();
	}

	private static int thrownLine(Map<String, byte[]> classes, String method) throws Exception {
		return thrownLine(classes, CLASS, method);
	}

	private static int thrownLine(Map<String, byte[]> classes, String owner, String method) throws Exception {
		Class<?> clazz = new Loader(classes).loadClass(owner.replace('/', '.'));
		try {
			java.lang.reflect.Method m = clazz.getDeclaredMethod(method);
			m.setAccessible(true);
			m.invoke(null);
		}
		catch (InvocationTargetException ex) {
			return java.util.Objects.requireNonNull(ex.getCause()).getStackTrace()[0].getLineNumber();
		}
		throw new AssertionError(method + " did not throw");
	}

	/** Class Lines: static methods that throw a fresh RuntimeException. */
	private static final class Fixture {

		final ConstantPool cp = ConstantPool.unbounded();

		final ClassDefinition.Builder definition;

		final ConstantPool.ClassConstant exception;

		final ConstantPool.MethodrefConstant init;

		Fixture(boolean namesLineNumbers) {
			this.definition = ClassDefinition.builder(this.cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER,
					this.cp.addClass(this.cp.addUtf8(CLASS)), this.cp.addClass(this.cp.addUtf8("java/lang/Object")),
					this.cp.addUtf8("Code"));
			if (namesLineNumbers) {
				this.definition.lineNumberTableName(this.cp.addUtf8("LineNumberTable"));
			}
			this.exception = this.cp.addClass(this.cp.addUtf8("java/lang/RuntimeException"));
			this.init = this.cp.addMethodref(this.exception,
					this.cp.addNameAndType(this.cp.addUtf8("<init>"), this.cp.addUtf8("(Ljava/lang/String;)V")));
		}

		void throwing(String name, List<ByteCodeWriter.LineNumberEntry> lines) {
			ConstantPool.StringConstant message = this.cp.addString("from " + name);
			List<Integer> code = new ArrayList<>();
			code.add(Opcode.NEW);
			u2(code, this.exception.index());
			code.add(Opcode.DUP);
			code.add(Opcode.LDC_W);
			u2(code, message.index());
			code.add(Opcode.INVOKESPECIAL);
			u2(code, this.init.index());
			code.add(Opcode.ATHROW);
			this.definition.addMethod(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, this.cp.addUtf8(name),
					this.cp.addUtf8("()V"), 3, 0, code, List.of(), lines);
		}

		byte[] throwingAndBuild(String name, List<ByteCodeWriter.LineNumberEntry> lines) {
			throwing(name, lines);
			return build().toBytes();
		}

		ClassDefinition build() {
			return this.definition.build();
		}

		private static void u2(List<Integer> code, int value) {
			code.add(value >> 8);
			code.add(value & 0xFF);
		}

	}

	/** Defines the written classes, the way a class path would serve them. */
	private static final class Loader extends ClassLoader {

		private final Map<String, byte[]> classes;

		Loader(Map<String, byte[]> classes) {
			super(LineNumberTableTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = this.classes.get(name.replace('.', '/'));
			if (bytes == null) {
				throw new ClassNotFoundException(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}

	}

}
