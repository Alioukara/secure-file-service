package io.github.alioukara.sfs.domain;

public class IllegalStateTransitionException extends DomainException {

    private final FileStatus from;
    private final FileStatus to;

    public IllegalStateTransitionException(FileStatus from, FileStatus to) {
        super("Illegal transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public FileStatus getFrom() {
        return from;
    }

    public FileStatus getTo() {
        return to;
    }
}
