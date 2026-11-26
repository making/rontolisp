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
 * reactor. SEVEN shapes, and this asserts every checked-in Worker glue is exactly what
 * its build writes, so no shipped Worker can drift from the compiler: a reactor that
 * FETCHES on each boundary ({@link am.ik.rontolisp.compiler.HostBoundary}) --
 * {@code dog-fetcher/src/worker.js} streaming and {@code btc-ticker/src/worker.js}
 * envelope, the latter carrying the {@code env.fetch} host half as well -- the same
 * streaming fetcher compiled {@code --reentrant} ({@code dog-relay/src/worker.js}: no
 * queue, per-call body state keyed by the call id), and a reactor that does not fetch, on
 * each boundary: the four {@code hello-*} directories, which import nothing at all, and
 * the four {@code httpbin-*} ones that go through {@code clackup}, plus the Workers whose
 * program draws no random number: {@code ring-hello-one-source}, and the two Java ones,
 * {@code hello-servlet-one-source} and {@code hello-mvcfn-one-source}, whose standard
 * output and error are the host's ({@code --host-output}); and the Java Worker whose
 * sessions are in {@code wasi:keyvalue}, {@code hello-session-one-source}: the store's
 * members imported through the canonical ABI, which the glue lifts and lowers and its
 * {@code defaultHost()} answers over Workers KV.
 *
 * <p>
 * The glue is derived from the DECLARATIONS alone, which is why the programs compiled
 * here are a handful of lines rather than the examples' own, and why one file per SHAPE
 * covers twelve directories: the same reactor shape declares the same imports and the
 * same {@code handle-request} export, so it emits the same file byte for byte. That is
 * asserted rather than assumed -- every directory in a family is pinned against the one
 * derived string. (Verified: each example's own build writes its file unchanged.) The one
 * fact beyond the declarations is which host hooks survive {@code --optimize}; every
 * example here but three both draws and reads the clock, so it keeps both, as these
 * unoptimized builds do. The exceptions are the Ring Worker and the two Java ones: the
 * transport reads the clock and nothing in the program draws, so their
 * {@code --optimize=size} builds keep only {@code __ronto_set_time}. The Java two are
 * pinned against the answering reactor's own shaken build; the Ring one against
 * {@code examples/clojure/ring-hello.clj} itself, since what a Clojure program's shake
 * keeps depends on the whole library it splices, which no stand-in of its declarations
 * has.
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

	// The Ring Worker and the Java ones: the hello-* declarations, shaken as their
	// build.sh shakes them, the Java ones with --host-output.
	private static final List<Path> RING_GLUE = glueIn("ring-hello-one-source");

	private static final List<Path> JAVA_GLUE = glueIn("hello-servlet-one-source", "hello-mvcfn-one-source");

	// The Java Worker whose sessions are in wasi:keyvalue: the members of the store its
	// session store calls, imported through the canonical ABI and answered over Workers
	// KV.
	private static final List<Path> KEYVALUE_GLUE = glueIn("hello-session-one-source");

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

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInRingGlueIsWhatAShakenAnsweringEnvelopeReactorBuildWrites() throws IOException {
		assertPinned(RING_GLUE, ringExampleGlue());
		assertPinned(JAVA_GLUE, shakenAnsweringEnvelopeGlue(true));
	}

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.glue.fix", matches = "true")
	void theCheckedInSessionGlueIsWhatAKeyvalueReactorBuildWrites() throws IOException {
		assertPinned(KEYVALUE_GLUE, keyvalueReactorGlue());
	}

	@Test
	void aShakenReactorThatDrawsNothingIsNotSeeded() {
		// The transport reads the clock and nothing draws, so --optimize=size keeps only
		// the clock hook, and the glue calls only what survived; the unshaken build
		// seeds.
		String shaken = shakenAnsweringEnvelopeGlue(false);
		assertThat(shaken).doesNotContain(HostGlueEmitter.SEED_RANDOM_EXPORT)
			.contains("exports." + HostGlueEmitter.SET_TIME_EXPORT + "(");
		String unshaken = answeringEnvelopeGlue();
		assertThat(unshaken).contains("exports." + HostGlueEmitter.SEED_RANDOM_EXPORT + "(");
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
		write(RING_GLUE, ringExampleGlue());
		write(JAVA_GLUE, shakenAnsweringEnvelopeGlue(true));
		write(KEYVALUE_GLUE, keyvalueReactorGlue());
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

	// A reactor whose handler opens a socket: --host-sockets splices the provider's
	// imports, every one of which the file answers over connect() of cloudflare:sockets.
	private static final String CONNECTING_REACTOR = """
			(defun app (env)
			  (declare (ignore env))
			  (let ((peer (rontolisp:tcp-connect "db.test" 5432)))
			    (write-line "ping" peer)
			    (let ((line (read-line peer)))
			      (close peer)
			      (list 200 (list :content-type "text/plain") (list line)))))
			(rontolisp:http-handler 'app)
			""";

	@Test
	void theHostsSocketsAreWrittenOverConnectAndEndWithTheirRequest() {
		WasmLispCompiler compiler = WasmLispCompiler.builder().noWasi(true).optimize(OptimizeLevel.SIZE).build();
		compiler.compile(am.ik.rontolisp.cli.CompileFrontendAccess.wasmReactorWithHostSockets(CONNECTING_REACTOR));
		String glue = glue(compiler, "worker.js");
		// The whole Worker: every import of the provider is the file's own to answer...
		assertThat(glue).contains("//   export default worker(module);")
			.contains("export function hostSockets(connect, owner = () => undefined) {")
			.contains("(await import(\"cloudflare:sockets\")).connect")
			.contains("export function defaultHost(sockets = hostSockets()) {")
			.contains("    tcp: sockets.tcp,\n");
		for (String field : HostSockets.FIELDS.keySet()) {
			assertThat(glue).contains("      " + field + ": bind(\"" + HostSockets.MODULE + "\", \"" + field + "\"");
		}
		// ...by a promise where the module waits, sends or closes, and at once
		// elsewhere...
		for (String field : HostSockets.SUSPENDING) {
			assertThat(glue).contains("    " + field + ": suspending(async (");
		}
		assertThat(glue).contains("    poll: (hosts) => first(hosts),").contains("    read: (h, max) => {");
		// ...every export entered through promising, and the module's clock written
		// again once a suspension has settled, where the time a wait took has passed.
		assertThat(glue).contains("WebAssembly.promising(exports[\"handle-request\"])").contains("""
				      } finally {
				        exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
				      }
				""");
		// worker() makes the sockets with the platform's connect, or the caller's, and
		// ends what a call left open with the call.
		assertThat(glue).contains("  const sockets = hostSockets(options.connect);\n")
			.contains("  const base = defaultHost(sockets);\n")
			.contains("""
					            } finally {
					              sockets.expire("Socket closed: the request that opened it has ended");
					            }
					""");
		// An import that suspends while the module loads is named before the trap.
		assertThat(glue).contains("      if (loading) console.error(whileLoading(key));\n")
			.contains("  exports._initialize();\n  loading = false;\n");
		// A program's own import of that module and another shape is its own to answer.
		String own = glueOf("""
				(rontolisp:wasm-import 'wait :from "tcp" :as "wait" :params '(:s32) :returns :s32)
				(defun ask (n) (wait n))
				(rontolisp:wasm-export 'ask :params '(:s32) :returns :s32)
				""");
		assertThat(own).doesNotContain("hostSockets")
			.doesNotContain("WebAssembly.promising(")
			.contains("wait: bind(\"tcp\", \"wait\"");
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
	void anOctetsImportHandsTheHostAUint8ArrayAndTakesOneBack() {
		String source = """
				(rontolisp:wasm-import 'swap :from "env" :as "swap" :params '(:octets) :returns :octets :async t)
				(rontolisp:async-defun relay (v) (length (rontolisp:await (swap v))))
				(rontolisp:wasm-export 'relay :params '(:int) :returns :int)
				""";
		String glue = glueOf(source);
		// the argument copied out as octets, never decoded; the answer staged as its
		// octets (text as its UTF-8 encoding) where a :string answer would be encoded
		assertThat(glue).contains("// (:octets) -> :octets")
			.contains("return (p0, p0Len) =>\n          settle(what, call(readBytes(p0, p0Len)), writeOctets);")
			.contains("const writeOctets = (value) => write(octets(value));")
			.contains("typeof chunk === \"string\" ? encoder.encode(chunk) : chunk;")
			.doesNotContain("readString")
			.doesNotContain("writeString");
		// --reentrant: the answer is a park block the module frees
		WasmLispCompiler reentrant = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.reentrant(true)
			.build();
		reentrant.compile(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		assertThat(glue(reentrant, "glue.js")).contains("settle(what, call(readBytes(p0, p0Len)), writeParkOctets);")
			.contains("const ptr = exports.__ronto_park_alloc(bytes.length);")
			.doesNotContain("writeOctets");
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
	void theHostEntropyAndOutputImportsAreImplementedRatherThanAskedFor() {
		String glue = glueOf("""
				(defun draw () (random 10))
				(rontolisp:wasm-export 'draw :params '() :returns :int)
				""", true);
		assertThat(glue).contains("random_get: (ptr, len) => {")
			.contains("crypto.getRandomValues(new Uint8Array(exports.memory.buffer, ptr, len))")
			.contains("fd_write: (fd, iovs, count, written) => {")
			.contains("lines((line) => console.log(\"%s\", line))")
			.contains("lines((line) => console.error(\"%s\", line))")
			.contains("export function instantiate(module) {");
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
		HostGlueEmitter.Surface surface = new HostGlueEmitter.Surface(List.of(), null, null,
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

	// The Ring Worker's own program, compiled as its build.sh does (--no-wasi
	// --optimize=size): the one Worker pinned against the example itself rather than a
	// stand-in of its declarations, because what its shake keeps depends on the whole
	// Clojure library the program splices.
	private static String ringExampleGlue() {
		Path example = Path.of("examples/clojure/ring-hello.clj").toAbsolutePath();
		try {
			List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess.wasmReactorFile(Files.readString(example),
					example.toString());
			WasmLispCompiler compiler = WasmLispCompiler.builder().noWasi(true).optimize(OptimizeLevel.SIZE).build();
			compiler.compile(program);
			return glue(compiler, "worker.js");
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private static String shakenAnsweringEnvelopeGlue(boolean hostOutput) {
		return reactorGlue(ANSWERING_REACTOR, HostBoundary.ENVELOPE, false, false, OptimizeLevel.SIZE, hostOutput);
	}

	// The declarations of examples/cloudflare-workers/hello-session-one-source: a reactor
	// calling the members of wasi:keyvalue's store its Java session store calls, through
	// the WIT servlet-httpserver-keyvalue carries, with that Worker's opt-ins and shake.
	private static final String KEYVALUE_REACTOR = """
			(rontolisp:wit-import "%s" :interface "wasi:keyvalue/store@0.2.0-draft2" :package kv :octets t)
			(defun app (env)
			  (declare (ignore env))
			  (kv:with-bucket (b (kv:open ""))
			    (kv:bucket-set b "k" (or (kv:bucket-get b "k") (make-array 0 :element-type '(unsigned-byte 8))))
			    (kv:bucket-delete b "gone")
			    (kv:bucket-list-keys b nil))
			  (list 200 (list :content-type "text/plain") (list "ok")))
			(rontolisp:http-handler 'app)
			""";

	private static String keyvalueReactorGlue() {
		String wit = Path.of("servlet-httpserver-keyvalue/src/main/resources/am/ik/servlet/httpserver/keyvalue/wit")
			.toAbsolutePath()
			.toString();
		List<LispVal> program = am.ik.rontolisp.cli.CompileFrontendAccess
			.wasmReactor(KEYVALUE_REACTOR.formatted(wit + "/keyvalue.wit"), wit, false);
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.SIZE)
			.hostRandom(true)
			.hostOutput(true)
			.build();
		compiler.compile(program);
		return glue(compiler, "worker.js");
	}

	// The CLI's --no-wasi reactor pipeline, in its order, with --host-fetch's splice
	// under the same condition the CLI puts it under. The PROGRAM and the BOUNDARY are
	// the only differences between the four shipped shapes' glue, which is the point of
	// running one pipeline four times rather than writing four.
	private static String reactorGlue(String source, HostBoundary boundary, boolean hostFetch) {
		return reactorGlue(source, boundary, hostFetch, false);
	}

	private static String reactorGlue(String source, HostBoundary boundary, boolean hostFetch, boolean reentrant) {
		return reactorGlue(source, boundary, hostFetch, reentrant, OptimizeLevel.NONE, false);
	}

	private static String reactorGlue(String source, HostBoundary boundary, boolean hostFetch, boolean reentrant,
			OptimizeLevel optimize, boolean hostOutput) {
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
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(optimize)
			.hostFetch(hostFetch)
			.reentrant(reentrant)
			.hostOutput(hostOutput)
			.build();
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

	private static String glueOf(String source, boolean hostOptIns) {
		WasmLispCompiler compiler = WasmLispCompiler.builder()
			.noWasi(true)
			.optimize(OptimizeLevel.NONE)
			.hostRandom(hostOptIns)
			.hostOutput(hostOptIns)
			.build();
		compiler.compile(LispReader.readAllFromString(source, Features.WASM_REACTOR));
		return glue(compiler, "glue.js");
	}

	private static String glue(WasmLispCompiler compiler, String fileName) {
		String glue = compiler.hostGlueJs(fileName);
		assertThat(glue).as("a --no-wasi core module always has host glue to write").isNotNull();
		return glue;
	}

}
