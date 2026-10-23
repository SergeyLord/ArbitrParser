package dev.sergeylord.kad;

import java.util.List;

/** Дело из списка КАД + поля с карточки (дата заседания, сумма иска, документы). */
public record KadCase(
        String caseId,
        String caseNumber,
        String caseType,
        String courtName,
        String judge,
        String date,
        List<Party> plaintiffs,
        List<Party> respondents,
        String nextHearing,
        String claimSum,
        String documents
) {
    public KadCase withCardDetails(String hearing, String claimSum, String documents) {
        return new KadCase(
                caseId, caseNumber, caseType, courtName, judge, date,
                plaintiffs, respondents,
                hearing == null ? "" : hearing,
                claimSum == null ? "" : claimSum,
                documents == null ? "" : documents
        );
    }
}
