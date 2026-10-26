package dev.sergeylord.tools;

import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.KadSearchClient;
import dev.sergeylord.kad.Party;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** End-to-end: one known card with a real PDF. */
public final class SmokeDocs {
    public static void main(String[] args) throws Exception {
        Path docsDir = Path.of("build", "kad-debug", "smoke-docs");
        Files.createDirectories(docsDir);
        // wipe previous
        try (var s = Files.list(docsDir)) {
            s.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }

        KadCase seed = new KadCase(
                "8979296a-c26e-4726-88b3-cc88bd974d28",
                "А67-9933/2026",
                "G",
                "АС Томской области",
                "",
                "21.10.2026",
                List.of(new Party("x", "")),
                List.of(new Party("y", "")),
                "",
                "",
                "",
                ""
        );

        try (KadSearchClient client = new KadSearchClient(false)) {
            client.warmUp();
            List<KadCase> out = client.enrichWithHearings(List.of(seed), docsDir, (c, t, m) ->
                    System.out.println("[" + c + "/" + t + "] " + m));
            KadCase done = out.get(0);
            System.out.println("RESULT hearing=[" + done.nextHearing() + "]");
            System.out.println("RESULT claim=[" + done.claimSum() + "]");
            System.out.println("RESULT docs=[" + done.documents() + "]");
            try (var s = Files.list(docsDir)) {
                s.forEach(p -> {
                    try {
                        System.out.println("FILE " + p.getFileName() + " size=" + Files.size(p));
                    } catch (Exception e) {
                        System.out.println("FILE " + p.getFileName() + " err=" + e.getMessage());
                    }
                });
            }
        }
    }
}
