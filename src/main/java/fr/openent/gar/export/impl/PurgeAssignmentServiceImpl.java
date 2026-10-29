package fr.openent.gar.export.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.export.PurgeAssignmentService;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import static fr.openent.gar.Gar.CONFIG;

import static fr.openent.gar.constants.GarConstants.DEFAULT_CONTROL_GROUP;

public class PurgeAssignmentServiceImpl implements PurgeAssignmentService {
    static final Neo4j neo4j = Neo4j.getInstance();
    static final Logger log = LoggerFactory.getLogger(PurgeAssignmentServiceImpl.class);
    private final String garGroupName;
    private final JsonObject raAssignmentPolicy;

    public PurgeAssignmentServiceImpl() {
        this.garGroupName = CONFIG.getString("control-group", DEFAULT_CONTROL_GROUP);
        this.raAssignmentPolicy = CONFIG.getJsonObject("gar-ra-assignment-policy", new JsonObject());
    }

    @Override
    public void raPurge(final String entId, final String source, final Handler<String> handler) {
        log.info(String.format("RA purge launch, GAR : %s, source : %s", entId, source));

        final String inSource = (Gar.AAF1D.equals(source)) ? "s.source = '" + Gar.AAF1D + "' " : "s.source <> '" + Gar.AAF1D + "' ";
        final String query = "MATCH (s:Structure)<-[:DEPENDS]-(g:ManualGroup {name: $garGroupName})-[i:IN]-(u:User) WHERE " +
                inSource + "AND s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND NOT (s)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) " +
                "WITH distinct u, COLLECT(i) as notAffectStruct " +
                "OPTIONAL MATCH (s:Structure)<-[:DEPENDS]-(:ProfileGroup)-[i:IN]-(u) WHERE " +
                inSource + "AND s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                "WITH distinct u, notAffectStruct, count(i) as isInGarStruct " +
                "OPTIONAL MATCH (s:Structure)<-[:DEPENDS]-(g:ManualGroup {name: $garGroupName})-[i:IN]-(u) WHERE " +
                inSource + "AND s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports  AND (s)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u) " +
                "WITH distinct u, notAffectStruct, isInGarStruct, count(i) as nbRespAffect WHERE " +
                "(isInGarStruct=0 AND size(notAffectStruct)<=2) OR (isInGarStruct>0 AND size(notAffectStruct)=1 AND nbRespAffect=0) " +
                "UNWIND notAffectStruct as r DELETE r";

        final JsonObject params = new JsonObject().put("garGroupName", this.garGroupName).put("entId", entId);


        neo4j.execute(query, params, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                log.info(String.format("RA purge successfully, GAR : %s, source : %s", entId, source));
                handler.handle("ok");
            } else {
                log.error(String.format("RA purge error, GAR : %s, source : %s, message : %s", entId, source, res.body().getString("message")));
                handler.handle("ko");
            }
        });
    }

    @Override
    public void raAssignment(final String entId, final String source, final Handler<String> handler) {
        log.info(String.format("RA assignment launch, GAR : %s, source : %s", entId, source));

        final String inSource = (Gar.AAF1D.equals(source)) ? "s.source = '" + Gar.AAF1D + "' " : "s.source <> '" + Gar.AAF1D + "' ";
        String query = "MATCH (s:Structure)<-[:DEPENDS]-(g:ManualGroup {name: $garGroupName}), (s)<-[:DEPENDS]-(pg:ProfileGroup)<-[:IN]-(u:User) WHERE " +
                inSource + "AND s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports ";

        if (Gar.AAF1D.equals(source) && this.raAssignmentPolicy.containsKey(entId) && "teacher".equalsIgnoreCase(raAssignmentPolicy.getString(entId))) {
            query += "AND pg.filter = 'Teacher' ";
        } else {
            query += "AND pg.filter IN ['Teacher','Personnel'] AND ANY(function IN u.functions WHERE function CONTAINS '$DIR$' OR function CONTAINS '$DOC$' OR function CONTAINS '$DIRECTION') ";
        }
        // CREATE UNIQUE n'existe plus en Cypher 3.x/ArcadeDB : MERGE crée la relation avec ces
        // propriétés si elle manque, exactement comme lui.
        query += "MERGE (u)-[:IN {source:'MANUAL'}]->(g)";

        final JsonObject params = new JsonObject().put("garGroupName", this.garGroupName).put("entId", entId);

        neo4j.execute(query, params, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                log.info(String.format("RA assignment successfully, GAR : %s, source : %s", entId, source));
                handler.handle("ok");
            } else {
                log.error(String.format("RA assignment error, GAR : %s, source : %s, message : %s", entId, source, res.body().getString("message")));
                handler.handle("ko");
            }
        });
    }

    @Override
    public void raPurgeAssignment(String entId, String source, Handler<String> handler) {
        this.raPurge(entId, source, res -> {
            this.raAssignment(entId, source, handler);
        });
    }
}
