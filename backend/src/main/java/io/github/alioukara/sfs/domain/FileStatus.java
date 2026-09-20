package io.github.alioukara.sfs.domain;

public enum FileStatus {

    PENDING,
    SCANNING,
    SCAN_FAILED,
    SCAN_FAILED_EXHAUSTED,
    CLEAN,
    INFECTED,
    UNSCANNABLE;

    /**
     * Mirrors the transition table of CLAUDE.md. Anything absent from it is forbidden.
     *
     * <p>No {@code default} branch: adding a status without deciding its
     * transitions must break the compilation, not silently forbid everything.
     */
    public boolean canTransitionTo(FileStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == SCANNING;

            case SCANNING -> target == CLEAN
                    || target == INFECTED
                    || target == SCAN_FAILED
                    || target == SCAN_FAILED_EXHAUSTED
                    || target == UNSCANNABLE
                    || target == PENDING;

            case SCAN_FAILED, SCAN_FAILED_EXHAUSTED -> target == PENDING;

            case CLEAN, INFECTED, UNSCANNABLE -> false;
        };
    }

    /**
     * Equality test, never {@code != INFECTED}: a status added later must be
     * refused by default rather than served by mistake.
     */
    public boolean isDownloadable() {
        return this == CLEAN;
    }
}
