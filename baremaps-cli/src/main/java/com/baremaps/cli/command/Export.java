/*
 * Copyright (C) 2020 The Baremaps Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baremaps.cli.command;

import static com.baremaps.cli.option.TileReaderOption.fast;

import com.baremaps.cli.option.TileReaderOption;
import com.baremaps.tiles.TileStore;
import com.baremaps.tiles.config.Config;
import com.baremaps.tiles.database.FastPostgisTileStore;
import com.baremaps.tiles.database.SlowPostgisTileStore;
import com.baremaps.tiles.mbtiles.MBTilesTileStore;
import com.baremaps.tiles.store.FileSystemTileStore;
import com.baremaps.tiles.stream.BatchFilter;
import com.baremaps.tiles.stream.TileFactory;
import com.baremaps.util.fs.FileSystem;
import com.baremaps.util.postgis.PostgisHelper;
import com.baremaps.util.tile.Tile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.ParseException;
import org.sqlite.SQLiteDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "export", description = "Export vector tiles from the Postgresql database.")
public class Export implements Callable<Integer> {

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
      names = {"--repository"},
      paramLabel = "URL",
      description = "The tile repository URL.",
      required = true)
  private URI repository;

  @Option(
      names = {"--delta"},
      paramLabel = "DELTA",
      description = "The input delta file.")
  private URI delta;

  @Option(
      names = {"--reader"},
      paramLabel = "READER",
      description = "The tile reader.")
  private TileReaderOption tileReader = fast;

  @Option(
      names = {"--batch-array-size"},
      paramLabel = "BATCH_ARRAY_SIZE",
      description = "The size of the batch array.")
  private int batchArraySize = 1;

  @Option(
      names = {"--batch-array-index"},
      paramLabel = "READER",
      description = "The index of the batch in the array.")
  private int batchArrayIndex = 0;

  @Option(
      names = {"--mbtiles"},
      paramLabel = "MBTILES",
      description = "The repository is in the MBTiles format.")
  private boolean mbtiles = false;

  @Override
  public Integer call() throws SQLException, ParseException, IOException {
    Configurator.setRootLevel(Level.getLevel(mixins.logLevel.name()));
    logger.info("{} processors available", Runtime.getRuntime().availableProcessors());

    // Initialize the datasource
    PoolingDataSource datasource = PostgisHelper.poolingDataSource(database);

    // Initialize the filesystem
    FileSystem filesystem = mixins.filesystem();

    // Read the configuration file
    logger.info("Reading configuration");
    try (InputStream input = filesystem.read(this.config)) {
      Config config = Config.load(input);

      logger.info("Initializing the source tile store");
      final TileStore tileSource = sourceTileStore(config, datasource);

      logger.info("Initializing the target tile store");
      final TileStore tileTarget = targetTileStore(config, filesystem);

      // Export the tiles
      logger.info("Generating the tiles");

      final Stream<Tile> stream;
      if (delta == null) {
        Envelope envelope = new Envelope(
            config.getBounds().getMinLon(), config.getBounds().getMaxLon(),
            config.getBounds().getMinLat(), config.getBounds().getMaxLat());
        stream = Tile.getTiles(envelope,
            (int) config.getBounds().getMinZoom(),
            (int) config.getBounds().getMaxZoom());
      } else {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(filesystem.read(delta)))) {
          stream = reader.lines().flatMap(line -> {
            String[] array = line.split(",");
            int x = Integer.parseInt(array[0]);
            int y = Integer.parseInt(array[1]);
            int z = Integer.parseInt(array[2]);
            Tile tile = new Tile(x, y, z);
            return Tile.getTiles(tile.envelope(),
                (int) config.getBounds().getMinZoom(),
                (int) config.getBounds().getMaxZoom());
          });
        }
      }

      stream.parallel()
          .filter(new BatchFilter(batchArraySize, batchArrayIndex))
          .forEach(new TileFactory(tileSource, tileTarget));
    }

    return 0;
  }

  private TileStore sourceTileStore(Config config, PoolingDataSource datasource) {
    switch (tileReader) {
      case fast:
        return new FastPostgisTileStore(datasource, config);
      case slow:
        return new SlowPostgisTileStore(datasource, config);
      default:
        throw new UnsupportedOperationException("Unsupported tile reader");
    }
  }

  private TileStore targetTileStore(Config config, FileSystem fileSystem)
      throws IOException {
    if (mbtiles) {
      SQLiteDataSource dataSource = new SQLiteDataSource();
      dataSource.setUrl("jdbc:sqlite:" + repository.getPath());
      MBTilesTileStore tilesStore = new MBTilesTileStore(dataSource);
      tilesStore.initializeDatabase();
      tilesStore.writeMetadata(metadata(config));
      return tilesStore;
    } else {
      return new FileSystemTileStore(fileSystem, repository);
    }
  }

  private Map<String, String> metadata(Config config)
      throws JsonProcessingException {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("name", config.getId());
    metadata.put("version", config.getVersion());
    metadata.put("description", config.getDescription());
    metadata.put("attribution", config.getAttribution());
    metadata.put("type", config.getType());
    metadata.put("format", "pbf");
    metadata.put("center", String.format("%f, %f", config.getCenter().getLon(), config.getCenter().getLat()));
    metadata.put("bounds", String.format("%f, %f, %f, %f",
        config.getBounds().getMinLon(), config.getBounds().getMinLat(),
        config.getBounds().getMaxLon(), config.getBounds().getMaxLat()));
    metadata.put("minzoom", Double.toString(config.getBounds().getMinZoom()));
    metadata.put("maxzoom", Double.toString(config.getBounds().getMaxZoom()));
    List<Map<String, Object>> layers = config.getLayers().stream().map(layer -> {
      Map<String, Object> map = new HashMap<>();
      map.put("id", layer.getId());
      map.put("description", layer.getDescription());
      map.put("minzoom", Integer.toString(layer.getMinZoom()));
      map.put("maxzoom", Integer.toString(layer.getMaxZoom()));
      map.put("fields", layer.getFields());
      return map;
    }).collect(Collectors.toList());
    metadata.put("json", new ObjectMapper().writeValueAsString(layers));
    return metadata;
  }

}