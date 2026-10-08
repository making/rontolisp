package am.ik.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Maven's {@code settings.xml} says that bears on resolution, honored as Maven 3.9
 * honors it: the local repository, offline mode, mirrors ({@code mirrorOf},
 * {@code mirrorOfLayouts}, {@code blocked}), proxies ({@code nonProxyHosts}, credentials)
 * and servers (credentials, {@code <configuration>}'s {@code httpHeaders},
 * {@code connectTimeout} and {@code requestTimeout}), passwords decrypted through
 * {@code settings-security.xml}. {@link #readGlobalAndUser()} merges the global file into
 * the user's as Maven does.
 *
 * @param localRepository the {@code <localRepository>}, or {@code null} for the default
 * @param offline the {@code <offline>} flag
 * @param mirrors the {@code <mirror>} entries, in order
 * @param proxies the {@code <proxy>} entries, in order
 * @param servers the {@code <server>} entries, in order
 */
public record MavenSettings(@Nullable Path localRepository, boolean offline, List<Mirror> mirrors, List<Proxy> proxies,
		List<Server> servers) {

	/**
	 * A {@code <mirror>} entry.
	 *
	 * @param id the mirror id, which names its {@code <server>}
	 * @param url the mirror URL
	 * @param mirrorOf the repositories it mirrors ({@code *}, {@code external:*},
	 * {@code central,!snapshots}, ...)
	 * @param layout the mirror's own layout ({@code default})
	 * @param mirrorOfLayouts the layouts of the repositories it mirrors
	 * ({@code default,legacy})
	 * @param blocked whether a repository it mirrors fails instead of being contacted
	 */
	public record Mirror(String id, String url, String mirrorOf, String layout, String mirrorOfLayouts,
			boolean blocked) {

		/**
		 * A mirror with Maven's defaults: layout {@code default}, mirroring
		 * {@code default,legacy} repositories, not blocked.
		 * @param id the mirror id
		 * @param url the mirror URL
		 * @param mirrorOf the repositories it mirrors
		 */
		public Mirror(String id, String url, String mirrorOf) {
			this(id, url, mirrorOf, "default", "default,legacy", false);
		}

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
	 * @param login its credentials, or {@code null}
	 */
	public record Proxy(String id, boolean active, String protocol, String host, int port, String nonProxyHosts,
			@Nullable Login login) {

		/**
		 * A proxy without credentials.
		 * @param id the proxy id
		 * @param active whether the proxy is active
		 * @param protocol the protocol it serves
		 * @param host the proxy host
		 * @param port the proxy port
		 * @param nonProxyHosts the host patterns it does not serve
		 */
		public Proxy(String id, boolean active, String protocol, String host, int port, String nonProxyHosts) {
			this(id, active, protocol, host, port, nonProxyHosts, null);
		}

	}

	/**
	 * A {@code <server>} entry: what a repository of its id is contacted with.
	 *
	 * @param id the id of the repository (or mirror) it configures
	 * @param login its credentials, or {@code null}
	 * @param headers its {@code httpHeaders}, in order
	 * @param connectTimeout its {@code connectTimeout}, or {@code null}
	 * @param requestTimeout its {@code requestTimeout} (the longest a transfer may go
	 * without a byte), or {@code null}
	 */
	public record Server(String id, @Nullable Login login, Map<String, String> headers,
			@Nullable Duration connectTimeout, @Nullable Duration requestTimeout) {

		/**
		 * Copies the headers, keeping their order.
		 * @param id the id
		 * @param login the credentials, or {@code null}
		 * @param headers the headers
		 * @param connectTimeout the connect timeout, or {@code null}
		 * @param requestTimeout the request timeout, or {@code null}
		 */
		public Server {
			headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
		}

		/**
		 * A server with credentials and no configuration.
		 * @param id the id
		 * @param login the credentials, or {@code null}
		 */
		public Server(String id, @Nullable Login login) {
			this(id, login, Map.of(), null, null);
		}

	}

	/**
	 * A user name and password.
	 *
	 * @param username the user name
	 * @param password the password, decrypted, or {@code null}
	 * @param passwordProblem why an encrypted-looking password could not be decrypted (it
	 * is then sent as written, as Maven sends it), or {@code null}
	 */
	public record Login(String username, @Nullable String password, @Nullable String passwordProblem) {

		/**
		 * Requires the user name.
		 * @param username the user name
		 * @param password the password, or {@code null}
		 * @param passwordProblem the decryption problem, or {@code null}
		 */
		public Login {
			Objects.requireNonNull(username, "username");
		}

		/**
		 * Names the user, never the password.
		 * @return the description
		 */
		@Override
		public String toString() {
			return "Login[username=" + this.username + ", password=****"
					+ (this.passwordProblem == null ? "" : ", passwordProblem=" + this.passwordProblem) + "]";
		}

	}

	/**
	 * Copies the lists.
	 * @param localRepository the local repository, or {@code null}
	 * @param offline the offline flag
	 * @param mirrors the mirrors
	 * @param proxies the proxies
	 * @param servers the servers
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
	 * Reads the settings Maven reads: the global {@code conf/settings.xml} of the Maven
	 * installation the {@code maven.home} system property, else the {@code MAVEN_HOME}
	 * environment variable, names (as {@code clj} finds it; without either, none), and
	 * the user's {@code ~/.m2/settings.xml}, merged as Maven merges them.
	 * @return the settings, {@link #none()} without either file
	 * @throws MavenResolutionException if a file cannot be read or parsed
	 */
	public static MavenSettings readGlobalAndUser() throws MavenResolutionException {
		return readGlobalAndUser(systemProperties(), System.getenv());
	}

	static MavenSettings readGlobalAndUser(Map<String, String> system, Map<String, String> env)
			throws MavenResolutionException {
		SettingsPasswords passwords = SettingsPasswords.of(system);
		Path user = Path.of(system.getOrDefault("user.home", "."), ".m2", "settings.xml");
		Path global = globalSettingsFile(system, env);
		MavenSettings userSettings = Files.isRegularFile(user) ? read(user, system, env, passwords) : none();
		MavenSettings globalSettings = global != null && Files.isRegularFile(global)
				? read(global, system, env, passwords) : none();
		return merge(userSettings, globalSettings);
	}

	/**
	 * The global settings file of the Maven installation {@code maven.home} or
	 * {@code MAVEN_HOME} names.
	 * @return its path, or {@code null} when neither names one
	 */
	static @Nullable Path globalSettingsFile(Map<String, String> system, Map<String, String> env) {
		String home = system.get("maven.home");
		if (home == null || home.isEmpty()) {
			home = env.get("MAVEN_HOME");
		}
		return home == null || home.isEmpty() ? null : Path.of(home, "conf", "settings.xml");
	}

	/**
	 * Maven's merge of the global settings into the user's: the user's local repository,
	 * else the global one; the user's offline flag alone; the user's mirrors, proxies and
	 * servers, then each global one whose id the user's do not use.
	 */
	static MavenSettings merge(MavenSettings user, MavenSettings global) {
		return new MavenSettings(user.localRepository != null ? user.localRepository : global.localRepository,
				user.offline, mergeById(user.mirrors, global.mirrors, Mirror::id),
				mergeById(user.proxies, global.proxies, Proxy::id),
				mergeById(user.servers, global.servers, Server::id));
	}

	private static <T> List<T> mergeById(List<T> dominant, List<T> recessive, Function<T, String> id) {
		Set<String> ids = new HashSet<>();
		for (T entry : dominant) {
			ids.add(id.apply(entry));
		}
		List<T> merged = new ArrayList<>(dominant);
		for (T entry : recessive) {
			if (!ids.contains(id.apply(entry))) {
				merged.add(entry);
			}
		}
		return merged;
	}

	/**
	 * Reads a settings file, expanding {@code ${...}} from the system properties and
	 * {@code ${env.NAME}} from the environment, and decrypting its passwords, as Maven
	 * does.
	 * @param file the settings file
	 * @return its settings
	 * @throws MavenResolutionException if the file cannot be read or parsed
	 */
	public static MavenSettings read(Path file) throws MavenResolutionException {
		Map<String, String> system = systemProperties();
		return read(file, system, System.getenv(), SettingsPasswords.of(system));
	}

	private static MavenSettings read(Path file, Map<String, String> system, Map<String, String> env,
			SettingsPasswords passwords) throws MavenResolutionException {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		}
		catch (IOException ex) {
			throw new MavenResolutionException("cannot read " + file + ": " + ex.getMessage(), ex);
		}
		return parse(bytes, file.toString(), system, env, passwords);
	}

	private static Map<String, String> systemProperties() {
		Map<String, String> system = new HashMap<>();
		for (String name : System.getProperties().stringPropertyNames()) {
			system.put(name, Objects.requireNonNullElse(System.getProperty(name), ""));
		}
		return system;
	}

	static MavenSettings parse(byte[] bytes, String source, Map<String, String> system, Map<String, String> env)
			throws MavenResolutionException {
		return parse(bytes, source, system, env, SettingsPasswords.of(system));
	}

	private static MavenSettings parse(byte[] bytes, String source, Map<String, String> system, Map<String, String> env,
			SettingsPasswords passwords) throws MavenResolutionException {
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
					values.leaf(mirror, "mirrorOf", ""), values.leaf(mirror, "layout", "default"),
					values.leaf(mirror, "mirrorOfLayouts", "default,legacy"),
					Boolean.parseBoolean(values.leaf(mirror, "blocked", "false"))));
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
					values.leaf(proxy, "host", ""), portNumber, values.leaf(proxy, "nonProxyHosts", ""),
					login(values, proxy, passwords)));
		}
		List<Server> servers = new ArrayList<>();
		for (XmlElement server : list(settings, "servers", "server")) {
			String id = values.leaf(server, "id", "default");
			Map<String, String> headers = new LinkedHashMap<>();
			Duration connectTimeout = null;
			Duration requestTimeout = null;
			XmlElement configuration = server.child("configuration");
			if (configuration != null) {
				XmlElement httpHeaders = configuration.child("httpHeaders");
				for (XmlElement property : httpHeaders == null ? List.<XmlElement>of()
						: httpHeaders.children("property")) {
					// Maven's transport skips a property missing its name or value
					String name = values.leaf(property, "name");
					String value = values.leaf(property, "value");
					if (name != null && value != null) {
						headers.put(name, value);
					}
				}
				connectTimeout = timeout(values, source, id, configuration, "connectTimeout", "connectionTimeout");
				requestTimeout = timeout(values, source, id, configuration, "requestTimeout", "readTimeout");
			}
			servers.add(new Server(id, login(values, server, passwords), headers, connectTimeout, requestTimeout));
		}
		return new MavenSettings(
				localRepository == null || localRepository.isEmpty() ? null : Path.of(localRepository).toAbsolutePath(),
				Boolean.parseBoolean(values.leaf(settings, "offline", "false")), mirrors, proxies, servers);
	}

	/** A username and its password, decrypted; none without a username. */
	private static @Nullable Login login(Values values, XmlElement entry, SettingsPasswords passwords)
			throws MavenResolutionException {
		String username = values.leaf(entry, "username");
		if (username == null) {
			return null;
		}
		SettingsPasswords.Decrypted password = passwords.decrypt(values.leaf(entry, "password"));
		return new Login(username, password.value(), password.problem());
	}

	/**
	 * A server's timeout in milliseconds, or Maven 3's legacy
	 * {@code httpConfiguration/all/<legacy>} spelling of it; not a number is refused as
	 * Maven refuses it.
	 */
	private static @Nullable Duration timeout(Values values, String source, String server, XmlElement configuration,
			String name, String legacy) throws MavenResolutionException {
		String text = values.leaf(configuration, name);
		if (text == null) {
			XmlElement http = configuration.child("httpConfiguration");
			XmlElement all = http == null ? null : http.child("all");
			text = all == null ? null : values.leaf(all, legacy);
		}
		if (text == null) {
			return null;
		}
		try {
			return Duration.ofMillis(Integer.parseInt(text));
		}
		catch (NumberFormatException ex) {
			throw new MavenResolutionException(
					source + ": <server> '" + server + "' " + name + " '" + text + "' is not a number", ex);
		}
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
	 * The first {@code <server>} of an id.
	 * @param id the repository (or mirror) id
	 * @return the server, or {@code null}
	 */
	@Nullable Server server(String id) {
		for (Server server : this.servers) {
			if (server.id().equals(id)) {
				return server;
			}
		}
		return null;
	}

	/**
	 * Maven's mirror selection ({@code DefaultMirrorSelector}): an entry naming the
	 * repository's id exactly wins, else the first whose pattern matches -- each only
	 * when its {@code mirrorOfLayouts} admit the repository's {@code default} layout.
	 * @param repository the repository
	 * @return the mirror, or {@code null}
	 */
	@Nullable Mirror mirrorFor(RemoteRepository repository) {
		for (Mirror mirror : this.mirrors) {
			if (mirror.mirrorOf().equals(repository.id()) && matchesLayout(mirror.mirrorOfLayouts())) {
				return mirror;
			}
		}
		for (Mirror mirror : this.mirrors) {
			if (matchesPattern(repository, mirror.mirrorOf()) && matchesLayout(mirror.mirrorOfLayouts())) {
				return mirror;
			}
		}
		return null;
	}

	/** {@code DefaultMirrorSelector.matchesType} for a {@code default} repository. */
	private static boolean matchesLayout(String mirrorOfLayouts) {
		String layout = "default";
		if (mirrorOfLayouts.isEmpty() || mirrorOfLayouts.equals("*") || mirrorOfLayouts.equals(layout)) {
			return true;
		}
		boolean result = false;
		for (String part : mirrorOfLayouts.split(",")) {
			if (part.length() > 1 && part.startsWith("!")) {
				if (part.substring(1).equals(layout)) {
					return false;
				}
			}
			else if (part.equals(layout)) {
				return true;
			}
			else if (part.equals("*")) {
				result = true;
			}
		}
		return result;
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
