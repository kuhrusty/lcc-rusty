This is a little take-home coding challenge which was done for a job
interview; it parses log files of a specific format and dumps out a summary
of the top n users' activity.  This is probably only of interest to me and
*maybe* the people who sent me the challenge.

The interesting thing about the challenge was that it needed to be able to
handle multiple log files which might not be given in chronological order.
My first approach was to keep every session for every user in memory for the
entire run--which was pretty cool, but I was concerned that it would be a
memory hog under some conditions, so I went back and rewrote it to probe the
log files to figure out what order they should be processed in, then parse
them in that order.

But, after submitting my solution (just the application source code, not my
unit tests), I was curious to see how the two approaches compared, so I went
back and refactored/cleaned up, and added some command-line options for
running both implementations & comparing the output; this is the result.  (I
wish I'd stuck with my original approach!)

The version I originally sent is tagged with `submitted`; it was just two
Java files and a README.

# To build & run

On a Linux/Unix box with java:

    $ ./gradlew build
    $ ./logtop path/to/log/files

(The first step is not strictly necessary, but it lets you see the output from
the build & tests.)

That uses the approach I sent the first time (scanning each log file until we
hit a user request, then parsing the log files in order based on the request
times we found); to run it using the second approach (storing each session in
memory for the lifetime of the run, so that we can handle the log file entries
in any order):

    $ ./logtop -p2 path/to/log/files

# To compare the two approaches

To compare the *source code* of the two versions in your favorite diff
program:

    $ xxdiff src/main/java/[RN]*FileParser.java

To compare the *behavior* of the two approaches, this will pause at startup to
let you connect to the running process with jconsole or whatever:

    $ ./logtop -c path/to/log/files

Then, in another shell, `ps -ef | grep LogTop` and start jconsole with that
PID:

    $ jconsole 666

Then, back in your first shell, hit enter in the waiting LogTop process.
