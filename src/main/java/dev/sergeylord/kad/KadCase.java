package dev.sergeylord.kad;

import java.util.List;

/** Карточка дела из выдачи КАД (+ опционально дата заседания с карточки). */
public record KadCase(
        String caseId,
        String caseNumber,
        String caseType,
        String courtName,
        String judge,
        String date,
        List<Party> plaintiffs,
        List<Party> respondents,
        String nextHearing
) {
    public KadCase withNextHearing(String hearing) {
        return new KadCase(
                caseId, caseNumber, caseType, courtName, judge, date,
                plaintiffs, respondents, hearing == null ? "" : hearing
        );
    }
}
