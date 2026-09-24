package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wasm.WasmWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bound on the size of any single emitted WASM function body.
 * <p>
 * A wasmtime cold compile (Cranelift) needs memory that grows superlinearly -- measured
 * at roughly the 1.8th power -- in the size of ONE function body, and nothing else about
 * the module matters. Measured on the concatenated {@code ci-spec.yaml} corpus, cold
 * cache, {@code wasmtime 47.0.2}:
 *
 * <pre>
 * largest body    9 KB -&gt; wasmtime peaks at  284 MB
 * largest body  261 KB -&gt; wasmtime peaks at  2.7 GB
 * largest body  437 KB -&gt; wasmtime peaks at  7.4 GB
 * largest body  850 KB -&gt; wasmtime peaks at 25.8 GB
 * </pre>
 *
 * So a backend that concatenates the whole toplevel into one body makes a large program
 * un-runnable: it is not slow, it exhausts the machine. (This is what took the CI
 * {@code native-image} job down -- a 16 GB runner was OOM-killed while compiling the
 * corpus, and the wasmtime compilation cache hides it on any host that has run the module
 * once before.)
 * <p>
 * The bound below is deliberately far under the point where a 16 GB machine is at risk.
 * <p>
 * There is a case per top-level SHAPE, because they are emitted by different paths that
 * have been out of step before: the synchronous top level chunks by size
 * ({@code WasmToplevelEmit}), while the {@code --component} async top level cuts its
 * resume at the awaits and outlines each await-free run -- which bounds nothing by
 * itself, since a run is as long as the program. Measuring only one of the two is how a
 * 650 KB component body shipped while the same program's synchronous build stayed at 214
 * KB.
 */
class WasmToplevelChunkingTest {

	/**
	 * 256 KiB of body keeps a cold wasmtime compile in the low gigabytes, with a wide
	 * margin against the smallest CI runner. Raising this is a decision about how big a
	 * machine users need, not a formatting detail.
	 */
	private static final int MAX_FUNCTION_BODY_BYTES = 256 * 1024;

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler().compile(program);
	}

	private static byte[] compileComponent(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return WasmLispCompiler.builder().component(true).build().compile(program);
	}

	/** A long top level whose forms are individually small. */
	private static String longToplevel() {
		return IntStream.range(0, 12000)
			.mapToObj(i -> "(print (+ %d (* %d 3)))".formatted(i, i))
			.collect(Collectors.joining("\n"));
	}

	@Test
	void aLongToplevelIsNotEmittedAsOneFunctionBody() {
		// Each form is small; only their number is unusual. A backend that concatenates
		// them into _start grows one body without bound.
		int largest = WasmModuleInspector.largestFunctionBodySize(compile(longToplevel()));

		assertThat(largest)
			.as("largest emitted function body; a monolithic toplevel makes a cold wasmtime "
					+ "compile need memory superlinear in this number")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	/**
	 * A program with a few thousand callables, every one of them reachable as a function
	 * VALUE, plus the {@code apply} that makes the spread dispatcher real.
	 */
	private static String manyCallables() {
		StringBuilder sb = new StringBuilder("(defvar *fs* nil)\n");
		for (int i = 0; i < 3000; i++) {
			sb.append("(defun f%d (a b c) (+ a b c %d))\n".formatted(i, i));
			sb.append("(setq *fs* (cons #'f%d *fs*))\n".formatted(i));
		}
		sb.append("(print (apply (car *fs*) (list 1 2 3)))\n");
		return sb.toString();
	}

	@Test
	void theDispatchLadderIsNotEmittedAsOneFunctionBody() {
		// The SPREAD dispatcher (_apply's) is one br_table case per callable in the
		// whole program -- ~110 bytes each, since a case walks its target's required
		// parameters out of the argument list -- so it grows with the program's
		// function count and with nothing a test author can see. Emitted as ONE body
		// this program's is 417,675 bytes; the ci-spec corpus reached 258 KB of it
		// against this same bound while every other body in the module was under 75 KB,
		// which left three callables of headroom for whoever added the next case.
		int largest = WasmModuleInspector.largestFunctionBodySize(compile(manyCallables()));

		assertThat(largest)
			.as("largest emitted function body of a program with 3000 callables; a dispatch "
					+ "ladder emitted as one body grows with the program's function count")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	@Test
	void aLongAsyncToplevelIsNotEmittedAsOneFunctionBody() {
		// One top-level await puts the WHOLE top level on the async resume path, which
		// outlines each await-free RUN of statements -- a run whose length is the
		// program's, not a bounded number of bytes. The size bound has to hold there
		// too: this is the shape every fetch/serve component and the --component leg of
		// the ci-spec corpus compiles as.
		String source = longToplevel() + "\n(print (rontolisp:await 42))\n";

		int largest = WasmModuleInspector.largestFunctionBodySize(compileComponent(source));

		assertThat(largest)
			.as("largest emitted function body of the ASYNC top level; a run outlined whole "
					+ "makes a cold wasmtime compile need memory superlinear in this number")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	/**
	 * A long top level led by every shape that binds a top-level name without a
	 * head-position definer: a nested assignment read back by a later form, a defun
	 * nested in a top-level form, and a multiple-value-setq. Each one once disabled the
	 * chunker for the rest of the program -- the first form that allocated a named local
	 * latched it off, so this program compiled as one ~300 KB body. Cutting must continue
	 * across all of them.
	 */
	@Test
	void chunkingContinuesAcrossTopLevelShapesThatBindWithoutAHeadPositionDefiner() {
		String source = "(print (progn (setq trip-nested-455 10) trip-nested-455))\n" //
				+ "(print trip-nested-455)\n" //
				+ "(let () (defun trip-nested-fn-455 () 42) (print (trip-nested-fn-455)))\n" //
				+ "(multiple-value-setq (trip-mva-455 trip-mvb-455) (values 1 2))\n" //
				+ longToplevel();

		int largest = WasmModuleInspector.largestFunctionBodySize(compile(source));

		assertThat(largest).as("largest emitted function body; a pinning form must delay cuts, never disable them")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	/**
	 * The same through the real compile pipeline: a compiler macro that introduces a
	 * top-level assignment of a name no earlier pass could have collected, called at the
	 * top level ahead of a long run of forms.
	 */
	@Test
	void chunkingContinuesAcrossACompilerMacroIntroducedAssignment() {
		String source = "(define-compiler-macro cm-trip-455 (x) `(progn (setq __cm_trip_455 ,x) __cm_trip_455))\n" //
				+ "(print (cm-trip-455 1))\n" //
				+ IntStream.range(0, 11000)
					.mapToObj(i -> "(print (+ %d (* %d 3)))".formatted(i, i))
					.collect(Collectors.joining("\n"));
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.corpus(source,
				am.ik.rontolisp.reader.Features.WASM, true, false);

		int largest = WasmModuleInspector.largestFunctionBodySize(new WasmLispCompiler().compile(program));

		assertThat(largest)
			.as("largest emitted function body; a backend-time-introduced assignment must not stop the chunker")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	/**
	 * A defun whose body is one DECISION TREE -- the shape fast-http's
	 * {@code match-i-case} header parser expands to -- with a leaf per value below
	 * {@code leaves}, balanced so the nesting stays shallow.
	 */
	static String decisionTreeDefun(String name, int leaves) {
		return "(defun %s (x) %s)".formatted(name, decisionTree(0, leaves));
	}

	private static String decisionTree(int lo, int hi) {
		if (hi - lo == 1) {
			return "(list %d (* x %d) (+ x %d 7) (- x %d))".formatted(lo, lo, lo, lo);
		}
		int mid = (lo + hi) / 2;
		return "(if (< x %d) %s %s)".formatted(mid, decisionTree(lo, mid), decisionTree(mid, hi));
	}

	@Test
	void aDecisionTreeDefunIsNotEmittedAsOneFunctionBody() {
		// One defun, no top-level length at all: the body is a single branch form, which
		// no top-level chunker reaches. fast-http's parse-header-field-and-value came out
		// at 748 KB this way, and its native frame alone exhausted wasmtime's default
		// stack. This one is 1.2 MB emitted whole.
		String source = decisionTreeDefun("big-tree", 4096) + "\n(print (big-tree 4000))\n";

		int largest = WasmModuleInspector.largestFunctionBodySize(compile(source));

		assertThat(largest).as("largest emitted function body of a program with one oversized decision-tree defun")
			.isLessThanOrEqualTo(MAX_FUNCTION_BODY_BYTES);
	}

	/**
	 * The cut rule itself: a cut waits while a name bound in the current chunk is still
	 * read by a later form, and closes past the last such reader. A quoted occurrence is
	 * not a read.
	 */
	@Test
	void aCutWaitsForTheLastReaderOfAChunkBoundName() {
		WasmLispCompiler.Ctx ctx = WasmLispCompiler.Ctx.builder()
			.writer(new WasmWriter(new ByteArrayOutputStream()))
			.bodyStream(new ByteArrayOutputStream())
			.stringTable(new WasmLispCompiler.StringTable(0, false, false))
			.build();
		ctx.locals.put("TRIP-WB-455", 1);
		List<LispVal> program = LispReader.readAllFromString("(print 1)\n(print trip-wb-455)\n");

		assertThat(WasmToplevelEmit.readsChunkLocal(program, 0, ctx))
			.as("a later form reads the chunk-bound name, so the cut must wait")
			.isTrue();
		assertThat(WasmToplevelEmit.readsChunkLocal(program, 1, ctx))
			.as("no form follows the reader, so the chunk may close")
			.isFalse();

		List<LispVal> quoted = LispReader.readAllFromString("(print 1)\n(print 'trip-wb-455)\n");
		assertThat(WasmToplevelEmit.readsChunkLocal(quoted, 0, ctx)).as("a quoted occurrence is not a read").isFalse();

		ctx.locals.clear();
		assertThat(WasmToplevelEmit.readsChunkLocal(program, 0, ctx)).as("nothing bound means no scan at all")
			.isFalse();
	}

}
