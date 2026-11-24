package ppc.sharding;

/**
 * One independent unit of work.
 *
 * @param id          stable id, used to check every task runs exactly once
 * @param trueMs      real duration the task takes when executed
 * @param estimatedMs what a duration-aware policy (LPT) believes the duration is
 */
public record Task(int id, double trueMs, double estimatedMs) {

    public Task withEstimate(double newEstimatedMs) {
        return new Task(id, trueMs, newEstimatedMs);
    }
}
