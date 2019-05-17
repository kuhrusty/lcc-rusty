import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class LogFileParserTest {

    private interface ParserFactory {
        public LogFileParser newParser();
    }

    @Test
    public void testReorderingFileParser() throws IOException {
        testCommonStuff(new ParserFactory() {
            @Override
            public LogFileParser newParser() {
                return new ReorderingFileParser(600, false);
            }
        });
    }

    @Test
    public void testNonChronologicalFileParser() throws IOException {
        testCommonStuff(new ParserFactory() {
            @Override
            public LogFileParser newParser() {
                return new NonChronologicalFileParser(600, false);
            }
        });
    }

    private void testCommonStuff(ParserFactory pf) throws IOException {

        //  Call processEverything() on a single file, check results
        LogFileParser fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/log1.txt"));
        check(fp.getSummary(5), 3,
                new Summary.User("71f28176", 2, 1, 6, 6),
                new Summary.User("b3a60c78", 2, 1, 1, 1),
                new Summary.User("489f3e87", 1, 1, 1, 1));

        //  Add a later file to the same parser, check results
        fp.processEverything(Arrays.asList("src/test/resources/log4.txt"));
        check(fp.getSummary(5), 3,
                new Summary.User("489f3e87", 9, 2, 10, 1),
                new Summary.User("71f28176", 3, 2,  6, 1),
                new Summary.User("b3a60c78", 2, 1,  1, 1));

        //  Pass both files at once to a new parser; should get the same result
        fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/log1.txt",
                                           "src/test/resources/log4.txt"));
        check(fp.getSummary(5), 3,
                new Summary.User("489f3e87", 9, 2, 10, 1),
                new Summary.User("71f28176", 3, 2,  6, 1),
                new Summary.User("b3a60c78", 2, 1,  1, 1));

        //  Pass both files in the opposite order; should get the same result
        fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/log4.txt",
                                           "src/test/resources/log1.txt"));
        check(fp.getSummary(5), 3,
                new Summary.User("489f3e87", 9, 2, 10, 1),
                new Summary.User("71f28176", 3, 2,  6, 1),
                new Summary.User("b3a60c78", 2, 1,  1, 1));

        //  Call processEverything() on a directory, check results
        fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/dir1"));
        check(fp.getSummary(5), 2,
                new Summary.User("71f28176", 3, 1, 365, 365),
                new Summary.User("489f3e87", 2, 1, 600, 600));

        //  Call processEverything() on all files + directories in a
        //  non-chronological order, check results
        fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/log4.txt",
                                           "src/test/resources/log1.txt",
                                           "src/test/resources/dir1"));
        check(fp.getSummary(5), 3,
                new Summary.User("489f3e87", 11, 2,  970,    1),
                new Summary.User("71f28176",  6, 1, 1091, 1091),
                new Summary.User("b3a60c78",  2, 1,    1,    1));

        //  Call processEverything() on all files + directories in a
        //  different non-chronological order; should get the same result
        fp = pf.newParser();
        fp.processEverything(Arrays.asList("src/test/resources/dir1",
                                           "src/test/resources/log1.txt",
                                           "src/test/resources/log4.txt"));
        check(fp.getSummary(5), 3,
                new Summary.User("489f3e87", 11, 2,  970,    1),
                new Summary.User("71f28176",  6, 1, 1091, 1091),
                new Summary.User("b3a60c78",  2, 1,    1,    1));

        //  Confirm that requesting fewer top results than the number of
        //  distinct users works, as that'll be the usual case, and we haven't
        //  actually hit that yet
        check(fp.getSummary(2), 3,
                new Summary.User("489f3e87", 11, 2,  970,    1),
                new Summary.User("71f28176",  6, 1, 1091, 1091));
    }

    void check(Summary got, int expectUniqueUsers, Summary.User... expectUsers) {
        Summary expected = new Summary();
        expected.uniqueUsers = expectUniqueUsers;
        expected.top.addAll(Arrays.asList(expectUsers));
        check(expected, got);
    }
    void check(Summary expected, Summary got) {
        assertEquals(expected.uniqueUsers, got.uniqueUsers);
        assertEquals(expected.top.size(), got.top.size());
        for (int ii = 0; ii < expected.top.size(); ++ii) {
            check(ii, expected.top.get(ii), got.top.get(ii));
        }
    }
    void check(int whichEntry, Summary.User expected, Summary.User got) {
        String msg = "Entry " + whichEntry;
        assertEquals(msg, expected.id, got.id);
        assertEquals(msg, expected.pages, got.pages);
        assertEquals(msg, expected.sessions, got.sessions);
        assertEquals(msg, expected.longest, got.longest);
        assertEquals(msg, expected.shortest, got.shortest);
    }
}
