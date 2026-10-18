package com.ibm.asyncutil.util;

import java.util.Optional;

/** Test stand-in for the Either that C2ME's storage thread keeps its queued saves in. */
public interface Either<L, R> {
    Optional<L> left();

    Optional<R> right();

    static <L, R> Either<L, R> ofLeft(L value) {
        return new Either<>() {
            public Optional<L> left() { return Optional.of(value); }
            public Optional<R> right() { return Optional.empty(); }
        };
    }

    static <L, R> Either<L, R> ofRight(R value) {
        return new Either<>() {
            public Optional<L> left() { return Optional.empty(); }
            public Optional<R> right() { return Optional.of(value); }
        };
    }
}
