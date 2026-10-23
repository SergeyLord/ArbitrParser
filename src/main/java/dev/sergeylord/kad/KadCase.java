package dev.sergeylord.kad;

import java.util.List;

/** Дело из списка КАД + поля с карточки (nextHearing; сумма иска пока не заполняем). */
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
        String claimSum
) {
    public KadCase withCardDetails(String hearing, String claimSum) {
        return new KadCase(
                caseId, caseNumber, caseType, courtName, judge, date,
                plaintiffs, respondents,
                hearing == null ? "" : hearing,
                claimSum == null ? "" : claimSum
        );
    }
}
