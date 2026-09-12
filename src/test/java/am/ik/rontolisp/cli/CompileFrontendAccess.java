package am.ik.rontolisp.cli;

import java.io.FileNotFoundException;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.eval.DistClient;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The compile path's front end as a TEST runs it: one door onto {@link CompileFrontend},
 * for the tests that need the program the CLI would hand a backend rather than a program
 * of their own making.
 *
 * <p>
 * <b>Why this class exists.</b> Every caller here used to spell the pass pipeline out by
 * hand, and every copy fell behind. The corpus guards ({@code JvmClassShakerCorpusTest},
 * {@code WasmTreeShakerCorpusTest}) were eight passes short when a {@code tokenizer:}
 * case joined the corpus and went red, and ten short when that was fixed -- plus
 * {@link am.ik.rontolisp.eval.VecLibrary} applied in a DIFFERENT POSITION, which no
 * census of pass names could have caught. The {@code asdf:load-system} library E2Es
 * ({@code AsdfLibraryE2eSupport}, {@code JvmLibraryMethodSizeTest}) carried a third and a
 * fourth copy, stopped at six passes: the coverage for compiling a real third-party tree
 * was compiling a program no user could build. The order now lives once, in
 * {@link CompileFrontend#expand}, and every test reaches it through here.
 *
 * <p>
 * This class lives in the {@code am.ik.rontolisp.cli} package so that
 * {@link CompileFrontend} and its {@code Result} stay package-private: the fix is to stop
 * duplicating an order, not to widen the CLI's API for a test.
 *
 * <p>
 * The one thing the front end does NOT settle for a caller is the source loader, which is
 * exactly what separates the two methods below: {@link #corpus} passes a loader that
 * THROWS, which is how the guards assert the catalogue never comes to depend on a file on
 * disk, while {@link #withSystemPath} reads the real filesystem because resolving an
 * {@code .asd} is the point of it.
 */
public final class CompileFrontendAccess {

	private CompileFrontendAccess() {
	}

	/**
	 * What the front end produced: the program a backend compiles, and the feature set it
	 * was read with -- which a backend re-reads to seed the compiled program's run-time
	 * {@code *features*}, so a {@code (member :F *features*)} and the {@code #+F} beside
	 * it cannot disagree.
	 *
	 * @param forms the expanded, spliced and pruned top-level forms
	 * @param features the feature set the program was read with
	 */
	public record Program(List<LispVal> forms, Features features) {
	}

	/**
	 * Reads, load-inlines and expands the corpus exactly as the CLI would for this
	 * target, with a source loader that refuses to read a file.
	 * @param source the corpus program text
	 * @param features the target feature set ({@code Features.JVM} /
	 * {@code Features.WASM})
	 * @param wasm whether the target is a {@code .wasm} output
	 * @param noWasi {@code --no-wasi}; the WASM guard compiles both ways
	 * @return the expanded, spliced and pruned top-level forms
	 */
	public static List<LispVal> corpus(String source, Features features, boolean wasm, boolean noWasi) {
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

	/**
	 * Reads and expands a WASM program whose relative paths (a
	 * {@code rontolisp:wit-import}'s {@code .wit}) resolve against a directory on disk,
	 * for whichever of the two core-module backends {@code noGc} names.
	 * @param source the program text
	 * @param baseDir the directory relative paths resolve against
	 * @param noGc {@code --no-gc}
	 * @return the expanded, spliced and pruned top-level forms
	 */
	public static List<LispVal> wasmReactor(String source, String baseDir, boolean noGc) {
		return CompileFrontend
			.run(source, null, baseDir, List.of(), DistClient.createDefault(List.of()), List.of(), true, false, false,
					false, true, noGc, false, null, false, false)
			.program();
	}

	/**
	 * Reads and expands a {@code --no-gc --component} program whose relative paths (a
	 * {@code rontolisp:wit-import}'s {@code .wit}) resolve against a directory on disk:
	 * the {@code WASM_NO_GC_COMPONENT} lowering, with WASI kept (a printing program gets
	 * the print micro-adapter, not the {@code --no-wasi} sink).
	 * @param source the program text
	 * @param baseDir the directory relative paths resolve against
	 * @return the expanded, spliced and pruned top-level forms
	 */
	public static List<LispVal> noGcComponent(String source, String baseDir) {
		return CompileFrontend
			.run(source, null, baseDir, List.of(), DistClient.createDefault(List.of()), List.of(), true, false, false,
					true, false, true, false, null, false, false)
			.program();
	}

	/**
	 * Compiles a source text through the WHOLE front end the CLI runs -- the read (with
	 * the target's own feature set), the {@code (load ...)} and ASDF inlining over the
	 * real filesystem, and the pass pipeline -- for a test that hands a library E2E's
	 * exercise to a backend.
	 * @param source the program text
	 * @param systemPath the ASDF system search path ({@code --system-path}): the
	 * directories holding the {@code .asd} files
	 * @param wasm whether the target is a {@code .wasm} output
	 * @param component {@code --component}
	 * @return the expanded program and the feature set it was read with
	 */
	public static Program withSystemPath(String source, List<String> systemPath, boolean wasm, boolean component) {
		// DistClient.createDefault mirrors the CLI's default (Quicklisp, cached under
		// ~/.rontolisp) and touches neither network nor disk unless the program actually
		// ql:quickloads something -- a vendored system resolves against systemPath.
		CompileFrontend.Result result = CompileFrontend.run(source, null, null, systemPath,
				DistClient.createDefault(List.of()), List.of(), wasm, false, false, component, false, false, false,
				null, false, false);
		return new Program(result.program(), result.features());
	}

}
