package fr.openent.gar.export.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.export.DataService;
import fr.openent.gar.helper.impl.PaginatorHelperImpl;
import fr.openent.gar.helper.impl.XmlExportHelperImpl;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Utils;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import static fr.openent.gar.constants.GarConstants.*;

import java.util.*;

public class DataServiceGroupImpl extends DataServiceBaseImpl implements DataService {

    private final String entId;
    private final String source;
    private final PaginatorHelperImpl paginator;
    private final JsonObject config;

    DataServiceGroupImpl(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source = source;
        this.config = config;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, GROUPS_ROOT, GROUPS_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
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
                        getAndProcessFosFromNeo4j(groupFosResults -> {
                            if (validResponse(groupFosResults, handler)) {
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
                log.error("[DataServiceGroupImpl@getAndProcessDivisionGroupsInfoFromNeo4j] Failed to process");
            }
        });
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
                log.error("[DataServiceGroupImpl@getAndProcessOtherGroupsInfoFromNeo4j] Failed to process");
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


    private void getAndProcessFosFromNeo4j(final Handler<Either<String, JsonObject>> handler) {
        getAndProcessGroupsFosFromNeo4j(0, event -> {
            if (validResponse(event, handler)) {
                getAndProcessClassesFosFromNeo4j(0, handler);
            }
        });
    }

    private void getAndProcessGroupsFosFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getGroupsFosFromNeo4j(skip, groupFosResults -> {
            if (validResponseNeo4j(groupFosResults, handler)) {
                Either<String, JsonObject> result = processGroupFosInfo(groupFosResults.right().getValue());

                if (groupFosResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessGroupsFosFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl@getAndProcessGroupsFosFromNeo4j] Failed to process");
            }
        });
    }

    private void getAndProcessClassesFosFromNeo4j(int skip, final Handler<Either<String, JsonObject>> handler) {
        getClassesFosFromNeo4j(skip, classesFosResults -> {
            if (validResponseNeo4j(classesFosResults, handler)) {
                Either<String, JsonObject> result = processClassFosInfo(classesFosResults.right().getValue());

                if (classesFosResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessClassesFosFromNeo4j(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceGroupImpl@getAndProcessClassesFosFromNeo4j] Failed to process");
            }
        });
    }

    static void getGroupsStructureInfoFromNeo4j(Map<String, Map<String, List<String>>> mapStructGroupClasses, String source,
                                                 String entId, Handler<Either<String, Boolean>> handler) {
        //in AAF1D s.groups is null for the moment
        final String divisionQuery;
        if(source.equals(Gar.AAF1D)){
            divisionQuery = "MATCH (s:Structure {source:'" + source + "'}) " +
                    "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports " +
                    "RETURN distinct s.UAI as uai, s.groups as groups";
        }else{
            divisionQuery = "MATCH (s:Structure) " +
                    "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                    "RETURN distinct s.UAI as uai, s.groups as groups";
        }

        JsonObject params = new JsonObject().put("entId", entId);

        neo4j.execute(divisionQuery, params, res -> {
            if (res.body() != null && res.body().containsKey("result")) {
                JsonArray queryResult = Utils.getOrElse(res.body().getJsonArray("result"), new JsonArray());

                queryResult.forEach((entry) -> {
                    if (entry instanceof JsonObject) {
                        JsonObject field = (JsonObject) entry;
                        String uai = field.getString("uai", "");
                        final HashMap<String, List<String>> mapGroup = new HashMap<>();

                        final JsonArray groups = field.getJsonArray("groups");

                        if (groups != null && !groups.isEmpty()) {
                            groups.forEach((group) -> {
                                String gp = (String) group;
                                if (gp != null && !gp.isEmpty()) {
                                    final String[] elems = gp.split("\\$");
                                    if (elems.length >= 3) {
                                        final List<String> classes = new ArrayList<>(Arrays.asList(elems).subList(2, elems.length));

                                        if (!classes.isEmpty()) {
                                            mapGroup.put(elems[0], classes);
                                        }
                                    }
                                }
                            });

                            if (!mapGroup.isEmpty()) {
                                mapStructGroupClasses.put(uai, mapGroup);
                            }
                        }
                    }
                });
                handler.handle(new Either.Right<>(true));
            } else {
                log.error("[DataServiceGroupImple@getGroupsStructureInfoFromNeo4j] Failed to process divisionQuery");
                handler.handle(new Either.Left<>("error"));
            }
        });
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
        final String groupsQuery = "MATCH (s:Structure)<-[:BELONGS]-(c:Class) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                "WITH collect(c.name) as classes, s " +
                "MATCH (u:User)-[:IN]->(fg:FunctionalGroup)-[d2:DEPENDS]->(s:Structure) " +
                "WHERE NOT (fg.name IN classes) " +
                "AND head(u.profiles) IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "WITH u,s,fg MATCH (u)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s) " +
                "with s.UAI as uai, fg " +
                "return distinct " +
                "coalesce(split(fg.externalId,\"$\")[1], fg.id) as `" + GROUPS_CODE + "`, " +
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
        final String classQuery = "MATCH (c:Class)-[:BELONGS]->(s:Structure)" +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                "RETURN distinct " +
                "split(c.externalId,\"$\")[1] as `" + GROUPS_CODE + "`, " +
                "s.UAI as `" + STRUCTURE_UAI + "`, " +
                "c.name as `" + GROUPS_DESC + "`, " +
                "\"" + GROUPS_DIVISION_NAME + "\" as `" + GROUPS_STATUS + "` " +
                "order by `" + STRUCTURE_UAI + "`, `" + GROUPS_CODE + "` " +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(classQuery, params, skip, handler);
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

    /**
     * Get groups content from Neo4j
     * Use external id for groups when available
     *
     * @param handler results
     */
    private void getDivisionGroupsPersonFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        final String classQuery = "MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure)" +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                "AND pg.filter IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "u.id as `" + PERSON_ID + "`, " +
                "coalesce(split(c.externalId,\"$\")[1], c.id) as `" + GROUPS_CODE + "` " +
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
        final String groupsQuery = "MATCH (s:Structure)<-[:BELONGS]-(c:Class) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                "WITH collect(c.name) as classes, s " +
                "MATCH (u:User)-[:IN]->(fg:FunctionalGroup)-[:DEPENDS]->(s:Structure) " +
                "WHERE NOT (fg.name IN classes) " +
                "AND head(u.profiles) IN ['Student', 'Teacher', 'Personnel'] " +
                "AND u.deleteDate IS NULL " +
                "WITH u,s,fg MATCH (u)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(s) " +
                "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "u.id as `" + PERSON_ID + "`, " +
                "coalesce(split(fg.externalId,\"$\")[1], fg.id) as `" + GROUPS_CODE + "` " +
                "order by `" + PERSON_ID + "`, `" + GROUPS_CODE + "`, `" + STRUCTURE_UAI + "`" +
                " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(groupsQuery, params, skip, handler);
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
     * Get groups fields of study from Neo4j
     * Use external id for groups when available
     * Field of study code may be prefixed by ACADEMY-
     *
     * @param handler results
     */
    private void getClassesFosFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String condition;
        if (this.config.containsKey("academy-prefix") && !"".equals(this.config.getString("academy-prefix").trim())) {
            condition = "CASE WHEN sub.code =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' THEN reduce(v=sub.code, prefix in split('" +
                    this.config.getString("academy-prefix") + "', '|') | replace(v, prefix + '-', '')) ELSE sub.code END as code";
        } else {
            condition = "CASE WHEN sub.code =~'.*-.*' THEN split(sub.code,\"-\")[1] ELSE sub.code END as code";
        }
        String query =
                "MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure) " +
                        "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                        "AND pg.filter IN ['Student', 'Teacher', 'Personnel'] " +
                        "AND u.deleteDate IS NULL " +
                        "WITH distinct u,s " +
                        "MATCH (u)-[t:TEACHES]->(sub:Subject)-[:SUBJECT]->(s) " +
                        "WHERE sub.code =~ '^(.*-)?([0-9]{2})([A-Z0-9]{4})$' " +
                        "WITH u.id as uid,  t.classes as classesList, " + condition +
                        ", s.UAI as uai " +
                        "unwind(classesList) as classes " +
                        "MATCH (c:Class{externalId:classes})-[:BELONGS]->(s:Structure) ";
        String dataReturn = "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "uid as `" + PERSON_ID + "`, " +
                "CASE WHEN  split(classes,\"$\")[1] IS NOT null THEN split(classes,\"$\")[1] ELSE classes END as `" + GROUPS_CODE + "`, " +
                "collect(toUpper(code)) as `" + STUDYFIELD_CODE + "` " +
                "order by `" + PERSON_ID + "`, `" + GROUPS_CODE + "`, `" + STRUCTURE_UAI + "`";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }

    /**
     * Process classes subjects
     *
     * @param classSubject Array of class subjects from Neo4j
     */
    private Either<String, JsonObject> processClassFosInfo(JsonArray classSubject) {
        Either<String, JsonObject> event =
                processSimpleArray(classSubject, GROUPS_CLASS_SUBJECT_NODE, GROUPS_SUBJECT_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing classes fos : " + event.left().getValue());
        } else {
            return event;
        }
    }

    /**
     * Get groups fields of study from Neo4j
     * Use external id for groups when available
     * Field of study code may be prefixed by ACADEMY-
     *
     * @param handler results
     */
    private void getGroupsFosFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String condition;
        if (this.config.containsKey("academy-prefix") && !"".equals(this.config.getString("academy-prefix").trim())) {
            condition = "CASE WHEN sub.code =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' THEN reduce(v=sub.code, prefix in split('" +
                    this.config.getString("academy-prefix") + "', '|') | replace(v, prefix + '-', '')) ELSE sub.code END as code";
        } else {
            condition = "CASE WHEN sub.code =~'.*-.*' THEN split(sub.code,\"-\")[1] ELSE sub.code END as code";
        }
        String query =
                "MATCH (u:User)-[:IN]->(pg:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(s:Structure) " +
                        "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                        "AND pg.filter IN ['Student', 'Teacher', 'Personnel'] " +
                        "AND u.deleteDate IS NULL " +
                        "WITH distinct u,s " +
                        "MATCH (u)-[t:TEACHES]->(sub:Subject)-[:SUBJECT]->(s) " +
                        "WHERE sub.code =~ '^(.*-)?([0-9]{2})([A-Z0-9]{4})$' " +
                        "WITH u, t.groups as grouplist, " + condition + ", s " +
                        "unwind(grouplist) as group " +
                        "MATCH (s:Structure)<-[:BELONGS]-(c:Class) " +
                        "WITH collect(c.name) as classes, u, group, code, s " +
                        "MATCH (u:User)-[:IN]->(fg:FunctionalGroup{externalId:group})-[:DEPENDS]->(s:Structure) " +
                        "WHERE NOT (fg.name IN classes)";
        String dataReturn = "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "u.id as `" + PERSON_ID + "`, " +
                "CASE WHEN  split(group,\"$\")[1] IS NOT null THEN split(group,\"$\")[1] ELSE group END as `" + GROUPS_CODE + "`, " +
                "collect(toUpper(code)) as `" + STUDYFIELD_CODE + "` " +
                "order by `" + PERSON_ID + "`, `" + GROUPS_CODE + "`, `" + STRUCTURE_UAI + "`";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }

    /**
     * Process groups subjects
     *
     * @param groupSubject Array of group subjects from Neo4j
     */
    private Either<String, JsonObject> processGroupFosInfo(JsonArray groupSubject) {
        Either<String, JsonObject> event =
                processSimpleArray(groupSubject, GROUPS_GROUP_SUBJECT_NODE, GROUPS_SUBJECT_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing groups fos : " + event.left().getValue());
        } else {
            return event;
        }
    }
}