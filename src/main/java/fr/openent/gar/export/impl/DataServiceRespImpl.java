package fr.openent.gar.export.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.helper.impl.PaginatorHelperImpl;
import fr.openent.gar.helper.impl.XmlExportHelperImpl;
import fr.openent.gar.export.DataService;
import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;


import static fr.openent.gar.constants.GarConstants.*;

public class DataServiceRespImpl extends DataServiceBaseImpl implements DataService {

    private final PaginatorHelperImpl paginator;
    private final String controlGroup;
    private final String entId;
    private final String source;

    DataServiceRespImpl(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source =source;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, RESP_ROOT, RESP_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
        controlGroup = config.getString("control-group", DEFAULT_CONTROL_GROUP);
    }

    @Override
    public void exportData(final Handler<Either<String, JsonObject>> handler) {
        getAndProcessRespFromNeo4j(0, respResults -> {
            if (validResponse(respResults, handler)) {
                xmlExportHelper.closeFile();
                handler.handle(new Either.Right<>(
                        new JsonObject().put(
                                FILE_LIST_KEY,
                                xmlExportHelper.getFileList()
                        )));
            } else {
                log.error("[DataServiceRespImpl@exportData] Failed to process");
            }
        });
    }

    private void getAndProcessRespFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getRespFromNeo4j(skip, respResults -> {
            if (validResponseNeo4j(respResults, handler)) {
                Either<String, JsonObject> result = processResp(respResults.right().getValue());

                if (respResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessRespFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl@getAndProcessRespFromNeo4j] Failed to process");
            }
        });
    }

    /**
     * Get person in charge of affectation from Neo4j
     * Get academic email when available, else get structure email, no personal email
     * @param handler results
     */
    private void getRespFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String query;

        if(source.equals(Gar.AAF1D)){
            query = "MATCH (s:Structure {source:'" + this.source + "'})" +
                    "<-[:DEPENDS]-(n:ManualGroup{name:\"" + controlGroup + "\"})<-[:IN]-(us:User) " +
                    " WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports";
        }else{
            query = "MATCH (s:Structure)" +
                    "<-[:DEPENDS]-(n:ManualGroup{name:\"" + controlGroup + "\"})<-[:IN]-(us:User) " +
                    " WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";
        }

        query += " AND head(us.profiles) IN ['Teacher','Personnel'] " +
                " AND us.deleteDate IS NULL " +
                " AND (us.emailAcademy IS NOT NULL OR us.emailInternal IS NOT NULL OR us.email IS NOT NULL) " +
                " WITH s, us ORDER BY s.id , us.id " +
                " WITH s, collect(us)[..50] as uc " +    // 50 first Teachers or Personnels in each Structures
                " UNWIND uc as u ";
        // CAUTION Don't use sr.UAI in dataReturn cause this structure is perhaps not a GAR structure
        String dataReturn = "RETURN DISTINCT u.id as `" + PERSON_ID + "`, " +
                "u.lastName as `" + PERSON_NAME + "`, " +
                "u.firstName as `" + PERSON_FIRST_NAME + "`, " +
                // Priority = emailAcademy > emailInternal > email
                "coalesce(u.emailAcademy, u.emailInternal, u.email) as `" + PERSON_MAIL + "`, " +
                "collect(s.UAI) as `" + RESP_ETAB + "` " +
                "order by `" + PERSON_ID + "` ";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }

    /**
     * Process person in charge
     * @param resps Array of respq from Neo4j
     */
    private Either<String, JsonObject> processResp(JsonArray resps) {
        Either<String, JsonObject> event = processSimpleArray(resps, RESP_NODE, RESP_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing resp : " + event.left().getValue());
        } else {
            return event;
        }
    }
}
