package com.dwurdy.straja.domain.model;

import java.util.List;

/** Uniform service result: success/failure code plus emitted domain events. */
public record Result(boolean ok, String code, List<DomainEvent> events) {
    public static Result pass() {
        return new Result(true, null, List.of());
    }

    public static Result pass(List<DomainEvent> events) {
        return new Result(true, null, events);
    }

    public static Result fail(String code) {
        return new Result(false, code, List.of());
    }

    public static Result fail(String code, List<DomainEvent> events) {
        return new Result(false, code, events);
    }
}
