package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code METHOD_NAMES} rosters the runtime-helper gates are recognized by.
 * <p>
 * {@code JvmLispCompiler} decides whether a gate under-predicted by matching an
 * unresolved own-class call against these sets: a name that a builder emits but the
 * roster omits turns a recoverable under-prediction into a hard compile error, and a name
 * in the roster that no builder emits would force a gate on for a call the gate cannot
 * satisfy. Both directions are checked here against what the builders actually produce,
 * so the rosters cannot drift away from them silently ({@code .kb/adjustable-arrays.md}).
 */
class JvmRuntimeGroupNamesTest {

	private static final String TO_STRING_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	@Test
	void theArrayRuntimeRosterIsExactlyWhatTheBuilderEmits() {
		ConstantPool cp = new ConstantPool();
		ClassConstant selfClass = cp.addClass(cp.addUtf8("Test"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		MethodrefConstant lispToString = selfMethod(cp, selfClass, "_lispToString", TO_STRING_DESC);
		MethodrefConstant lispToDisplayString = selfMethod(cp, selfClass, "_lispToDisplayString", TO_STRING_DESC);

		List<JvmArrayRuntimeBuilder.ArrayMethod> emitted = new ArrayList<>(
				JvmArrayRuntimeBuilder.build(cp, objectClass, objectArrayClass, selfClass));
		emitted.addAll(JvmArrayRuntimeBuilder.buildToStringMethods(cp, lispToString, lispToDisplayString, selfClass));

		assertThat(emitted.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, JvmArrayRuntimeBuilder.METHOD_NAMES));
	}

	@Test
	void theHashRuntimeRosterIsExactlyWhatTheBuilderEmits() {
		ConstantPool cp = new ConstantPool();
		ClassConstant selfClass = cp.addClass(cp.addUtf8("Test"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant equal = selfMethod(cp, selfClass, "_equal", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodrefConstant strv = selfMethod(cp, selfClass, "_strv", "(Ljava/lang/Object;)Ljava/lang/Object;");
		ClassConstant stringArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/String;"));

		List<JvmHashRuntimeBuilder.HashMethod> emitted = JvmHashRuntimeBuilder.build(cp, selfClass, objectClass,
				objectArrayClass, longValueOf, equal, strv, stringArrayClass, false);

		assertThat(emitted.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, JvmHashRuntimeBuilder.METHOD_NAMES));
	}

	@Test
	void theEqualpFoldRosterIsExactlyWhatTheBuilderAddsForIt() {
		ConstantPool cp = new ConstantPool();
		ClassConstant selfClass = cp.addClass(cp.addUtf8("Test"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant equal = selfMethod(cp, selfClass, "_equal", "(Ljava/lang/Object;Ljava/lang/Object;)I");
		MethodrefConstant strv = selfMethod(cp, selfClass, "_strv", "(Ljava/lang/Object;)Ljava/lang/Object;");
		ClassConstant stringArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/String;"));

		List<JvmHashRuntimeBuilder.HashMethod> folding = JvmHashRuntimeBuilder.build(cp, selfClass, objectClass,
				objectArrayClass, longValueOf, equal, strv, stringArrayClass, true);

		Set<String> names = new java.util.LinkedHashSet<>(JvmHashRuntimeBuilder.METHOD_NAMES);
		names.addAll(JvmHashRuntimeBuilder.EQUALP_METHOD_NAMES);
		assertThat(folding.stream().map(m -> m.name().index()).collect(Collectors.toSet()))
			.isEqualTo(indicesOf(cp, names));
	}

	private static MethodrefConstant selfMethod(ConstantPool cp, ClassConstant selfClass, String name, String desc) {
		return cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	// The pool de-duplicates, so re-adding a name yields the very index the builder used.
	private static Set<Integer> indicesOf(ConstantPool cp, Set<String> names) {
		return names.stream().map(name -> cp.addUtf8(name).index()).collect(Collectors.toSet());
	}

}
