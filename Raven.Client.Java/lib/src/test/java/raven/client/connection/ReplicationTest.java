package raven.client.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import raven.abstractions.data.Etag;
import raven.abstractions.data.JsonDocument;
import raven.abstractions.data.PutResult;
import raven.abstractions.data.UuidType;
import raven.abstractions.json.linq.RavenJArray;
import raven.abstractions.json.linq.RavenJObject;
import raven.abstractions.replication.ReplicationDestination;
import raven.abstractions.replication.ReplicationDestination.TransitiveReplicationOptions;
import raven.abstractions.replication.ReplicationDocument;


public class ReplicationTest extends AbstractReplicationTest {

  private static final String SOURCE = "source";
  private static final String TARGET = "target";

  @Test
  public void testCreateDb() throws Exception {
    try {
      createDb(SOURCE, 1);
      createDb(TARGET, 2);

      List<String> result = serverClient.getDatabaseNames(2);

      assertEquals(1, result.size());
      assertTrue(result.contains(SOURCE));

      IDatabaseCommands source = serverClient.forDatabase(SOURCE);
      IDatabaseCommands target = serverClient2.forDatabase(TARGET);

      ReplicationDocument repDoc = createReplicationDocument();

      RavenJObject o = new RavenJObject();
     // List<RavenJObject> destinations = new ArrayList<>();
     // RavenJObject dest = new RavenJObject();
     // dest.add("Url", );
      List<String> destinations = new ArrayList<>();
      destinations.add(DEFAULT_SERVER_URL_2);
      o.add("Destinations", new RavenJArray(destinations));

      source.put("Raven/Replication/Destinations", null, RavenJObject.fromObject(repDoc), new RavenJObject());

      Etag etag = new Etag();
      etag.setup(UuidType.DOCUMENTS, System.currentTimeMillis());

      PutResult putResult = source.put("testVal1", etag, RavenJObject.parse("{ \"key\" : \"val1\"}"), new RavenJObject());
      assertNotNull(result);

      JsonDocument jsonDocument = source.get("testVal1");
      assertEquals("val1", jsonDocument.getDataAsJson().get("key").value(String.class));

      Thread.sleep(1000);

      jsonDocument = target.get("testVal1");
      assertEquals("val1", jsonDocument.getDataAsJson().get("key").value(String.class));

    } finally {
      deleteDb(SOURCE, 1);
      deleteDb(TARGET, 2);
    }
  }

  protected ReplicationDocument createReplicationDocument() {
    ReplicationDestination rep = new ReplicationDestination();
    rep.setUrl(DEFAULT_SERVER_URL_2);
    rep.setDatabase(TARGET);
    rep.setTransitiveReplicationBehavior(TransitiveReplicationOptions.NONE);
    rep.setIgnoredClient(Boolean.FALSE);
    rep.setDisabled(Boolean.FALSE);
    ReplicationDocument repDoc = new ReplicationDocument();
    repDoc.getDestinations().add(rep);
    return repDoc;
  }


}
