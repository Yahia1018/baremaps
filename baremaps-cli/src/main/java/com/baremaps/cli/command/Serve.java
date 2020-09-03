
package com.baremaps.cli.command;

import com.baremaps.cli.service.ChangePublisher;
import com.baremaps.cli.service.ConfigService;
import com.baremaps.cli.service.StyleService;
import com.baremaps.cli.service.TemplateService;
import com.baremaps.cli.service.TileService;
import com.baremaps.tiles.config.Config;
import com.baremaps.tiles.config.Loader;
import com.baremaps.tiles.store.PostgisTileStore;
import com.baremaps.tiles.store.TileStore;
import com.baremaps.util.postgis.PostgisHelper;
import com.baremaps.util.storage.BlobStore;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.streaming.ServerSentEvents;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Provider;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Serve vector tiles from the the Postgresql database.")
public class Serve implements Callable<Integer> {

  private static Logger logger = LogManager.getLogger();

  @Mixin
  private Mixins mixins;

  @Option(
      names = {"--database"},
      paramLabel = "JDBC",
      description = "The JDBC url of the Postgres database.",
      required = true)
  private String database;

  @Option(
      names = {"--config"},
      paramLabel = "YAML",
      description = "The YAML configuration file.",
      required = true)
  private URI config;

  @Option(
      names = {"--assets"},
      paramLabel = "ASSETS",
      description = "A directory containing assets.")
  private URI assets;

  @Option(
      names = {"--watch-changes"},
      paramLabel = "WATCH_CHANGES",
      description = "Watch for file changes.")
  private boolean watchChanges = false;

  private Server server;

  @Override
  public Integer call() throws IOException {
    Configurator.setRootLevel(Level.getLevel(mixins.logLevel.name()));
    logger.info("{} processors available", Runtime.getRuntime().availableProcessors());

    BlobStore blobStore = mixins.blobStore();
    Loader loader = new Loader(blobStore);
    Provider<Config> provider = () -> {
      try {
        return loader.load(this.config);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    Config config = provider.get();
    if (!watchChanges) {
      provider = () -> config;
    }

    logger.info("Initializing datasource");
    PoolingDataSource datasource = PostgisHelper.poolingDataSource(database);

    logger.info("Initializing tile reader");
    final TileStore tileStore = new PostgisTileStore(datasource, provider);

    logger.info("Initializing server");
    String host = config.getServer().getHost();
    int port = config.getServer().getPort();
    int threads = Runtime.getRuntime().availableProcessors();
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(threads);
    ServerBuilder builder = Server.builder()
        .defaultHostname(host)
        .http(port)
        .service("/", new TemplateService(provider))
        .service("/favicon.ico",
            FileService.of(ClassLoader.getSystemClassLoader(), "/favicon.ico"))
        .service("/config.yaml", new ConfigService(provider))
        .service("/style.json", new StyleService(provider))
        .service("regex:^/tiles/(?<z>[0-9]+)/(?<x>[0-9]+)/(?<y>[0-9]+).pbf$",
            new TileService(tileStore))
        .blockingTaskExecutor(executor, true);

    // Initialize the assets handler if a path has been provided
    if (assets != null) {
      builder.service("/assets/", FileService.of(Paths.get(assets.getPath())));
    }

    // Keep a connection open with the browser.
    // When the server restarts, for instance when a change occurs in the configuration,
    // The browser reloads the webpage and displays the changes.
    Path directory = Paths.get(this.config.getPath()).toAbsolutePath().getParent();
    if (watchChanges && Files.exists(directory)) {
      ChangePublisher publisher = new ChangePublisher(directory);
      builder.service("/changes/", (ctx, req) -> {
        ctx.clearRequestTimeout();
        return ServerSentEvents.fromPublisher(publisher);
      });
    }

    server = builder.build();
    server.start();

    return 0;
  }

}