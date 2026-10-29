package fr.openent.gar.export.impl;

import fr.openent.gar.export.DataService;
import fr.openent.gar.helper.impl.PaginatorHelperImpl;
import fr.openent.gar.helper.impl.XmlExportHelperImpl;
import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.openent.gar.constants.GarConstants.*;
import static fr.openent.gar.export.impl.DataServiceGroupImpl.*;

public class DataServiceGroupImpl1d extends DataServiceBaseImpl implements DataService {

    private final PaginatorHelperImpl paginator;
    private final JsonObject config;
    private final String entId;
    private final String source;
    private final Boolean hasAcademyPrefix;

    DataServiceGroupImpl1d(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source = source;
        this.config = config;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, GROUPS_ROOT, GROUPS_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
        hasAcademyPrefix = this.config.containsKey("academy-prefix") && !"".equals(this.config.getString("academy-prefix").trim());
    }

    /**
     * Export Data to folder
     * - Export Groups info
     * - Export Groups content (people into the groups)
     * - Export Groups fields of study     *
     */
    @Override
    public void exportData(final Handler<Either<String, JsonObject>> handler) {
        getAndProcessGroupsInfoFromNeo4j(groupsResults -> {
            if (validResponse(groupsResults, handler)) {
                getAndProcessGroupsPersonFromNeo4j(groupPersonResults -> {
                    if (validResponse(groupPersonResults, handler)) {
                        xmlExportHelper.closeFile();
                        handler.handle(new Either.Right<>(
                                new JsonObject().put(
                                        FILE_LIST_KEY,
                                        xmlExportHelper.getFileList()
                                )));
                    }
                });
            }
        });
    }

    /**
     * Process groups info, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessGroupsInfoFromNeo4j(final Handler<Either<String, JsonObject>> handler) {
        final Map<String, Map<String, List<String>>> mapStructGroupClasses = new HashMap<>();
        getGroupsStructureInfoFromNeo4j(mapStructGroupClasses, this.source, entId, res -> {
            if (res.isRight()) {
                getAndProcessDivisionGroupsInfoFromNeo4j(0, event -> {
                    if (validResponse(event, handler)) {
                        getAndProcessOtherGroupsInfoFromNeo4j(0, mapStructGroupClasses, handler);
                    }
                });
            } else {
                handler.handle(new Either.Left<>(res.left().getValue()));
            }
        });
    }

    private void getAndProcessDivisionGroupsInfoFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getDivisionGroupsInfoFromNeo4j(skip, groupsResults -> {
            if (validResponseNeo4j(groupsResults, handler)) {
                Either<String, JsonObject> result = processGroupsInfo(groupsResults.right().getValue());

                if (groupsResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessDivisionGroupsInfoFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl1d@getAndProcessDivisionGroupsInfoFromNeo4j] Failed to process");
            }
        });
    }

    /**
     * Process groups info
     *
     * @param groups Array of groups from Neo4j
     */
    private Either<String, JsonObject> processGroupsInfo(JsonArray groups) {
        Either<String, JsonObject> event = processSimpleArray(groups, GROUPS_NODE, GROUPS_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing groups infos : " + event.left().getValue());
        } else {
            return event;
        }
    }

    private void getAndProcessOtherGroupsInfoFromNeo4j(int skip, final Map<String, Map<String, List<String>>> mapStructGroupClasses,
                                                       final Handler<Either<String, JsonObject>> handler) {
        getOtherGroupsInfoFromNeo4j(skip, groupsResults -> {
            if (validResponseNeo4j(groupsResults, handler)) {
                final JsonArray results = groupsResults.right().getValue();
                if (results != null && !results.isEmpty()) {
                    for (final Object obj : results.getList()) {
                        if (!(obj instanceof JsonObject)) continue;
                        final JsonObject jo = (JsonObject) obj;

                        final Map<String, List<String>> groupClasses = mapStructGroupClasses.get(jo.getString(STRUCTURE_UAI));
                        if (groupClasses != null) {
                            if (groupClasses.containsKey(jo.getString(GROUPS_CODE))) {
                                jo.put(GROUPS_DIVISION, new JsonArray(groupClasses.get(jo.getString(GROUPS_CODE))));
                            }
                        }
                    }

                    Either<String, JsonObject> result = processGroupsInfo(results);

                    if (results.size() == PaginatorHelperImpl.LIMIT) {
                        getAndProcessOtherGroupsInfoFromNeo4j(skip + PaginatorHelperImpl.LIMIT, mapStructGroupClasses, handler);
                    } else {
                        handler.handle(result);
                    }
                } else {
                    handler.handle(new Either.Right<>(null));
                }
            } else {
                log.error("[DataServiceGroupImpl1d@getAndProcessOtherGroupsInfoFromNeo4j] Failed to process");
            }
        });
    }

    private void getAndProcessGroupsPersonFromNeo4j(final Handler<Either<String, JsonObject>> handler) {
        getAndProcessDivisionGroupsPersonFromNeo4j(0, event -> {
            if (validResponse(event, handler)) {
                getAndProcessOtherGroupsPersonFromNeo4j(0, handler);
            }
        });
    }

    private void getAndProcessDivisionGroupsPersonFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getDivisionGroupsPersonFromNeo4j(skip, groupPersonResults -> {
            if (validResponseNeo4j(groupPersonResults, handler)) {
                Either<String, JsonObject> result = processGroupPersonInfo(groupPersonResults.right().getValue());

                if (groupPersonResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessDivisionGroupsPersonFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl@getAndProcessDivisionGroupsPersonFromNeo4j] Failed to process");
            }
        });
    }

    private void getAndProcessOtherGroupsPersonFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getOtherGroupsPersonFromNeo4j(skip, groupPersonResults -> {
            if (validResponseNeo4j(groupPersonResults, handler)) {
                Either<String, JsonObject> result = processGroupPersonInfo(groupPersonResults.right().getValue());

                if (groupPersonResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessOtherGroupsPersonFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl@getAndProcessOtherGroupsPersonFromNeo4j] Failed to process");
            }
        });
    }

    /**
     * Process groups content
     *
     * @param groupPerson Array of group content from Neo4j
     */
    private Either<String, JsonObject> processGroupPersonInfo(JsonArray groupPerson) {
        Either<String, JsonObject> event =
                processSimpleArray(groupPerson, GROUPS_PERSON_NODE, GROUPS_PERSON_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing groups content : " + event.left().getValue());
        } else {
            return event;
        }
    }

    /**
     * Get groups info from Neo4j
     * Get classes (or divisions)
     * Get user groups. Link groups to classes only for students
     * Get group external id if it exists, else get internal id
     *
     * @param handler results
     */
    private void getOtherGroupsInfoFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        final String condition;
        if (hasAcademyPrefix) {
            condition = "CASE WHEN fg.externalId =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' THEN reduce(v=fg.externalId, prefix in split('" +
                    this.config.getString("academy-prefix") +"', '|') | replace(v, prefix + '-', '')) ELSE fg.externalId END as fgcode";
        } else {
            condition = "split(fg.externalId,\"-\") as fgcode";
        }

        final String groupsQuery = "MATCH (s:Structure)<-[:BELONGS]-(c:Class) WITH collect(c.name) as classes " +
                "MATCH (u:User)-[:IN]->(fg:FunctionalGroup)-[d2:DEPENDS]->(s:Structure {source:'" + this.source + "'}) " +
                "WHERE NOT (fg.name IN classes) " +
                "WITH u,s,fg MATCH (u)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                "AND head(u.profiles) IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "with distinct s.UAI as uai, fg, " + condition +
                " return distinct coalesce(" + (hasAcademyPrefix ? "fgcode" : "fgcode[size(fgcode)-1]") + ", fg.id) as `" + GROUPS_CODE + "`, " +
                "uai as `" + STRUCTURE_UAI + "`, " +
                "fg.name as `" + GROUPS_DESC + "`, " +
                "\"" + GROUPS_GROUP_NAME + "\" as `" + GROUPS_STATUS + "` " +
                "order by `" + STRUCTURE_UAI + "`, `" + GROUPS_CODE + "` " +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(groupsQuery, params, skip, handler);
    }


    /**
     * Get groups info from Neo4j
     * Get classes (or divisions)
     * Get user groups. Link groups to classes only for students
     * Get group external id if it exists, else get internal id
     *
     * @param handler results
     */
    private void getDivisionGroupsInfoFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        //in 1D (no more split(c.externalId,"$")[1] for code)
        final String condition;
        if (hasAcademyPrefix) {
            condition = "CASE WHEN c.externalId =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' " +
                    "THEN reduce(v=c.externalId, prefix in split('" +
                    this.config.getString("academy-prefix") +"', '|') | replace(v, prefix + '-', '')) " +
                    "ELSE c.externalId END as ccode";
        } else {
            condition = "split(c.externalId,\"-\") as ccode";
        }
        final String classQuery = "MATCH (c:Class)-[:BELONGS]->(s:Structure {source:'" + this.source + "'}) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                "WITH distinct s.UAI as uai, c, " + condition +
                " RETURN distinct " +
                "coalesce(" + (hasAcademyPrefix ? "ccode" : "ccode[size(ccode)-1]") + ", c.id) as `" + GROUPS_CODE + "`, " +
                "uai as `" + STRUCTURE_UAI + "`, " +
                "c.name as `" + GROUPS_DESC + "`, " +
                "\"" + GROUPS_DIVISION_NAME + "\" as `" + GROUPS_STATUS + "` " +
                "order by `" + STRUCTURE_UAI + "`, `" + GROUPS_CODE + "` " +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(classQuery, params, skip, handler);
    }

    /**
     * Get groups content from Neo4j
     * Use external id for groups when available
     *
     * @param handler results
     */
    private void getDivisionGroupsPersonFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        //in 1D (no more split(c.externalId,"$")[1] for code)
        final String condition;
        if (hasAcademyPrefix) {
            condition = "CASE WHEN c.externalId =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' " +
                    "THEN reduce(v=c.externalId, prefix in split('" +
                    this.config.getString("academy-prefix") +"', '|') | replace(v, prefix + '-', '')) " +
                    "ELSE c.externalId END as ccode";
        } else {
            condition = "split(c.externalId,\"-\") as ccode";
        }
        final String classQuery = "MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure {source:'" + this.source + "'})" +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                "AND pg.filter IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "WITH distinct s.UAI as uai, u.id as uid, c, " + condition +
                " return distinct uai as `" + STRUCTURE_UAI + "`, " +
                "uid as `" + PERSON_ID + "`, " +
                "coalesce(" + (hasAcademyPrefix ? "ccode" : "ccode[size(ccode)-1]") + ", c.id) as `" + GROUPS_CODE + "` " +
                "order by `" + PERSON_ID + "`, `" + GROUPS_CODE + "`, `" + STRUCTURE_UAI + "` " +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(classQuery, params, skip, handler);
    }

    /**
     * Get groups content from Neo4j
     * Use external id for groups when available
     *
     * @param handler results
     */
    private void getOtherGroupsPersonFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        final String condition;
        if (hasAcademyPrefix) {
            condition = "CASE WHEN fg.externalId =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' " +
                    "THEN reduce(v=fg.externalId, prefix in split('" +
                    this.config.getString("academy-prefix") +"', '|') | replace(v, prefix + '-', '')) " +
                    "ELSE fg.externalId END as fgcode";
        } else {
            condition = "split(fg.externalId,\"-\") as fgcode";
        }
        final String groupsQuery =
                "MATCH (s:Structure {source:'" + this.source + "'}) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                "OPTIONAL MATCH (s)<-[:BELONGS]-(c:Class) " +
                "WITH collect(c.name) as classes, s " +
                "MATCH (s)<-[:DEPENDS]-(fg:FunctionalGroup)<-[:IN]-(u:User) " +
                "WHERE NOT (fg.name IN classes) AND head(u.profiles) IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "with distinct s.UAI as uai, u.id as uid, fg, " + condition +
                " return distinct uai as `" + STRUCTURE_UAI + "`, " +
                "uid as `" + PERSON_ID + "`, " +
                "coalesce(" + (hasAcademyPrefix ? "fgcode" : "fgcode[size(fgcode)-1]") + ", fg.id) as `" + GROUPS_CODE + "` " +
                "order by `" + PERSON_ID + "`, `" + GROUPS_CODE + "`, `" + STRUCTURE_UAI + "`" +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(groupsQuery, params, skip, handler);
    }
}
