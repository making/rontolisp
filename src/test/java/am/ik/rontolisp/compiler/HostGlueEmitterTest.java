package am.ik.rontolisp.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.GrayStreamsLibrary;
import am.ik.rontolisp.eval.HostFetchLibrary;
import am.ik.rontolisp.eval.HttpReactorInliner;
import am.ik.rontolisp.eval.HttpReactorLibrary;
import am.ik.rontolisp.eval.HttpServerLibrary;
import am.ik.rontolisp.eval.JsonLibrary;
import am.ik.rontolisp.eval.LispPreludeLibrary;
import am.ik.rontolisp.eval.UserMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@code --emit-js-glue} output against the declarations it is derived from --
 * the {@link GlImportObjectTest} rule applied to the host boundary of a {@code --no-wasi}
 * reactor: {@code examples/cloudflare-workers/dog-fetcher/src/worker.js} is CHECKED IN,
 * and this asserts it is exactly what a fetching reactor's build writes, so the shipped
 * Worker and the compiler cannot drift apart.
 *
 * <p>
 * The glue is derived from the DECLARATIONS alone, which is why the program compiled here
 * is four lines rather than the example's whole tiny-routes application: the same
 * {@code --no-wasi --host-fetch} reactor shape declares the same four imports and the
 * same {@code handle-request} export, so it emits the same file byte for byte. (Verified:
 * the example's own build writes this file unchanged.)
 *
 * <p>
 * Regenerate after changing the emitter with
 * {@code ./mvnw -Drontolisp.glue.fix=true -Dtest=HostGlueEmitterTest#fixWorkerGlue test}.
 */
class HostGlueEmitterTest {

	private static final Path WORKER_GLUE = Path.of("examples/cloudflare-workers/dog-fetcher/src/worker.js");

	private static final String FIX = "./mvnw -Drontolisp.glue.fix=true -Dtest=HostGlueEmitterTest#fixWorkerGlue test";

	// The declaration shape of examples/cloudflare-workers/dog-fetcher: a reactor whose
	// handler fetches. --host-fetch splices env.fetch + env.readResponseBody, the reactor
	// transport synthesizes handle-request + env.readRequestBody + env.writeResponseBody.
	private static final String FETCHING_REACTOR = """
			(rontolisp:async-defun upstream (url)
			  (rontolisp:await (rontolisp:fetch url)))
			(defun app (env)
			  (declare (ignore env))
			  (rontolisp::%future-force (upstream "https://example.test/"))
			  (list 200 (list :content-type "text/plain") (list "ok")))
			(rontolisp:http-handler 'app)
			""";

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInWorkerGlueIsWhatAFetchingReactorBuildWrites() throws IOException {
		assertThat(Files.readString(WORKER_GLUE, StandardCharsets.UTF_8))
			.as("%s is stale -- regenerate it with: %s (or examples/cloudflare-workers/dog-fetcher/build.sh)",
					WORKER_GLUE, FIX)
			.isEqualTo(fetchingReactorGlue());
	}

	/**
	 * Maintenance helper: rewrites the checked-in Worker glue. Enabled only with
	 * {@code -Drontolisp.glue.fix=true}.
	 * @throws IOException if the file cannot be written
	 */
	@Test
	@EnabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void fixWorkerGlue() throws IOException {
		Files.writeString(WORKER_GLUE, fetchingReactorGlue(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + WORKER_GLUE);
	}

	@Test
	void theGeneratedGlueCarriesEveryDeclaredHalfOfTheBoundary() {
		String glue = fetchingReactorGlue();
		// One import-object property per import, under the host's own spelling...
		assertThat(glue)
			.contains(
					"fetch: bind(\"" + HostFetchLibrary.IMPORT_MODULE + "\", \"" + HostFetchLibrary.IMPORT_FIELD + "\"")
			.contains("\"" + HostFetchLibrary.BODY_IMPORT_FIELD + "\"")
			.contains("readRequestBody")
			.contains("writeResponseBody");
		// ...and one entry point per export, camelCased like a WIT-lowered field.
		assertThat(glue).contains("handleRequest: make$handleRequest(serialised),")
			.contains("exports[\"handle-request\"]");
		// The three obligations the build prints, WRITTEN rather than described.
		assertThat(glue).contains("new WebAssembly.Suspending(")
			.contains("WebAssembly.promising(exports[\"handle-request\"])")
			.contains("queue.then(work, work)");
		// The allocator bracket, and the copy that has to happen before it pops.
		assertThat(glue).contains("__ronto_alloc_mark()").contains("__ronto_alloc_reset(mark)");
		// The two hooks a --no-wasi module cannot answer for itself, before its top
		// level.
		assertThat(glue.indexOf("__ronto_seed_random")).isLessThan(glue.indexOf("exports._initialize()"));
		assertThat(glue.indexOf("__ronto_set_time")).isLessThan(glue.indexOf("exports._initialize()"));
	}

	@Test
	void anExportThatCannotReachASuspendingImportIsNotEnteredThroughPromising() {
		String glue = glueOf("""
				(rontolisp:wasm-import 'pull :from "net" :as "pull" :params '(:string) :returns :string :async t)
				(rontolisp:async-defun grab (u) (rontolisp:await (pull u)))
				(rontolisp:wasm-export 'grab :params '(:string) :returns :string)
				(defun pure (n) (* n 2))
				(rontolisp:wasm-export 'pure :params '(:int) :returns :int)
				""");
		assertThat(glue).contains("WebAssembly.promising(exports[\"grab\"])");
		assertThat(glue).as("a pure export never reaches the import, so it is entered directly")
			.contains("const entry$pure = exports[\"pure\"];");
	}

	@Test
	void aModuleThatImportsNothingGetsGlueThatAsksForNothing() {
		String glue = glueOf("""
				(defun twice (n) (* n 2))
				(rontolisp:wasm-export 'twice :params '(:int) :returns :int)
				""");
		assertThat(glue).contains("const imports = {};")
			.contains("export function instantiate(module) {")
			.as("nothing to mark, nothing to enter through promising, and no queue to join")
			.doesNotContain("export function suspending")
			.doesNotContain("WebAssembly.promising")
			.doesNotContain("serially");
	}

	@Test
	void theHostRandomEntropyImportIsImplementedRatherThanAskedFor() {
		String glue = glueOf("""
				(defun draw () (random 10))
				(rontolisp:wasm-export 'draw :params '() :returns :int)
				""", true);
		assertThat(glue).contains("random_get: (ptr, len) => {")
			.contains("crypto.getRandomValues(new Uint8Array(exports.memory.buffer, ptr, len))");
	}

	@Test
	void anExportNamedLikeAGeneratedHelperDoesNotShadowIt() {
		// An export name is the program's, and nothing stops it being `call` or `bind` --
		// the names the glue's own helpers carry. It must not become a `const` beside
		// them (`SyntaxError: Identifier 'call' has already been declared`, which the
		// build would not notice), so an entry point is a PROPERTY of the returned object
		// and the two locals an export does declare are namespaced with `$`.
		String glue = glueOf("""
				(rontolisp:wasm-import 'ping :from "env" :as "ping" :params '() :returns :int)
				(defun call (x) (+ x (ping)))
				(rontolisp:wasm-export 'call :params '(:int) :returns :int)
				(defun bind (x) (* x 2))
				(rontolisp:wasm-export 'bind :params '(:int) :returns :int)
				""");
		assertThat(glue).contains("const entry$call = exports[\"call\"];")
			.contains("const entry$bind = exports[\"bind\"];")
			.contains("    call: make$call(")
			.contains("    bind: make$bind(");
		// ...and the helpers they are named after are still the ones declared.
		assertThat(glue).contains("const call = (run, entry, stage, decode) =>")
			.contains("const bind = (moduleName, field, wrap) =>");
	}

	@Test
	void twoExportsClaimingOneJavaScriptNameAreRefused() {
		HostGlueEmitter.Surface surface = new HostGlueEmitter.Surface(List.of(), null,
				List.of(new HostGlueEmitter.Export("do-it", List.of(), BoundaryType.VOID, false),
						new HostGlueEmitter.Export("doIt", List.of(), BoundaryType.VOID, false)),
				false, false, false, null);
		assertThatThrownBy(() -> HostGlueEmitter.emit("glue.js", surface))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("doIt")
			.hasMessageContaining(":as");
	}

	// --- the builds ------------------------------------------------------------------

	private static String fetchingReactorGlue() {
		// The CLI's --no-wasi --host-fetch reactor pipeline, in its order.
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(FETCHING_REACTOR, Features.WASM_REACTOR));
		loaded = HostFetchLibrary.process(loaded);
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		List<LispVal> program = GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				true);
		compiler.compile(program);
		return glue(compiler, "worker.js");
	}

	private static String glueOf(String source) {
		return glueOf(source, false);
	}

	private static String glueOf(String source, boolean hostRandom) {
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false,
				hostRandom, false);
		compiler.compile(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		return glue(compiler, "glue.js");
	}

	private static String glue(WasmLispCompiler compiler, String fileName) {
		String glue = compiler.hostGlueJs(fileName);
		assertThat(glue).as("a --no-wasi core module always has host glue to write").isNotNull();
		return glue;
	}

}
