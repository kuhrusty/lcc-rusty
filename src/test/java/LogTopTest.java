import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LogTopTest {
    @Test
    public void testPrintSummary() {
        Summary summary = new Summary();
        summary.uniqueUsers = 666;
        summary.top.add(new Summary.User("sally-bob", 123, 3, 113, 1));
        summary.top.add(new Summary.User("leroy", 12, 4, 1203, 1));

        assertEquals("Total unique users: 666\n" +
                     "Top users:\n" +
                     "id              # pages # sess  longest shortest\n" +
                     "sally-bob       123     3       1       0\n" +
                     "leroy           12      4       20      0\n",
                LogTop.summaryToString(summary, false));

        assertEquals("Total unique users: 666\n" +
                     "Top users:\n" +
                     "id              # pages # sess  longest shortest\n" +
                     "sally-bob       123     3       1:53    0:01\n" +
                     "leroy           12      4       20:03   0:01\n",
                LogTop.summaryToString(summary, true));
    }
}
