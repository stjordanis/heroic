package com.spotify.heroic.statistics;

public interface MetadataManagerReporter {
    public CallbackReporter.Context reportRefresh();

    public CallbackReporter.Context reportFindTags();

    public CallbackReporter.Context reportFindTimeSeries();

    public CallbackReporter.Context reportFindKeys();

    public MetadataBackendReporter newMetadataBackend(String id);
}