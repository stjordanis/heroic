package com.spotify.heroic.metrics;

public class MetricQueryException extends Exception {
    private static final long serialVersionUID = 7030576547562919861L;

    public MetricQueryException(String string) {
        super(string);
    }
}
