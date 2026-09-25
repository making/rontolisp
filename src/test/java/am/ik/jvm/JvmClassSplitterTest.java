package am.ik.jvm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The splitter against hand-built definitions, where every entry and every byte is known:
 * a chain of static methods too large for one tiny class spreads over parts and still
 * runs, what cannot move stays, what the shaker would drop is not written, and a
 * definition that fits comes out exactly as the shaker writes it. The whole compiler's
 * use of it -- programs, output shapes, the forced split -- is
 * {@code JvmLispCompilerSplitTest}.
 */
class JvmClassSplitterTest {

	private static final int CHAIN = 30;

	// Each class small enough that the chain needs several: every step brings its own
	// name, a string and the reference to the next step.
	private static final int TINY_BUDGET = 40;

	@Test
	void aChainTooLargeForOneClassRunsAcrossParts() throws Exception {
		ClassDefinition definition = chain(ConstantPool.unbounded());
		JvmClassSplitter.Split split = JvmClassSplitter.split(definition, null, named(definition, "result"),
				TINY_BUDGET);
		assertThat(split.parts()).as("the chain needs several classes").hasSizeGreaterThan(2);
		assertThat(split.parts().keySet()).allSatisfy(name -> assertThat(name).startsWith("SplitMe$Part"));

		Map<String, byte[]> classes = augmented(split);
		for (byte[] bytes : classes.values()) {
			// Each class's own count stays within what it was filled to: the budget, plus
			// the Class entry of the next part its last step calls into.
			assertThat(poolCount(bytes)).isLessThanOrEqualTo(TINY_BUDGET + 2);
		}
		Class<?> main = new Loader(classes).loadClass("SplitMe");
		main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		assertThat(main.getMethod("result").invoke(null)).as("every step ran, whichever class holds it")
			.isEqualTo(CHAIN);
		// The main class is filled first, the parts take the rest in order: every step is
		// declared exactly once, and a moved method is no longer private.
		assertThat(declared(main)).contains("main", "result");
		List<String> steps = new ArrayList<>(declared(main).stream().filter(name -> name.startsWith("step")).toList());
		for (String part : split.parts().keySet()) {
			Class<?> partClass = new Loader(classes).loadClass(part.replace('/', '.'));
			for (Method method : partClass.getDeclaredMethods()) {
				assertThat(method.getName()).startsWith("step");
				assertThat(Modifier.isPrivate(method.getModifiers())).as("a part's methods are package-visible")
					.isFalse();
				steps.add(method.getName());
			}
		}
		assertThat(steps).hasSize(CHAIN).doesNotHaveDuplicates();
	}

	// Started past 65535, every index is one a class file cannot carry: the code keeps
	// them
	// whole and the split re-points each into its class's own pool.
	@Test
	void indexesPastTheFormatLimitAreRepointedIntoEachClassesPool() throws Exception {
		ClassDefinition definition = chain(ConstantPool.unboundedFrom(70_000));
		JvmClassSplitter.Split split = JvmClassSplitter.split(definition, null, named(definition, "result"),
				TINY_BUDGET);
		Class<?> main = new Loader(augmented(split)).loadClass("SplitMe");
		main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
		assertThat(main.getMethod("result").invoke(null)).isEqualTo(CHAIN);
	}

	// A method whose identity is its class stays there whatever the budget: an instance
	// method, a synchronized one (the class is its monitor), one asking MethodHandles for
	// a
	// lookup (the class is the lookup's), and the class initializer.
	@Test
	void whatCannotMoveStaysInTheMainClass() throws Exception {
		ConstantPool cp = ConstantPool.unbounded();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant lookup = cp.addMethodref(
				cp.addClass(cp.addUtf8("java/lang/invoke/MethodHandles")),
				cp.addNameAndType(cp.addUtf8("lookup"), cp.addUtf8("()Ljava/lang/invoke/MethodHandles$Lookup;")));
		b.method(AccessFlag.ACC_STATIC, "<clinit>", "()V", new Code().op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PUBLIC, "instance", "()V", new Code().op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC | AccessFlag.ACC_SYNCHRONIZED, "locked", "()V",
				new Code().op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "looksUp", "()V",
				new Code().op(Opcode.INVOKESTATIC).u2(lookup.index()).op(Opcode.POP).op(Opcode.RETURN));
		for (int i = 0; i < 20; i++) {
			b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "free" + i, "()V",
					new Code().ldc(cp.addString("filler-" + i)).op(Opcode.POP).op(Opcode.RETURN));
		}
		JvmClassSplitter.Split split = JvmClassSplitter.split(b.build(), null, method -> false, TINY_BUDGET);
		assertThat(split.parts()).isNotEmpty();
		Class<?> main = new Loader(augmented(split)).loadClass("SplitMe");
		assertThat(declared(main)).contains("instance", "locked", "looksUp");
		assertThat(declared(main).stream().filter(name -> name.startsWith("free"))).as("what can move did")
			.hasSizeLessThan(20);
	}

	// Asked to shake, the split drops exactly what JvmClassShaker drops -- and a
	// definition
	// that then fits one class comes out byte for byte as the shaker writes it, so the
	// split route cannot make a class that fits any different.
	@Test
	void aDefinitionThatFitsIsWrittenExactlyAsTheShakerWritesIt() {
		ConstantPool cp = ConstantPool.unbounded();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant used = b.ref("used", "()V");
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new Code().op(Opcode.INVOKESTATIC).u2(used.index()).op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "used", "()V",
				new Code().ldc(cp.addString("kept")).op(Opcode.POP).op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "unused", "()V",
				new Code().ldc(cp.addString("dropped")).op(Opcode.POP).op(Opcode.RETURN));
		ClassDefinition definition = b.build();
		JvmClassSplitter.Split split = JvmClassSplitter.split(definition, Set.of("main"), method -> false);
		assertThat(split.parts()).isEmpty();
		assertThat(split.mainClass()).isEqualTo(JvmClassShaker.shake(definition.toBytes(), Set.of("main")));
	}

	@Test
	void unresolvedOwnCallsAreReportedAsTheShakerReportsThem() {
		ConstantPool cp = ConstantPool.unbounded();
		Builder b = new Builder(cp);
		ConstantPool.MethodrefConstant missing = b.ref("missing", "()V");
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new Code().op(Opcode.INVOKESTATIC).u2(missing.index()).op(Opcode.RETURN));
		ClassDefinition definition = b.build();
		assertThat(JvmClassSplitter.unresolvedSelfMethods(definition))
			.isEqualTo(JvmClassShaker.unresolvedSelfMethods(definition.toBytes()))
			.singleElement()
			.satisfies(unresolved -> assertThat(unresolved.name()).isEqualTo("missing"));
	}

	// main calls step0, each step bumps a private static counter and calls the next, and
	// result() reads the counter back: the chain works only if every cross-class call and
	// every field access from a part resolves.
	private static ClassDefinition chain(ConstantPool cp) {
		Builder b = new Builder(cp);
		ConstantPool.Utf8Constant counterName = cp.addUtf8("counter");
		ConstantPool.Utf8Constant intDesc = cp.addUtf8("I");
		b.definition.addField(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, counterName, intDesc);
		ConstantPool.FieldrefConstant counter = cp.addFieldref(b.thisClass, cp.addNameAndType(counterName, intDesc));
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				new Code().op(Opcode.INVOKESTATIC).u2(b.ref("step0", "()V").index()).op(Opcode.RETURN));
		b.method(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, "result", "()I",
				new Code().op(Opcode.GETSTATIC).u2(counter.index()).op(Opcode.IRETURN));
		for (int k = 0; k < CHAIN; k++) {
			Code code = new Code().ldc(cp.addString("text-" + k))
				.op(Opcode.POP)
				.op(Opcode.GETSTATIC)
				.u2(counter.index())
				.op(Opcode.ICONST_1)
				.op(Opcode.IADD)
				.op(Opcode.PUTSTATIC)
				.u2(counter.index());
			if (k + 1 < CHAIN) {
				code.op(Opcode.INVOKESTATIC).u2(b.ref("step" + (k + 1), "()V").index());
			}
			b.method(AccessFlag.ACC_PRIVATE | AccessFlag.ACC_STATIC, "step" + k, "()V", code.op(Opcode.RETURN));
		}
		return b.build();
	}

	private static Predicate<ClassDefinition.Method> named(ClassDefinition definition, String name) {
		return method -> definition.cp().utf8At(method.name().index()).equals(name);
	}

	private static Map<String, byte[]> augmented(JvmClassSplitter.Split split) {
		Map<String, byte[]> classes = new LinkedHashMap<>();
		classes.put("SplitMe", StackMapAugmenter.augment(split.mainClass(), 61));
		split.parts().forEach((name, bytes) -> classes.put(name, StackMapAugmenter.augment(bytes, 61)));
		return classes;
	}

	private static int poolCount(byte[] classFile) {
		return ((classFile[8] & 0xFF) << 8 | (classFile[9] & 0xFF)) - 1;
	}

	private static List<String> declared(Class<?> clazz) {
		return Arrays.stream(clazz.getDeclaredMethods()).map(Method::getName).toList();
	}

	/** A definition under construction: class SplitMe extends Object. */
	private static final class Builder {

		final ConstantPool cp;

		final ConstantPool.ClassConstant thisClass;

		final ClassDefinition.Builder definition;

		private final Map<String, ConstantPool.Utf8Constant> utf8 = new HashMap<>();

		Builder(ConstantPool cp) {
			this.cp = cp;
			this.thisClass = cp.addClass(cp.addUtf8("SplitMe"));
			this.definition = ClassDefinition.builder(cp, AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, this.thisClass,
					cp.addClass(cp.addUtf8("java/lang/Object")), cp.addUtf8("Code"));
		}

		ConstantPool.MethodrefConstant ref(String name, String desc) {
			return this.cp.addMethodref(this.thisClass, this.cp.addNameAndType(this.utf8(name), this.utf8(desc)));
		}

		void method(int access, String name, String desc, Code code) {
			this.definition.addMethod(access, this.utf8(name), this.utf8(desc), 4, 1, code.bytes, List.of());
		}

		ClassDefinition build() {
			return this.definition.build();
		}

		private ConstantPool.Utf8Constant utf8(String s) {
			return this.utf8.computeIfAbsent(s, this.cp::addUtf8);
		}

	}

	/**
	 * A method body, written the way the generators write one: a u2's high part kept
	 * whole.
	 */
	private static final class Code {

		final List<Integer> bytes = new ArrayList<>();

		Code op(int opcode) {
			this.bytes.add(opcode);
			return this;
		}

		Code u2(int value) {
			this.bytes.add(value >> 8);
			this.bytes.add(value & 0xFF);
			return this;
		}

		Code ldc(ConstantPool.Constant constant) {
			if (constant.index() <= 255) {
				return this.op(Opcode.LDC).op(constant.index());
			}
			return this.op(Opcode.LDC_W).u2(constant.index());
		}

	}

	/** Defines the written classes, the way a class path would serve them. */
	private static final class Loader extends ClassLoader {

		private final Map<String, byte[]> classes;

		Loader(Map<String, byte[]> classes) {
			super(JvmClassSplitterTest.class.getClassLoader());
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
