package fr.openent.gar.services;

import fr.openent.gar.service.impl.DefaultParameterService;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jRest;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.stubbing.Answer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;

@RunWith(VertxUnitRunner.class)
public class DefaultParameterServiceTest {

    private Vertx vertx;
    private final Neo4j neo4j = Neo4j.getInstance();
    private final Neo4jRest neo4jRest = Mockito.mock(Neo4jRest.class);

    private DefaultParameterService parameterService;

    @Before
    public void setUp() throws NoSuchFieldException {
        vertx = Vertx.vertx();

        FieldSetter.setField(neo4j, neo4j.getClass().getDeclaredField("database"), neo4jRest);

        this.parameterService = new DefaultParameterService(vertx.eventBus());
    }

    @Test
    public void testUserHasGarGroup(TestContext ctx) {
        Async async = ctx.async();

        String expectedQuery =
                "MATCH (u:User {id: $userId})-[:IN]->(g:ManualGroup{name: $groupName })-[:DEPENDS]->(s:Structure) " +
                "WHERE s.id IN $structureIds " +
                "RETURN DISTINCT s.id AS structureId";
        JsonObject expectedParams = new JsonObject()
                .put("userId", "a25cd679-b30b-4701-8c60-231cdc30cdf2")
                .put("structureIds", new JsonArray().add("5c04e497-cb43-4589-8332-16cc8a873920"))
                .put("groupName", "RESP-AFFECT-GAR");

        JsonObject mockParams = new JsonObject()
                .put("userId", "a25cd679-b30b-4701-8c60-231cdc30cdf2")
                .put("structureIds", new JsonArray().add("5c04e497-cb43-4589-8332-16cc8a873920"));

        Mockito.doAnswer((Answer<Void>) invocation -> {
            String queryResult = invocation.getArgument(0);
            JsonObject paramsResult = invocation.getArgument(1);
            ctx.assertEquals(queryResult, expectedQuery);
            ctx.assertEquals(paramsResult.toString(), expectedParams.toString());
            async.complete();
            return null;
        }).when(neo4jRest).execute(Mockito.anyString(), Mockito.any(JsonObject.class), Mockito.any(Handler.class));

        try {
            Whitebox.invokeMethod(this.parameterService, "userHasGarGroup", mockParams);
        } catch (Exception e) {
            ctx.assertNull(e);
        }

        async.await(10000);
    }
}
