package dev.sergeylord.kad;

/** Прогресс долгой операции (карточки дел и т.п.). */
@FunctionalInterface
public interface ProgressListener {
    void onProgress(int current, int total, String message);
}
