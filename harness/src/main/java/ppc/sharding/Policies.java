package ppc.sharding;

import java.util.List;

/** The four policies compared in the study, in a fixed order. */
public final class Policies {

    private Policies() {
    }

    public static List<Policy> all() {
        return List.of(new StaticRoundRobin(), new Lpt(), new DynamicQueue(), new WorkStealing());
    }
}
