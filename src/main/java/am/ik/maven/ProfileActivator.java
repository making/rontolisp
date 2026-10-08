package am.ik.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Maven 3.9's profile activation, decided per POM: a profile is active when it has at
 * least one condition and all of them hold; when no profile of the POM is active that
 * way, its {@code activeByDefault} profiles are. The conditions read the activation
 * context, not the POM's properties: the JDK and OS from the system properties
 * ({@code java.version}, {@code os.name}, {@code os.arch}, {@code os.version}), a
 * property condition from the user properties then the system properties, a file
 * condition from the file system.
 */
final class ProfileActivator {

	private ProfileActivator() {
	}

	/**
	 * Selects the active profiles of one POM.
	 * @param profiles the POM's profiles, their activation already interpolated
	 * @param user the user properties
	 * @param system the system properties
	 * @param problems collects the errors that make the POM invalid
	 * @return the active profiles, in POM order
	 */
	static List<PomModel.Profile> active(List<PomModel.Profile> profiles, Map<String, String> user,
			Map<String, String> system, List<String> problems) {
		List<PomModel.Profile> active = new ArrayList<>();
		List<PomModel.Profile> byDefault = new ArrayList<>();
		for (PomModel.Profile profile : profiles) {
			PomModel.Activation activation = profile.activation();
			if (activation == null) {
				continue;
			}
			if (isActive(profile.id(), activation, user, system, problems)) {
				active.add(profile);
			}
			else if (isTrue(activation.activeByDefault())) {
				byDefault.add(profile);
			}
		}
		return active.isEmpty() ? byDefault : active;
	}

	private static boolean isActive(@Nullable String profileId, PomModel.Activation activation,
			Map<String, String> user, Map<String, String> system, List<String> problems) {
		String jdk = activation.jdk();
		PomModel.Os os = activation.os();
		PomModel.Property property = activation.property();
		PomModel.FileCheck file = activation.file();
		if (jdk == null && os == null && property == null && file == null) {
			return false;
		}
		// Every present condition is evaluated, as Maven's "&=" does, so each reports
		// its problems.
		boolean active = true;
		if (jdk != null) {
			active &= jdk(profileId, jdk, system, problems);
		}
		if (os != null) {
			active &= os(os, system);
		}
		if (property != null) {
			active &= property(profileId, property, user, system, problems);
		}
		if (file != null) {
			active &= file(file);
		}
		return active;
	}

	static boolean jdk(@Nullable String profileId, String jdk, Map<String, String> system, List<String> problems) {
		String version = system.get("java.version");
		if (version == null || version.isEmpty()) {
			problems.add("Failed to determine Java version for profile " + profileId);
			return false;
		}
		if (jdk.startsWith("!")) {
			return !version.startsWith(jdk.substring(1));
		}
		if (jdk.startsWith("[") || jdk.startsWith("(")) {
			try {
				return isInRange(version, range(jdk));
			}
			catch (NumberFormatException ex) {
				// Maven reports a warning and leaves the profile inactive.
				return false;
			}
		}
		return version.startsWith(jdk);
	}

	private record RangeValue(String value, boolean closed) {
	}

	private static List<RangeValue> range(String range) {
		List<RangeValue> ranges = new ArrayList<>();
		for (String token : range.split(",")) {
			if (token.startsWith("[")) {
				ranges.add(new RangeValue(token.replace("[", ""), true));
			}
			else if (token.startsWith("(")) {
				ranges.add(new RangeValue(token.replace("(", ""), false));
			}
			else if (token.endsWith("]")) {
				ranges.add(new RangeValue(token.replace("]", ""), true));
			}
			else if (token.endsWith(")")) {
				ranges.add(new RangeValue(token.replace(")", ""), false));
			}
			else if (token.isEmpty()) {
				ranges.add(new RangeValue("", false));
			}
		}
		if (ranges.size() < 2) {
			ranges.add(new RangeValue("99999999", false));
		}
		return ranges;
	}

	private static boolean isInRange(String value, List<RangeValue> range) {
		int left = relationOrder(value, range.get(0), true);
		if (left == 0) {
			return true;
		}
		if (left < 0) {
			return false;
		}
		return relationOrder(value, range.get(1), false) <= 0;
	}

	private static int relationOrder(String value, RangeValue rangeValue, boolean isLeft) {
		if (rangeValue.value().isEmpty()) {
			return isLeft ? 1 : -1;
		}
		List<String> valueTokens = new ArrayList<>(Arrays.asList(value.replaceAll("[^\\d._-]", "").split("[._-]")));
		List<String> rangeTokens = new ArrayList<>(Arrays.asList(rangeValue.value().split("\\.")));
		while (valueTokens.size() < 3) {
			valueTokens.add("0");
		}
		while (rangeTokens.size() < 3) {
			rangeTokens.add("0");
		}
		for (int i = 0; i < 3; i++) {
			int x = Integer.parseInt(valueTokens.get(i));
			int y = Integer.parseInt(rangeTokens.get(i));
			if (x < y) {
				return -1;
			}
			if (y < x) {
				return 1;
			}
		}
		if (!rangeValue.closed()) {
			return isLeft ? -1 : 1;
		}
		return 0;
	}

	static boolean os(PomModel.Os os, Map<String, String> system) {
		String expectedFamily = os.family();
		String expectedName = os.name();
		String expectedArch = os.arch();
		String expectedVersion = os.version();
		boolean active = expectedArch != null || expectedFamily != null || expectedName != null
				|| expectedVersion != null;
		String name = systemValue(system, "os.name");
		String arch = systemValue(system, "os.arch");
		String version = systemValue(system, "os.version");
		if (active && expectedFamily != null) {
			active = negatable(expectedFamily.toLowerCase(Locale.ENGLISH), test -> isFamily(test, name));
		}
		if (active && expectedName != null) {
			active = negatable(expectedName.toLowerCase(Locale.ENGLISH), name::equals);
		}
		if (active && expectedArch != null) {
			active = negatable(expectedArch.toLowerCase(Locale.ENGLISH), arch::equals);
		}
		if (active && expectedVersion != null) {
			if (expectedVersion.startsWith("regex:")) {
				active = version.matches(expectedVersion.substring("regex:".length()));
			}
			else {
				active = negatable(expectedVersion, version::equalsIgnoreCase);
			}
		}
		return active;
	}

	private static String systemValue(Map<String, String> system, String key) {
		String value = system.get(key);
		if (value == null) {
			value = System.getProperty(key, "");
		}
		return value.toLowerCase(Locale.ENGLISH);
	}

	private interface Test {

		boolean holds(String value);

	}

	private static boolean negatable(String expected, Test test) {
		if (expected.startsWith("!")) {
			return !test.holds(expected.substring(1));
		}
		return test.holds(expected);
	}

	/**
	 * Maven's {@code Os.isFamily}, over a lowercased OS name.
	 * @param family the family, lowercased
	 * @param name the OS name, lowercased
	 * @return whether the OS belongs to the family
	 */
	static boolean isFamily(String family, String name) {
		boolean isWindows = name.contains("windows");
		boolean is9x = isWindows
				&& (name.contains("95") || name.contains("98") || name.contains("me") || name.contains("ce"));
		return switch (family) {
			case "windows" -> isWindows;
			case "win9x" -> is9x;
			case "winnt" -> isWindows && !is9x;
			case "os/2" -> name.contains("os/2");
			case "netware" -> name.contains("netware");
			case "dos" -> File.pathSeparator.equals(";") && !isFamily("netware", name) && !isWindows;
			case "mac" -> name.contains("mac") || name.contains("darwin");
			case "tandem" -> name.contains("nonstop_kernel");
			case "unix" -> File.pathSeparator.equals(":") && !isFamily("openvms", name)
					&& (!isFamily("mac", name) || name.endsWith("x"));
			case "z/os" -> name.contains("z/os") || name.contains("os/390");
			case "os/400" -> name.contains("os/400");
			case "openvms" -> name.contains("openvms");
			default -> name.contains(family.toLowerCase(Locale.US));
		};
	}

	static boolean property(@Nullable String profileId, PomModel.Property property, Map<String, String> user,
			Map<String, String> system, List<String> problems) {
		String name = property.name();
		boolean reverseName = false;
		if (name != null && name.startsWith("!")) {
			reverseName = true;
			name = name.substring(1);
		}
		if (name == null || name.isEmpty()) {
			problems.add("The property name is required to activate the profile " + profileId);
			return false;
		}
		String actual = user.get(name);
		if (actual == null) {
			actual = system.get(name);
		}
		String expected = property.value();
		if (expected != null && !expected.isEmpty()) {
			boolean reverseValue = expected.startsWith("!");
			boolean result = (reverseValue ? expected.substring(1) : expected).equals(actual);
			return reverseValue != result;
		}
		boolean present = actual != null && !actual.isEmpty();
		return reverseName != present;
	}

	/**
	 * A file condition, its path already interpolated: an absolute path that exists (or,
	 * for {@code missing}, does not). A relative path never matches -- a dependency's POM
	 * has no project directory -- and neither does one still naming {@code ${basedir}}.
	 */
	static boolean file(PomModel.FileCheck file) {
		String exists = file.exists();
		String absent = file.missing();
		String path;
		boolean missing;
		if (exists != null && !exists.isEmpty()) {
			path = exists;
			missing = false;
		}
		else if (absent != null && !absent.isEmpty()) {
			path = absent;
			missing = true;
		}
		else {
			return false;
		}
		File candidate = new File(path);
		if (!candidate.isAbsolute()) {
			return false;
		}
		return missing != candidate.exists();
	}

	private static boolean isTrue(@Nullable String flag) {
		return flag != null && Boolean.parseBoolean(flag);
	}

}
