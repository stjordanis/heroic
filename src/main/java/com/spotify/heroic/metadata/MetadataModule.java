package com.spotify.heroic.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Singleton;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.spotify.heroic.statistics.HeroicReporter;
import com.spotify.heroic.statistics.MetadataManagerReporter;

@RequiredArgsConstructor
public class MetadataModule extends PrivateModule {
    private static final List<MetadataBackendConfig> DEFAULT_BACKENDS = new ArrayList<>();

    private final List<MetadataBackendConfig> backends;

    @JsonCreator
    public static MetadataModule create(@JsonProperty("backends") List<MetadataBackendConfig> backends) {
        if (backends == null) {
            backends = DEFAULT_BACKENDS;
        }

        return new MetadataModule(backends);
    }

    public static MetadataModule createDefault() {
        return create(null);
    }

    @Provides
    @Singleton
    public MetadataManagerReporter reporter(HeroicReporter reporter) {
        return reporter.newMetadataBackendManager();
    }

    @Override
    protected void configure() {
        bindBackends(backends);
        bind(MetadataManager.class).in(Scopes.SINGLETON);
        expose(MetadataManager.class);
    }

    private void bindBackends(final Collection<MetadataBackendConfig> configs) {
        final Multibinder<MetadataBackend> bindings = Multibinder.newSetBinder(binder(), MetadataBackend.class,
                Names.named("backends"));

        int i = 0;

        for (final MetadataBackendConfig config : configs) {
            final String id = config.id() != null ? config.id() : config.buildId(i++);

            final Key<MetadataBackend> key = Key.get(MetadataBackend.class, Names.named(id));

            install(config.module(key, id));

            bindings.addBinding().to(key);
        }
    }
}