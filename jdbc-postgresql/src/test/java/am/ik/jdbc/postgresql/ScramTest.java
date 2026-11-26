package am.ik.jdbc.postgresql;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScramTest {

	// The exchange of RFC 7677, section 3.
	private static final String NONCE = "rOprNGfwEbeRWgbNEkqO";

	private static final String SERVER_FIRST = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
			+ "s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";

	@Test
	void theExchangeOfRfc7677() throws Exception {
		Scram scram = new Scram("user", "pencil", NONCE);
		assertThat(scram.clientFirst()).isEqualTo("n,,n=user,r=rOprNGfwEbeRWgbNEkqO");
		assertThat(scram.clientFinal(SERVER_FIRST))
			.isEqualTo("c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
					+ "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=");
		scram.verify("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=");
	}

	@Test
	void aServerThatDoesNotHoldThePasswordIsRefused() throws Exception {
		Scram scram = new Scram("user", "pencil", NONCE);
		scram.clientFinal(SERVER_FIRST);
		assertThatThrownBy(() -> scram.verify("v=7rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="))
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("signature");
		assertThatThrownBy(() -> scram.verify("e=invalid-proof")).hasMessageContaining("invalid-proof");
		// A nonce that does not extend the client's, and a message with no salt.
		assertThatThrownBy(() -> new Scram("", "pencil", NONCE).clientFinal("r=other,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1"))
			.hasMessageContaining("nonce");
		assertThatThrownBy(() -> new Scram("", "pencil", NONCE).clientFinal("r=" + NONCE + "x,i=4096"))
			.hasMessageContaining("salt");
	}

	@Test
	void aPasswordIsPreparedAsRfc4013Says() {
		// The examples of RFC 4013, section 3; a password the profile prohibits is used
		// as given.
		assertThat(SaslPrep.prepare("I­X")).isEqualTo("IX");
		assertThat(SaslPrep.prepare("user")).isEqualTo("user");
		assertThat(SaslPrep.prepare("USER")).isEqualTo("USER");
		assertThat(SaslPrep.prepare("ª")).isEqualTo("a");
		assertThat(SaslPrep.prepare("Ⅸ")).isEqualTo("IX");
		assertThat(SaslPrep.prepare("\u0007")).isEqualTo("\u0007");
		assertThat(SaslPrep.prepare("ا1")).isEqualTo("ا1");
		// A space outside ASCII is a space; right to left throughout is allowed; a code
		// point Unicode 3.2 leaves unassigned keeps the password as given, whatever else
		// it holds.
		assertThat(SaslPrep.prepare("a　ｂ")).isEqualTo("a b");
		assertThat(SaslPrep.prepare("اب")).isEqualTo("اب");
		assertThat(SaslPrep.prepare("ｂ🍵")).isEqualTo("ｂ🍵");
	}

}
