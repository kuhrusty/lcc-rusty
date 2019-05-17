import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * Processes one or more log files and generates a summary of the top n users'
 * page views and sessions.
 *
 * <p>This uses 32-bit seconds-since-1/1/70 throughout; have the intern fix that
 * before 2038.
 */
public class LogTop {

    /**
     * We expect at least one file or directory; for each directory, we'll
     * attempt to read all files in it.
     */
    public static void main(String[] argv) throws IOException {
        Options opts = new Options();
        opts.addOption("c", false,
                "compare: wait for input on stdin so that you can connect " +
                 "with jconsole or whatever, run both LogFileParser implementations, " +
                 "and compare output.");
        opts.addOption("p2", false,
                "use NonChronologicalFileParser instead of ReorderingFileParser");
        opts.addOption("s", false,
                "display seconds (m:ss) in summary, instead of just minutes");
        opts.addOption("t", true,
                "top how-many users to list; defaults to 5");
        opts.addOption("T", true,
                "session threshold time, in seconds; defaults to " +
                LogFileParser.CONNECTION_THRESHOLD_S);
        opts.addOption("v", false,
                "verbose");
        opts.addOption("v1", false,
                "in compare mode, first LogFileParser verbose");
        opts.addOption("v2", false,
                "in compare mode, second LogFileParser verbose");
        CommandLine args = null;
        try {
            args = new DefaultParser().parse(opts, argv);
        } catch (org.apache.commons.cli.ParseException pe) {
            usage(opts, pe.getMessage());
        }
        argv = args.getArgs();
        if (argv.length == 0) usage(opts, "I need at least one file or directory!");

        boolean compare = args.hasOption("c");
        boolean includeSeconds = args.hasOption("s");
        int threshold = positiveIntOpt(opts, args, "T",
                LogFileParser.CONNECTION_THRESHOLD_S);
        int topHowMany = positiveIntOpt(opts, args, "t", 5);

        if (!compare) {
            LogFileParser lfp = args.hasOption("p2") ?
                    new NonChronologicalFileParser(threshold, args.hasOption("v")) :
                    new ReorderingFileParser(threshold, args.hasOption("v"));
            lfp.processEverything(Arrays.asList(argv));
            System.out.println(summaryToString(lfp.getSummary(topHowMany), includeSeconds));
            return;
        }

        //  If we're here, then instead of running one parser and bailing,
        //  we're running both and comparing their output.

        String parserClass1 = "ReorderingFileParser";
        LogFileParser lfp1 = new ReorderingFileParser(threshold,
                args.hasOption("v") || args.hasOption("v1" ));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("hit enter to begin " + parserClass1);
        in.readLine();

        long start = System.currentTimeMillis();
        lfp1.processEverything(Arrays.asList(argv));
        long elapsed1 = System.currentTimeMillis() - start;

        String parserClass2 = "NonChronologicalFileParser";
        LogFileParser lfp2 = new NonChronologicalFileParser(threshold,
                args.hasOption("v") || args.hasOption("v2" ));

        System.out.println("hit enter to begin " + parserClass2);
        in.readLine();

        start = System.currentTimeMillis();
        lfp2.processEverything(Arrays.asList(argv));
        long elapsed2 = System.currentTimeMillis() - start;

        System.out.println("hit enter to generate summary and exit");
        in.readLine();

        String out1 = summaryToString(lfp1.getSummary(topHowMany), includeSeconds);
        String out2 = summaryToString(lfp2.getSummary(topHowMany), includeSeconds);
        if (out1.equals(out2)) {
            System.out.println("Both approaches said:\n\n" + out1);
        } else {
            System.out.println("NO " + parserClass1 + " said:\n\n" + out1 +
                    "\nbut " + parserClass2 + " said:\n\n" + out2);
        }
        System.out.println(parserClass1 + ": " + elapsed1 + " ms");
        System.out.println(parserClass2 + ": " + elapsed2 + " ms");
    }

    private static int positiveIntOpt(Options opts, CommandLine cl, String optName, int defaultValue) {
        int rv = defaultValue;
        if (cl.hasOption(optName)) {
            try {
                rv = Integer.parseInt(cl.getOptionValue(optName));
            } catch (NumberFormatException nfe) {
                usage(opts, "-" + optName + " should be a number!");
            }
            if (rv < 1) usage(opts, "-" + optName + " should be positive!");
        }
        return rv;
    }

    /**
     * Calls System.exit().
     */
    private static void usage(Options opts, String msg) {
        if (msg != null) System.err.println(msg + "\n");
        new HelpFormatter().printHelp(
                "LogTop [options] files and/or directories", opts);
        System.exit(1);
    }

    static String summaryToString(Summary summary, boolean includeSeconds) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(os);
        printSummary(summary, includeSeconds, ps);
        ps.close();
        return os.toString();
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
}
