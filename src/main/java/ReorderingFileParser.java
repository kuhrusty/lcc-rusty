import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Processes one or more log files and generates a summary of the top n users'
 * page views and sessions.  To use this guy, create a new instance and call
 * processEverything() followed by getSummary().
 *
 * <p>Because we can't be sure we're receiving the log files in chronological
 * order, we make two passes: first, we open each file and parse it far enough
 * to find a user request, and extract the date of that request; then, once
 * we've figured out the files' chronological order, we go back and parse them
 * completely in that order.
 */
public class ReorderingFileParser extends LogFileParser {

    public ReorderingFileParser(int connectionThresholdS, boolean verbose) {
        super(connectionThresholdS, verbose);
    }

    /**
     * If you call this multiple times, you are responsible for making sure
     * all files in one call have later times than all files in previous calls.
     */
    @Override
    public int processEverything(List<String> paths) throws IOException {
        ArrayList<LogFile> files = new ArrayList<>();

        //  Build the list of files
        for (String path : paths) {
            File tf = new File(path);
            if (tf.isDirectory()) {
                for (File tf2 : tf.listFiles()) {
                    files.add(new LogFile(tf2.toString()));
                }
            } else {
                files.add(new LogFile(path));
            }
        }

        //  Now attempt to extract the first request date from each file.
        //  If a file doesn't seem to contain any user requests, we remove it
        //  from the list.
        for (int ii = 0; ii < files.size(); ++ii) {
            LogFile lf = files.get(ii);
            int start = processFile(lf.path, true);
            if (start == 0) {
                if (verbose) {
                    System.err.println("Didn't find any user requests in " +
                            lf.path + ", ignoring...");
                }
                files.remove(ii--);  //  careful, ii is screwed up now
            } else {
                lf.startS = start;
            }
        }

        //  Sort the list of files...
        Collections.sort(files, new Comparator<LogFile>() {
            @Override
            public int compare(LogFile f1, LogFile f2) {
                return f1.startS - f2.startS;
            }
        });

        //  Now process the files for real!
        int requestsAtStart = userRequests;
        for (int ii = 0; ii < files.size(); ++ii) {
            processFile(files.get(ii).path, false);
        }
        return userRequests - requestsAtStart;
    }

    /**
     * Parses the given log file.  If justLookingForStartDate is true, then
     * we'll bail as soon as we find a user request, and return the request
     * time in seconds since 1/1/70 UTC (or 0 if no user requests are found
     * at all); if false, then we'll parse the whole file and return 0.
     *
     * <p>If you're calling this instead of processEverything(), then you're
     * responsible for passing files in chronological order.
     */
    private int processFile(String fileName, boolean justLookingForStartDate)
            throws IOException {
        if (verbose) {
            System.err.println("processFile(\"" + fileName +
                    "\"), justLookingForStartDate " + justLookingForStartDate);
        }
        LineParser lp = new LineParser();
        LineParser.Context ct = new LineParser.Context();
        ct.verbose = verbose;
        ct.verboseCurrentFile = fileName;
        ct.verboseCurrentLine = 0;
        BufferedReader in = new BufferedReader(new FileReader(fileName));
        int startDate = 0;
        String line;
        while ((line = in.readLine()) != null) {
            ++ct.verboseCurrentLine;
            if (!lp.parseLine(line, ct)) continue;
            if (justLookingForStartDate) {
                if (ct.requestS != 0) {
                    startDate = ct.requestS;
                    break;
                }
            } else {
                handleUserRequest(ct);
            }
        }
        in.close();
        //  ugh, nice API
        if (!justLookingForStartDate) userRequests += ct.userRequests;
        return justLookingForStartDate ? startDate : 0;
    }

    private void handleUserRequest(LineParser.Context ct) {
        User user = getOrCreate(ct.userID);
        ++user.pages;
        if (user.lastRequestS == 0) {
            //  This is the first request we've seen for them, and so also the
            //  start of a new session.
            user.lastRequestS = ct.requestS;
            user.lastSessionStartS = ct.requestS;
        } else if (ct.requestS < user.lastRequestS) {
            //  Snarl!  The example log files have a couple entries which are
            //  not in chronological order; we don't *actually* care about that
            //  unless we get records so far out of order *for a single user*
            //  that they're before the start of the user's current session.
            //  We can't easily recover from that, because it means we might
            //  have incorrectly decided that a previous session ended.
            if (ct.requestS < user.lastSessionStartS) {
                //  I wasn't going to use verboseCurrentFile unless verbose was
                //  set... but then I also wasn't going to croak here.
                throw new RuntimeException("gackk, " + ct.verboseCurrentFile +
                        " " + ct.verboseCurrentLine + ": got request time " +
                        ct.requestS + " for uid " + user.id +
                        ", which is before last session start time of " +
                        user.lastSessionStartS);
            }
            //  Not setting user.lastRequestS here, because we already got a
            //  request later in this same session.
        } else if (user.lastRequestS + connectionThresholdS >= ct.requestS) {
            //  This request is within their last existing session.
            user.lastRequestS = ct.requestS;
        } else {
            //  This request is beyond the end of their last session, so wrap
            //  that one up and start a new session.
            ++user.sessions;
            int elapsed = user.lastRequestS - user.lastSessionStartS;
            if (elapsed == 0) elapsed = SINGLE_CONNECTION_SESSION_LENGTH;
            if ((elapsed < user.shortest) || (user.shortest == 0)) {
                user.shortest = elapsed;
            }
            if (elapsed > user.longest) user.longest = elapsed;
            user.lastRequestS = ct.requestS;
            user.lastSessionStartS = ct.requestS;
        }
    }

    @Override
    public Summary getSummary(int topHowMany) {
        Summary rv = new Summary();
        rv.uniqueUsers = users.size();

        //  This is the crudest possible way to get the top n users, but the
        //  *code* is easy.  Note to self: have the intern rewrite this bit.
        ArrayList<User> all = new ArrayList<>(users.values());
        Collections.sort(all, byPages);

        //  Now copy the top n into the Summary.
        for (int ii = 0; (ii < topHowMany) && (ii < all.size()); ++ii) {
            User user = all.get(ii);
            //  "end" the last session they were in the midst of.  This
            //  duplicates the logic in processLine(), but we don't modify the
            //  User here, because that keeps someone from being able to call
            //  processFile(), getSummary(), processFile(), getSummary()...
            //  without goofing up sessions which span files.
            int shortest = user.shortest;
            int longest = user.longest;
            int elapsed = user.lastRequestS - user.lastSessionStartS;
            if (elapsed == 0) elapsed = SINGLE_CONNECTION_SESSION_LENGTH;
            if ((elapsed < shortest) || (shortest == 0)) shortest = elapsed;
            if (elapsed > longest) longest = elapsed;

            //  user.sessions + 1 to include the one we just "ended"
            rv.top.add(new Summary.User(user.id, user.pages, user.sessions + 1,
                    longest, shortest));
        }
        return rv;
    }

    /**
     * We create one of these per alleged log file we're going to process.
     */
    private static class LogFile {
        public LogFile(String path) {
            this.path = path;
        }
        private final String path;
        private int startS = 0;
    }

    private static class User {
        public User(String id) {
            this.id = id;
        }
        final String id;
        int pages = 0;
        int sessions = 0;  //  "completed," not counting current session
        int longest = 0;
        int shortest = 0;
        int lastRequestS = 0;
        int lastSessionStartS = 0;
    }

    private User getOrCreate(String id) {
        User rv = users.get(id);
        if (rv == null) {
            rv = new User(id);
            users.put(id, rv);
        }
        return rv;
    }

    /**
     * Orders Users by number of page requests, descending.
     */
    private static final Comparator<User> byPages = new Comparator<User>() {
        @Override
        public int compare(User u1, User u2) {
            return u2.pages - u1.pages;
        }
    };

    private HashMap<String, User> users = new HashMap<>();
}
