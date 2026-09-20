package io.github.alioukara.sfs.antivirus;

public record ScanResult(Verdict verdict, String signature, String reason) {

    public ScanResult {
        if (verdict == null) {
            throw new IllegalArgumentException("Verdict is required");
        }
        if (verdict == Verdict.INFECTED && isBlank(signature)) {
            throw new IllegalArgumentException("An INFECTED verdict must carry a signature");
        }
        if (verdict == Verdict.INCONCLUSIVE && isBlank(reason)) {
            throw new IllegalArgumentException("An INCONCLUSIVE verdict must carry a reason");
        }
        if (verdict == Verdict.CLEAN && (signature != null || reason != null)) {
            throw new IllegalArgumentException("A CLEAN verdict carries neither signature nor reason");
        }
    }

    public static ScanResult clean() {
        return new ScanResult(Verdict.CLEAN, null, null);
    }

    public static ScanResult infected(String signature) {
        return new ScanResult(Verdict.INFECTED, signature, null);
    }

    public static ScanResult inconclusive(String reason) {
        return new ScanResult(Verdict.INCONCLUSIVE, null, reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
