// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import rontolisp.internal.InstalledProvider;

// The providers are the ones a JDK installs, by its names and in its order; a program
// installs none of its own.
public final class Security {

    private Security() {
    }

    // The provider of a name as a JDK spells it, null for none.
    public static Provider getProvider(String name) {
        return name == null ? null : InstalledProvider.named(name);
    }

    public static Provider[] getProviders() {
        return InstalledProvider.all();
    }

    // The algorithms of the services of a type, whatever its case, in upper case: a set
    // no one changes.
    public static Set<String> getAlgorithms(String serviceName) {
        Set<String> algorithms = new HashSet<>();
        if (serviceName != null) {
            for (Provider provider : getProviders()) {
                for (Provider.Service service : provider.getServices()) {
                    if (service.getType().equalsIgnoreCase(serviceName)) {
                        algorithms.add(service.getAlgorithm().toUpperCase(Locale.ENGLISH));
                    }
                }
            }
        }
        return Collections.unmodifiableSet(algorithms);
    }
}
