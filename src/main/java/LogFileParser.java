import java.io.IOException;
import java.util.List;

/**
 * Base-class log-file-parsing stuff.
 */
public abstract class LogFileParser {
    /**
     * The length of a single-connection "session," in seconds.  If you set
     * this to zero, our shortest-session logic will break.  Actually, if you
     * set it to anything greater than one, we'll be brain-damaged, because a
     * single-connection "session" will be considered longer than a session
     * consisting of two connections a second apart.  (But, as long as we're
     * rounding down to whole minutes in printSummary(), none of this matters.)
     */
    public static final int SINGLE_CONNECTION_SESSION_LENGTH = 1;

    public static final int CONNECTION_THRESHOLD_S = 600;  //  10 minutes, in s

    protected LogFileParser(int connectionThresholdS, boolean verbose) {
        this.connectionThresholdS = connectionThresholdS;
        this.verbose = verbose;
    }

    /**
     * Calls processFile() on each element which is a file, and on all files in
     * each element which is a directory.  Order doesn't matter.
     */
    abstract public int processEverything(List<String> paths) throws IOException;

    /**
     * Returns the summary of the log files read so far.
     *
     * @param topHowMany how many top users you want.  The resulting Summary
     *                   may have fewer users than that, but it won't have
     *                   more.
     * @return a new Summary instance, never null.
     */
    abstract public Summary getSummary(int topHowMany);

    int connectionThresholdS;
    boolean verbose;
    //  same as LineParser.userRequests, for spanning multiple calls
    //  now, if this guy were designing for *scale*, this would be a long.  FAIL
    int userRequests = 0;
}
