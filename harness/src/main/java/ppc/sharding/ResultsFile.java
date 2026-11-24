package ppc.sharding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Append-only CSV of finished runs. One row is written (and closed) per run, so an interrupted grid
 * loses at most the run in progress and can be resumed.
 *
 * <p>overhead = makespan / lowerBound - 1 (0 means the lower bound was met).
 */
final class ResultsFile {

    static final String HEADER = "variant,policy,skew,est_error,workers,rep,n,median_ms,"
            + "task_seed,noise_seed,makespan_ms,lower_bound_ms,overhead,idle_share,timestamp_utc";

    private static final int COL_N = 6;
    private static final int COL_MEDIAN = 7;

    private final Path path;

    ResultsFile(Path path) {
        this.path = path;
    }

    /** Keys of finished runs. Refuses a file written with a different header, task count or median. */
    Set<String> openAndLoadDoneKeys(int n, double medianMs) throws IOException {
        Set<String> done = new HashSet<>();
        if (!Files.exists(path)) {
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            Files.writeString(path, HEADER + "\n", StandardOpenOption.CREATE_NEW);
            return done;
        }
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IllegalStateException(path + " has an unexpected header; refusing to append");
        }
        if (!endsWithNewline()) {
            // an interrupted write left a partial row: end it so the next append starts on its own line
            Files.writeString(path, "\n", StandardOpenOption.APPEND);
        }
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != HEADER.split(",").length) {
                continue; // a torn last line from an interrupted write: that run will simply be redone
            }
            if (Integer.parseInt(f[COL_N]) != n || Double.parseDouble(f[COL_MEDIAN]) != medianMs) {
                throw new IllegalStateException(path + " was written with n=" + f[COL_N] + ", median="
                        + f[COL_MEDIAN] + "; refusing to mix it with n=" + n + ", median=" + medianMs);
            }
            done.add(String.join(",", f[0], f[1], f[2], f[3], f[4], f[5]));
        }
        return done;
    }

    private boolean endsWithNewline() throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return bytes.length == 0 || bytes[bytes.length - 1] == '\n';
    }

    void append(String row) throws IOException {
        Files.writeString(path, row + "\n", StandardOpenOption.APPEND);
    }
}
