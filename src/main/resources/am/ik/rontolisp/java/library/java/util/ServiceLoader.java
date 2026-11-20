// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

// The providers of a service the files META-INF/services/<the service's binary name>
// name, which the lowering reads where a program calls load with a class literal: the
// files of the loader's resources in order, a name once, each name's class found then
// and made here (.kb/java-frontend.md, "Resources"). What a JDK 25 answers decides where
// a provider fails: an iterator's next for every failure, a stream's advance for a class
// not found, of another type or with no public constructor of no parameters, a
// provider's get for one that cannot be made. The iterator's
// instances and the stream's providers are kept apart, each in the order they were
// found, and each goes on from where its lookup stopped.
public final class ServiceLoader<S> implements Iterable<S> {

    public interface Provider<S> extends Supplier<S> {

        Class<? extends S> type();

        S get();
    }

    // what the lowering found of a provider named in a file
    static final int FOUND = 0;

    static final int NOT_FOUND = 1;

    static final int NOT_SUBTYPE = 2;

    static final int NO_CONSTRUCTOR = 3;

    static final int ABSTRACT = 4;

    static final int INACCESSIBLE = 5;

    private static final class Named {

        final String name;

        final int outcome;

        final Supplier<?> maker;

        final Supplier<?> type;

        Named(String name, int outcome, Supplier<?> maker, Supplier<?> type) {
            this.name = name;
            this.outcome = outcome;
            this.maker = maker;
            this.type = type;
        }
    }

    private final Class<S> service;

    private final ArrayList<Named> named = new ArrayList<>();

    // the iterator's: the instances made, and how far its lookup went
    private final ArrayList<S> instances = new ArrayList<>();

    private int instanced;

    private Named pending;

    // the stream's: the providers found, and how far its lookup went
    private final ArrayList<Provider<S>> providers = new ArrayList<>();

    private int provided;

    private ServiceLoader(Class<S> service) {
        this.service = service;
    }

    public static native <S> ServiceLoader<S> load(Class<S> service);

    public static native <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader);

    public static native <S> ServiceLoader<S> loadInstalled(Class<S> service);

    // what the lowering makes of the calls above: a loader, then each provider it finds
    static <S> ServiceLoader<S> made(Class<S> service) {
        return new ServiceLoader<>(Objects.requireNonNull(service));
    }

    static <S> ServiceLoader<S> with(ServiceLoader<S> loader, String name, int outcome, Supplier<?> maker,
            Supplier<?> type) {
        loader.named.add(new Named(name, outcome, maker, type));
        return loader;
    }

    private ServiceConfigurationError failed(String message, Throwable cause) {
        return new ServiceConfigurationError(service.getName() + ": " + message, cause);
    }

    // a provider whose class there is, of the service's type
    private Named found(Named found) {
        if (found.outcome == NOT_FOUND) {
            throw new ServiceConfigurationError(service.getName() + ": Provider " + found.name + " not found");
        }
        if (found.outcome == NOT_SUBTYPE) {
            throw new ServiceConfigurationError(service.getName() + ": " + found.name + " not a subtype");
        }
        return found;
    }

    // its public constructor of no parameters
    private void constructed(Named found) {
        if (found.outcome == NO_CONSTRUCTOR) {
            throw failed(found.name + " Unable to get public no-arg constructor",
                    new NoSuchMethodException(found.name + ".<init>()"));
        }
    }

    private S made(Named found) {
        if (found.outcome == ABSTRACT) {
            throw failed("Provider " + found.name + " could not be instantiated", new InstantiationException());
        }
        if (found.outcome == INACCESSIBLE) {
            throw failed("Provider " + found.name + " could not be instantiated",
                    new IllegalAccessException("class java.util.ServiceLoader$ProviderImpl (in module java.base) "
                            + "cannot access a member of class " + found.name + " with modifiers \"public\""));
        }
        try {
            return (S) found.maker.get();
        }
        catch (Throwable thrown) {
            throw failed("Provider " + found.name + " could not be instantiated", thrown);
        }
    }

    private boolean lookup() {
        if (pending == null && instanced < named.size()) {
            pending = named.get(instanced++);
        }
        return pending != null;
    }

    public Iterator<S> iterator() {
        return new Iterator<S>() {

            private int index;

            public boolean hasNext() {
                synchronized (ServiceLoader.this) {
                    return index < instances.size() || lookup();
                }
            }

            public S next() {
                synchronized (ServiceLoader.this) {
                    if (index < instances.size()) {
                        return instances.get(index++);
                    }
                    if (!lookup()) {
                        throw new NoSuchElementException();
                    }
                    Named held = pending;
                    pending = null;
                    Named found = found(held);
                    constructed(found);
                    S made = made(found);
                    instances.add(made);
                    index++;
                    return made;
                }
            }
        };
    }

    public Stream<Provider<S>> stream() {
        Iterator<Provider<S>> found = new Iterator<Provider<S>>() {

            private int index;

            private Provider<S> next;

            public boolean hasNext() {
                synchronized (ServiceLoader.this) {
                    if (next == null) {
                        if (index < providers.size()) {
                            next = providers.get(index);
                        }
                        else if (provided < named.size()) {
                            Named provider = found(named.get(provided++));
                            constructed(provider);
                            next = new Provider<S>() {
                                public Class<? extends S> type() {
                                    return (Class<? extends S>) provider.type.get();
                                }

                                public S get() {
                                    return made(provider);
                                }
                            };
                            providers.add(next);
                        }
                    }
                    return next != null;
                }
            }

            public Provider<S> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Provider<S> provider = next;
                next = null;
                index++;
                return provider;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(found, 0), false);
    }

    public Optional<S> findFirst() {
        Iterator<S> found = iterator();
        return found.hasNext() ? Optional.of(found.next()) : Optional.empty();
    }

    public synchronized void reload() {
        instances.clear();
        instanced = 0;
        pending = null;
        providers.clear();
        provided = 0;
    }

    public String toString() {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }
}
