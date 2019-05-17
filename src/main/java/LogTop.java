//  To build & run:
//
//  $ javac *.java
//  $ java LogTop path/to/log/files

import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes one or more log files and generates a summary of the top n users'
 * page views and sessions.  To use this guy, create a new instance and either
 * call processEverything() followed by getSummary(), or processFile() and
 * getSummary() in any combination.
 *
 * <p>Because we can't be sure we're receiving the log files in chronological
 * order, we make two passes: first, we open each file and parse it far enough
 * to find a user request, and extract the date of that request; then, once
 * we've figured out the files' chronological order, we go back and parse them
 * completely in that order.
 *
 * <p>(That's a lot simpler than my first implementation, which was to process
 * the files without regard for chronological order, but it meant we had to
 * keep <i>all</i> sessions for <i>all</i> users in memory for the entire run,
 * which was a little embarrassing.  <i>But,</i> it was really cool that we
 * could find a connection near the end of file 1, then find a connection near
 * the start of file 3, then process file 2 and discover that the user had
 * maintained a single session the entire time.  It also meant that we could
 * handle concurrent logs from multiple servers--if a user's connections were
 * load-balanced across multiple hosts, we could still properly detect the case
 * where they had only had one session.)
 *
 * <p>This uses 32-bit seconds-since-1/1/70 throughout; have the intern fix that
 * before 2038.
 */
public class LogTop {
    /**
     * The length of a single-connection "session," in seconds.  If you set
     * this to zero, our shortest-session logic will break.  Actually, if you
     * set it to anything greater than one, we'll be brain-damaged, because a
     * single-connection "session" will be considered longer than a session
     * consisting of two connections a second apart.  (But, as long as we're
     * rounding down to whole minutes in printSummary(), none of this matters.)
     */
    private static final int SINGLE_CONNECTION_SESSION_LENGTH = 1;

    //  well, I didn't include an API for goofing with this.
    private static final int CONNECTION_THRESHOLD_S = 600;  //  10 minutes, in s

    //  yo dawg, I heard you like regular expressions, so I put a... well,
    //  actually, it's just one moderately nasty RE which captures the date and
    //  user ID.
    private static final Pattern USER_REQUEST = Pattern.compile(
            //  IP address, which we don't care about.
            "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
            //  Other junk which I should probably have looked for
            //  documentation on.
            "\\s+-\\s+-\\s+" +
            //  Finally, the date string!  Horrible: we're just looking for a
            //  blob of non-space stuff starting with a number, followed by
            //  space, followed by non-space, which we very much hope is the TZ
            //  offset.  This blob gets passed on to DateFormat for parsing.
            "(\\d\\S+\\s+\\S+)\\s+" +
            //  Now the actual request!  That last "[ /?]" bit is on there
            //  because we have some requests which don't have a fourth slash;
            //  they're just "POST /666/666/666 HTTP/1.0", and we have others
            //  of the form "POST /666/666/666?667 HTTP/1.0"
            //  Also, not sure I've seen the PATCH method before, but it's in
            //  the example logs!
            "\"(?:GET|POST|PUT|PATCH|DELETE)\\s+/[0-9a-f]+/[0-9a-f]+/([0-9a-f]+)[ /?]");
            //  and we don't care about anything after the UID.

    //  Not super excited about the log file date format; if sysadmins would
    //  just learn to read seconds or milliseconds since 1/1/70 UTC, we
    //  wouldn't have to parse nonsense like "15/Aug/2016:23:59:20 -0500".
    //
    //  Was kind of hoping there'd be a log entry with a different TZ offset
    //  than -0500 in the example data; this format string may be fragile.
    //
    //  Note that this guy's not static because SimpleDateFormat's not thread-safe.
    private final DateFormat dateFormat =
            new SimpleDateFormat("d/MMM/yyyy:HH:mm:ss Z");

    /**
     * We expect at least one file or directory; for each directory, we'll
     * attempt to read all files in it.
     */
    public static void main(String[] argv) throws IOException {
        if (argv.length == 0) {
            System.err.println("I need at least one file or directory!");
            return;
        }
        LogTop lt = new LogTop();
        if (lt.processEverything(argv) == 0) {
            System.err.println("Didn't find any user requests at all, " +
                    "which is... suspicious.");
            return;
        }
        printSummary(lt.getSummary(5), false, System.out);
    }

    /**
     * Prints the given Summary to the given stream.
     *
     * @param summary must not be null.
     * @param includeSeconds if true, session lengths will be displayed as
     *                       minutes:seconds; if false, only minutes will be
     *                       displayed (rounded down).
     * @param out must not be null.
     */
    public static void printSummary(Summary summary, boolean includeSeconds,
            PrintStream out) {
        out.println("Total unique users: " + summary.uniqueUsers);
        out.println("Top users:");
        out.println("id              # pages # sess  longest shortest");
        for (Summary.User user : summary.top) {
            out.println(String.format("%-15s %-7d %-7d %-7s %s",
                    user.id, user.pages, user.sessions,
                    formatDuration(user.longest, includeSeconds),
                    formatDuration(user.shortest, includeSeconds)));
        }
    }
    private static String formatDuration(int seconds, boolean includeSeconds) {
        return includeSeconds ?
                String.format("%d:%02d", seconds / 60, seconds % 60) :
                Integer.toString(seconds / 60);
    }

    //  OK, from here down is the real work.

    /**
     * Calls processFile() on each element which is a file, and on all files in
     * each element which is a directory.  Order doesn't matter; we'll examine
     * the files to determine the order in which to process them.
     *
     * @return the total number of user requests found in all files.
     */
    public int processEverything(String[] paths) throws IOException {
        int requestsAtStart = userRequests;
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
    public int processFile(String fileName, boolean justLookingForStartDate)
            throws IOException {
        if (verbose) {
            System.err.println("processFile(\"" + fileName +
                    "\"), justLookingForStartDate " + justLookingForStartDate);
        }
        verboseCurrentFile = fileName;
        verboseCurrentLine = 0;
        BufferedReader in = new BufferedReader(new FileReader(fileName));
        int startDate = 0;
        String line;
        while ((line = in.readLine()) != null) {
            ++verboseCurrentLine;
            startDate = processLine(line, justLookingForStartDate);
            if (justLookingForStartDate && (startDate != 0)) {
                break;
            }
        }
        in.close();
        return justLookingForStartDate ? startDate : 0;
    }

    /**
     * Attempts to parse a single line.  (The log format wasn't exactly
     * <i>specified</i> in the challenge description, so the logic here is
     * based on looking at the example data.)  If the line doesn't appear to be
     * a request from a user, it's silently ignored.
     *
     * <p>If you're calling this instead of processEverything(), then you're
     * responsible for passing lines in chronological order.
     *
     * @return the user request's timestamp in seconds since 1/1/70 UTC, or 0
     *         if the line does not appear to contain a user request.
     */
    public int processLine(String line, boolean justLookingForStartDate) {
        Matcher tm = USER_REQUEST.matcher(line);
        if (!tm.find()) {
            if (verbose) {
                System.err.println(verboseCurrentFile + " " +
                        verboseCurrentLine + ": ignoring " + line);
            }
            return 0;
        }

        String dateStr = tm.group(1);
        String uid = tm.group(2);

        int requestS = 0;
        try {
            Date anotherObjectForGC = dateFormat.parse(dateStr);
            requestS = (int)(anotherObjectForGC.getTime() / 1000L);  //  ms to s
        } catch (ParseException pe) {
            if (verbose) {
                System.err.println(verboseCurrentFile + " " +
                        verboseCurrentLine + ": got ParseException on \"" +
                        dateStr + "\": " + pe.getMessage());
            }
            return 0;
        }
        if (justLookingForStartDate) {
            return requestS;
        }

        ++userRequests;
        User user = getOrCreate(uid);
        ++user.pages;
        if (user.lastRequestS == 0) {
            //  This is the first request we've seen for them, and so also the
            //  start of a new session.
            user.lastRequestS = requestS;
            user.lastSessionStartS = requestS;
        } else if (requestS < user.lastRequestS) {
            //  Snarl!  The example log files have a couple entries which are
            //  not in chronological order; we don't *actually* care about that
            //  unless we get records so far out of order *for a single user*
            //  that they're before the start of the user's current session.
            //  We can't easily recover from that, because it means we might
            //  have incorrectly decided that a previous session ended.
            if (requestS < user.lastSessionStartS) {
                //  I wasn't going to use verboseCurrentFile unless verbose was
                //  set... but then I also wasn't going to croak here.
                throw new RuntimeException("gackk, " + verboseCurrentFile +
                        " " + verboseCurrentLine + ": got request time " +
                        requestS + " for uid " + user.id +
                        ", which is before last session start time of " +
                        user.lastSessionStartS);
            }
            //  Not setting user.lastRequestS here, because we already got a
            //  request later in this same session.
        } else if (user.lastRequestS + CONNECTION_THRESHOLD_S >= requestS) {
            //  This request is within their last existing session.
            user.lastRequestS = requestS;
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
            user.lastRequestS = requestS;
            user.lastSessionStartS = requestS;
        }
        return requestS;
    }

    /**
     * Returns the summary of the log files read so far.
     *
     * @param topHowMany how many top users you want.  The resulting Summary
     *                   may have fewer users than that, but it won't have
     *                   more.
     * @return a new Summary instance, never null.
     */
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

    //  Valid log entry lines, not necessarily the total number of lines.
    //  now, if this guy were designing for *scale*, this would be a long.  FAIL
    private int userRequests = 0;
    private HashMap<String, User> users = new HashMap<>();

    private boolean verbose = false;  //  if true, we'll dump warnings to System.err
    private String verboseCurrentFile;
    private int verboseCurrentLine = 0;
}
