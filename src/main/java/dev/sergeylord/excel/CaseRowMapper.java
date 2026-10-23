package dev.sergeylord.excel;

import dev.sergeylord.kad.KadCase;
import dev.sergeylord.kad.Party;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Раскладка дела КАД → заполняемые колонки Excel. */
public final class CaseRowMapper {

    private CaseRowMapper() {
    }

    public record MappedRow(
            int rowNumber,
            String caseNumber,
            String ourSide,
            String otherSides,
            String court,
            String nextHearing,
            String claimSum
    ) {
    }

    public static MappedRow map(KadCase kadCase, String ourInn, int rowNumber) {
        boolean wePlaintiff = containsInn(kadCase.plaintiffs(), ourInn);
        boolean weRespondent = containsInn(kadCase.respondents(), ourInn);

        String ourSide;
        List<Party> others;
        if (wePlaintiff && !weRespondent) {
            ourSide = "Истец";
            others = kadCase.respondents();
        } else if (weRespondent && !wePlaintiff) {
            ourSide = "Ответчик";
            others = kadCase.plaintiffs();
        } else if (wePlaintiff) {
            ourSide = "Истец";
            others = withoutInn(kadCase.respondents(), ourInn);
            others = new ArrayList<>(others);
            others.addAll(withoutInn(kadCase.plaintiffs(), ourInn));
        } else {
            ourSide = "Третье лицо";
            others = new ArrayList<>();
            others.addAll(withoutInn(kadCase.plaintiffs(), ourInn));
            others.addAll(withoutInn(kadCase.respondents(), ourInn));
        }

        String otherSides = others.stream()
                .map(Party::name)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining("; "));

        return new MappedRow(
                rowNumber,
                nullToEmpty(kadCase.caseNumber()),
                ourSide,
                otherSides,
                nullToEmpty(kadCase.courtName()),
                nullToEmpty(kadCase.nextHearing()),
                nullToEmpty(kadCase.claimSum())
        );
    }

    private static boolean containsInn(List<Party> parties, String inn) {
        if (parties == null) {
            return false;
        }
        for (Party p : parties) {
            if (p.hasInn(inn)) {
                return true;
            }
        }
        return false;
    }

    private static List<Party> withoutInn(List<Party> parties, String inn) {
        List<Party> out = new ArrayList<>();
        if (parties == null) {
            return out;
        }
        for (Party p : parties) {
            if (!p.hasInn(inn)) {
                out.add(p);
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
