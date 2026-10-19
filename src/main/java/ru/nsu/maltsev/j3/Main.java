package ru.nsu.maltsev.j3;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            usage(out);
            return 0;
        }
        URI root;
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException("Expected at most one URL argument");
            }
            root = Crawler.validateRoot(URI.create(args.length == 0 ? "http://localhost:8080/" : args[0]));
        } catch (IllegalArgumentException e) {
            err.println("Invalid arguments: " + e.getMessage());
            usage(err);
            return 2;
        }

        long start = System.nanoTime();
        try (var client = new HttpPageClient()) {
            var messages = new Crawler(client).crawl(root);
            messages.forEach(out::println);
            err.printf(Locale.ROOT, "Collected %d messages in %.3f seconds.%n",
                    messages.size(), (System.nanoTime() - start) / 1_000_000_000.0);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Crawl interrupted.");
            return 1;
        } catch (IOException e) {
            err.println("Crawl failed: " + e.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar target/lab_j3.jar [http://localhost:8080/]");
    }
}
