package am.ik.servlet.httpserver;

import jakarta.servlet.Registration;

/** What the options of a builder's registrations share. */
final class Registrations {

	private Registrations() {
	}

	/** A registration's name: not empty. */
	static String named(String name) {
		if (name.isEmpty()) {
			throw new IllegalArgumentException("a registration has a name");
		}
		return name;
	}

	/** Refuses an option given after its registration's lambda returned. */
	static void open(boolean given) {
		if (given) {
			throw new IllegalStateException("the options of a registration are given inside its lambda");
		}
	}

	/** Gives a registration an initialization parameter, a name once. */
	static void initParameter(Registration.Dynamic registration, String name, String value) {
		if (!registration.setInitParameter(name, value)) {
			throw new IllegalArgumentException(
					"the initialization parameter " + name + " of " + registration.getName() + " is given twice");
		}
	}

}
