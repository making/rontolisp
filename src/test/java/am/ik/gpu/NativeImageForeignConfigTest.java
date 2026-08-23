package am.ik.gpu;

import java.io.IOException;
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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the native-image downcall registration of {@code am.ik.gpu} against the bindings
 * themselves.
 *
 * <p>
 * Native Image builds a downcall stub only for a signature listed in
 * {@code reachability-metadata.json}, and the linker REFUSES to make a handle for any
 * other. Both drivers bind every entry point in their constructor, so one unregistered
 * shape fails the whole construction -- and the failure looked exactly like a machine
 * with no device: the binary printed "libcuda.so.1 is not present: this machine has no
 * NVIDIA driver" on a machine with a working GPU and ran the kernels unaccelerated. It is
 * invisible to every other test, because on the JVM the linker registers nothing ahead of
 * time and binds whatever it is asked for.
 *
 * <p>
 * So the drivers record each shape as they bind it and this test compares that record --
 * what is actually asked for -- with the checked-in file. Neither driver needs its device
 * here: the constructors are handed a lookup that answers every name with an address that
 * is never called, so both halves run on any machine. The {@code --blas} entries in the
 * same file are not covered here; they are bound from {@code am.ik.rontolisp.eval}, which
 * this package may not see.
 */
class NativeImageForeignConfigTest {

	private static final Path METADATA = Path.of("src", "main", "resources", "META-INF", "native-image",
			"am.ik.rontolisp", "rontolisp", "reachability-metadata.json");

	/**
	 * A lookup that finds everything. The address is arbitrary and non-NULL: a handle is
	 * MADE for it, which is all this test is about, and never invoked.
	 */
	private static final SymbolLookup EVERYTHING = name -> Optional.of(MemorySegment.ofAddress(0x1000));

	/** The metadata schema's aliases for the primitive layouts, in one spelling. */
	private static final Map<String, String> ALIASES = Map.of("boolean", "jboolean", "byte", "jbyte", "char", "jchar",
			"short", "jshort", "int", "jint", "long", "jlong", "float", "jfloat", "double", "jdouble");

	@Test
	void everyCudaDowncallShapeIsRegistered() throws IOException {
		CudaDriver driver = new CudaDriver(EVERYTHING);
		assertThat(missing(driver.signatures(), driver.criticalSignatures()))
			.as("CUDA downcall shapes with no entry in %s -- the native binary refuses to bind them, so --gpu "
					+ "declines as though this machine had no NVIDIA driver", METADATA)
			.isEmpty();
	}

	@Test
	void everyMetalDowncallShapeIsRegistered() throws IOException {
		MetalDriver driver = new MetalDriver(EVERYTHING, EVERYTHING, EVERYTHING);
		assertThat(missing(driver.signatures(), Set.of()))
			.as("Metal downcall shapes with no entry in %s -- the native binary refuses to bind them, so --gpu "
					+ "declines as though this machine were not a Mac", METADATA)
			.isEmpty();
	}

	private static List<String> missing(Set<FunctionDescriptor> plain, Set<FunctionDescriptor> critical)
			throws IOException {
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

	/** Every {@code foreign.downcalls} entry, in this test's own spelling. */
	private static Set<String> registered() throws IOException {
		JsonNode downcalls = JsonMapper.builder()
			.build()
			.readTree(Files.readString(METADATA, StandardCharsets.UTF_8))
			.path("foreign")
			.path("downcalls");
		assertThat(downcalls.size()).as("foreign.downcalls entries in %s", METADATA).isPositive();
		Set<String> registered = new LinkedHashSet<>();
		for (JsonNode entry : downcalls) {
			List<String> parameters = new ArrayList<>();
			for (JsonNode parameter : entry.path("parameterTypes")) {
				parameters.add(alias(parameter.asString()));
			}
			// A critical registration is a different stub than the same shape without
			// it, and only allowHeapAccess makes the one both copies are bound with.
			boolean critical = entry.path("options").path("critical").path("allowHeapAccess").asBoolean(false);
			registered.add(signature(alias(entry.path("returnType").asString("void")), parameters, critical));
		}
		return registered;
	}

	private static String signature(FunctionDescriptor descriptor, boolean critical) {
		return signature(descriptor.returnLayout().map(NativeImageForeignConfigTest::type).orElse("void"),
				descriptor.argumentLayouts().stream().map(NativeImageForeignConfigTest::type).toList(), critical);
	}

	private static String signature(String returnType, List<String> parameterTypes, boolean critical) {
		return returnType + "(" + String.join(",", parameterTypes) + ")" + (critical ? " critical" : "");
	}

	/** One layout, spelled the way {@code reachability-metadata.json} spells it. */
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
			case GroupLayout group -> group.memberLayouts()
				.stream()
				.map(NativeImageForeignConfigTest::type)
				.collect(Collectors.joining(",", "struct(", ")"));
			default -> throw new IllegalStateException("no metadata spelling for " + layout);
		};
	}

	private static String alias(String type) {
		return ALIASES.getOrDefault(type, type);
	}

}
