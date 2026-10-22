package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code _arityErr} table chunks ({@link JvmRuntimeBuilder#arityErrChunks}): any
 * funcId span tables in chunks of at most one constant each, every chunk covering its
 * range contiguously and every cell reading back the shape it was built from. And the
 * operator-names chunks ({@link JvmRuntimeBuilder#arityNameChunks}): names past one
 * joined constant ride a chunk each, every chunk under one {@code CONSTANT_Utf8}, and the
 * chunks joined recover every entry in order.
 */
class JvmArityTableTest {

	private static int plain(int required, boolean variadic) {
		return required * 2 + (variadic ? 1 : 0);
	}

	private static int named(int required, boolean variadic, int operator) {
		return plain(required, variadic) | (operator << JvmArityOperators.OPERATOR_SHIFT);
	}

	/**
	 * A span past one table (g46: the clojure-spec corpus at 30,568 funcIds, past the
	 * 30,000 single-table cliff, answered every value-path arity miss with nil): two
	 * chunks here, each constant under one {@code CONSTANT_Utf8} and every shape
	 * recovered through the chunk's own side table.
	 */
	@Test
	void chunksCoverASpanPastOneTable() {
		int base = 50;
		int span = 35_000;
		SortedMap<Integer, Integer> shapes = new TreeMap<>();
		for (int i = 0; i < span; i++) {
			if (i % 11 == 0) {
				continue; // a gap: no dispatchable callee, the miss arm's nil
			}
			int shape = switch (i % 3) {
				case 0 -> plain(2, false);
				case 1 -> named(1, true, 9);
				default -> named(3, false, 700); // past the nine in-cell bits, in every
													// chunk
			};
			shapes.put(base + i, shape);
		}
		List<JvmRuntimeBuilder.ArityErrChunk> chunks = JvmRuntimeBuilder.arityErrChunks(shapes, base, span);
		assertThat(chunks).hasSize(2);
		int offset = 0;
		for (JvmRuntimeBuilder.ArityErrChunk chunk : chunks) {
			assertThat(chunk.offset()).isEqualTo(offset);
			assertThat(chunk.length()).isLessThanOrEqualTo(20_000);
			offset += chunk.length();
			assertThat(chunk.table()).hasSize(chunk.length());
			// three bytes per char at most: under one CONSTANT_Utf8 (65,535) with
			// headroom, whatever the program's size
			assertThat(utf8Length(chunk.table())).isLessThanOrEqualTo(60_000);
			if (chunk.operators() != null) {
				assertThat(chunk.operators()).hasSize(chunk.length());
				assertThat(utf8Length(chunk.operators())).isLessThanOrEqualTo(60_000);
			}
		}
		assertThat(offset).isEqualTo(span);
		// every chunk is wide (a 700-operator shape in each), so every chunk reads
		// through its side table and every shape comes back exactly
		assertThat(chunks).allMatch(chunk -> chunk.operators() != null);
		for (JvmRuntimeBuilder.ArityErrChunk chunk : chunks) {
			String operators = chunk.operators();
			if (operators == null) {
				throw new AssertionError("expected a side table");
			}
			for (int i = 0; i < chunk.length(); i++) {
				Integer shape = shapes.get(base + chunk.offset() + i);
				char cell = chunk.table().charAt(i);
				if (shape == null) {
					assertThat(cell).isEqualTo((char) 0x7F);
					assertThat(operators.charAt(i)).isEqualTo((char) 0);
					continue;
				}
				assertThat(cell).isNotEqualTo((char) 0x7F);
				int recovered = (operators.charAt(i) << JvmArityOperators.OPERATOR_SHIFT) | (cell - 1);
				assertThat(recovered).isEqualTo(shape);
			}
		}
	}

	/**
	 * A span one table covers stays one chunk with no side table when no operator needs
	 * one, and the plain cells read back directly.
	 */
	@Test
	void aSmallPlainSpanStaysOneChunkWithoutOperators() {
		SortedMap<Integer, Integer> shapes = new TreeMap<>();
		for (int i = 0; i < 100; i++) {
			shapes.put(i, plain(i % 4, (i & 1) == 0));
		}
		List<JvmRuntimeBuilder.ArityErrChunk> chunks = JvmRuntimeBuilder.arityErrChunks(shapes, 0, 100);
		assertThat(chunks).hasSize(1);
		JvmRuntimeBuilder.ArityErrChunk chunk = chunks.get(0);
		assertThat(chunk.offset()).isZero();
		assertThat(chunk.length()).isEqualTo(100);
		assertThat(chunk.operators()).isNull();
		for (int i = 0; i < 100; i++) {
			assertThat(chunk.table().charAt(i) - 1).isEqualTo(shapes.get(i));
		}
	}

	/** No dispatchable callee: no chunks, and the miss arm answers nil. */
	@Test
	void anEmptySpanHasNoChunks() {
		assertThat(JvmRuntimeBuilder.arityErrChunks(new TreeMap<>(), 0, 0)).isEmpty();
	}

	/**
	 * More operator names than one joined constant holds (g49: 60,000 bytes, past which
	 * late callees reported as {@code Function}): every chunk's joined bytes stay under
	 * one {@code CONSTANT_Utf8}, and the chunks joined recover every entry in order.
	 */
	@Test
	void namesPastOneConstantRideAChunkEach() {
		// Built through a real registry, as buildArityMethods builds them: 1,200
		// declared reports, each with its own prefix, past the 60,000-byte budget
		// past which late callees reported as Function.
		JvmArityOperators operators = new JvmArityOperators();
		for (int i = 1; i <= 1200; i++) {
			operators.declare("bp-" + i, new am.ik.rontolisp.DeclaredArityReport(
					"BUDGET-" + i + "-0123456789-0123456789", ") tail-" + i + "-0123456789"));
		}
		for (int i = 1; i <= 1200; i++) {
			operators.shape(1, false, "bp-" + i);
		}
		List<String> names = operators.freeze();
		List<String> chunks = JvmRuntimeBuilder.arityNameChunks(names);
		assertThat(chunks.size()).isGreaterThan(1);
		for (String chunk : chunks) {
			assertThat(utf8Length(chunk)).isLessThanOrEqualTo(60_000);
		}
		assertThat(String.join("\n", chunks).split("\n", -1)).containsExactlyElementsOf(names);
	}

	/**
	 * Names one joined constant holds stay one chunk holding that join, so the
	 * single-chunk {@code _arityMsg} emission is the one it always was; no names is no
	 * chunks.
	 */
	@Test
	void namesWithinOneConstantStayOneChunk() {
		List<String> names = List.of("CONS", "" + 'W' + "(0) wd-600");
		assertThat(JvmRuntimeBuilder.arityNameChunks(names)).containsExactly(String.join("\n", names));
		assertThat(JvmRuntimeBuilder.arityNameChunks(List.of())).isEmpty();
	}

	/** The class file's modified UTF-8 length, as the constant-pool writer counts it. */
	private static int utf8Length(CharSequence text) {
		int length = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			length += c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
		}
		return length;
	}

	/** The chunk-offset arithmetic the multi-chunk emission walks, over gapless ids. */
	@Test
	void chunkBoundariesCoverEveryId() {
		SortedMap<Integer, Integer> shapes = new TreeMap<>();
		int span = 20_000 * 3 + 7;
		for (int i = 0; i < span; i++) {
			shapes.put(i, named(1, false, 700));
		}
		List<JvmRuntimeBuilder.ArityErrChunk> chunks = JvmRuntimeBuilder.arityErrChunks(shapes, 0, span);
		assertThat(chunks).hasSize(4);
		List<Integer> covered = new ArrayList<>();
		for (JvmRuntimeBuilder.ArityErrChunk chunk : chunks) {
			for (int i = 0; i < chunk.length(); i++) {
				covered.add(chunk.offset() + i);
			}
		}
		assertThat(covered).hasSize(span);
		for (int i = 0; i < span; i++) {
			assertThat(covered.get(i)).isEqualTo(i);
		}
	}

}
