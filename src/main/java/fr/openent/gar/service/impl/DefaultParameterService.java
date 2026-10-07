package fr.openent.gar.service.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.constants.Field;
import fr.openent.gar.helper.IModelHelper;
import fr.openent.gar.model.Neo4jStructure;
import fr.openent.gar.service.ParameterService;
import fr.wseduc.webutils.Either;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;

import java.util.List;

public class DefaultParameterService implements ParameterService {

    private final EventBus eb;
    private final Logger log = LoggerFactory.getLogger(DefaultParameterService.class);
    private static final String GAR_GROUP_NAME = "RESP-AFFECT-GAR";
    private static final String GAR_GROUP_SOURCE = "MANUAL";
    private static final String GAR_LINK_NAME = "GAR_AFFECTATION_IHM_CONNECTEUR";
    private static final String FUNCTION_DIRECTION_NAME = "DIR";
    private static final String FUNCTION_DOCUMENTATION_NAME = "DOC";


    public DefaultParameterService(EventBus eb) {
        this.eb = eb;
    }

    @Override
    public void undeployStructureGar(String structureId, String entId, Handler<Either<String, JsonObject>> handler) {
        String query = "MATCH (s:Structure {id:{structureId}}) SET s.exports = FILTER(val IN s.exports " +
                "WHERE val <> ('GAR-' + {entId})) RETURN s.exports;";
        JsonObject params = new JsonObject()
                .put("structureId", structureId)
                .put("entId", entId);
        Neo4j.getInstance().execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
    }

    @Override
    public void getStructureGar(String entId, Handler<Either<String, JsonArray>> handler) {
        String query = "MATCH (s:Structure) WHERE HAS(s.UAI) OPTIONAL MATCH (s)<-[:DEPENDS]-(g:ManualGroup{name: {groupName} }) " +
                "WHERE s.source starts with '"+ Gar.AAF +"' " +
                "RETURN DISTINCT s.UAI as uai, s.name as name, s.id as structureId, s.source as source, " +
                "(HAS(s.exports) AND ('GAR-' + {entId}) IN s.exports) as deployed, g.id as id";

        JsonObject params = new JsonObject().put("groupName", GAR_GROUP_NAME).put("entId", entId);
        Neo4j.getInstance().execute(query, params, Neo4jResult.validResultHandler(handler));
    }

    @Override
    public Future<List<Neo4jStructure>> getDeployedStructureGar(String entId) {
        Promise<List<Neo4jStructure>> promise = Promise.promise();

        String query = "MATCH (s:Structure) WHERE HAS(s.UAI) AND " +
                "s.source starts with '"+ Gar.AAF +"' AND (HAS(s.exports) AND ('GAR-' + {entId}) IN s.exports) " +
                "RETURN s.UAI as uai";
        JsonObject params = new JsonObject().put("entId", entId);

        String error = String.format("[DefaultParameterService@%s::getDeployedStructureGar] Fail to get deployed structure", this.getClass().getSimpleName());
        Neo4j.getInstance().execute(query, params, Neo4jResult.validResultHandler(IModelHelper.sqlResultToIModel(promise, Neo4jStructure.class, error)));
        return promise.future();
    }

    @Override
    public void createGarGroupToStructure(JsonObject body, Handler<Either<String, JsonObject>> handler) {
        String query = "MATCH (s:Structure {id:{structureId}}) " +
                "OPTIONAL MATCH (s)<-[:DEPENDS]-(g:ManualGroup{name: {groupName} }) " +
                "SET s.exports = coalesce(s.exports, []) + ('GAR-' + {entId}) RETURN g.id as groupId";
        JsonObject creationParams = new JsonObject()
                .put("structureId", body.getString("structureId"))
                .put("entId", body.getString("entId"))
                .put("groupName", GAR_GROUP_NAME);
        Neo4j.getInstance().execute(query, creationParams, Neo4jResult.validUniqueResultHandler(either -> {
            if (either.isLeft()) {
                handler.handle(new Either.Left<>("Failed to deploy structure"));
                return;
            }

            JsonObject creationResult = either.right().getValue();
            if (!(null == creationResult.getValue("groupId"))) {
                handler.handle(new Either.Right<>(new JsonObject()));
                return;
            }

            body.put("groupDisplayName", body.getString("name"));
            JsonObject action = new JsonObject()
                    .put("action", "manual-create-group")
                    .put("structureId", body.getString("structureId"))
                    .put("classId", body.getString("classId"))
                    .put("group", body);
            eb.request("entcore.feeder", action, (Handler<AsyncResult<Message<JsonObject>>>) createGarResult -> {
                if (createGarResult.failed()) {
                    handler.handle(new Either.Left<>("Failed to create gar group"));
                    return;
                }

                String groupId = createGarResult.result().body()
                        .getJsonArray("results")
                        .getJsonArray(0)
                        .getJsonObject(0).getString("id");

                String queryRole = "MATCH (a:Application)-[]->(ac:Action)<-[]-(r:Role)" +
                        " WHERE a.name = {linkName} RETURN r.id as id";

                Neo4j.getInstance().execute(queryRole, new JsonObject().put("linkName", GAR_LINK_NAME),
                        Neo4jResult.validUniqueResultHandler(linkResult -> {
                            if (linkResult.isLeft() || !linkResult.right().getValue().containsKey("id")) {
                                // 0 ligne (rôle GAR_LINK_NAME introuvable) : validUniqueResultHandler renvoie un
                                // faux succès avec un JsonObject vide (cf. audit SqlResult/Neo4jResult), d'où le
                                // contrôle explicite de "id" en plus de isLeft() — sans lui, la relation
                                // (Group)-[:AUTHORIZED]->(Role) n'était jamais créée mais l'appelant recevait
                                // quand même un succès.
                                handler.handle(new Either.Left<>("Failed to fetch role id"));
                                return;
                            }
                            String roleId = linkResult.right().getValue().getString("id");
                            String queryLink = "MATCH (r:Role), (g:Group) " +
                                    "WHERE r.id = {roleId} and g.id = {groupId} " +
                                    "CREATE UNIQUE (g)-[:AUTHORIZED]->(r) ";
                            JsonObject params = new JsonObject()
                                    .put("groupId", groupId)
                                    .put("roleId", roleId);
                            Neo4j.getInstance().execute(queryLink, params, Neo4jResult.validUniqueResultHandler(handler));
                        }));
            });
        }));
    }

    @Override
    public void addUserToGarGroup(JsonObject body, Handler<Either<String, JsonObject>> handler) {
        final String query;
        final JsonObject params = new JsonObject()
                .put("groupName", GAR_GROUP_NAME)
                .put("source", GAR_GROUP_SOURCE)
                .put("groupId", body.getString("groupId"))
                .put("structureId", body.getString("structureId"));
        if (Gar.AAF.equals(body.getString("source"))) {
            query = "match (g:ManualGroup{name: {groupName}, id: {groupId} }), " +
                    "(u:User{profiles:['Personnel']})--(Structure{id: {structureId} }) " +
                    "WHERE ANY(function IN u.functions WHERE function CONTAINS {direction} OR function CONTAINS {documentation}) " +
                    "create unique (u)-[r:IN{source:{source}}]->(g) ";
            params.put("direction", FUNCTION_DIRECTION_NAME);
            params.put("documentation", FUNCTION_DOCUMENTATION_NAME);
        } else {
            query = "MATCH (g:ManualGroup{name: {groupName}, id: {groupId} }), " +
                    "(s:Structure {id: {structureId}})-[:DEPENDS]-(pg:ProfileGroup)-[:IN]-(u:User)-[r:HAS_FUNCTION]-n " +
                    "WHERE n.externalId='ADMIN_LOCAL' and s.id IN r.scope " +
                    "create unique (u)-[:IN{source:{source}}]->(g) ";
        }
        Neo4j.getInstance().execute(query, params, Neo4jResult.validUniqueResultHandler(handler));
    }

    @Override
    public Future<JsonArray> userHasGarGroup(JsonObject body) {
        Promise<JsonArray> promise = Promise.promise();
        final String query =
                "MATCH (u:User)-[:IN]->(g:ManualGroup{name: {groupName} })-[:DEPENDS]->(s:Structure) " +
                "WHERE s.id IN {structureIds} AND u.id = {userId} " +
                "RETURN DISTINCT s.id AS structureId";
        final JsonObject params = new JsonObject()
                .put(Field.USER_ID, body.getString(Field.USER_ID))
                .put(Field.STRUCTURE_IDS, body.getJsonArray(Field.STRUCTURE_IDS))
                .put(Field.GROUP_NAME, GAR_GROUP_NAME);

        Neo4j.getInstance().execute(query, params, Neo4jResult.validResultHandler(either -> {
            if (either.isLeft()) {
                promise.fail(either.left().getValue());
                log.error(String.format("[DefaultParameterService@%s::userHasGarGroup] Error during request neo4j check %s.", this.getClass().getSimpleName(), either.left().getValue()));
            } else {
                promise.complete(either.right().getValue());
            }
        }));

        return promise.future();
    }
}
