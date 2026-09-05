package am.ik.rontolisp.cli;

import java.io.FileNotFoundException;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The compile front end as the corpus guards ({@code JvmClassShakerCorpusTest},
 * {@code WasmTreeShakerCorpusTest}) run it: the CLI's own pass pipeline over the
 * {@code ci-spec.yaml} catalogue.
 *
 * <p>
 * <b>Why this class is here rather than in the guards.</b> Both guards used to spell the
 * pipeline out by hand, and both had fallen behind {@link CompileFrontend}: eight passes
 * missing when a {@code tokenizer:} case joined the corpus and went red, and ten still
 * missing when this was written -- plus {@link am.ik.rontolisp.eval.VecLibrary} applied
 * in a DIFFERENT POSITION, which no census of pass names could have caught. The order now
 * lives once, in {@link CompileFrontend#expand}, and the two guards reach it through
 * here.
 *
 * <p>
 * This class lives in the {@code am.ik.rontolisp.cli} package so that {@code expand} and
 * {@code Result} stay package-private: the fix is to stop duplicating an order, not to
 * widen the CLI's API for a test.
 *
 * <p>
 * The one thing it does NOT delegate is the source loader. {@link CompileFrontend#run}
 * reads through the real filesystem; the guards deliberately pass a loader that THROWS,
 * which is how they assert that the catalogue never comes to depend on a file on disk.
 * That is an assertion, not a convenience, so it is kept and stated here.
 */
public final class CorpusFrontend {

	private CorpusFrontend() {
	}

	/**
	 * Reads, load-inlines and expands the corpus exactly as the CLI would for this
	 * target.
	 * @param source the corpus program text
	 * @param features the target feature set ({@code Features.JVM} /
	 * {@code Features.WASM})
	 * @param wasm whether the target is a {@code .wasm} output
	 * @param noWasi {@code --no-wasi}; the WASM guard compiles both ways
	 * @return the expanded, spliced and pruned top-level forms
	 */
	public static List<LispVal> program(String source, Features features, boolean wasm, boolean noWasi) {
		// #. in the corpus rides the marker read (resolved in UserMacroExpander), like
		// the CLI.
		List<LispVal> read = source.contains("#.") ? LispReader.readAllWithReadEvalMarkers(source, features)
				: LispReader.readAllFromString(source, features);
		// LoadInliner splices the built-in ASDF shim systems the corpus load-systems
		// (bordeaux-threads' bt2 case), exactly like the CLI. The loader throws: see the
		// class comment -- the corpus must reference no filesystem source.
		List<LispVal> loaded = LoadInliner.inline(read, path -> {
			throw new FileNotFoundException(path);
		}, null, List.of(), features);
		return CompileFrontend
			.expand(loaded, features, null, wasm, false, false, noWasi, false, false, HostBoundary.ENVELOPE, false,
					false)
			.program();
	}

}
