package io.gazetteer.osm.postgis;

import io.gazetteer.osm.model.Change;
import io.gazetteer.osm.model.Entity;
import io.gazetteer.osm.model.Node;
import io.gazetteer.osm.model.Relation;
import io.gazetteer.osm.model.Way;
import io.gazetteer.osm.postgis.NodeTable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.apache.commons.dbcp2.PoolingDataSource;

public class ChangeConsumer implements Consumer<Change> {

  private final PoolingDataSource pool;

  public ChangeConsumer(PoolingDataSource pool) {
    this.pool = pool;
  }

  @Override
  public void accept(Change change) {
    try (Connection connection = pool.getConnection()) {
      Entity entity = change.getEntity();
      if (entity instanceof Node) {
        Node node = (Node) entity;
        switch (change.getType()) {
          case create:
            NodeTable.insert(connection, node);
          case modify:
            NodeTable.update(connection, node);
          case delete:
            NodeTable.delete(connection, node.getInfo().getId());
        }
      } else if (entity instanceof Way) {
        Way way = (Way) entity;
        switch (change.getType()) {
          case create:
          case modify:
          case delete:
        }
      } else if (entity instanceof Relation) {
        switch (change.getType()) {
          case create:
          case delete:
          case modify:
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }


}