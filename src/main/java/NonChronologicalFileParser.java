import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

/**
 * Processes one or more log files and generates a summary of the top n users'
 * page views and sessions.
 *
 * <p>Because we can't be sure we're receiving the log files in chronological
 * order, we could find a request near the end of file 1, then find a request
 * near the start of file 3, then process file 2 and discover that the user
 * maintained a single session the entire time.  To detect this, we keep
 * <i>all</i> sessions for <i>all</i> users in memory for the entire run, which
 * is probably horrible.
 *
 * <p>One nice side effect of that is that this <i>should</i> be able to handle
 * concurrent logs from multiple servers: if a user connects to one host, then
 * their next connection is load-balanced to a second host, then their third
 * connection is back to the first host, this should correctly interpret those
 * as a single session (although I didn't test that).  Hopefully it's worth the
 * memory which this thing probably hogs!
 */
public class NonChronologicalFileParser extends LogFileParser {

    public NonChronologicalFileParser(int connectionThresholdS, boolean verbose) {
        super(connectionThresholdS, verbose);
    }

    @Override
    public int processEverything(List<String> paths) throws IOException {
        int requestsAtStart = userRequests;
        for (String path : paths) {
            File tf = new File(path);
            if (tf.isDirectory()) {
                for (File tf2 : tf.listFiles()) {
                    processFile(tf2);
                }
            } else {
                processFile(tf);
            }
        }
        return userRequests - requestsAtStart;
    }

    /**
     * Parses the given log file.  Probably not real exciting if the given file
     * isn't a log file.
     *
     * @return the number of user requests found.
     */
    private void processFile(File file) throws IOException {
        if (verbose) System.err.println("processFile(\"" + file + "\")");
        LineParser lp = new LineParser();
        LineParser.Context ct = new LineParser.Context();
        ct.verbose = verbose;
        ct.verboseCurrentFile = file.getName();
        ct.verboseCurrentLine = 0;
        BufferedReader in = new BufferedReader(new FileReader(file));
        int startingRequests = userRequests;
        String line;
        while ((line = in.readLine()) != null) {
            ++ct.verboseCurrentLine;
            if (lp.parseLine(line, ct)) {
                handleUserRequest(ct);
            }
        }
        in.close();
    }

    private void handleUserRequest(LineParser.Context ct) {
        User user = getOrCreate(ct.userID);
        ++user.pages;

        //  if you do this instead of reusing the same object across calls, at
        //  least pass it to the calls to user.sessions.add()
        //Session searchKey = new Session(requestS);
        searchKey.start = ct.requestS;
        Session before = user.sessions.floor(searchKey);
        Session after = user.sessions.ceiling(searchKey);
        if (before == null) {
            if (after == null) {
                //  This is our first session for this user!
                user.sessions.add(new Session(ct.requestS));
            } else if (ct.requestS + connectionThresholdS >= after.start) {
                //  This request is part of that session; crank its start back
                //  to this request time.  We "know" that's not affecting its
                //  order in the tree.
                after.start = ct.requestS;
            } else {
                //  This request is far enough away to be its own session.
                user.sessions.add(new Session(ct.requestS));
            }
        } else {
            if (before.end >= ct.requestS) {
                //  This request is already within the bounds of this session;
                //  nothing to do!
            } else if (before.end + connectionThresholdS >= ct.requestS) {
                //  Does this request bridge the gap between the two sessions?
                if ((after != null) && (ct.requestS + connectionThresholdS >= after.start)) {
                    //  Yep!  Coalesce the two sessions into one.
                    before.end = after.end;
                    user.sessions.remove(after);
                } else {
                    //  No, this request just extends the session.
                    before.end = ct.requestS;
                }
            } else {
                //  This request is too far away to be part of the "before"
                //  session.  Is it part of the "after" session, or is it a new
                //  session between them?
                if ((after != null) && (ct.requestS + connectionThresholdS >= after.start)) {
                    //  It's part of the "after" session.  As above, crank its
                    //  start back to this request time; we "know" that's not
                    //  affecting its order in the tree.
                    after.start = ct.requestS;
                } else {
                    //  it's its' own request!
                    user.sessions.add(new Session(ct.requestS));
                }
            }
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
            int shortest = Integer.MAX_VALUE;
            int longest = 0;
            for (Session ts : user.sessions) {
                int elapsed = ts.end - ts.start;
                if (elapsed == 0) elapsed = SINGLE_CONNECTION_SESSION_LENGTH;
                if (elapsed < shortest) shortest = elapsed;
                if (elapsed > longest) longest = elapsed;
            }
            rv.top.add(new Summary.User(user.id, user.pages, user.sessions.size(),
                    longest, shortest));
        }
        return rv;
    }

    private static class User {
        public User(String id) {
            this.id = id;
        }
        final String id;
        int pages = 0;
        final TreeSet<Session> sessions = new TreeSet<>(byStartTime);
    }
    private static class Session {
        public Session(int requestTime) {
            start = requestTime;
            end = requestTime;
        }
        //  We do modify this value, and we do use it as the sort key in the
        //  sessions TreeSet, but when we modify its value, we should never be
        //  affecting its order in the tree.
        int start;
        int end;
    }
    private static final Comparator<Session> byStartTime = new Comparator<Session>() {
        @Override
        public int compare(Session s1, Session s2) {
            return s1.start - s2.start;
        }
    };

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
    //  We reuse this guy in every call to handleUserRequest(), which is just
    //  one of the reasons processEverything() is non-reentrant.  This is just
    //  to keep from throwing away another object in every call to
    //  handleUserRequest(), an optimization which is almost certainly without
    //  measurable benefit, unless you're measuring how well I sleep at night.
    private Session searchKey = new Session(0);
}
