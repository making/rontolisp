package am.ik.rontolisp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LispFloatArray}'s permits and {@link FloatWidth}'s constants are in BIJECTION.
 *
 * <p>
 * That is what makes two ways of asking a packed array its width safe to have at once --
 * an exhaustive {@code switch} over the sealed type when you need the concrete array, and
 * one over {@link LispFloatArray#width()} when you need the width as a value
 * ({@code .kb/vec.md}). Under a bijection {@code width()} is not a second question but a
 * total, injective renaming of the first, so an exhaustive switch over either is
 * exhaustive over both, and the convention is a consequence rather than something to
 * remember.
 *
 * <p>
 * One direction the compiler already holds: a permit added without a constant cannot
 * answer the abstract {@code width()} and does not compile. The other direction is the
 * one nothing objects to and this test exists for -- a CONSTANT added ahead of its
 * permit, while someone wires a reader or the wire format before the array type exists.
 * Every enum switch then still compiles and still looks exhaustive, quietly carrying an
 * arm for a width no array can be.
 *
 * <p>
 * {@code .todo/683} step 3 wants a reflective test over the same {@code permits} clause
 * for a different property -- that every permit is REACHABLE from {@code make-array} --
 * and owns that half. This one asserts only the bijection.
 */
class FloatWidthTest {

	@Test
	void everyPermitHasExactlyOneWidthConstantAndEveryConstantHasAPermit() {
		List<LispFloatArray> onePerPermit = List.of(new LispSingleFloatArray(new float[] { 1.0f }, new int[] { 1 }),
				new LispDoubleFloatArray(new double[] { 1.0 }, new int[] { 1 }),
				new LispBFloat16Array(new short[] { 0x3f80 }, new int[] { 1 }));

		Set<Class<?>> permits = Set.of(LispFloatArray.class.getPermittedSubclasses());
		Set<Class<?>> covered = new HashSet<>();
		Set<FloatWidth> widths = new HashSet<>();
		for (LispFloatArray array : onePerPermit) {
			covered.add(array.getClass());
			widths.add(array.width());
		}
		// The instances really are one per permit, so what follows is about the permits
		// rather than about this list.
		assertThat(covered).as("one instance per permit of LispFloatArray").isEqualTo(permits);
		assertThat(widths).as("each permit answers a DISTINCT width").hasSize(onePerPermit.size());
		assertThat(widths).as("the widths a permit can answer are exactly FloatWidth's constants")
			.containsExactlyInAnyOrder(FloatWidth.values());
		assertThat(permits).as("a constant added ahead of its permit leaves an arm no array can reach")
			.hasSameSizeAs(FloatWidth.values());
	}

	/**
	 * The codes are LITERALS, pinned as literals.
	 *
	 * <p>
	 * Three templates that TRAVEL with a compiled program hardcode these numbers --
	 * {@code codegen/jvm/JvmSimdVectorTemplate.laGatherStrided},
	 * {@code codegen/jvm/JvmGpuTemplate.gpuGatherStrided} and the emitted body
	 * {@code codegen/wasm/WasmLinalgSimdRuntimeBuilder.buildGatherStrided} writes. They
	 * cannot import {@link FloatWidth}: the root package does not travel, so the width
	 * they read off {@code %la-gather-strided}'s wire is compared against a bare
	 * {@code 0} or {@code 1}.
	 *
	 * <p>
	 * Distinctness and round-tripping do NOT pin that. Reordering the constants, or
	 * inserting one ahead of {@link FloatWidth#SINGLE}, leaves the codes distinct and
	 * round-tripping, leaves the test below green, and makes all three templates read
	 * every width as the wrong one -- silently, from a one-line edit that looks harmless.
	 * So the values themselves are asserted here.
	 */
	@Test
	void theWidthCodesAreTheNumbersTheTravellingTemplatesHardcode() {
		assertThat(FloatWidth.SINGLE.code()).isEqualTo(0);
		assertThat(FloatWidth.DOUBLE.code()).isEqualTo(1);
		assertThat(FloatWidth.BFLOAT16.code()).isEqualTo(2);
	}

	@Test
	void everyWidthCodeIsDistinctAndReadsBack() {
		Set<Integer> codes = new HashSet<>();
		for (FloatWidth width : FloatWidth.values()) {
			codes.add(width.code());
			assertThat(FloatWidth.ofCode(width.code())).as("%s round-trips through its code", width).isEqualTo(width);
		}
		assertThat(codes).as("the codes cross a backend boundary, so two widths must not share one")
			.hasSameSizeAs(FloatWidth.values());
	}

}
