package com.neaz.wallet.support;

import com.neaz.wallet.billpayment.application.port.BillerClient;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.billpayment.domain.BillerPaymentStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The unreliable biller from the brief. Each {@code pay()} for a reference it has not paid yet does one of:
 *
 * <pre>
 * APPROVE                60%   pays, answers Approved
 * DECLINE                15%   does not pay, answers Declined
 * TIMEOUT_AFTER_PAYING   15%   pays, then throws (we never hear back)
 * TIMEOUT_BEFORE_PAYING  10%   does not pay, throws
 * </pre>
 *
 * Outcomes come from a scripted list if one is set, otherwise from a seeded {@link Random}, so runs are
 * deterministic. A reference is never paid twice: {@code pay()} for an already paid reference returns the original
 * approval. {@link #status} always tells the truth. Synchronized, so it is safe under concurrent tests.
 */
public final class FakeBiller implements BillerClient {

    public static final long DEFAULT_SEED = 20_260_001L;

    public enum Behaviour {
        APPROVE,
        DECLINE,
        TIMEOUT_AFTER_PAYING,
        TIMEOUT_BEFORE_PAYING
    }

    /** What a real HTTP client would surface on a read timeout. */
    public static final class Timeout extends RuntimeException {
        Timeout(UUID reference) {
            super("biller timed out for reference " + reference);
        }
    }

    private final Map<UUID, Long> paid = new HashMap<>();
    private final Map<UUID, Integer> payCalls = new HashMap<>();
    private final Map<Behaviour, Integer> behaviourCounts = new HashMap<>();
    private final Deque<Behaviour> script = new ArrayDeque<>();
    private Random random = new Random(DEFAULT_SEED);

    public synchronized void reset() {
        paid.clear();
        payCalls.clear();
        behaviourCounts.clear();
        script.clear();
        random = new Random(DEFAULT_SEED);
    }

    /** The next {@code pay()} calls for unpaid references behave as listed, in order; then random again. */
    public synchronized void script(Behaviour... behaviours) {
        script.addAll(List.of(behaviours));
    }

    @Override
    public synchronized BillerOutcome pay(UUID reference, String billerReference, long amount) {
        payCalls.merge(reference, 1, Integer::sum);
        if (paid.containsKey(reference)) {
            return new BillerOutcome.Approved();
        }
        Behaviour behaviour = next();
        behaviourCounts.merge(behaviour, 1, Integer::sum);
        return switch (behaviour) {
            case APPROVE -> {
                paid.put(reference, amount);
                yield new BillerOutcome.Approved();
            }
            case DECLINE -> new BillerOutcome.Declined("account closed");
            case TIMEOUT_AFTER_PAYING -> {
                paid.put(reference, amount);
                throw new Timeout(reference);
            }
            case TIMEOUT_BEFORE_PAYING -> throw new Timeout(reference);
        };
    }

    @Override
    public synchronized BillerPaymentStatus status(UUID reference) {
        return paid.containsKey(reference) ? BillerPaymentStatus.PAID : BillerPaymentStatus.NOT_PAID;
    }

    public synchronized long totalReceived() {
        return paid.values().stream().mapToLong(Long::longValue).sum();
    }

    public synchronized Set<UUID> paidReferences() {
        return Set.copyOf(paid.keySet());
    }

    public synchronized int payCalls(UUID reference) {
        return payCalls.getOrDefault(reference, 0);
    }

    public synchronized int totalPayCalls() {
        return payCalls.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized int timesBehaved(Behaviour behaviour) {
        return behaviourCounts.getOrDefault(behaviour, 0);
    }

    private Behaviour next() {
        if (!script.isEmpty()) {
            return script.poll();
        }
        int roll = random.nextInt(100);
        if (roll < 60) {
            return Behaviour.APPROVE;
        }
        if (roll < 75) {
            return Behaviour.DECLINE;
        }
        if (roll < 90) {
            return Behaviour.TIMEOUT_AFTER_PAYING;
        }
        return Behaviour.TIMEOUT_BEFORE_PAYING;
    }
}
