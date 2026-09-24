package dev.reeldeal.fishing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A flat weighted-random pool, standing in for a vanilla loot pool's single
 * roll across its (already-flattened) entries. Composite entry types don't
 * need their own model here -- {@code group} just means "add these
 * candidates to the same pool", which is exactly what repeated {@link #add}
 * calls do, and {@code alternatives} (first eligible child wins) is modeled
 * as a single candidate whose own eligibility/value is resolved by the
 * caller before adding it.
 */
public final class WeightedPool<T> {

    private record Candidate<T>(T value, int weight, boolean eligible) {
    }

    private final List<Candidate<T>> candidates = new ArrayList<>();

    public WeightedPool<T> add(T value, int weight) {
        return add(value, weight, true);
    }

    public WeightedPool<T> add(T value, int weight, boolean eligible) {
        if (weight > 0) {
            candidates.add(new Candidate<>(value, weight, eligible));
        }
        return this;
    }

    /** @return the picked value, or null if no eligible candidate had positive weight. */
    public T pick(Random random) {
        int total = 0;
        for (Candidate<T> c : candidates) {
            if (c.eligible()) {
                total += c.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        int cursor = 0;
        for (Candidate<T> c : candidates) {
            if (!c.eligible()) {
                continue;
            }
            cursor += c.weight();
            if (roll < cursor) {
                return c.value();
            }
        }
        return null; // unreachable
    }
}
