package am.ik.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a {@code settings.xml} contributes, read as Maven 3.9 reads it: the local
 * repository and offline mode, mirrors and proxies matched the way Maven matches them,
 * servers' credentials (passwords decrypted as Maven encrypted them) and configuration,
 * and the global file merged under the user's.
 */
class MavenSettingsTest {

	/**
	 * What {@code mvn --encrypt-master-password master-secret} printed (Maven 3.9.16).
	 */
	private static final String MASTER = "{sadyJDGuegUH7D0PIiLePq/sLyDEvwVZJg0IjIowY4c=}";

	/**
	 * What {@code mvn --encrypt-password 's3cr3t pässword'} printed under {@link #MASTER}
	 * (Maven 3.9.16).
	 */
	private static final String ENCRYPTED = "{NvW/lM/fZd4HVRMYz3XsMWz+eN9LfMhsffO63XDAquvuZdex1JBbmv6iMryfsQO4}";

	@TempDir
	Path temp;

	private static final RemoteRepository CENTRAL = RemoteRepository.CENTRAL;

	private static final RemoteRepository CLOJARS = RemoteRepository.CLOJARS;

	private static final RemoteRepository LOCAL_FILE = new RemoteRepository("disk", "file:///srv/repo/");

	private static final RemoteRepository LOCAL_HOST = new RemoteRepository("lan", "https://localhost:8443/repo/");

	private static final RemoteRepository PLAIN_HTTP = new RemoteRepository("legacy", "http://old.example/repo/");

	private static MavenSettings parse(String xml) throws MavenResolutionException {
		return MavenSettings.parse(xml.getBytes(StandardCharsets.UTF_8), "settings.xml",
				Map.of("user.home", "/home/dev"), Map.of("REPO_ROOT", "/data"));
	}

	private static MavenSettings mirrors(String mirrorOf) {
		return new MavenSettings(null, false,
				List.of(new MavenSettings.Mirror("m", "https://mirror.example/", mirrorOf)), List.of(), List.of());
	}

	@Test
	void readsTheEntriesThatBearOnResolution() throws MavenResolutionException {
		MavenSettings settings = parse(
				"""
						<settings>
						  <localRepository>${user.home}/maven-cache</localRepository>
						  <offline>true</offline>
						  <mirrors><mirror><id>nexus</id><url>https://nexus.example/</url><mirrorOf>*</mirrorOf></mirror></mirrors>
						  <proxies><proxy><id>office</id><host>proxy.example</host></proxy></proxies>
						  <servers><server><id>clojars</id><username>me</username></server></servers>
						  <profiles/>
						</settings>
						""");

		assertThat(settings.localRepository()).isEqualTo(Path.of("/home/dev/maven-cache"));
		assertThat(settings.offline()).isTrue();
		assertThat(settings.mirrors())
			.containsExactly(new MavenSettings.Mirror("nexus", "https://nexus.example/", "*"));
		assertThat(settings.proxies())
			.containsExactly(new MavenSettings.Proxy("office", true, "http", "proxy.example", 8080, ""));
		assertThat(settings.servers())
			.containsExactly(new MavenSettings.Server("clojars", new MavenSettings.Login("me", null, null)));
		assertThat(
				parse("<settings><localRepository>${env.REPO_ROOT}/m2</localRepository></settings>").localRepository())
			.isEqualTo(Path.of("/data/m2"));
	}

	@Test
	void anUnreadableFileIsRefused() {
		assertThatThrownBy(() -> parse("<settings><mirrors></settings>")).isInstanceOf(MavenResolutionException.class)
			.hasMessageStartingWith("settings.xml is not well-formed: end tag name </settings> must match");
	}

	@Test
	void mirrorOfMatchesAsMavenMatchesIt() {
		assertThat(mirrors("*").mirrorFor(LOCAL_FILE)).isNotNull();
		assertThat(mirrors("central").mirrorFor(CENTRAL)).isNotNull();
		assertThat(mirrors("central").mirrorFor(CLOJARS)).isNull();
		assertThat(mirrors("central,clojars").mirrorFor(CLOJARS)).isNotNull();
		assertThat(mirrors("*,!central").mirrorFor(CENTRAL)).isNull();
		assertThat(mirrors("*,!central").mirrorFor(CLOJARS)).isNotNull();
		assertThat(mirrors("external:*").mirrorFor(CENTRAL)).isNotNull();
		assertThat(mirrors("external:*").mirrorFor(LOCAL_FILE)).isNull();
		assertThat(mirrors("external:*").mirrorFor(LOCAL_HOST)).isNull();
		assertThat(mirrors("external:http:*").mirrorFor(PLAIN_HTTP)).isNotNull();
		assertThat(mirrors("external:http:*").mirrorFor(CENTRAL)).isNull();
	}

	@Test
	void anExactMirrorOfWinsOverAPattern() {
		MavenSettings settings = new MavenSettings(null, false,
				List.of(new MavenSettings.Mirror("all", "https://all.example/", "*"),
						new MavenSettings.Mirror("only-clojars", "https://clojars-mirror.example/", "clojars")),
				List.of(), List.of());

		assertThat(settings.mirrorFor(CLOJARS)).extracting(MavenSettings.Mirror::id).isEqualTo("only-clojars");
		assertThat(settings.mirrorFor(CENTRAL)).extracting(MavenSettings.Mirror::id).isEqualTo("all");
	}

	@Test
	void aProxyServesItsProtocolAndAnHttpOneServesHttpsToo() {
		MavenSettings http = proxies(new MavenSettings.Proxy("p", true, "http", "proxy.example", 3128, ""));
		MavenSettings inactive = proxies(new MavenSettings.Proxy("p", false, "http", "proxy.example", 3128, ""));
		MavenSettings bypassed = proxies(
				new MavenSettings.Proxy("p", true, "https", "proxy.example", 3128, "*.maven.org|localhost"));

		assertThat(http.proxyFor(CENTRAL)).isNotNull();
		assertThat(http.proxyFor(LOCAL_FILE)).isNull();
		assertThat(inactive.proxyFor(CENTRAL)).isNull();
		assertThat(bypassed.proxyFor(CENTRAL)).isNull();
		assertThat(bypassed.proxyFor(CLOJARS)).isNotNull();
		assertThat(bypassed.proxyFor(PLAIN_HTTP)).isNull();
	}

	private static MavenSettings proxies(MavenSettings.Proxy proxy) {
		return new MavenSettings(null, false, List.of(), List.of(proxy), List.of());
	}

	@Test
	void readsMirrorsProxiesAndServersAsMavensTransportUsesThem() throws MavenResolutionException {
		MavenSettings settings = parse("""
				<settings>
				  <mirrors><mirror><id>blocker</id><url>http://0.0.0.0/</url><mirrorOf>external:http:*</mirrorOf>
				    <blocked>true</blocked><mirrorOfLayouts>default</mirrorOfLayouts></mirror></mirrors>
				  <proxies><proxy><id>office</id><host>proxy.example</host><port>3128</port>
				    <username>pu</username><password>pp</password></proxy></proxies>
				  <servers>
				    <server><id>nexus</id><username>deployer</username><password>${env.REPO_ROOT}</password>
				      <configuration>
				        <httpHeaders>
				          <property><name>X-Token</name><value>t1</value></property>
				          <property><name>X-Nameless</name></property>
				          <property><name>X-Second</name><value>t2</value></property>
				        </httpHeaders>
				        <connectTimeout>2000</connectTimeout>
				        <httpConfiguration><all><readTimeout>9000</readTimeout></all></httpConfiguration>
				      </configuration>
				    </server>
				    <server><id>anonymous</id><password>never-sent-alone</password></server>
				  </servers>
				</settings>
				""");

		assertThat(settings.mirrors()).containsExactly(
				new MavenSettings.Mirror("blocker", "http://0.0.0.0/", "external:http:*", "default", "default", true));
		assertThat(settings.proxies()).containsExactly(new MavenSettings.Proxy("office", true, "http", "proxy.example",
				3128, "", new MavenSettings.Login("pu", "pp", null)));
		assertThat(settings.servers()).containsExactly(
				new MavenSettings.Server("nexus", new MavenSettings.Login("deployer", "/data", null),
						Map.of("X-Token", "t1", "X-Second", "t2"), Duration.ofMillis(2000), Duration.ofMillis(9000)),
				new MavenSettings.Server("anonymous", null));
		assertThat(settings.servers().get(0).headers()).containsOnlyKeys("X-Token", "X-Second");
		assertThat(String.valueOf(settings.servers().get(0).login())).doesNotContain("/data");
		assertThatThrownBy(() -> parse("""
				<settings><servers><server><id>s</id><configuration><requestTimeout>soon</requestTimeout>
				</configuration></server></servers></settings>""")).isInstanceOf(MavenResolutionException.class)
			.hasMessage("settings.xml: <server> 's' requestTimeout 'soon' is not a number");
	}

	@Test
	void anEncryptedPasswordIsDecryptedWithTheMasterPasswordAsMavenEncryptedIt()
			throws IOException, MavenResolutionException, GeneralSecurityException {
		Path security = this.temp.resolve("settings-security.xml");
		Files.writeString(security, "<settingsSecurity><master>" + MASTER + "</master></settingsSecurity>");
		String xml = "<settings><servers><server><id>s</id><username>u</username><password>rotated 2026-10 " + ENCRYPTED
				+ "</password></server></servers></settings>";

		assertThat(login(xml, Map.of("settings.security", security.toString())))
			.isEqualTo(new MavenSettings.Login("u", "s3cr3t pässword", null));
		// the default location, under the user's home, relocated from there
		Path m2 = Files.createDirectories(this.temp.resolve("home/.m2"));
		Files.writeString(m2.resolve("settings-security.xml"),
				"<settingsSecurity><relocation>" + security + "</relocation></settingsSecurity>");
		assertThat(login(xml, Map.of("user.home", this.temp.resolve("home").toString())).password())
			.isEqualTo("s3cr3t pässword");
		assertThat(SettingsPasswords.decrypt64(MASTER.substring(1, MASTER.length() - 1), "settings.security"))
			.isEqualTo("master-secret");
	}

	@Test
	void aPasswordThatCannotBeDecryptedIsKeptAsWrittenWithTheReason() throws IOException, MavenResolutionException {
		String xml = "<settings><servers><server><id>s</id><username>u</username><password>" + ENCRYPTED
				+ "</password></server></servers></settings>";
		Path missing = this.temp.resolve("absent.xml");
		Path wrongMaster = this.temp.resolve("wrong.xml");
		Files.writeString(wrongMaster,
				"<settingsSecurity><master>{QUJDREVGR0hJSktMTU5PUFFSU1RVVldY}</master></settingsSecurity>");

		MavenSettings.Login withoutMaster = login(xml, Map.of("settings.security", missing.toString()));
		assertThat(withoutMaster.password()).isEqualTo(ENCRYPTED);
		assertThat(withoutMaster.passwordProblem())
			.isEqualTo("cannot retrieve master password: cannot read " + missing);
		assertThat(login(xml, Map.of("settings.security", wrongMaster.toString())).passwordProblem()).isNotNull();
		// braces around something make a value look encrypted; anything else is itself
		assertThat(login(xml.replace(ENCRYPTED, "plain{}text"), Map.of("settings.security", missing.toString())))
			.isEqualTo(new MavenSettings.Login("u", "plain{}text", null));
	}

	private static MavenSettings.Login login(String xml, Map<String, String> system) throws MavenResolutionException {
		MavenSettings settings = MavenSettings.parse(xml.getBytes(StandardCharsets.UTF_8), "settings.xml", system,
				Map.of());
		return Objects.requireNonNull(settings.servers().get(0).login());
	}

	@Test
	void theGlobalSettingsMergeUnderTheUsersAsMavenMergesThem() throws IOException, MavenResolutionException {
		Path m2 = Files.createDirectories(this.temp.resolve("home/.m2"));
		Path conf = Files.createDirectories(this.temp.resolve("maven/conf"));
		Files.writeString(m2.resolve("settings.xml"),
				"""
						<settings>
						  <mirrors><mirror><id>nexus</id><url>https://user.example/</url><mirrorOf>*</mirrorOf></mirror></mirrors>
						  <servers><server><id>nexus</id><username>me</username></server></servers>
						</settings>
						""");
		Files.writeString(conf.resolve("settings.xml"), """
				<settings>
				  <localRepository>/global/repo</localRepository>
				  <offline>true</offline>
				  <mirrors>
				    <mirror><id>nexus</id><url>https://global.example/</url><mirrorOf>*</mirrorOf></mirror>
				    <mirror><id>maven-default-http-blocker</id><url>http://0.0.0.0/</url>
				      <mirrorOf>external:http:*</mirrorOf><blocked>true</blocked></mirror>
				  </mirrors>
				  <proxies><proxy><id>office</id><host>proxy.example</host></proxy></proxies>
				</settings>
				""");
		Map<String, String> system = new HashMap<>(Map.of("user.home", this.temp.resolve("home").toString()));
		Map<String, String> env = Map.of("MAVEN_HOME", this.temp.resolve("maven").toString());

		MavenSettings merged = MavenSettings.readGlobalAndUser(system, env);

		assertThat(merged.localRepository()).isEqualTo(Path.of("/global/repo"));
		assertThat(merged.offline()).as("the global offline flag is not merged").isFalse();
		assertThat(merged.mirrors()).extracting(MavenSettings.Mirror::url)
			.containsExactly("https://user.example/", "http://0.0.0.0/");
		assertThat(merged.proxies()).extracting(MavenSettings.Proxy::id).containsExactly("office");
		assertThat(merged.servers()).extracting(MavenSettings.Server::id).containsExactly("nexus");
		// maven.home wins over MAVEN_HOME; without either there is no global file
		system.put("maven.home", this.temp.resolve("nowhere").toString());
		assertThat(MavenSettings.readGlobalAndUser(system, env).proxies()).isEmpty();
		assertThat(MavenSettings.globalSettingsFile(Map.of(), Map.of())).isNull();
	}

	@Test
	void mirrorOfLayoutsMustAdmitTheDefaultLayout() {
		assertThat(layouts("legacy").mirrorFor(CENTRAL)).isNull();
		assertThat(layouts("*,!default").mirrorFor(CENTRAL)).isNull();
		assertThat(layouts("legacy,*").mirrorFor(CENTRAL)).isNotNull();
		assertThat(layouts("").mirrorFor(CENTRAL)).isNotNull();
	}

	private static MavenSettings layouts(String mirrorOfLayouts) {
		return new MavenSettings(null, false, List
			.of(new MavenSettings.Mirror("m", "https://mirror.example/", "central", "default", mirrorOfLayouts, false)),
				List.of(), List.of());
	}

}
