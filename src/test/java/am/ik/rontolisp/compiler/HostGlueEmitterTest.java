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
 * reactor. TWO checked-in files, one per boundary
 * ({@link am.ik.rontolisp.compiler.HostBoundary}), and this asserts each is exactly what
 * its build writes, so neither shipped Worker can drift from the compiler:
 * {@code dog-fetcher/src/worker.js} on the streaming boundary, and
 * {@code btc-ticker/src/worker.js} on the envelope one -- which additionally carries the
 * two halves the transport fixes, the {@code env.fetch} host half and the whole
 * {@code worker()}.
 *
 * <p>
 * The glue is derived from the DECLARATIONS alone, which is why the programs compiled
 * here are four lines rather than the examples' own: the same
 * {@code --no-wasi --host-fetch} reactor shape declares the same imports and the same
 * {@code handle-request} export, so it emits the same file byte for byte. (Verified: each
 * example's own build writes its file unchanged.)
 *
 * <p>
 * Regenerate after changing the emitter with
 * {@code ./mvnw -Drontolisp.glue.fix=true -Dtest=HostGlueEmitterTest#fixWorkerGlue test}.
 */
class HostGlueEmitterTest {

	private static final Path WORKER_GLUE = Path.of("examples/cloudflare-workers/dog-fetcher/src/worker.js");

	private static final Path TICKER_GLUE = Path.of("examples/cloudflare-workers/btc-ticker/src/worker.js");

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

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInTickerGlueIsWhatAnEnvelopeReactorBuildWrites() throws IOException {
		assertThat(Files.readString(TICKER_GLUE, StandardCharsets.UTF_8))
			.as("%s is stale -- regenerate it with: %s (or examples/cloudflare-workers/btc-ticker/build.sh)",
					TICKER_GLUE, FIX)
			.isEqualTo(envelopeReactorGlue());
	}

	/**
	 * Maintenance helper: rewrites both checked-in Worker glue files. Enabled only with
	 * {@code -Drontolisp.glue.fix=true}.
	 * @throws IOException if a file cannot be written
	 */
	@Test
	@EnabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void fixWorkerGlue() throws IOException {
		Files.writeString(WORKER_GLUE, fetchingReactorGlue(), StandardCharsets.UTF_8);
		Files.writeString(TICKER_GLUE, envelopeReactorGlue(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + WORKER_GLUE + " and " + TICKER_GLUE);
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
	void theEnvelopeBoundaryWritesTheHostHalvesTheTransportFixes() {
		String glue = envelopeReactorGlue();
		// Nothing of the split survives: no body imports, and so no read cursor, no
		// `drop` to discard one, and nothing for a host to implement but env.fetch --
		// which this file implements itself.
		assertThat(glue).doesNotContain(HostFetchLibrary.BODY_IMPORT_FIELD)
			.doesNotContain("readRequestBody")
			.doesNotContain("writeResponseBody")
			.doesNotContain("const readers = new Map()")
			.doesNotContain("drop,");
		assertThat(glue).contains("export function defaultHost() {")
			.contains("fetch: suspending(async (head) => {")
			.contains("const response = await fetch(request.url, {")
			// The reply's whole body rides the head here -- that IS this boundary.
			.contains("body: await response.text(),")
			.contains("return JSON.stringify({ error: String(error) });");
		// ...and the whole Worker over the envelope, entered through the queue because
		// the derived fetch can suspend.
		assertThat(glue).contains("export function worker(module, options = {}) {")
			.contains("async fetch(request, env, ctx) {")
			.contains("target: url.pathname + url.search,")
			.contains("head[\"remote-addr\"] = remoteAddr;")
			.contains("await live().serially((lisp) => {")
			// The instance is bound at admission but re-checked INSIDE the section: a
			// call parked ahead of this one can poison it in between.
			.contains("if (poisoned) throw new Error(\"instance discarded by an earlier trap\");")
			.contains("return lisp.handleRequest(input);")
			.contains("return new Response(head.body || null, {");
		// The streaming boundary writes NEITHER: with a body out of band the host owns
		// the reader the octets come from, which no declaration states.
		assertThat(fetchingReactorGlue()).doesNotContain("export function defaultHost")
			.doesNotContain("export function worker");
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
				false, false, false, null, false, null);
		assertThatThrownBy(() -> HostGlueEmitter.emit("glue.js", surface))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("doIt")
			.hasMessageContaining(":as");
	}

	// --- the builds ------------------------------------------------------------------

	private static String fetchingReactorGlue() {
		return reactorGlue(HostBoundary.STREAMING);
	}

	private static String envelopeReactorGlue() {
		return reactorGlue(HostBoundary.ENVELOPE);
	}

	// The CLI's --no-wasi --host-fetch reactor pipeline, in its order. The BOUNDARY is
	// the only difference between the two shipped Workers' glue, which is the point of
	// running one pipeline twice rather than writing two.
	private static String reactorGlue(HostBoundary boundary) {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(FETCHING_REACTOR, Features.WASM_REACTOR));
		loaded = HostFetchLibrary.process(loaded, boundary);
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true, boundary);
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
