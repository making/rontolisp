package am.ik.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What a Maven {@code settings.xml} says that bears on resolution. The local repository
 * and offline mode are honored. Mirrors and proxies are not implemented, and are refused
 * by name: a repository a mirror covers, or a proxy would route, is never contacted
 * directly behind the user's back ({@link #checkRemoteAccess}). Server credentials are
 * not sent; a repository that asks for them fails with HTTP 401/403, and the message
 * names the {@code <server>} entry when there is one.
 *
 * @param localRepository the {@code <localRepository>}, or {@code null} for the default
 * @param offline the {@code <offline>} flag
 * @param mirrors the {@code <mirror>} entries, in order
 * @param proxies the {@code <proxy>} entries, in order
 * @param servers the ids of the {@code <server>} entries
 */
public record MavenSettings(@Nullable Path localRepository, boolean offline, List<Mirror> mirrors, List<Proxy> proxies,
		List<String> servers) {

	/**
	 * A {@code <mirror>} entry.
	 *
	 * @param id the mirror id
	 * @param url the mirror URL
	 * @param mirrorOf the repositories it mirrors ({@code *}, {@code external:*},
	 * {@code central,!snapshots}, ...)
	 */
	public record Mirror(String id, String url, String mirrorOf) {
	}

	/**
	 * A {@code <proxy>} entry.
	 *
	 * @param id the proxy id
	 * @param active whether the proxy is active
	 * @param protocol the protocol it serves
	 * @param host the proxy host
	 * @param port the proxy port
	 * @param nonProxyHosts the {@code |}-separated host patterns it does not serve
	 */
	public record Proxy(String id, boolean active, String protocol, String host, int port, String nonProxyHosts) {
	}

	/**
	 * Copies the lists.
	 * @param localRepository the local repository, or {@code null}
	 * @param offline the offline flag
	 * @param mirrors the mirrors
	 * @param proxies the proxies
	 * @param servers the server ids
	 */
	public MavenSettings {
		mirrors = List.copyOf(mirrors);
		proxies = List.copyOf(proxies);
		servers = List.copyOf(servers);
	}

	/**
	 * No settings: the default local repository, online, no mirror, proxy or server.
	 * @return the empty settings
	 */
	public static MavenSettings none() {
		return new MavenSettings(null, false, List.of(), List.of(), List.of());
	}

	/**
	 * The user settings file, {@code ~/.m2/settings.xml}.
	 * @return its path
	 */
	public static Path userSettingsFile() {
		return Path.of(System.getProperty("user.home", "."), ".m2", "settings.xml");
	}

	/**
	 * Reads the user settings file when there is one.
	 * @return its settings, or {@link #none()} without the file
	 * @throws MavenResolutionException if the file cannot be read or parsed
	 */
	public static MavenSettings readUserSettings() throws MavenResolutionException {
		Path file = userSettingsFile();
		return Files.isRegularFile(file) ? read(file) : none();
	}

	/**
	 * Reads a settings file, expanding {@code ${...}} from the system properties and
	 * {@code ${env.NAME}} from the environment, as Maven does.
	 * @param file the settings file
	 * @return its settings
	 * @throws MavenResolutionException if the file cannot be read or parsed
	 */
	public static MavenSettings read(Path file) throws MavenResolutionException {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot read " + file + ": " + ex.getMessage(), ex);
		}
		Map<String, String> system = new HashMap<>();
		for (String name : System.getProperties().stringPropertyNames()) {
			system.put(name, Objects.requireNonNullElse(System.getProperty(name), ""));
		}
		return parse(bytes, file.toString(), system, System.getenv());
	}

	static MavenSettings parse(byte[] bytes, String source, Map<String, String> system, Map<String, String> env)
			throws MavenResolutionException {
		XmlElement settings;
		try {
			settings = XmlParser.parse(bytes);
		}
		catch (XmlParser.Malformed ex) {
			throw new MavenResolutionException(source + " is not well-formed: " + ex.getMessage(), ex);
		}
		Interpolator interpolator = new Interpolator(List.of(Interpolator.of(system),
				expression -> expression.startsWith("env.") ? env.get(expression.substring("env.".length())) : null));
		Values values = new Values(interpolator, source);
		String localRepository = values.leaf(settings, "localRepository");
		List<Mirror> mirrors = new ArrayList<>();
		for (XmlElement mirror : list(settings, "mirrors", "mirror")) {
			mirrors.add(new Mirror(values.leaf(mirror, "id", "default"), values.leaf(mirror, "url", ""),
					values.leaf(mirror, "mirrorOf", "")));
		}
		List<Proxy> proxies = new ArrayList<>();
		for (XmlElement proxy : list(settings, "proxies", "proxy")) {
			String port = values.leaf(proxy, "port", "8080");
			int portNumber;
			try {
				portNumber = Integer.parseInt(port);
			}
			catch (NumberFormatException ex) {
				throw new MavenResolutionException(source + ": proxy port '" + port + "' is not a number", ex);
			}
			proxies.add(new Proxy(values.leaf(proxy, "id", "default"),
					Boolean.parseBoolean(values.leaf(proxy, "active", "true")), values.leaf(proxy, "protocol", "http"),
					values.leaf(proxy, "host", ""), portNumber, values.leaf(proxy, "nonProxyHosts", "")));
		}
		List<String> servers = new ArrayList<>();
		for (XmlElement server : list(settings, "servers", "server")) {
			servers.add(values.leaf(server, "id", "default"));
		}
		return new MavenSettings(
				localRepository == null || localRepository.isEmpty() ? null : Path.of(localRepository).toAbsolutePath(),
				Boolean.parseBoolean(values.leaf(settings, "offline", "false")), mirrors, proxies, servers);
	}

	private static List<XmlElement> list(XmlElement settings, String listName, String itemName) {
		XmlElement list = settings.child(listName);
		return list == null ? List.of() : list.children(itemName);
	}

	/** Reads trimmed, interpolated leaf values. */
	private record Values(Interpolator interpolator, String source) {

		@Nullable String leaf(XmlElement parent, String name) throws MavenResolutionException {
			XmlElement child = parent.child(name);
			if (child == null) {
				return null;
			}
			try {
				return this.interpolator.interpolate(child.text().trim());
			}
			catch (Interpolator.CycleException ex) {
				throw new MavenResolutionException(this.source + ": " + ex.getMessage(), ex);
			}
		}

		String leaf(XmlElement parent, String name, String fallback) throws MavenResolutionException {
			String value = leaf(parent, name);
			return value == null ? fallback : value;
		}

	}

	/**
	 * Refuses, by name, a remote access these settings route somewhere else: a repository
	 * a mirror covers, or one an active proxy serves.
	 * @param repository the repository about to be contacted
	 * @throws MavenResolutionException naming the mirror or proxy
	 */
	public void checkRemoteAccess(RemoteRepository repository) throws MavenResolutionException {
		Mirror mirror = mirrorFor(repository);
		if (mirror != null) {
			throw new MavenResolutionException("settings.xml mirrors repository " + repository + " to '" + mirror.id()
					+ "' (" + mirror.url() + ", mirrorOf " + mirror.mirrorOf()
					+ "); mirrors are not supported, so it is not contacted");
		}
		Proxy proxy = proxyFor(repository);
		if (proxy != null) {
			throw new MavenResolutionException("settings.xml routes " + repository + " through proxy '" + proxy.id()
					+ "' (" + proxy.protocol() + "://" + proxy.host() + ":" + proxy.port()
					+ "); proxies are not supported, so it is not contacted");
		}
	}

	/**
	 * Answers whether a {@code <server>} entry configures the repository of this id.
	 * @param repositoryId the repository id
	 * @return {@code true} when there is such an entry
	 */
	public boolean hasServer(String repositoryId) {
		return this.servers.contains(repositoryId);
	}

	/**
	 * Maven's mirror selection: an entry naming the repository's id exactly wins, else
	 * the first whose pattern matches.
	 * @param repository the repository
	 * @return the mirror, or {@code null}
	 */
	@Nullable Mirror mirrorFor(RemoteRepository repository) {
		for (Mirror mirror : this.mirrors) {
			if (mirror.mirrorOf().equals(repository.id())) {
				return mirror;
			}
		}
		for (Mirror mirror : this.mirrors) {
			if (matchesPattern(repository, mirror.mirrorOf())) {
				return mirror;
			}
		}
		return null;
	}

	private static boolean matchesPattern(RemoteRepository repository, String pattern) {
		String id = repository.id();
		if (pattern.equals("*") || pattern.equals(id)) {
			return true;
		}
		boolean result = false;
		for (String part : pattern.split(",")) {
			String repo = part.trim();
			if (repo.length() > 1 && repo.startsWith("!")) {
				if (repo.substring(1).equals(id)) {
					return false;
				}
			}
			else if (repo.equals(id)) {
				return true;
			}
			else if (repo.equals("external:*") && isExternal(repository)) {
				result = true;
			}
			else if (repo.equals("external:http:*") && isExternal(repository) && repository.protocol().equals("http")) {
				result = true;
			}
			else if (repo.equals("*")) {
				result = true;
			}
		}
		return result;
	}

	private static boolean isExternal(RemoteRepository repository) {
		String host = repository.host();
		return !(host.equals("localhost") || host.equals("127.0.0.1") || repository.protocol().equals("file"));
	}

	/**
	 * Maven's proxy selection: the first active proxy per protocol whose
	 * {@code nonProxyHosts} does not cover the host; an {@code https} repository falls
	 * back to an {@code http} proxy.
	 * @param repository the repository
	 * @return the proxy, or {@code null}
	 */
	@Nullable Proxy proxyFor(RemoteRepository repository) {
		if (repository.protocol().equals("file")) {
			return null;
		}
		Proxy byProtocol = null;
		Proxy http = null;
		for (Proxy proxy : this.proxies) {
			if (!proxy.active() || isNonProxyHost(repository.host(), proxy.nonProxyHosts())) {
				continue;
			}
			String protocol = proxy.protocol().toLowerCase(Locale.ENGLISH);
			if (byProtocol == null && protocol.equals(repository.protocol())) {
				byProtocol = proxy;
			}
			if (http == null && protocol.equals("http")) {
				http = proxy;
			}
		}
		if (byProtocol == null && repository.protocol().equals("https")) {
			return http;
		}
		return byProtocol;
	}

	private static boolean isNonProxyHost(String host, String nonProxyHosts) {
		for (String pattern : nonProxyHosts.split("\\|")) {
			if (pattern.isEmpty()) {
				continue;
			}
			String regex = pattern.replace(".", "\\.").replace("*", ".*");
			if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(host).matches()) {
				return true;
			}
		}
		return false;
	}

}
