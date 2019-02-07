package io.gazetteer.osm.osmxml;

import io.gazetteer.osm.model.Change;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.NoSuchElementException;

import static io.gazetteer.osm.OSMTestUtil.OSM_PBF_DATA;
import static org.junit.jupiter.api.Assertions.*;

public class ChangeIteratorTest {

  @Test
  public void next() throws Exception {
    Iterator<Change> reader = ChangeUtil.iterator(OSM_PBF_DATA);
    while (reader.hasNext()) {
      Change block = reader.next();
      assertNotNull(block);
    }
    assertFalse(reader.hasNext());
  }

  @Test
  public void nextException() throws Exception {
    assertThrows(NoSuchElementException.class, () -> {
      Iterator<Change> reader = ChangeUtil.iterator(OSM_PBF_DATA);
      while (reader.hasNext()) {
        reader.next();
      }
      reader.next();
    });
  }
}