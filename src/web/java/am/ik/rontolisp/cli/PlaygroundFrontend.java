package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.Features;

/**
 * The browser playground's way into the compile path's one pass pipeline
 * ({@link CompileFrontend#expand}): a program compiled in the page is expanded as the
 * command line expands it, reading the files it names from what the page uploaded. In
 * this package because the pipeline is package-private, and under {@code src/web/java}
 * because only the {@code web} Maven profile compiles it; nothing else of the command
 * line is reached from the browser build.
 */
public final class PlaygroundFrontend {

	private PlaygroundFrontend() {
	}

	/**
	 * Expands read forms for a backend.
	 * @param forms the top-level forms the page's language read
	 * @param features the feature set they were read with
	 * @param wasm whether the target is a {@code .wasm} output (a Preview 1 module)
	 * @param loader the page's uploaded files
	 * @return the expanded, spliced and pruned program
	 */
	public static List<LispVal> expand(List<LispVal> forms, Features features, boolean wasm, SourceLoader loader) {
		return CompileFrontend
			.expand(new CompileFrontend.Loaded(forms, features),
					CompileFrontend.Options.builder().wasm(wasm).loader(loader).build())
			.program();
	}

}
