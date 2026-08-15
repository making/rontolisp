package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural checks of {@code --reentrant} ({@code .kb/wasm-import.md}): the opt-in that
 * relaxes the re-entry guard by making the module own its per-call state. What is pinned:
 *
 * <ul>
 * <li>the guard is RETIRED on a reentrant module and kept on every other -- the todo-337
 * refusal stays the default, and a module that keeps it is byte-identical to one built
 * before the flag existed (all of the machinery below is reentrant-gated);</li>
 * <li>the park-block allocator ({@code __ronto_park_alloc}/{@code __ronto_park_free})
 * rides exactly the reentrant modules with a memory-typed boundary;</li>
 * <li>the refusals: a program nothing can suspend, {@code --component},
 * {@code --dynamic}, and the ID-LESS streaming body boundary (a host-side cursor with no
 * per-call identity; the id-carrying shape the CLI synthesizes under the flag
 * composes).</li>
 * </ul>
 */
class WasmReentrantCompilerTest {

	private static final String SUSPENDING_MODULE = """
			(rontolisp:wasm-import 'slow :from "env" :params '(:int) :returns :int :async t)
			(defun poke (n) (rontolisp::%future-force (slow n)))
			(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
			""";

	@Test
	void aReentrantModuleCarriesNoReentryGuard() {
		byte[] guarded = compile(SUSPENDING_MODULE, false);
		byte[] reentrant = compile(SUSPENDING_MODULE, true);
		// The guard's trap-and-set (if; unreachable; end; i32.const 1; global.set) is
		// in the guarded build and nowhere in the reentrant one -- overlap is the
		// point of the flag.
		assertThat(countOf(guarded, GUARD_TRAP_AND_SET)).isEqualTo(1);
		assertThat(countOf(reentrant, GUARD_TRAP_AND_SET)).isZero();
		assertThat(reentrant).isNotEqualTo(guarded);
	}

	@Test
	void parkHelpersRideOnlyAReentrantModuleWithAMemoryBoundary() {
		String stringModule = """
				(rontolisp:wasm-import 'ask :from "env" :params '() :returns :string :async t)
				(defun poke () (rontolisp::%future-force (ask)))
				(rontolisp:wasm-export 'poke :params '() :returns :string)
				""";
		assertThat(containsAscii(compile(stringModule, true), "__ronto_park_alloc")).isTrue();
		assertThat(containsAscii(compile(stringModule, true), "__ronto_park_free")).isTrue();
		assertThat(containsAscii(compile(stringModule, false), "__ronto_park_alloc")).isFalse();
		// A reentrant module whose whole boundary is scalar has no cross-call staging
		// and gains no park allocator either.
		assertThat(containsAscii(compile(SUSPENDING_MODULE, true), "__ronto_park_alloc")).isFalse();
	}

	@Test
	void reentrantRequiresAProgramThatCanSuspend() {
		String scalarOnly = """
				(defun poke (n) (+ n 1))
				(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
				""";
		assertThatThrownBy(() -> compile(scalarOnly, true)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--reentrant requires a program that can suspend");
	}

	@Test
	void reentrantRefusesTheIdLessStreamingBodyBoundary() {
		// The ID-LESS body imports are a host-side cursor -- "the current request's
		// body" -- with no per-call identity, exactly what overlapped calls lack. What
		// reaches this shape today is a hand-written reactor's own imports; the
		// synthesized ones carry the id below.
		String streaming = """
				(rontolisp:wasm-import 'read-body :from "env" :as "readRequestBody" :params '() :returns :bytes :async t)
				(defvar *buf* (make-array 16 :element-type '(unsigned-byte 8)))
				(defun poke () (rontolisp::%future-force (read-body *buf*)))
				(rontolisp:wasm-export 'poke :params '() :returns :int)
				""";
		assertThatThrownBy(() -> compile(streaming, true)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("call identity")
			.hasMessageContaining("readRequestBody")
			.hasMessageContaining("leading :int")
			.hasMessageContaining("--host-boundary=envelope");
	}

	@Test
	void reentrantComposesWithTheIdCarryingStreamingBodyBoundary() {
		// The composed build the refusal above used to defer: with a
		// leading :int call id the import stops being a cursor -- every pull names the
		// call it belongs to -- so the flag and the streaming boundary compose. This is
		// the shape HttpReactorInliner / HostFetchLibrary synthesize under --reentrant.
		String streaming = """
				(rontolisp:wasm-import 'read-body :from "env" :as "readRequestBody" :params '(:int) :returns :bytes :async t)
				(defvar *buf* (make-array 16 :element-type '(unsigned-byte 8)))
				(defun poke (id) (rontolisp::%future-force (read-body id *buf*)))
				(rontolisp:wasm-export 'poke :params '(:int) :returns :int)
				""";
		assertThat(containsAscii(compile(streaming, true), "readRequestBody")).isTrue();
	}

	@Test
	void reentrantRefusesComponentAndDynamic() {
		assertThatThrownBy(
				() -> new WasmLispCompiler(false, true, true, OptimizeLevel.NONE, false, false, false, false, true))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--component");
		assertThatThrownBy(
				() -> new WasmLispCompiler(true, false, true, OptimizeLevel.NONE, false, false, false, false, true))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--dynamic");
	}

	// if (blocktype empty); unreachable; end; i32.const 1; global.set -- the re-entry
	// guard's trap-and-set, minus the leading global.get whose index varies by module.
	private static final byte[] GUARD_TRAP_AND_SET = { 0x04, 0x40, 0x00, 0x0b, 0x41, 0x01, 0x24 };

	private static byte[] compile(String source, boolean reentrant) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, true, OptimizeLevel.NONE, false, false, false, false, reentrant)
			.compile(program);
	}

	private static int countOf(byte[] module, byte[] needle) {
		int count = 0;
		outer: for (int i = 0; i <= module.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (module[i + j] != needle[j]) {
					continue outer;
				}
			}
			count++;
		}
		return count;
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

}
