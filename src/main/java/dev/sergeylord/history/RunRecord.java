package dev.sergeylord.history;

/** Одна запись в истории прогонов. */
public record RunRecord(
        String id,
        String startedAtIso,
        String finishedAtIso,
        String inn,
        String excelPath,
        String status,
        int casesFound,
        long durationMs,
        String message
) {
}
