package com.cras.service;

/** Thrown when an allocation needs more units than are in stock (mapped to HTTP 409). */
public class InsufficientStockException extends RuntimeException {
    private final int available;
    private final int requested;

    public InsufficientStockException(int available, int requested) {
        super("Insufficient inventory. Available=" + available + ", requested=" + requested);
        this.available = available;
        this.requested = requested;
    }

    public int getAvailable() { return available; }
    public int getRequested() { return requested; }
}
