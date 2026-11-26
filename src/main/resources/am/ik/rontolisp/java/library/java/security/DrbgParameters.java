// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.util.Arrays;
import java.util.Locale;

// The parameters of a DRBG (NIST SP 800-90A): those it is made with, those of a draw and
// those of a reseed. A strength is a number of bits, -1 for the generator's own; the
// byte arrays are copied in and out.
public final class DrbgParameters {

    private DrbgParameters() {
    }

    public enum Capability {
        PR_AND_RESEED, RESEED_ONLY, NONE;

        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean supportsReseeding() {
            return this != NONE;
        }

        public boolean supportsPredictionResistance() {
            return this == PR_AND_RESEED;
        }
    }

    public static Instantiation instantiation(int requestedStrength, Capability capability,
            byte[] personalizationString) {
        if (capability == null) {
            throw new NullPointerException();
        }
        return new Instantiation(strength(requestedStrength), capability, copy(personalizationString));
    }

    public static NextBytes nextBytes(int requestedStrength, boolean predictionResistance, byte[] additionalInput) {
        return new NextBytes(strength(requestedStrength), predictionResistance, copy(additionalInput));
    }

    public static Reseed reseed(boolean predictionResistance, byte[] additionalInput) {
        return new Reseed(predictionResistance, copy(additionalInput));
    }

    private static int strength(int requested) {
        if (requested < -1) {
            throw new IllegalArgumentException("Illegal security strength: " + requested);
        }
        return requested;
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }

    public static final class Instantiation implements SecureRandomParameters {

        private final int strength;

        private final Capability capability;

        private final byte[] personalizationString;

        private Instantiation(int strength, Capability capability, byte[] personalizationString) {
            this.strength = strength;
            this.capability = capability;
            this.personalizationString = personalizationString;
        }

        public int getStrength() {
            return strength;
        }

        public Capability getCapability() {
            return capability;
        }

        public byte[] getPersonalizationString() {
            return copy(personalizationString);
        }

        // the strength, the capability and the personalization string's bytes in decimal
        public String toString() {
            return strength + "," + capability + "," + Arrays.toString(personalizationString);
        }
    }

    public static final class NextBytes implements SecureRandomParameters {

        private final int strength;

        private final boolean predictionResistance;

        private final byte[] additionalInput;

        private NextBytes(int strength, boolean predictionResistance, byte[] additionalInput) {
            this.strength = strength;
            this.predictionResistance = predictionResistance;
            this.additionalInput = additionalInput;
        }

        public int getStrength() {
            return strength;
        }

        public boolean getPredictionResistance() {
            return predictionResistance;
        }

        public byte[] getAdditionalInput() {
            return copy(additionalInput);
        }
    }

    public static final class Reseed implements SecureRandomParameters {

        private final boolean predictionResistance;

        private final byte[] additionalInput;

        private Reseed(boolean predictionResistance, byte[] additionalInput) {
            this.predictionResistance = predictionResistance;
            this.additionalInput = additionalInput;
        }

        public boolean getPredictionResistance() {
            return predictionResistance;
        }

        public byte[] getAdditionalInput() {
            return copy(additionalInput);
        }
    }
}
