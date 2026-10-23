package dev.sergeylord;

import dev.sergeylord.config.AppDefaults;
import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.KadSearchClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Консольный режим (отладка): --console [ИНН] [--headless] [--limit=N] [--pages=N] */
public final class ConsoleRunner {

    private ConsoleRunner() {
    }

    public static void run(String[] args) {
        String inn = AppDefaults.DEFAULT_INN;
        boolean headless = false;
        int maxCases = 25;

        for (String arg : args) {
            if ("--console".equalsIgnoreCase(arg)) {
                continue;
            }
            if ("--headed".equalsIgnoreCase(arg)) {
                headless = false;
            } else if ("--headless".equalsIgnoreCase(arg)) {
                headless = true;
            } else if (arg.startsWith("--limit=")) {
                maxCases = Integer.parseInt(arg.substring("--limit=".length()));
            } else if (arg.startsWith("--pages=")) {
                maxCases = Integer.parseInt(arg.substring("--pages=".length())) * 25;
            } else if (!arg.startsWith("-")) {
                inn = arg.trim();
            }
        }

        System.out.println("=== ArbitrParser console ===");
        System.out.println("Query: " + inn);
        System.out.println("Mode: " + (headless ? "headless" : "headed"));
        System.out.println("Limit: " + maxCases);

        try (KadSearchClient client = new KadSearchClient(headless)) {
            client.warmUp();
            List<KadCase> cases = client.searchByParticipant(inn, maxCases);

            StringBuilder out = new StringBuilder();
            out.append("=== Result (").append(cases.size()).append(") ===\n");
            int i = 1;
            for (KadCase c : cases) {
                out.append(String.format(
                        "%d) %s | %s | type=%s | date=%s%n",
                        i++, c.caseNumber(), c.courtName(), c.caseType(), c.date()
                ));
                out.append("   plaintiffs:  ").append(
                        c.plaintiffs().stream().map(dev.sergeylord.kad.Party::display)
                                .reduce((a, b) -> a + "; " + b).orElse("")
                ).append('\n');
                out.append("   respondents: ").append(
                        c.respondents().stream().map(dev.sergeylord.kad.Party::display)
                                .reduce((a, b) -> a + "; " + b).orElse("")
                ).append('\n');
            }
            System.out.println(out);
            Path resultFile = Path.of("build", "kad-debug", "result.txt");
            Files.createDirectories(resultFile.getParent());
            Files.writeString(resultFile, out, StandardCharsets.UTF_8);
            System.out.println("UTF-8 copy: " + resultFile.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
