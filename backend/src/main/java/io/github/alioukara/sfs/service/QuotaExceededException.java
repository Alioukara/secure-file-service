package io.github.alioukara.sfs.service;

public class QuotaExceededException extends RuntimeException {

    private final QuotaAccount account;

    public QuotaExceededException(QuotaAccount account, long usedBytes, long limitBytes) {
        super("Quota " + account + " reached: " + usedBytes + " of " + limitBytes + " bytes used");
        this.account = account;
    }

    public QuotaAccount getAccount() {
        return account;
    }
}
