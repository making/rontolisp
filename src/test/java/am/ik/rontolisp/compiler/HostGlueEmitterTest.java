package am.ik.rontolisp.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
 * reactor. FIVE shapes, and this asserts every checked-in Worker glue is exactly what its
 * build writes, so no shipped Worker can drift from the compiler: a reactor that FETCHES
 * on each boundary ({@link am.ik.rontolisp.compiler.HostBoundary}) --
 * {@code dog-fetcher/src/worker.js} streaming and {@code btc-ticker/src/worker.js}
 * envelope, the latter carrying the {@code env.fetch} host half as well -- the same
 * streaming fetcher compiled {@code --reentrant} ({@code dog-relay/src/worker.js}: no
 * queue, per-call body state keyed by the call id), and a reactor that does not fetch, on
 * each boundary: the four {@code hello-*} directories, which import nothing at all, and
 * the four {@code httpbin-*} ones that go through {@code clackup}.
 *
 * <p>
 * The glue is derived from the DECLARATIONS alone, which is why the programs compiled
 * here are a handful of lines rather than the examples' own, and why one file per SHAPE
 * covers nine directories: the same reactor shape declares the same imports and the same
 * {@code handle-request} export, so it emits the same file byte for byte. That is
 * asserted rather than assumed -- every directory in a family is pinned against the one
 * derived string. (Verified: each example's own build writes its file unchanged.)
 *
 * <p>
 * {@code examples/cloudflare-workers/httpbin} is deliberately absent: it writes its
 * {@code rontolisp:wasm-export} by hand, the compile path recognises the SYNTHESIZED
 * bridge, and so no {@code worker()} is emitted for it and its host stays hand-written
 * ({@code .kb/wasm-import.md}).
 *
 * <p>
 * Regenerate after changing the emitter with
 * {@code ./mvnw -Drontolisp.glue.fix=true -Dtest=HostGlueEmitterTest#fixWorkerGlue test}.
 */
class HostGlueEmitterTest {

	private static final List<Path> WORKER_GLUE = glueIn("dog-fetcher");

	private static final List<Path> TICKER_GLUE = glueIn("btc-ticker");

	private static final List<Path> RELAY_GLUE = glueIn("dog-relay");

	// One file between four directories, and one between four: the glue is derived from
	// the declarations, and these carry the same ones. The claim their READMEs make is
	// checked here rather than asserted there.
	private static final List<Path> HELLO_GLUE = glueIn("hello-clack", "hello-clack-one-source", "hello-ningle",
			"hello-tiny-routes");

	private static final List<Path> HTTPBIN_GLUE = glueIn("httpbin-clack", "httpbin-clack-one-source", "httpbin-ningle",
			"httpbin-tiny-routes");

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

	// And of the seven Workers that only answer: the same synthesized bridge with no
	// env.fetch beside it, which on the envelope boundary leaves a module importing
	// NOTHING and on the streaming one the reactor's own two body imports.
	private static final String ANSWERING_REACTOR = """
			(defun app (env)
			  (declare (ignore env))
			  (list 200 (list :content-type "text/plain") (list "ok")))
			(rontolisp:http-handler 'app)
			""";

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInWorkerGlueIsWhatAFetchingReactorBuildWrites() throws IOException {
		assertPinned(WORKER_GLUE, fetchingReactorGlue());
	}

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInTickerGlueIsWhatAnEnvelopeReactorBuildWrites() throws IOException {
		assertPinned(TICKER_GLUE, envelopeReactorGlue());
	}

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInRelayGlueIsWhatAReentrantStreamingReactorBuildWrites() throws IOException {
		assertPinned(RELAY_GLUE, reentrantReactorGlue());
	}

	@Test
	void theReentrantGlueKeysItsBodyStateByCallId() {
		String glue = reentrantReactorGlue();
		// No queue -- overlap is the point -- and the per-call body state is a map
		// keyed by the id worker() mints per request and the envelope carries; the
		// reply readers are keyed by the id defaultHost() mints per fetch.
		assertThat(glue).doesNotContain("queue.then(work, work)")
			.doesNotContain("serially")
			.contains("handleRequest: make$handleRequest((work) => work()),")
			.contains("\"" + ReactorEnvelope.CALL_ID_KEY + "\": callId,")
			.contains("requestBodies.set(callId, octets);")
			.contains("responseChunks.get(id)?.push(chunk)")
			.contains("\"" + FetchResponseShape.HOST_BODY_ID_KEY + "\": id,")
			.contains("upstreams.get(id)")
			// ...and no single-slot cursor, no drop-on-entry, no lisp thunk.
			.doesNotContain("readers.forEach((drop) => drop());")
			.doesNotContain("defaultHost(() => instance)")
			.contains("const base = defaultHost();");
	}

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInHelloGlueIsWhatAnAnsweringEnvelopeReactorBuildWrites() throws IOException {
		assertPinned(HELLO_GLUE, answeringEnvelopeGlue());
	}

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInHttpbinGlueIsWhatAnAnsweringStreamingReactorBuildWrites() throws IOException {
		assertPinned(HTTPBIN_GLUE, answeringStreamingGlue());
	}

	private static void assertPinned(List<Path> paths, String expected) throws IOException {
		for (Path path : paths) {
			assertThat(Files.readString(path, StandardCharsets.UTF_8))
				.as("%s is stale -- regenerate it with: %s (or that directory's build.sh)", path, FIX)
				.isEqualTo(expected);
		}
	}

	/**
	 * Maintenance helper: rewrites every checked-in Worker glue file. Enabled only with
	 * {@code -Drontolisp.glue.fix=true}.
	 * @throws IOException if a file cannot be written
	 */
	@Test
	@EnabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void fixWorkerGlue() throws IOException {
		write(WORKER_GLUE, fetchingReactorGlue());
		write(TICKER_GLUE, envelopeReactorGlue());
		write(RELAY_GLUE, reentrantReactorGlue());
		write(HELLO_GLUE, answeringEnvelopeGlue());
		write(HTTPBIN_GLUE, answeringStreamingGlue());
	}

	private static void write(List<Path> paths, String glue) throws IOException {
		for (Path path : paths) {
			Files.writeString(path, glue, StandardCharsets.UTF_8);
			System.out.println("Wrote " + path);
		}
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
			.contains("const body = head.body;")
			.contains("return new Response(body?.length ? body : null, {");
	}

	@Test
	void theStreamingBoundaryWritesThemToo() {
		// The host half is derivable on BOTH boundaries: where a body is out of band,
		// the reader it comes from is the platform Request/Response the generated
		// worker() is already holding, so it writes those entries as well and the
		// deployment's own file is three lines either way. What differs is only the
		// DATA -- which is the whole point of the pair.
		String glue = fetchingReactorGlue();
		assertThat(glue).contains("export function defaultHost(lisp) {")
			.contains("export function worker(module, options = {}) {")
			// the reply body's reader, and the cursor a second fetch supersedes
			.contains("upstream = response.body ? response.body.getReader() : null;")
			.contains("lisp?.()?.drop(\"env." + HostFetchLibrary.BODY_IMPORT_FIELD + "\");")
			// the reactor's two bodies, per-call state owned by worker()
			.contains("readRequestBody: () => {")
			.contains("writeResponseBody: (chunk) => responseChunks.push(chunk),")
			.contains("requestBody = octets;")
			.contains("const body = head.body ?? collected();");
		// Out of band the head carries no body key in either direction: the request's
		// would be a second copy the transport ignores, and the reply's absence is what
		// puts the module's stream over the import.
		assertThat(glue).doesNotContain("head.body = decoder.decode(octets)")
			.doesNotContain("body: await response.text(),");
		// In band, none of that exists and the head carries both.
		String envelope = envelopeReactorGlue();
		assertThat(envelope).contains("export function defaultHost() {")
			.contains("body: await response.text(),")
			.contains("head.body = decoder.decode(octets);")
			.doesNotContain("upstream")
			.doesNotContain("responseChunks");
	}

	@Test
	void aReactorThatOnlyANSWERSIsAWholeWorkerWithNoHostAtAll() {
		// The hello-* trio. On the envelope boundary a reactor that never fetches
		// imports NOTHING -- so there is no host half to ask for or to implement -- and
		// worker(module) is still the whole Worker, because mapping a Request onto the
		// envelope is transport work either way. The hand-written host this replaced
		// still declared env.readRequestBody / env.writeResponseBody, which this module
		// does not link.
		String glue = answeringEnvelopeGlue();
		assertThat(glue).contains("const imports = {};")
			.contains("export function instantiate(module) {")
			.contains("export function worker(module, options = {}) {")
			.contains("return (instance ??= instantiate(module));")
			.doesNotContain("export function defaultHost")
			.doesNotContain("readRequestBody")
			.doesNotContain("writeResponseBody")
			.doesNotContain("options.host");
		// Nothing can suspend, so the call is entered directly: no marking protocol, no
		// promising entry, and no queue to pay a promise for.
		assertThat(glue).contains("const head = JSON.parse(live().handleRequest(input));")
			.doesNotContain("serially")
			.doesNotContain("WebAssembly.promising");
		// The body rides the head in both directions, which is what `envelope` means.
		assertThat(glue).contains("if (octets?.length) head.body = decoder.decode(octets);")
			.contains("const body = head.body;");
	}

	@Test
	void aStreamingReactorThatOnlyANSWERSStillWritesItsOwnBodyImports() {
		// The httpbin-* four. Nothing fetches, so no env.fetch half is written -- but
		// the reactor's own two body imports are still worker()'s to fill, from the
		// Request it is holding and the Response it is building.
		String glue = answeringStreamingGlue();
		assertThat(glue).doesNotContain("export function defaultHost")
			.doesNotContain(HostFetchLibrary.BODY_IMPORT_FIELD)
			.doesNotContain("let upstream = null;");
		assertThat(glue).contains("const base = {};")
			.contains("readRequestBody: () => {")
			.contains("writeResponseBody: (chunk) => responseChunks.push(chunk),")
			.contains("requestBody = octets;")
			.contains("const body = head.body ?? collected();");
		// The body imports are declared :async t, so a host MAY suspend in them and the
		// call goes through the queue -- which is the only difference from the trio
		// above that is not about a body.
		assertThat(glue).contains("await live().serially((lisp) => {");
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
				false, false, false, null, false, null, false);
		assertThatThrownBy(() -> HostGlueEmitter.emit("glue.js", surface))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("doIt")
			.hasMessageContaining(":as");
	}

	// --- the builds ------------------------------------------------------------------

	private static String fetchingReactorGlue() {
		return reactorGlue(FETCHING_REACTOR, HostBoundary.STREAMING, true);
	}

	private static String envelopeReactorGlue() {
		return reactorGlue(FETCHING_REACTOR, HostBoundary.ENVELOPE, true);
	}

	private static String reentrantReactorGlue() {
		return reactorGlue(FETCHING_REACTOR, HostBoundary.STREAMING, true, true);
	}

	private static String answeringEnvelopeGlue() {
		return reactorGlue(ANSWERING_REACTOR, HostBoundary.ENVELOPE, false);
	}

	private static String answeringStreamingGlue() {
		return reactorGlue(ANSWERING_REACTOR, HostBoundary.STREAMING, false);
	}

	// The CLI's --no-wasi reactor pipeline, in its order, with --host-fetch's splice
	// under the same condition the CLI puts it under. The PROGRAM and the BOUNDARY are
	// the only differences between the four shipped shapes' glue, which is the point of
	// running one pipeline four times rather than writing four.
	private static String reactorGlue(String source, HostBoundary boundary, boolean hostFetch) {
		return reactorGlue(source, boundary, hostFetch, false);
	}

	private static String reactorGlue(String source, HostBoundary boundary, boolean hostFetch, boolean reentrant) {
		List<LispVal> loaded = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		if (hostFetch) {
			loaded = HostFetchLibrary.process(loaded, boundary, reentrant);
		}
		loaded = HttpReactorInliner.process(loaded, WitExportDirective.Backend.WASM_GC, true, boundary, reentrant);
		loaded = HttpReactorLibrary.process(loaded);
		loaded = HttpServerLibrary.process(loaded, false);
		List<LispVal> program = GrayStreamsLibrary
			.process(LispPreludeLibrary.process(JsonLibrary.process(UserMacroExpander.expand(loaded))));
		WasmLispCompiler compiler = new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false,
				hostFetch, reentrant);
		compiler.compile(program);
		return glue(compiler, "worker.js");
	}

	// examples/cloudflare-workers/<dir>/src/worker.js, for every directory that carries
	// the SAME one.
	private static List<Path> glueIn(String... directories) {
		return Stream.of(directories)
			.map(directory -> Path.of("examples/cloudflare-workers", directory, "src", "worker.js"))
			.toList();
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
