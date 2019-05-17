//  See LogTop.java

import java.util.ArrayList;
import java.util.List;

/**
 * Returned by LogTop.getSummary().  A new one of these is created with every
 * call, so I didn't bother with getters/setters; mess it up however you want.
 */
public class Summary {
    public int uniqueUsers;
    /**
     * Top users by page view, sorted highest first.
     */
    public List<User> top = new ArrayList<>();

    public static class User {
        public String id;
        /**
         * The number of requests they made.
         */
        public int pages;
        /**
         * The number of sessions they maintained.
         */
        public int sessions;
        /**
         * The length of their longest session, in seconds.
         */
        public int longest;
        /**
         * The length of their shortest session, in seconds.
         */
        public int shortest;

        //  Here's the part where I actually wish I'd used Kotlin.  It's funny;
        //  someone fixes all the stuff I complain about in Java, and I realize
        //  that what I enjoy most about Java *is* complaining.
        public User(String id, int pages, int sessions, int longest, int shortest) {
            this.id = id;
            this.pages = pages;
            this.sessions = sessions;
            this.longest = longest;
            this.shortest = shortest;
        }
    }
}
