package com.spotify.heroic;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;
import javax.servlet.DispatcherType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewritePatternRule;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLog;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import com.spotify.heroic.config.HeroicConfig;
import com.spotify.heroic.consumer.Consumer;
import com.spotify.heroic.consumer.ConsumerConfig;
import com.spotify.heroic.http.query.QueryResource.StoredMetricQueries;
import com.spotify.heroic.injection.LifeCycle;
import com.spotify.heroic.metadata.ClusteredMetadataManager;
import com.spotify.heroic.statistics.HeroicReporter;
import com.spotify.heroic.statistics.semantic.SemanticHeroicReporter;
import com.spotify.metrics.core.MetricId;
import com.spotify.metrics.core.SemanticMetricRegistry;
import com.spotify.metrics.ffwd.FastForwardReporter;
import com.spotify.metrics.jvm.GarbageCollectorMetricSet;
import com.spotify.metrics.jvm.ThreadStatesMetricSet;

@Slf4j
public class Main {
    public static final String DEFAULT_CONFIG = "heroic.yml";

    public static Injector injector;

    public static Set<LifeCycle> managed = new HashSet<>();

    public static final GuiceServletContextListener LISTENER = new GuiceServletContextListener() {
        @Override
        protected Injector getInjector() {
            return injector;
        }
    };

    @RequiredArgsConstructor
    public static class IsSubclassOf extends AbstractMatcher<TypeLiteral<?>> {
        private final Class<?> type;

        @Override
        public boolean matches(TypeLiteral<?> t) {
            return type.isAssignableFrom(t.getRawType());
        }
    }

    @RequiredArgsConstructor
    public static class LifeCycleTypeListener implements TypeListener {
        private final Set<LifeCycle> managed;

        @Override
        public <I> void hear(final TypeLiteral<I> type,
                final TypeEncounter<I> encounter) {
            encounter.register(new InjectionListener<I>() {
                @Override
                public void afterInjection(I i) {
                    managed.add((LifeCycle) i);
                }
            });
        }
    }

    @RequiredArgsConstructor
    public static class BinderSetup {
        private final Set<LifeCycle> managed;
        private final IsSubclassOf lifecycleMatcher = new IsSubclassOf(
                LifeCycle.class);

        public void listen(Binder binder) {
            binder.bindListener(lifecycleMatcher, new LifeCycleTypeListener(
                    managed));
        }
    }

    public static Injector setupInjector(final HeroicConfig config,
            final HeroicReporter reporter,
            final ScheduledExecutorService scheduledExecutor,
            final ApplicationLifecycle lifecycle) throws Exception {
        log.info("Building Guice Injector");

        final List<Module> modules = new ArrayList<Module>();

        final BinderSetup binderSetup = new BinderSetup(managed);

        modules.add(new AbstractModule() {
            @Provides
            @Singleton
            public HeroicReporter reporter() {
                return reporter;
            }

            @Override
            protected void configure() {
                bind(ApplicationLifecycle.class).toInstance(lifecycle);
                bind(ScheduledExecutorService.class).toInstance(
                        scheduledExecutor);
                bind(ClusteredMetadataManager.class).in(Scopes.SINGLETON);
                bind(StoredMetricQueries.class).in(Scopes.SINGLETON);

                binderSetup.listen(binder());
            }
        });

        modules.add(new SchedulerModule(config.getRefreshClusterSchedule()));

        modules.add(config.getClient().module());
        modules.add(config.getMetrics().module());
        modules.add(config.getMetadata().module());
        modules.add(config.getCluster().module());
        modules.add(config.getCache().module());

        setupConsumers(config, reporter, modules);

        final Injector injector = Guice.createInjector(modules);

        // touch all bindings to make sure they are 'eagerly' initialized.
        for (final Entry<Key<?>, Binding<?>> entry : injector.getAllBindings()
                .entrySet()) {
            entry.getValue().getProvider().get();
        }

        return injector;
    }

    private static void setupConsumers(final HeroicConfig config,
            final HeroicReporter reporter, final List<Module> modules) {
        int consumerCount = 0;

        for (final ConsumerConfig consumer : config.getConsumers()) {
            final String id = consumer.id() != null ? consumer.id() : consumer
                    .buildId(consumerCount++);
            final Key<Consumer> key = Key.get(Consumer.class, Names.named(id));
            modules.add(consumer.module(key, reporter.newConsumer(id)));
        }
    }

    public static void main(String[] args) throws Exception {
        final String configPath;

        if (args.length < 1) {
            configPath = DEFAULT_CONFIG;
        } else {
            configPath = args[0];
        }

        final SemanticMetricRegistry registry = new SemanticMetricRegistry();
        final HeroicReporter reporter = new SemanticHeroicReporter(registry);

        final HeroicConfig config = setupConfig(configPath, reporter);

        if (config == null) {
            System.exit(1);
            return;
        }

        final ScheduledExecutorService scheduledExecutor = new ScheduledThreadPoolExecutor(
                10);

        final CountDownLatch startupLatch = new CountDownLatch(1);

        final ApplicationLifecycle lifecycle = new ApplicationLifecycle() {
            @Override
            public void awaitStartup() throws InterruptedException {
                startupLatch.await();
            }
        };

        injector = setupInjector(config, reporter, scheduledExecutor, lifecycle);

        final Server server = setupHttpServer(config);
        final FastForwardReporter ffwd = setupReporter(registry);

        try {
            server.start();
        } catch (final Exception e) {
            log.error("Failed to start server", e);
            System.exit(1);
            return;
        }

        /* fire startable handlers */
        if (!startLifeCycles()) {
            log.info("Failed to start all lifecycle components");
            System.exit(1);
            return;
        }

        final Scheduler scheduler = injector.getInstance(Scheduler.class);

        final CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(
                setupShutdownHook(ffwd, server, scheduler, latch,
                        scheduledExecutor));

        startupLatch.countDown();
        log.info("Heroic was successfully started!");

        latch.await();
        System.exit(0);
    }

    private static boolean startLifeCycles() {
        boolean ok = true;

        /* fire Stoppable handlers */
        for (final LifeCycle startable : managed) {
            log.info("Starting: {}", startable);

            try {
                startable.start();
            } catch (final Exception e) {
                log.error("Failed to start {}", startable, e);
                ok = false;
            }
        }

        return ok;
    }

    private static boolean stopLifeCycles() {
        boolean ok = true;

        /* fire Stoppable handlers */
        for (final LifeCycle stoppable : managed) {
            log.info("Stopping: {}", stoppable);

            try {
                stoppable.stop();
            } catch (final Exception e) {
                log.error("Failed to stop {}", stoppable, e);
                ok = false;
            }
        }

        return ok;
    }

    private static HeroicConfig setupConfig(final String configPath,
            final HeroicReporter reporter) throws IOException {
        log.info("Loading configuration from: {}", configPath);

        final HeroicConfig config;

        try {
            config = HeroicConfig.parse(Paths.get(configPath), reporter);
        } catch (final JsonMappingException e) {
            final JsonLocation location = e.getLocation();
            log.error(String.format("%s[%d:%d]: %s", configPath,
                    location == null ? null : location.getLineNr(),
                    location == null ? null : location.getColumnNr(),
                    e.getOriginalMessage()));

            if (log.isDebugEnabled())
                log.debug("Configuration error", e);

            return null;
        }

        return config;
    }

    private static Server setupHttpServer(final HeroicConfig config)
            throws IOException {
        log.info("Starting HTTP Server...");

        final Server server = new Server(config.getPort());

        final ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        // Initialize and register GuiceFilter
        context.addFilter(GuiceFilter.class, "/*",
                EnumSet.allOf(DispatcherType.class));
        context.addEventListener(Main.LISTENER);

        // Initialize and register Jersey ServletContainer
        final ServletHolder jerseyServlet = context.addServlet(
                ServletContainer.class, "/*");
        jerseyServlet.setInitOrder(1);
        jerseyServlet.setInitParameter("javax.ws.rs.Application",
                WebApp.class.getName());

        final RequestLogHandler requestLogHandler = new RequestLogHandler();

        requestLogHandler.setRequestLog(new Slf4jRequestLog());

        final RewriteHandler rewrite = new RewriteHandler();
        makeRewriteRules(rewrite);

        final HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { rewrite, context,
                requestLogHandler });

        server.setHandler(handlers);

        return server;
    }

    private static void makeRewriteRules(RewriteHandler rewrite) {
        {
            final RewritePatternRule rule = new RewritePatternRule();
            rule.setPattern("/metrics");
            rule.setReplacement("/query/metrics");
            rewrite.addRule(rule);
        }

        {
            final RewritePatternRule rule = new RewritePatternRule();
            rule.setPattern("/metrics-stream/*");
            rule.setReplacement("/query/metrics-stream");
            rewrite.addRule(rule);
        }

        {
            final RewritePatternRule rule = new RewritePatternRule();
            rule.setPattern("/tags");
            rule.setReplacement("/metadata/tags");
            rewrite.addRule(rule);
        }

        {
            final RewritePatternRule rule = new RewritePatternRule();
            rule.setPattern("/keys");
            rule.setReplacement("/metadata/keys");
            rewrite.addRule(rule);
        }
    }

    private static FastForwardReporter setupReporter(
            final SemanticMetricRegistry registry) throws IOException {
        final MetricId gauges = MetricId.build();

        registry.register(gauges, new ThreadStatesMetricSet());
        registry.register(gauges, new GarbageCollectorMetricSet());
        registry.register(gauges, new MemoryUsageGaugeSet());

        final FastForwardReporter ffwd = FastForwardReporter
                .forRegistry(registry).schedule(TimeUnit.SECONDS, 30)
                .prefix(MetricId.build("heroic").tagged("service", "heroic"))
                .build();

        ffwd.start();

        return ffwd;
    }

    private static Thread setupShutdownHook(final FastForwardReporter ffwd,
            final Server server, final Scheduler scheduler,
            final CountDownLatch latch,
            final ScheduledExecutorService scheduledExecutor) {
        return new Thread() {
            @Override
            public void run() {
                log.info("Shutting down Heroic");

                log.info("Shutting down scheduler");

                try {
                    scheduler.shutdown(true);
                } catch (final SchedulerException e) {
                    log.error("Scheduler shutdown failed", e);
                }

                try {
                    log.info("Waiting for server to shutdown");
                    server.stop();
                    server.join();
                } catch (final Exception e) {
                    log.error("Server shutdown failed", e);
                }

                log.info("Stopping scheduled executor service");

                scheduledExecutor.shutdownNow();

                try {
                    scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    log.error("Failed to shut down scheduled executor service");
                }

                log.info("Stopping life cycles");
                stopLifeCycles();

                log.info("Stopping fast forward reporter");
                ffwd.stop();

                if (LogManager.getContext() instanceof LoggerContext) {
                    log.info("Shutting down log4j2, Bye Bye!");
                    Configurator.shutdown((LoggerContext) LogManager
                            .getContext());
                } else {
                    log.warn("Unable to shutdown log4j2, Bye Bye!");
                }

                latch.countDown();
            }
        };
    }
}
