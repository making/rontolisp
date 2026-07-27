package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the invariant that no {@link LispEvaluator} method crosses HotSpot's
 * {@code HugeMethodLimit}.
 *
 * <p>
 * {@code -XX:+DontCompileHugeMethods} is on by default and refuses to JIT-compile any
 * method whose bytecode exceeds 8000 bytes; such a method runs in the bytecode
 * interpreter forever. That is invisible in every functional test and cost this class
 * 2.7x: the operator table of {@code evalCons} -- the innermost method of the whole
 * interpreter -- had grown to 8209 bytecodes, so every evaluated form paid interpreted
 * dispatch (todo 188). The table is now split across {@code evalCons} and
 * {@code evalConsRareOperator}; this test fails the build if either half (or any other
 * method here) grows back past the cliff, since the symptom is otherwise only a slow
 * program.
 */
class LispEvaluatorHotMethodSizeTest {

	/**
	 * HotSpot's {@code HugeMethodLimit}. Kept as the raw limit rather than a lower target
	 * so the failure message names the real cliff; the headroom is the split itself.
	 */
	private static final int HUGE_METHOD_LIMIT = 8000;

	@Test
	void noEvaluatorMethodIsTooBigToJitCompile() throws IOException {
		record Method(String name, int size) {
		}
		byte[] classBytes;
		try (InputStream in = LispEvaluator.class.getResourceAsStream("LispEvaluator.class")) {
			assertThat(in).as("LispEvaluator.class on the test classpath").isNotNull();
			classBytes = in.readAllBytes();
		}
		ClassModel model = ClassFile.of().parse(classBytes);
		List<Method> tooBig = new ArrayList<>();
		int largest = 0;
		for (MethodModel method : model.methods()) {
			if (method.code().orElse(null) instanceof CodeAttribute code) {
				largest = Math.max(largest, code.codeLength());
				if (code.codeLength() > HUGE_METHOD_LIMIT) {
					tooBig.add(new Method(method.methodName().stringValue(), code.codeLength()));
				}
			}
		}
		assertThat(largest).as("at least one method body was measured").isGreaterThan(0);
		assertThat(tooBig)
			.as("LispEvaluator methods past HotSpot's HugeMethodLimit of %d bytecodes -- these never get "
					+ "JIT-compiled, so the interpreter runs them in the bytecode interpreter. Split the "
					+ "method (see evalConsRareOperator) rather than raising the limit.", HUGE_METHOD_LIMIT)
			.isEmpty();
	}

}
