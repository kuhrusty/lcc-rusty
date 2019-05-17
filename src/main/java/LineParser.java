import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single log file line.
 */
public class LineParser {

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
    private final DateFormat dateFormat = new SimpleDateFormat("d/MMM/yyyy:HH:mm:ss Z");

    /**
     * This contains all the stuff which goes in & out of parseLine().
     */
    public static class Context {
        /**
         * The running total number of user requests found.
         */
        int userRequests = 0;

        /**
         * If true, we'll dump warnings to System.err.
         */
        boolean verbose = false;
        /**
         * The name of the file currently being parsed, or null.
         */
        String verboseCurrentFile;
        /**
         * The line number of the file currently being parsed, or 0.
         */
        int verboseCurrentLine = 0;

        /**
         * If parseLine() returns true, this will be set to the user ID found
         * in the given line.
         */
        String userID;
        /**
         * If parseLine() returns true, this will be set to the request time in
         * seconds since 1/1/70 UTC.
         */
        int requestS;
    }

    /**
     * Attempts to parse a single log file line.  (The log format wasn't
     * exactly specified in the challenge description, so the logic here is
     * based on looking at the example data.)  If the line can't be parsed (or
     * doesn't appear to be a request from a user), it's silently ignored.
     *
     * @param line the alleged log file line
     * @param ct information about the line being parsed; must not be null.
     *           This is also where the parse results are put if this returns
     *           true.
     * @return true if the line was successfully parsed as a user request,
     *         false if not.
     */
    public boolean parseLine(String line, Context ct) {
        Matcher tm = USER_REQUEST.matcher(line);
        if (!tm.find()) {
            if (ct.verbose) {
                System.err.println(ct.verboseCurrentFile + " " +
                        ct.verboseCurrentLine + ": ignoring " + line);
            }
            return false;
        }

        String dateStr = tm.group(1);
        String uid = tm.group(2);

        int requestS = 0;
        try {
            Date anotherObjectForGC = dateFormat.parse(dateStr);
            requestS = (int)(anotherObjectForGC.getTime() / 1000L);  //  ms to s
        } catch (ParseException pe) {
            if (ct.verbose) {
                System.err.println(ct.verboseCurrentFile + " " +
                        ct.verboseCurrentLine + ": got ParseException on \"" +
                        dateStr + "\": " + pe.getMessage());
            }
            return false;
        }

        ++ct.userRequests;
        ct.userID = uid;
        ct.requestS = requestS;
        return true;
    }
}
