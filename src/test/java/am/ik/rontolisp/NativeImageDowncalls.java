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
 * this file. Every one of them binds against {@link #EVERYTHING}, so none of the tests
 * needs the library it is about.
 *
 * @see am.ik.gpu.NativeImageForeignConfigTest
 * @see am.ik.rontolisp.eval.LinalgBlasDeclineTest
 */
public final class NativeImageDowncalls {

	private static final Path METADATA = Path.of("src", "main", "resources", "META-INF", "native-image",
			"am.ik.rontolisp", "rontolisp", "reachability-metadata.json");

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
		Set<String> registered = registered();
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
	 * The upcall shapes with no entry in the checked-in file: what a native image would
	 * refuse to build a stub for. An upcall stub is registered under
	 * {@code foreign.upcalls}, separately from the downcalls, and has no critical option.
	 * @param shapes the shapes bound with {@code Linker.upcallStub}
	 * @return the unregistered ones, spelled as the file spells them
	 */
	public static List<String> missingUpcalls(Set<FunctionDescriptor> shapes) {
		Set<String> registered = registered("upcalls");
		List<String> missing = new ArrayList<>();
		for (FunctionDescriptor descriptor : shapes) {
			if (!registered.contains(signature(descriptor, false))) {
				missing.add(signature(descriptor, false));
			}
		}
		return missing;
	}

	/** Every {@code foreign.downcalls} entry, in this class's own spelling. */
	private static Set<String> registered() {
		return registered("downcalls");
	}

	private static Set<String> registered(String section) {
		JsonNode downcalls = JsonMapper.builder().build().readTree(read(METADATA)).path("foreign").path(section);
		assertThat(downcalls.size()).as("foreign.%s entries in %s", section, METADATA).isPositive();
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
			registered.add(signature(alias(entry.path("returnType").asString("void")), parameters, critical)
					+ (capture ? " capture" : ""));
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
