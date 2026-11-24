package ppc.sharding;

/** What "running" a task means. Real runs sleep/spin for the duration; tests plug in a recorder. */
@FunctionalInterface
public interface TaskBody {

    void execute(Task task) throws InterruptedException;
}
