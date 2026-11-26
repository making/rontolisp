package am.ik.rontolisp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * Build-time version information.
 * <p>
 * The values are read once from the {@code am/ik/rontolisp/version.properties} resource
 * that the Maven build writes during {@code generate-resources}. This class is a
 * checked-in source rather than a generated one on purpose: goals invoked outside the
 * lifecycle do not fork {@code generate-sources} (notably {@code mvn clean javadoc:jar}),
 * so a generated {@code Version.java} would be missing exactly when such a goal needs to
 * resolve it.
 * <p>
 * When the resource is absent -- the classes were compiled outside Maven, or the build
 * ran with {@code -Dversion-generate-skip=true} -- every accessor returns
 * {@code "unknown"}.
 */
public final class Version {

	/**
	 * What every accessor answers when the build information is unavailable -- the
	 * resource is absent, or the property was left unexpanded (a build with no git
	 * repository leaves the git ones so). A caller that formats build info tests against
	 * this rather than printing it.
	 */
	public static final String UNKNOWN = "unknown";

	private static final String VERSION;

	private static final String BUILD;

	private static final String GIT_COMMIT;

	private static final String GIT_BRANCH;

	static {
		Properties properties = new Properties();
		try (InputStream in = Version.class.getResourceAsStream("version.properties")) {
			if (in != null) {
				properties.load(in);
			}
		}
		catch (IOException ignored) {
			// Fall through to the "unknown" defaults below.
		}
		VERSION = resolved(properties.getProperty("version"));
		BUILD = resolved(properties.getProperty("build"));
		GIT_COMMIT = resolved(properties.getProperty("gitCommit"));
		GIT_BRANCH = resolved(properties.getProperty("gitBranch"));
	}

	private Version() {
	}

	/**
	 * Returns the project version.
	 * @return the project version
	 */
	public static String getVersion() {
		return VERSION;
	}

	/**
	 * Returns the build timestamp.
	 * @return the build timestamp
	 */
	public static String getBuild() {
		return BUILD;
	}

	/**
	 * Returns the abbreviated git commit id.
	 * @return the abbreviated git commit id
	 */
	public static String getGitCommit() {
		return GIT_COMMIT;
	}

	/**
	 * Returns the git branch name.
	 * @return the git branch name
	 */
	public static String getGitBranch() {
		return GIT_BRANCH;
	}

	/**
	 * Returns the version information as a JSON string.
	 * @return the version information as a JSON string
	 */
	public static String getVersionAsJson() {
		return "{\"version\": \"" + escape(VERSION) + "\", \"buildTimestamp\": \"" + escape(BUILD)
				+ "\", \"gitCommit\": \"" + escape(GIT_COMMIT) + "\", \"gitBranch\": \"" + escape(GIT_BRANCH) + "\"}";
	}

	/**
	 * A build run without a git repository leaves the git properties unexpanded (the
	 * {@code git-commit-id} plugin is configured not to fail there), so a literal
	 * {@code ${...}} placeholder counts as missing rather than as a value.
	 */
	private static String resolved(@Nullable String value) {
		return (value == null || value.isEmpty() || value.startsWith("${")) ? UNKNOWN : value;
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
