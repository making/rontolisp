// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.util.Random;
import rontolisp.internal.Hashing;
import rontolisp.internal.InstalledProvider;

// Every bit a SecureRandom gives is the host's entropy, drawn when asked
// (rontolisp:random-bytes: SecureRandom of the JVM on the interpreter and the JVM, WASI's
// random_get in a .wasm), whatever the algorithm's name. A seed is taken and adds
// nothing: no SecureRandom repeats another's draws.
//
// A DRBG keeps the parameters it is made with -- a strength of 112, 128, 192 or 256 bits
// and whether it resists prediction -- and holds a draw or a reseed to them; the others
// take no parameters.
public class SecureRandom extends Random {

    private final String algorithm;

    public SecureRandom() {
        this.algorithm = "NativePRNG";
    }

    public SecureRandom(byte[] seed) {
        this.algorithm = "NativePRNG";
        setSeed(seed);
    }

    private SecureRandom(String algorithm, boolean named) {
        this.algorithm = algorithm;
    }

    public static SecureRandom getInstance(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        if (kind(algorithm) == null) {
            throw new NoSuchAlgorithmException(algorithm + " SecureRandom not available");
        }
        return new SecureRandom(algorithm, true);
    }

    public static SecureRandom getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        rontolisp.internal.Hashing.provided(provider);
        if (kind(algorithm) == null || !provider.equals("SUN")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider);
        }
        return new SecureRandom(algorithm, true);
    }

    public static SecureRandom getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        Hashing.provided(provider, "SecureRandom", algorithm);
        if (kind(algorithm) == null || !provider.getName().equals("SUN")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        return new SecureRandom(algorithm, true);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters params)
            throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        if (kind(algorithm) == null) {
            throw new NoSuchAlgorithmException(algorithm + " SecureRandom not available");
        }
        return instantiated(algorithm, params);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters params, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        Hashing.provided(provider);
        if (kind(algorithm) == null || !provider.equals("SUN")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider);
        }
        return instantiated(algorithm, params);
    }

    public static SecureRandom getInstance(String algorithm, SecureRandomParameters params, Provider provider)
            throws NoSuchAlgorithmException {
        if (algorithm == null) {
            throw new NullPointerException("null algorithm name");
        }
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        Hashing.provided(provider, "SecureRandom", algorithm);
        if (kind(algorithm) == null || !provider.getName().equals("SUN")) {
            throw new NoSuchAlgorithmException("no such algorithm: " + algorithm + " for provider " + provider.getName());
        }
        return instantiated(algorithm, params);
    }

    // A generator of a name SUN has, made with parameters: a DRBG of a DRBG's, its
    // strength the least of 112, 128, 192 and 256 that is no less than the one asked for
    // (128 for -1). The refusal names the class of the subset that answers the name; an
    // object's class is no interface, so its text is "class " and its name.
    private static SecureRandom instantiated(String algorithm, SecureRandomParameters params)
            throws NoSuchAlgorithmException {
        String standard = standard(algorithm);
        IllegalArgumentException refused;
        if (!standard.equals("DRBG") || !(params instanceof DrbgParameters.Instantiation)) {
            refused = new IllegalArgumentException("Unsupported params: class " + params.getClass().getName());
        } else {
            DrbgParameters.Instantiation asked = (DrbgParameters.Instantiation) params;
            int requested = asked.getStrength();
            if (requested <= 256) {
                return new Drbg(algorithm,
                        requested == -1 ? 128 : requested <= 112 ? 112 : requested <= 128 ? 128 : requested <= 192 ? 192
                                : 256,
                        asked.getCapability() == DrbgParameters.Capability.PR_AND_RESEED, asked.getPersonalizationString());
            }
            refused = new IllegalArgumentException("strength too big: " + requested);
        }
        throw new NoSuchAlgorithmException("Error constructing implementation (algorithm: " + standard
                + ", provider: SUN, class: java.security.SecureRandom)", refused);
    }

    public static SecureRandom getInstanceStrong() throws NoSuchAlgorithmException {
        return new SecureRandom("NativePRNGBlocking", true);
    }

    // The standard name of a generator of a name SUN has.
    private static String standard(String algorithm) {
        switch (algorithm.toUpperCase()) {
            case "NATIVEPRNG":
                return "NativePRNG";
            case "NATIVEPRNGBLOCKING":
                return "NativePRNGBlocking";
            case "NATIVEPRNGNONBLOCKING":
                return "NativePRNGNonBlocking";
            case "SHA1PRNG":
                return "SHA1PRNG";
            default:
                return "DRBG";
        }
    }

    // The text a JDK's generator of a name has, null for a name of none.
    private static String kind(String algorithm) {
        switch (algorithm.toUpperCase()) {
            case "NATIVEPRNG":
                return "NativePRNG";
            case "NATIVEPRNGBLOCKING":
                return "Blocking";
            case "NATIVEPRNGNONBLOCKING":
                return "NonBlocking";
            case "SHA1PRNG":
                return "SecureRandom";
            case "DRBG":
                return "Hash_DRBG,SHA-256,128,reseed_only";
            default:
                return null;
        }
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String toString() {
        return kind(algorithm);
    }

    public Provider getProvider() {
        return InstalledProvider.named("SUN");
    }

    // A DRBG's parameters, a new object each time; none for any other generator.
    public SecureRandomParameters getParameters() {
        if (!algorithm.equalsIgnoreCase("DRBG")) {
            return null;
        }
        Drbg made = this instanceof Drbg ? (Drbg) this : null;
        return DrbgParameters.instantiation(made == null ? 128 : made.strength,
                made != null && made.predictive ? DrbgParameters.Capability.PR_AND_RESEED
                        : DrbgParameters.Capability.RESEED_ONLY,
                made == null ? null : made.personalization);
    }

    public void setSeed(byte[] seed) {
        if (seed == null) {
            throw new NullPointerException();
        }
    }

    // Random's constructor sets a seed too, before this class's fields are.
    public void setSeed(long seed) {
    }

    public void nextBytes(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException();
        }
        fill(bytes);
    }

    protected final int next(int numBits) {
        return numBits <= 0 ? 0 : bits() >>> (32 - numBits);
    }

    // Only a DRBG reseeds, and every draw is fresh entropy already; the others refuse.
    public void reseed() {
        if (!algorithm.equalsIgnoreCase("DRBG")) {
            throw new UnsupportedOperationException();
        }
    }

    // A draw a DRBG holds to its strength and its resistance to prediction.
    public void nextBytes(byte[] bytes, SecureRandomParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        if (bytes == null) {
            throw new NullPointerException();
        }
        if (!algorithm.equalsIgnoreCase("DRBG")) {
            throw new UnsupportedOperationException();
        }
        if (!(params instanceof DrbgParameters.NextBytes)) {
            throw new IllegalArgumentException("unknown params type:class " + params.getClass().getName());
        }
        DrbgParameters.NextBytes asked = (DrbgParameters.NextBytes) params;
        Drbg made = this instanceof Drbg ? (Drbg) this : null;
        if (asked.getStrength() > (made == null ? 128 : made.strength)) {
            throw new IllegalArgumentException("strength too high: " + asked.getStrength());
        }
        if (asked.getPredictionResistance() && (made == null || !made.predictive)) {
            throw new IllegalArgumentException("pr not available");
        }
        fill(bytes);
    }

    // A reseed a DRBG holds to its resistance to prediction.
    public void reseed(SecureRandomParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("params cannot be null");
        }
        if (!algorithm.equalsIgnoreCase("DRBG")) {
            throw new UnsupportedOperationException();
        }
        if (!(params instanceof DrbgParameters.Reseed)) {
            throw new IllegalArgumentException("unknown params type: class " + params.getClass().getName());
        }
        if (((DrbgParameters.Reseed) params).getPredictionResistance()
                && !(this instanceof Drbg && ((Drbg) this).predictive)) {
            throw new IllegalArgumentException("pr not available");
        }
    }

    public byte[] generateSeed(int numBytes) {
        return getSeed(numBytes);
    }

    public static byte[] getSeed(int numBytes) {
        if (numBytes < 0) {
            throw new IllegalArgumentException("numBytes cannot be negative");
        }
        byte[] seed = new byte[numBytes];
        fill(seed);
        return seed;
    }

    // The host's entropy into every element.
    private static native void fill(byte[] bytes);

    // Thirty-two bits of the host's entropy.
    private static native int bits();

    // A DRBG made with parameters: its strength in bits, whether it resists prediction
    // and its personalization string. One made without has 128 bits and resists none.
    private static final class Drbg extends SecureRandom {

        private final int strength;

        private final boolean predictive;

        private final byte[] personalization;

        private Drbg(String algorithm, int strength, boolean predictive, byte[] personalization) {
            super(algorithm, true);
            this.strength = strength;
            this.predictive = predictive;
            this.personalization = personalization;
        }

        public String toString() {
            return "Hash_DRBG,SHA-256," + strength + "," + (predictive ? "pr_and_reseed" : "reseed_only");
        }
    }
}
