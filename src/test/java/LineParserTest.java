import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LineParserTest {
    static final String[] line = new String[]{
            "10.10.6.90 - - 15/Aug/2016:23:59:20 -0500 \"GET /ecf8427e/b443dc7f/71f28176/174ef735/1dd4d421 HTTP/1.0\" 200 - \"-\" \"-\" 7 \"10.10.1.231, 10.10.6.90\" -",
            "10.10.2.104 - - 15/Aug/2016:23:59:21 -0500 \"GET /ecf8427e/b443dc7f/b3a60c78/43eb7b22?139df06a HTTP/1.0\" 200 - \"-\" \"-\" 5 \"10.10.1.231, 10.10.2.104\" -",
            "10.10.2.104 - - 16/Aug/2016:00:00:18 -0500 \"GET / HTTP/1.1\" 200 - \"-\" \"ELB-HealthChecker/1.0i\" 0 \"-\" -",
            "10.10.1.225 - - 16/Aug/2016:00:02:24 -0500 \"POST /ecf8427e/b443dc7f/eaefd399 HTTP/1.0\" 201 - \"-\" \"-i\" 224 \"10.10.1.225\" -",
            "10.10.1.226 - - 16/Aug/2016:00:53:57 -0500 \"PATCH /ecf8427e/b443dc7f/b796739b/f246be87/48f892ef/44ec6258 HTTP/1.0\" 204 - \"-\" \"-i\" 21 \"10.10.1.226\" -",
            "10.10.2.104 - - 16/Aug/2016:07:07:43 -0500 \"DELETE /ecf8427e/b443dc7f/489f3e87/723c5c24/b9e1ea1d/4a08f7d2?1fdfeec9 HTTP/1.0\" 204 - \"-\" \"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_4) AppleWebKit/600.7.12 (KHTML, like Gecko) Version/8.0.7 Safari/600.7.12i\" 12 \"103.204.119.234, 10.10.4.75, 10.10.2.104\" -",
            "10.10.2.104 - - 16/Aug/2016:00:37:18 -0500 \"PUT /ecf8427e/b443dc7f/489f3e87/fe317314/0390d2f1/31d7a40c/91580fb9/949b3fb2 HTTP/1.0\" 200 - \"-\" \"-\" 24 \"10.10.1.231, 10.10.2.104\" -",
    };

    @Test
    public void testParseLine() {
        LineParser lp = new LineParser();
        LineParser.Context ct = new LineParser.Context();

        assertFalse(lp.parseLine("garbage", ct));

        check(lp, ct, line[0], "71f28176", 1471323560, 1);
        check(lp, ct, line[1], "b3a60c78", 1471323561, 2);
        assertFalse(lp.parseLine(line[2], ct));
        check(lp, ct, line[3], "eaefd399", 1471323744, 3);
        check(lp, ct, line[4], "b796739b", 1471326837, 4);
        check(lp, ct, line[5], "489f3e87", 1471349263, 5);
        check(lp, ct, line[6], "489f3e87", 1471325838, 6);
    }

    /**
     * Confirms that the given parser parses the given line and puts the
     * expected stuff into the given context.
     */
    void check(LineParser lp, LineParser.Context ct, String line, String expectUID, int expectRequestS, int expectUserRequests) {
        assertTrue(lp.parseLine(line, ct));
        assertEquals(expectUID, ct.userID);
        assertEquals(expectRequestS, ct.requestS);
        assertEquals(expectUserRequests, ct.userRequests);
    }
}
