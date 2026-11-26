package am.ik.jdbc.spin.postgres;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A connection URL as the address the host is asked to open.
 */
class AddressTest {

	private static Properties login() {
		Properties info = new Properties();
		info.setProperty("user", "alice");
		info.setProperty("password", "p:w@d/1");
		return info;
	}

	@Test
	void aUrlIsTheHostsAddress() throws SQLException {
		Address address = Address.parse("jdbc:postgresql://db.example:5433/shop", login());
		assertThat(address).isNotNull();
		assertThat(address.host).isEqualTo("postgres://alice:p%3Aw%40d%2F1@db.example:5433/shop");
		assertThat(address.database).isEqualTo("shop");
		assertThat(address.user).isEqualTo("alice");
		assertThat(address.rootCertificate).isNull();
		// The defaults: the server here, its usual port, the database named as the user.
		assertThat(Address.parse("jdbc:postgresql:/", login()).host)
			.isEqualTo("postgres://alice:p%3Aw%40d%2F1@localhost:5432/alice");
		assertThat(Address.parse("jdbc:postgresql:shop", login()).host)
			.isEqualTo("postgres://alice:p%3Aw%40d%2F1@localhost:5432/shop");
		assertThat(Address.parse("jdbc:postgresql://[::1]/shop", login()).host)
			.isEqualTo("postgres://alice:p%3Aw%40d%2F1@[::1]:5432/shop");
		// A setting of the URL wins over the properties, and a name outside ASCII is
		// percent-encoded UTF-8.
		assertThat(Address.parse("jdbc:postgresql://db/%E5%BA%97?user=bob&password=", login()).host)
			.isEqualTo("postgres://bob@db:5432/%E5%BA%97");
		assertThat(Address.parse("jdbc:mysql://db/shop", login())).isNull();
	}

	@Test
	void theSettingsTheHostKnowsGoUnderItsNames() throws SQLException {
		assertThat(Address.parse(
				"jdbc:postgresql://db/shop?sslmode=require&ApplicationName=my+app&options=-c%20statement_timeout%3D5s"
						+ "&connectTimeout=3&socketTimeout=9&unknown=x",
				login()).host)
			.isEqualTo("postgres://alice:p%3Aw%40d%2F1@db:5432/shop?sslmode=require&application_name=my%20app"
					+ "&options=-c%20statement_timeout%3D5s&connect_timeout=3");
		// The host verifies the certificate and the name whenever it speaks TLS: the
		// verifying modes are its require.
		for (String mode : new String[] { "verify-full", "verify-ca", "require" }) {
			assertThat(Address.parse("jdbc:postgresql://db/shop?sslmode=" + mode, login()).host)
				.endsWith("/shop?sslmode=require");
		}
		assertThat(Address.parse("jdbc:postgresql://db/shop?ssl=true", login()).host).endsWith("?sslmode=require");
		assertThat(Address.parse("jdbc:postgresql://db/shop?sslmode=disable", login()).host)
			.endsWith("?sslmode=disable");
		assertThatThrownBy(() -> Address.parse("jdbc:postgresql://db/shop?sslmode=allow", login()))
			.isInstanceOf(SQLFeatureNotSupportedException.class)
			.hasMessageContaining("sslmode=allow");
		// A certificate is its PEM text, kept apart from the address.
		Properties info = login();
		info.setProperty("sslrootcert", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
		Address address = Address.parse("jdbc:postgresql://db/shop?sslmode=require", info);
		assertThat(address.rootCertificate).startsWith("-----BEGIN CERTIFICATE-----");
		assertThat(address.host).doesNotContain("BEGIN");
		assertThatThrownBy(() -> Address.parse("jdbc:postgresql://db/shop?sslrootcert=/etc/ca.crt", login()))
			.isInstanceOf(SQLFeatureNotSupportedException.class)
			.hasMessageContaining("PEM");
		assertThatThrownBy(() -> Address.parse("jdbc:postgresql://a,b/shop", login()))
			.isInstanceOf(SQLFeatureNotSupportedException.class);
	}

}
