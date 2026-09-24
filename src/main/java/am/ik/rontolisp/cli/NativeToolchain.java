package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The two native halves of {@code --native} for the host: the precompile shim
 * ({@code librlprecomp}, wasmtime + Cranelift behind a C ABI, called here through FFM)
 * and the runner stub every output starts with. Both are classpath resources under
 * {@code am/ik/rontolisp/native/<os>-<arch>/}, laid out by
 * {@code rontolisp-native/build.sh} (.kb/native-output.md).
 *
 * <p>
 * {@code dlopen} needs a real file, so the shim is extracted once per content to
 * {@code <cache>/native/<sha256 prefix>/} -- written to a temporary file and moved into
 * place, so concurrent compiles never load a half-written library. The cache is
 * {@code -Drontolisp.native.cache=DIR}, else {@code $XDG_CACHE_HOME/rontolisp}, else
 * {@code ~/Library/Caches/rontolisp} on macOS and {@code ~/.cache/rontolisp} elsewhere.
 *
 * <p>
 * Lives in {@code cli}, which the browser playground (-Pweb, no filesystem, no FFM) never
 * reaches.
 */
final class NativeToolchain {

	/** Where build.sh puts each platform's pair on the classpath. */
	static final String RESOURCE_ROOT = "am/ik/rontolisp/native/";

	/** The system property that overrides the extraction cache. */
	static final String CACHE_PROPERTY = "rontolisp.native.cache";

	/** {@code int32_t rl_precompile(const uint8_t*, size_t, uint8_t**, size_t*)}. */
	static final FunctionDescriptor PRECOMPILE = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
			ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

	/** {@code void rl_free(uint8_t*, size_t)}. */
	static final FunctionDescriptor FREE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

	/** {@code const char *rl_version(void)}. */
	static final FunctionDescriptor VERSION = FunctionDescriptor.of(ValueLayout.ADDRESS);

	/** Every shape this class asks the linker for, for the native-image metadata test. */
	static final List<FunctionDescriptor> DOWNCALLS = List.of(PRECOMPILE, FREE, VERSION);

	private static @Nullable NativeToolchain loaded;

	private final byte[] stub;

	private final MethodHandle precompile;

	private final MethodHandle free;

	private NativeToolchain(byte[] stub, MethodHandle precompile, MethodHandle free) {
		this.stub = stub;
		this.precompile = precompile;
		this.free = free;
	}

	/**
	 * The host's toolchain, loaded on first use and kept for the process.
	 * @return the toolchain
	 * @throws UnsupportedOperationException when this build carries no shim and stub for
	 * the host
	 * @throws IllegalStateException when the shim and the stub were built for different
	 * engines
	 */
	static synchronized NativeToolchain load() {
		NativeToolchain toolchain = loaded;
		if (toolchain == null) {
			toolchain = open(hostPlatform(), cacheRoot());
			loaded = toolchain;
		}
		return toolchain;
	}

	/**
	 * Whether this build carries the shim and the stub for the host -- without loading
	 * anything.
	 * @return {@code true} when {@link #load} can find both resources
	 */
	static boolean availableOnHost() {
		String platform = hostPlatform();
		ClassLoader loader = NativeToolchain.class.getClassLoader();
		return platform != null && loader.getResource(RESOURCE_ROOT + platform + "/" + shimName(platform)) != null
				&& loader.getResource(RESOURCE_ROOT + platform + "/rlrun") != null;
	}

	/** The runner stub's bytes; an output starts with them. */
	byte[] stub() {
		return this.stub;
	}

	/**
	 * Precompiles a Preview 1 module for the engine the stub runs.
	 * @param wasm the wasm-GC backend's module
	 * @return the precompiled module
	 * @throws IllegalStateException when wasmtime refuses the module
	 */
	byte[] precompile(byte[] wasm) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = arena.allocateFrom(ValueLayout.JAVA_BYTE, wasm);
			MemorySegment outPointer = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
			int rc = (int) this.precompile.invokeExact(in, (long) wasm.length, outPointer, outLength);
			long length = outLength.get(ValueLayout.JAVA_LONG, 0);
			MemorySegment result = outPointer.get(ValueLayout.ADDRESS, 0).reinterpret(length);
			byte[] bytes;
			try {
				bytes = result.toArray(ValueLayout.JAVA_BYTE);
			}
			finally {
				this.free.invokeExact(result, length);
			}
			if (rc != 0) {
				throw new IllegalStateException("--native: wasmtime could not precompile the module: "
						+ new String(bytes, StandardCharsets.UTF_8));
			}
			return bytes;
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new IllegalStateException("--native: the precompile call failed", ex);
		}
	}

	/**
	 * Loads the pair for {@code platform}: reads both resources, extracts the shim under
	 * {@code cacheRoot}, binds it and checks that it precompiles for the engine the stub
	 * runs.
	 */
	static NativeToolchain open(@Nullable String platform, Path cacheRoot) {
		if (platform == null) {
			throw new UnsupportedOperationException("--native is not available for "
					+ System.getProperty("os.name", "?") + "-" + System.getProperty("os.arch", "?"));
		}
		String shimName = shimName(platform);
		byte[] shim = resource(platform + "/" + shimName);
		byte[] stub = resource(platform + "/rlrun");
		if (shim == null || stub == null) {
			throw new UnsupportedOperationException("--native is not available for " + platform
					+ ": this build of rontolisp carries no precompile shim and runner stub for it"
					+ " (rontolisp-native/build.sh builds them)");
		}
		Path library = extract(shim, cacheRoot.resolve("native").resolve(sha256Prefix(shim)).resolve(shimName));
		Linker linker = Linker.nativeLinker();
		SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
		MethodHandle precompile = linker.downcallHandle(find(lookup, "rl_precompile"), PRECOMPILE);
		MethodHandle free = linker.downcallHandle(find(lookup, "rl_free"), FREE);
		MethodHandle version = linker.downcallHandle(find(lookup, "rl_version"), VERSION);
		String fingerprint;
		try {
			fingerprint = ((MemorySegment) version.invokeExact()).reinterpret(Long.MAX_VALUE).getString(0);
		}
		catch (RuntimeException | Error ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new IllegalStateException("--native: rl_version failed", ex);
		}
		// A module precompiled for one engine is refused by any other at start-up, so a
		// mismatched pair would write outputs that never run: refuse before writing one.
		List<String> carried = NativeExecutable.stubFingerprints(stub);
		if (!carried.contains(fingerprint)) {
			throw new IllegalStateException("--native: the runner stub for " + platform + " was built for "
					+ (carried.isEmpty() ? "an unknown engine (no RLNATIVE-FINGERPRINT marker)" : carried.getFirst())
					+ " but the precompile shim for " + fingerprint + "; rebuild both with rontolisp-native/build.sh");
		}
		return new NativeToolchain(stub, precompile, free);
	}

	private static MemorySegment find(SymbolLookup lookup, String name) {
		return lookup.find(name)
			.orElseThrow(() -> new IllegalStateException("--native: the precompile shim has no " + name));
	}

	private static @Nullable String hostPlatform() {
		return NativeExecutable.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
	}

	private static String shimName(String platform) {
		return platform.startsWith("macos-") ? "librlprecomp.dylib" : "librlprecomp.so";
	}

	private static byte @Nullable [] resource(String name) {
		try (InputStream in = NativeToolchain.class.getClassLoader().getResourceAsStream(RESOURCE_ROOT + name)) {
			return in == null ? null : in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The extraction cache root of this process.
	 * @return the directory {@code native/} is created under
	 */
	static Path cacheRoot() {
		return cacheRoot(System.getProperty(CACHE_PROPERTY), System.getenv("XDG_CACHE_HOME"),
				System.getProperty("user.home"), System.getProperty("os.name", ""));
	}

	/**
	 * The extraction cache root: the {@value #CACHE_PROPERTY} property, else
	 * {@code $XDG_CACHE_HOME/rontolisp} (an absolute one only, as the XDG spec says),
	 * else the platform's per-user cache directory.
	 * @param override the property's value
	 * @param xdgCacheHome {@code $XDG_CACHE_HOME}
	 * @param home the user's home directory
	 * @param osName the {@code os.name} property
	 * @return the directory {@code native/} is created under
	 */
	static Path cacheRoot(@Nullable String override, @Nullable String xdgCacheHome, String home, String osName) {
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		if (xdgCacheHome != null && !xdgCacheHome.isBlank() && Path.of(xdgCacheHome).isAbsolute()) {
			return Path.of(xdgCacheHome, "rontolisp");
		}
		boolean mac = osName.toLowerCase(Locale.ROOT).startsWith("mac");
		return Path.of(home).resolve(mac ? "Library/Caches/rontolisp" : ".cache/rontolisp");
	}

	/**
	 * Writes {@code bytes} to {@code target} unless a file of the same size is already
	 * there. The directory name is the content's hash and a file only appears there by an
	 * atomic move, so a file of the right size is the right file.
	 */
	static Path extract(byte[] bytes, Path target) {
		try {
			if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
				return target;
			}
			Path dir = target.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
			try {
				Files.write(tmp, bytes);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(tmp);
			}
			return target;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("--native: cannot extract the precompile shim to " + target, ex);
		}
	}

	private static String sha256Prefix(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
