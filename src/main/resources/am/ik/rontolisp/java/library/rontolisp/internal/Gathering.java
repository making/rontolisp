// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.stream.Gatherer;

// A gatherer as a stage of a stream. What it pushes while it is given one element is
// held until the stages after it ask, so a push answers true until the stream has
// stopped asking: a gatherer is not told that the stages after it took their last
// element among those it pushed for one of its own.
final class Gathering<T, A, R> implements Gatherer.Downstream<R> {

    private final Flow.Pull<T> pull;

    private final Flow.Ending source;

    private final Flow.Told before;

    private final Gatherer<T, A, R> gatherer;

    private Gatherer.Integrator<A, T, R> integrator;

    private A state;

    private boolean begun;

    private boolean done;

    private boolean rejecting;

    private final ArrayList<R> held = new ArrayList<>();

    private int at;

    private Gathering(Flow.Pull<T> pull, Flow.Ending source, Flow.Told before, Gatherer<T, A, R> gatherer) {
        this.pull = pull;
        this.source = source;
        this.before = before;
        this.gatherer = gatherer;
    }

    static <T, A, R> ObjectFlow<R> over(Flow.Origin origin, Flow.Pull<T> pull, Flow.Ending source,
            Gatherer<T, A, R> gatherer) {
        Gathering<T, A, R> stage = new Gathering<T, A, R>(pull, source, origin.told, gatherer);
        origin.told = what -> stage.told(what);
        Flow.Ending ending = new Flow.Ending();
        ObjectFlow<R> flow = new ObjectFlow<R>(origin, ending, null, () -> stage.next(ending));
        flow.gathering = stage;
        return flow;
    }

    // a gatherer after this one, nothing between them: the two are one gatherer, as
    // andThen makes them
    @SuppressWarnings("unchecked")
    <RR> ObjectFlow<RR> then(Flow.Origin origin, Gatherer<? super R, ?, RR> next) {
        origin.told = before;
        return over(origin, pull, source, (Gatherer<T, Object, RR>) gatherer.andThen(next));
    }

    public boolean push(R element) {
        if (rejecting) {
            return false;
        }
        held.add(element);
        return true;
    }

    public boolean isRejecting() {
        return rejecting;
    }

    private void begin() {
        if (!begun) {
            begun = true;
            if (before != null) {
                before.of(Flow.BEGIN);
            }
            state = gatherer.initializer().get();
            integrator = gatherer.integrator();
        }
    }

    private void told(int what) {
        if (what == Flow.BEGIN) {
            begin();
            return;
        }
        if (what == Flow.LEAVE) {
            rejecting = true;
        }
        if (before != null) {
            before.of(what);
        }
        if (what == Flow.END && !done) {
            done = true;
            begin();
            gatherer.finisher().accept(state, this);
        }
    }

    private R next(Flow.Ending ending) {
        for (;;) {
            if (at < held.size()) {
                R element = held.get(at);
                at++;
                if (at == held.size()) {
                    held.clear();
                    at = 0;
                }
                return element;
            }
            if (done) {
                ending.reached = true;
                return null;
            }
            begin();
            T element = pull.next();
            if (source.reached) {
                done = true;
            }
            else if (!integrator.integrate(state, element, this)) {
                done = true;
                Flow.leave(before);
            }
            if (done) {
                gatherer.finisher().accept(state, this);
            }
        }
    }
}
