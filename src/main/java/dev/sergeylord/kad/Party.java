package dev.sergeylord.kad;

/** Участник дела. */
public record Party(String name, String inn) {

    public String display() {
        if (inn == null || inn.isBlank()) {
            return name == null ? "" : name;
        }
        return name + " (INN " + inn + ")";
    }

    public boolean hasInn(String targetInn) {
        if (targetInn == null || targetInn.isBlank() || inn == null) {
            return false;
        }
        return inn.replaceAll("\\D", "").equals(targetInn.replaceAll("\\D", ""));
    }
}
