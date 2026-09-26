package am.ik.rontolisp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checked-in native-image downcall registration, for the tests that pin an FFM
 * binding against it.
 *
 * <p>
 * Native Image builds a downcall stub only for a signature listed in
 * {@code reachability-metadata.json}, and the linker REFUSES to make a handle for any
 * other. Every binding here asks for all of its handles at once -- in a constructor, or
 * in one static block -- so a single unregistered shape takes the whole binding down, and
 * what the user sees is the acceleration declining as though the machine had no library:
 * {@code --gpu} shipped a release saying "libcuda.so.1 is not present: this machine has
 * no NVIDIA driver" on a machine with a working GPU. Nothing else catches it, because on
 * the JVM the linker registers nothing ahead of time and binds whatever it is asked for,
 * and a decline is an ordinary outcome that prints the ordinary answer.
 *
 * <p>
 * So each binding records the shapes it asks the linker for and its test compares that
 * record -- what is actually asked for, not a list someone remembered to update -- with
 * the files. Every one of them binds against {@link #EVERYTHING}, so none of the tests
 * needs the library it is about.
 *
 * <p>
 * The registration is more than one file under rontolisp's
 * {@code META-INF/native-image/am.ik.rontolisp/}, and rontolisp's own binary reads every
 * one of them; the plain methods ask about that union. The {@code objc:} package's is a
 * file of its own ({@link #OBJC}), which the JVM backend copies into a compiled program
 * so an image built from the user's jar -- which reads only that jar's {@code META-INF}
 * -- needs no configuration. That file must stand ALONE, so its tests ask about it by
 * path.
 *
 * @see am.ik.gpu.NativeImageForeignConfigTest
 * @see am.ik.rontolisp.eval.LinalgBlasDeclineTest
 */
public final class NativeImageDowncalls {

	/** Where rontolisp's own image reads its registration from: every file below. */
	private static final Path NATIVE_IMAGE = Path.of("src", "main", "resources", "META-INF", "native-image",
			"am.ik.rontolisp");

	/** The {@code objc:} registration a compiled program carries. */
	public static final Path OBJC = NATIVE_IMAGE.resolve("rontolisp-objc").resolve("reachability-metadata.json");

	/**
	 * A lookup that finds every name. The address is arbitrary and non-NULL: a handle is
	 * MADE for it, which is all these tests are about, and never invoked.
	 */
	public static final SymbolLookup EVERYTHING = name -> Optional.of(MemorySegment.ofAddress(0x1000));

	/** The metadata schema's aliases for the primitive layouts, in one spelling. */
	private static final Map<String, String> ALIASES = Map.of("boolean", "jboolean", "byte", "jbyte", "char", "jchar",
			"short", "jshort", "int", "jint", "long", "jlong", "float", "jfloat", "double", "jdouble");

	private NativeImageDowncalls() {
	}

	/**
	 * The shapes with no entry in the checked-in file: what a native image would refuse
	 * to bind.
	 * @param plain the shapes bound without {@code critical}
	 * @param critical the shapes bound with {@code critical(true)}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missing(Set<FunctionDescriptor> plain, Set<FunctionDescriptor> critical) {
		return missing(registered(), plain, critical);
	}

	/**
	 * The shapes with no entry in ONE file: what an image built from a compiled program
	 * that carries only that file would refuse to bind.
	 * @param metadata the {@code reachability-metadata.json} that must stand alone
	 * @param plain the shapes bound without {@code critical}
	 * @param critical the shapes bound with {@code critical(true)}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missing(Path metadata, Set<FunctionDescriptor> plain, Set<FunctionDescriptor> critical) {
		return missing(registered(metadata, "downcalls"), plain, critical);
	}

	private static List<String> missing(Set<String> registered, Set<FunctionDescriptor> plain,
			Set<FunctionDescriptor> critical) {
		List<String> missing = new ArrayList<>();
		for (FunctionDescriptor descriptor : plain) {
			if (!registered.contains(signature(descriptor, false))) {
				missing.add(signature(descriptor, false));
			}
		}
		for (FunctionDescriptor descriptor : critical) {
			if (!registered.contains(signature(descriptor, true))) {
				missing.add(signature(descriptor, true));
			}
		}
		return missing;
	}

	/**
	 * The downcall shapes -- bound with {@code captureCallState} and without
	 * {@code critical}, the {@code am.ik.ffi} runtime's kind -- with no entry in the
	 * checked-in file. A capture registration is a different stub than the same shape
	 * without it, so the spelling carries the flag.
	 * @param captured the shapes bound with {@code Linker.Option.captureCallState}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingCaptured(Set<FunctionDescriptor> captured) {
		Set<String> registered = registered();
		List<String> missing = new ArrayList<>();
		for (FunctionDescriptor descriptor : captured) {
			String signature = signature(descriptor, false) + " capture";
			if (!registered.contains(signature)) {
				missing.add(signature);
			}
		}
		return missing;
	}

	/**
	 * The VARIADIC downcall shapes with no entry in the checked-in file. A variadic
	 * registration is a different stub than the same shape without one -- on the Apple
	 * arm64 ABI a variadic argument travels on the stack where a fixed one travels in a
	 * register -- so the spelling carries the split.
	 * @param shapes the shapes bound with {@code Linker.Option.firstVariadicArg}
	 * @param firstVariadicArg the index every one of them was bound at
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingVariadic(Set<FunctionDescriptor> shapes, int firstVariadicArg) {
		return missingVariadic(registered(), shapes, firstVariadicArg);
	}

	/**
	 * The VARIADIC downcall shapes with no entry in ONE file.
	 * @param metadata the {@code reachability-metadata.json} that must stand alone
	 * @param shapes the shapes bound with {@code Linker.Option.firstVariadicArg}
	 * @param firstVariadicArg the index every one of them was bound at
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingVariadic(Path metadata, Set<FunctionDescriptor> shapes, int firstVariadicArg) {
		return missingVariadic(registered(metadata, "downcalls"), shapes, firstVariadicArg);
	}

	private static List<String> missingVariadic(Set<String> registered, Set<FunctionDescriptor> shapes,
			int firstVariadicArg) {
		List<String> missing = new ArrayList<>();
		for (FunctionDescriptor descriptor : shapes) {
			String signature = signature(descriptor, false) + " variadic@" + firstVariadicArg;
			if (!registered.contains(signature)) {
				missing.add(signature);
			}
		}
		return missing;
	}

	/**
	 * The upcall shapes with no entry in the checked-in file: what a native image would
	 * refuse to build a stub for. An upcall stub is registered under
	 * {@code foreign.upcalls}, separately from the downcalls, and has no critical option.
	 * @param shapes the shapes bound with {@code Linker.upcallStub}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingUpcalls(Set<FunctionDescriptor> shapes) {
		return missingUpcalls(registered("upcalls"), shapes);
	}

	/**
	 * The upcall shapes with no entry in ONE file.
	 * @param metadata the {@code reachability-metadata.json} that must stand alone
	 * @param shapes the shapes bound with {@code Linker.upcallStub}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingUpcalls(Path metadata, Set<FunctionDescriptor> shapes) {
		return missingUpcalls(registered(metadata, "upcalls"), shapes);
	}

	private static List<String> missingUpcalls(Set<String> registered, Set<FunctionDescriptor> shapes) {
		List<String> missing = new ArrayList<>();
		for (FunctionDescriptor descriptor : shapes) {
			if (!registered.contains(signature(descriptor, false))) {
				missing.add(signature(descriptor, false));
			}
		}
		return missing;
	}

	/**
	 * Every {@code foreign.downcalls} entry of every file, in this class's own spelling.
	 */
	private static Set<String> registered() {
		return registered("downcalls");
	}

	/**
	 * Every VARIADIC {@code foreign.downcalls} entry of one file, so a test can pin the
	 * file and the rule that generated it against each other in both directions.
	 * @param metadata the {@code reachability-metadata.json} to read
	 * @return the entries, spelled as {@link #missingVariadic} spells them
	 */
	public static List<String> registeredVariadic(Path metadata) {
		return registered(metadata, "downcalls").stream().filter(entry -> entry.contains(" variadic@")).toList();
	}

	/** One section of every file rontolisp's own image reads, as one set. */
	private static Set<String> registered(String section) {
		Set<String> registered = new LinkedHashSet<>();
		for (Path metadata : files()) {
			JsonNode entries = JsonMapper.builder().build().readTree(read(metadata)).path("foreign").path(section);
			if (!entries.isMissingNode()) {
				registered.addAll(registered(metadata, section));
			}
		}
		assertThat(registered).as("foreign.%s entries under %s", section, NATIVE_IMAGE).isNotEmpty();
		return registered;
	}

	/** Every {@code reachability-metadata.json} rontolisp's own image reads. */
	private static List<Path> files() {
		try (var walk = Files.walk(NATIVE_IMAGE)) {
			return walk.filter(path -> path.getFileName().toString().equals("reachability-metadata.json"))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Every {@code foreign.downcalls} entry of another metadata file, in this class's own
	 * spelling -- for a file that must hold exactly a binding's shapes.
	 * @param metadata the {@code reachability-metadata.json} to read
	 * @return the entries, spelled as {@link #signature} spells them
	 */
	public static Set<String> registeredDowncalls(Path metadata) {
		return registered(metadata, "downcalls");
	}

	private static Set<String> registered(Path metadata, String section) {
		JsonNode downcalls = JsonMapper.builder().build().readTree(read(metadata)).path("foreign").path(section);
		assertThat(downcalls.size()).as("foreign.%s entries in %s", section, metadata).isPositive();
		Set<String> registered = new LinkedHashSet<>();
		for (JsonNode entry : downcalls) {
			List<String> parameters = new ArrayList<>();
			for (JsonNode parameter : entry.path("parameterTypes")) {
				parameters.add(alias(parameter.asString()));
			}
			// A critical registration is a different stub than the same shape without
			// it, and only allowHeapAccess makes the one a heap segment can be passed to.
			boolean critical = entry.path("options").path("critical").path("allowHeapAccess").asBoolean(false);
			boolean capture = entry.path("options").path("captureCallState").asBoolean(false);
			// A variadic registration is its own stub: the same layouts called with a
			// different ABI, so it never answers for the fixed-arity entry or vice versa.
			JsonNode variadic = entry.path("options").path("firstVariadicArg");
			registered.add(signature(alias(entry.path("returnType").asString("void")), parameters, critical)
					+ (capture ? " capture" : "") + (variadic.isMissingNode() ? "" : " variadic@" + variadic.asInt()));
		}
		return registered;
	}

	/** One shape, spelled the way {@code reachability-metadata.json} spells it. */
	public static String signature(FunctionDescriptor descriptor, boolean critical) {
		return signature(descriptor.returnLayout().map(NativeImageDowncalls::type).orElse("void"),
				descriptor.argumentLayouts().stream().map(NativeImageDowncalls::type).toList(), critical);
	}

	private static String signature(String returnType, List<String> parameterTypes, boolean critical) {
		return returnType + "(" + String.join(",", parameterTypes) + ")" + (critical ? " critical" : "");
	}

	private static String type(MemoryLayout layout) {
		return switch (layout) {
			case AddressLayout ignored -> "void*";
			case ValueLayout.OfBoolean ignored -> "jboolean";
			case ValueLayout.OfByte ignored -> "jbyte";
			case ValueLayout.OfChar ignored -> "jchar";
			case ValueLayout.OfShort ignored -> "jshort";
			case ValueLayout.OfInt ignored -> "jint";
			case ValueLayout.OfLong ignored -> "jlong";
			case ValueLayout.OfFloat ignored -> "jfloat";
			case ValueLayout.OfDouble ignored -> "jdouble";
			// A struct's padding is part of the spelling: the image builder rebuilds the
			// layout with MemoryLayout.structLayout, which refuses a member that does
			// not sit at its own alignment.
			case java.lang.foreign.PaddingLayout padding -> "padding(" + padding.byteSize() + ")";
			case GroupLayout group -> group.memberLayouts()
				.stream()
				.map(NativeImageDowncalls::type)
				.collect(Collectors.joining(",", "struct(", ")"));
			default -> throw new IllegalStateException("no metadata spelling for " + layout);
		};
	}

	private static String alias(String type) {
		return ALIASES.getOrDefault(type, type);
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
