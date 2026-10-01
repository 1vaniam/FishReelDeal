package dev.reeldeal.fishing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


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
