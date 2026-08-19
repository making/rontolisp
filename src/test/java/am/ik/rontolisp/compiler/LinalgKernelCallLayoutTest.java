package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The call-site shape both {@code --simd} codegens pattern-match for the option forms of
 * the {@code linalg:} kernels: numpy-style keywords, literal, any order, each once.
 */
class LinalgKernelCallLayoutTest {

	private static List<LispVal> args(String call) {
		List<LispVal> form = ((LispCons) LispReader.readFromString(call)).toList();
		return form.subList(1, form.size());
	}

	private static final LinalgKernelCallLayout.Extended SUM = Objects
		.requireNonNull(LinalgKernelCallLayout.extended(LispNames.LINALG_SUM));

	@Test
	void keywordsMapOntoTheKernelParametersInAnyOrder() {
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :axis 0)"))).containsExactly(0, 2, -1);
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :axis 0 :keepdims t)"))).containsExactly(0,
				2, 4);
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :keepdims t :axis 0)"))).containsExactly(0,
				4, 2);
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :keepdims t)"))).containsExactly(0, -1, 2);
	}

	@Test
	void aTailThatIsNotLiteralDeclaredKeywordsIsNotAKernelCall() {
		// The required-only call is the base kernel's, not the extended one's.
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a)"))).isNull();
		// Positional options (the pre-&key spelling) are no longer a shape.
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a 0)"))).isNull();
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a 0 t)"))).isNull();
		// Unknown, repeated, odd, or computed keywords route to the defun's &key
		// prologue.
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :axes 0)"))).isNull();
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :axis 0 :axis 1)"))).isNull();
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a :axis)"))).isNull();
		assertThat(LinalgKernelCallLayout.layout(SUM, 1, args("(linalg:sum a (pick) 0)"))).isNull();
	}

	@Test
	void transposeKeepsItsPositionalAxesList() {
		LinalgKernelCallLayout.Extended transpose = Objects
			.requireNonNull(LinalgKernelCallLayout.extended(LispNames.LINALG_TRANSPOSE));
		assertThat(transpose.keywords()).isEmpty();
		assertThat(LinalgKernelCallLayout.layout(transpose, 1, args("(linalg:transpose a '(1 0))"))).containsExactly(0,
				1);
		assertThat(LinalgKernelCallLayout.layout(transpose, 1, args("(linalg:transpose a '(1 0) extra)"))).isNull();
	}

	@Test
	void onlyTheOptionTakingMembersHaveAShape() {
		assertThat(Objects.requireNonNull(LinalgKernelCallLayout.extended(LispNames.LINALG_ARGMAX)).keywords())
			.containsExactly("AXIS");
		assertThat(LinalgKernelCallLayout.extended(LispNames.LINALG_ADD)).isNull();
	}

}
