package dev.sergeylord.kad;

import java.util.List;

/** Карточка дела из выдачи КАД (MVP). */
public record KadCase(
        String caseId,
        String caseNumber,
        String caseType,
        String courtName,
        String judge,
        String date,
        List<String> plaintiffs,
        List<String> respondents
) {
}
