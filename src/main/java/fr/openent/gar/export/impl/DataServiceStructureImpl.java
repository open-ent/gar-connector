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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static fr.openent.gar.constants.GarConstants.*;

public class DataServiceStructureImpl extends DataServiceBaseImpl implements DataService {

    private final String entId;
    private final Boolean hasAcademyPrefix;
    private final String source;
    private final PaginatorHelperImpl paginator;
    private final JsonObject config;
    private final Set<String> checkUniqueMefs;
    private final Set<String> checkUniqueFos;

    DataServiceStructureImpl(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source = source;
        this.config = config;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, STRUCTURE_ROOT, STRUCTURE_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
        hasAcademyPrefix = this.config.containsKey("academy-prefix") && !"".equals(this.config.getString("academy-prefix").trim());
        this.checkUniqueMefs = new HashSet<>();
        this.checkUniqueFos = new HashSet<>();
    }

    /**
     * Export Data to folder
     * - Export Structures info and build mapStructures with mapping between structures ID and UAI
     * - Export Structures Mefs
     * - Export Structures fields of study
     */
    @Override
    public void exportData(final Handler<Either<String, JsonObject>> handler) {
        getAndProcessStructuresInfo(0, structInfoResults -> {
            if (validResponse(structInfoResults, handler)) {
                getAndProcessStructuresTeachersMefs(0, structMefsTeachersResults -> {
                    if (validResponse(structMefsTeachersResults, handler)) {
                        getAndProcessStructuresStudentsMefs(0, structMefsStudentsResults -> {
                            if (validResponse(structMefsStudentsResults, handler)) {
                                getAndProcessStructuresFos(structFosResults -> {
                                    if (validResponse(structFosResults, handler)) {
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
        });
    }

    /**
     * Process structure info, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessStructuresInfo(int skip, final Handler<Either<String, JsonObject>> handler) {
        getStucturesInfoFromNeo4j(skip, this.source, entId, paginator, structResults -> {
            if (validResponseNeo4j(structResults, handler)) {
                Either<String, JsonObject> result = processStructuresInfo(structResults.right().getValue());

                if (structResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStructuresInfo(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStructureImpl@getAndProcessStructuresInfo] Failed to process");
            }
        });
    }

    /**
     * Process structure Teachers mefs, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessStructuresTeachersMefs(int skip, final Handler<Either<String, JsonObject>> handler) {
        getStucturesTeachersMefsFromNeo4j(skip, structResults -> {
            if (validResponseNeo4j(structResults, handler)) {
                Either<String, JsonObject> result = processStucturesMefs(structResults.right().getValue());

                if (structResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStructuresTeachersMefs(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStructureImpl@getAndProcessStructuresTeachersMefs] Failed to process");
            }
        });
    }

    /**
     * Process structure Students mefs, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessStructuresStudentsMefs(int skip, final Handler<Either<String, JsonObject>> handler) {
        getStucturesStudentsMefsFromNeo4j(skip, structResults -> {
            if (validResponseNeo4j(structResults, handler)) {
                Either<String, JsonObject> result = processStucturesMefs(structResults.right().getValue());

                if (structResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStructuresStudentsMefs(skip + PaginatorHelperImpl.LIMIT, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStructureImpl@getAndProcessStructuresStudentsMefs] Failed to process");
            }
        });
    }

    /**
     * Process structure fields of study, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessStructuresFos(final Handler<Either<String, JsonObject>> handler) {
        final Map<String, String> fieldOfStudyLabels = new HashMap<>();
        final Map<String, Map<String, String>> subjectLabelsByCodeUai = new HashMap<>();

        getFieldOfStudyLabelsFromNeo4j(fieldOfStudyLabels, fieldOfStudyResult -> {
            if (fieldOfStudyResult.isRight()) {
                getSubjectLabelsFromNeo4j(subjectLabelsByCodeUai, subjectLabelsResult -> {
                    if (subjectLabelsResult.isRight()) {
                        getAndProcessStructuresFosFromNeo4j(0, fieldOfStudyLabels, subjectLabelsByCodeUai, processStructureFos -> {
                            if (processStructureFos.isRight()) {
                                getAndProcessStudentFosFromNeo4j(0, fieldOfStudyLabels, subjectLabelsByCodeUai, handler);
                            } else {
                                handler.handle(new Either.Left<>(processStructureFos.left().getValue()));
                            }
                        });
                    } else {
                        handler.handle(new Either.Left<>(subjectLabelsResult.left().getValue()));
                    }
                });
            } else {
                handler.handle(new Either.Left<>(fieldOfStudyResult.left().getValue()));
            }
        });
    }

    private void getAndProcessStructuresFosFromNeo4j(int skip, final Map<String, String> fieldOfStudyLabels,
                                                     final Map<String, Map<String, String>> subjectLabelsByCodeUai,
                                                     final Handler<Either<String, JsonObject>> handler) {
        getStucturesFosFromNeo4j(skip, fosResults -> {
            if (validResponseNeo4j(fosResults, handler)) {
                final JsonArray jrFosRes = fosResults.right().getValue();
                applyFosLabel(fieldOfStudyLabels, subjectLabelsByCodeUai, jrFosRes);

                Either<String, JsonObject> result = processStucturesFos(jrFosRes);

                if (jrFosRes.size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStructuresFosFromNeo4j(skip + PaginatorHelperImpl.LIMIT, fieldOfStudyLabels,
                            subjectLabelsByCodeUai, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStructureImpl@getAndProcessStructuresFosFromNeo4j] Failed to process");
            }
        });
    }

    private void getAndProcessStudentFosFromNeo4j(int skip, final Map<String, String> fieldOfStudyLabels,
                                                     final Map<String, Map<String, String>> subjectLabelsByCodeUai,
                                                     final Handler<Either<String, JsonObject>> handler) {
        getStudentFosFromNeo4j(skip, fosResults -> {
            if (validResponseNeo4j(fosResults, handler)) {
                final JsonArray jrFosRes = fosResults.right().getValue();
                applyFosLabel(fieldOfStudyLabels, subjectLabelsByCodeUai, jrFosRes);

                Either<String, JsonObject> result = processStucturesFos(jrFosRes);

                if (jrFosRes.size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStudentFosFromNeo4j(skip + PaginatorHelperImpl.LIMIT, fieldOfStudyLabels,
                            subjectLabelsByCodeUai, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStructureImpl@getAndProcessStudentFosFromNeo4j] Failed to process");
            }
        });
    }

    private void applyFosLabel(final Map<String, String> fieldOfStudyLabels, final Map<String, Map<String, String>> subjectLabelsByCodeUai,
                               final JsonArray fosResults) {
        fosResults.forEach(fosResult -> {
            if (fosResult instanceof JsonObject) {
                JsonObject entry = (JsonObject) fosResult;
                if (entry.containsKey(STRUCTURE_UAI) && entry.containsKey(STUDYFIELD_CODE)) {
                    String uai = entry.getString(STRUCTURE_UAI);
                    String fosCode = entry.getString(STUDYFIELD_CODE);
                    if (subjectLabelsByCodeUai.containsKey(uai) && subjectLabelsByCodeUai.get(uai).containsKey(fosCode)) {
                        entry.put(STUDYFIELD_DESC, subjectLabelsByCodeUai.get(uai).get(fosCode));
                    } else {
                        if (fieldOfStudyLabels.containsKey(fosCode)) {
                            entry.put(STUDYFIELD_DESC, fieldOfStudyLabels.get(fosCode));
                        } else {
                            entry.put(STUDYFIELD_DESC, "MATIERE " + fosCode);
                        }
                    }
                }
            }
        });
    }

    private void getFieldOfStudyLabelsFromNeo4j(final Map<String, String> fieldOfStudyLabels, Handler<Either<String, Boolean>> handler) {
        String query = "MATCH (fos:FieldOfStudy) " +
                "RETURN fos.externalId as id, fos.name as name ORDER BY fos.externalId ";

        neo4j.execute(query, new JsonObject(), res -> {
            if (res.body() != null && res.body().containsKey("result")) {
                JsonArray fieldOfStudyResult = Utils.getOrElse(res.body().getJsonArray("result"), new JsonArray());
                fieldOfStudyResult.forEach((entry) -> {
                    if (entry instanceof JsonObject) {
                        JsonObject field = (JsonObject) entry;
                        String id = field.getString("id", "");
                        String name = field.getString("name", "");

                        if (!id.isEmpty() && !name.isEmpty()) {
                            if (hasAcademyPrefix) {
                                id = id.replaceFirst("(" + this.config.getString("academy-prefix") + ")-", "");
                            }
                            fieldOfStudyLabels.put(id, name);
                        }
                    }
                });
                handler.handle(new Either.Right<>(true));
            } else {
                log.error("[DataServiceStructureImpl@getFosLabelsFromNeo4j] Failed to process FieldOfStudy label query");
                handler.handle(new Either.Left<>("error"));
            }
        });
    }

    private void getSubjectLabelsFromNeo4j(final Map<String, Map<String, String>> subjectLabelsByCodeUai, Handler<Either<String, Boolean>> handler) {
        String query = "MATCH (s:Structure)<-[:SUBJECT]-(sub:Subject) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                "RETURN s.UAI as UAI, sub.code as code, sub.label as label";
        neo4j.execute(query, new JsonObject().put("entId", entId), res -> {
            if (res.body() != null && res.body().containsKey("result")) {
                JsonArray queryResult = Utils.getOrElse(res.body().getJsonArray("result"), new JsonArray());

                queryResult.forEach((entry) -> {
                    if (entry instanceof JsonObject) {
                        JsonObject field = (JsonObject) entry;
                        String uai = field.getString("UAI", "");
                        String code = field.getString("code", "");
                        String label = field.getString("label", "");
                        if (!uai.isEmpty() && !code.isEmpty() && !label.isEmpty()) {
                            if (!subjectLabelsByCodeUai.containsKey(uai)) {
                                subjectLabelsByCodeUai.put(uai, new HashMap<>());
                            }
                            if (hasAcademyPrefix) {
                                code = code.replaceFirst("(" + this.config.getString("academy-prefix") + ")-", "");
                            }
                            subjectLabelsByCodeUai.get(uai).put(code, label);
                        }
                    }
                });

                handler.handle(new Either.Right<>(true));
            } else {
                log.error("[DataServiceStructureImpl@getSubjectLabelsFromNeo4j] Failed to process subject label query");
                handler.handle(new Either.Left<>("error"));
            }
        });
    }

    /**
     * Get structures infos from Neo4j
     *
     * @param handler results
     */
    static void getStucturesInfoFromNeo4j(int skip, String source, String entId, PaginatorHelperImpl paginator,
                                          Handler<Either<String, JsonArray>> handler) {
        String query;
        if(source.equals(Gar.AAF1D)){
            query = "MATCH (s:Structure {source:'" + source + "'}) " +
                    "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports ";
        }else{
            query = "MATCH (s:Structure) " +
                    "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";
        }
// Don't export optional attachment structure attribute
//                "OPTIONAL MATCH (g2:ManualGroup{name:\\\"\" + CONTROL_GROUP + \"\\\"})-[:DEPENDS]->(s2:Structure)<-[:HAS_ATTACHMENT]-(s:Structure) ";
        String dataReturn = "RETURN distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "s.name as `" + STRUCTURE_NAME + "`, " +
//                "collect(distinct s2.UAI)[0]  as `" + STRUCTURE_RATTACH + "`, " +
                "s.contract  as `" + STRUCTURE_CONTRACT + "`, " +
                "s.phone  as `" + STRUCTURE_PHONE + "`, " +
                //TODO GARStructureTelephone
                "s.externalId  as structid " +
                "order by " + "`" + STRUCTURE_UAI + "`";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }

    /**
     * Process structures info
     * Update general map with mapping between structures ID and UAI
     *
     * @param structures Array of structures from Neo4j
     */
    private Either<String, JsonObject> processStructuresInfo(JsonArray structures) {
        try {
            //clean mapStructures before process structures.
            mapStructures.clear();
            for (Object o : structures) {
                if (!(o instanceof JsonObject)) continue;
                JsonObject structure = (JsonObject) o;

                if (isMandatoryFieldsAbsent(structure, STRUCTURE_NODE_MANDATORY)) continue;

                updateMap(structure);
                xmlExportHelper.saveObject(STRUCTURE_NODE, structure);
            }
            return new Either.Right<>(null);
        } catch (Exception e) {
            log.error("Error when processing structures Info : ", e);
            return new Either.Left<>("Error when processing structures Info : " + e.getMessage());
        }
    }

    /**
     * Update mapStructures with ID and UAI of structure
     * Then remove ID from object
     *
     * @param structure object with structure info
     */
    static void updateMap(JsonObject structure) {
        String structId = structure.getString("structid");
        String structUAI = structure.getString(STRUCTURE_UAI);
        mapStructures.put(structId, structUAI);
        structure.remove("structid");
    }

    /**
     * Get structures students mefs from Neo4j
     * For each structure :
     * Each student has one mef attached
     * Each teacher can have many mefs attached
     *
     * @param handler results
     */
    private void getStucturesStudentsMefsFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String queryStudentsMefs = "MATCH (n:User)-[:IN]->(pg:ProfileGroup {filter:'Student'})-[:DEPENDS]->";

        queryStudentsMefs += "(s:Structure) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";

        queryStudentsMefs += " AND n.module IS NOT NULL AND  n.deleteDate IS NULL " +
                "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "n.module as `" + MEF_CODE + "`, " +
                "n.moduleName as `" + MEF_DESCRIPTION + "` " +
                "order by `" + STRUCTURE_UAI + "` , `" + MEF_CODE + "` ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(queryStudentsMefs, params, skip, handler);
    }

    /**
     * Get structures Teachers mefs from Neo4j
     * For each structure :
     * Each student has one mef attached
     * Each teacher can have many mefs attached
     *
     * @param handler results
     */
    private void getStucturesTeachersMefsFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String queryTeachersMefs = "MATCH (n:User)-[:IN|DEPENDS*1..2]->(pg:ProfileGroup {filter:'Teacher'})-[:DEPENDS]->";

        queryTeachersMefs += "(s:Structure) " +
                "where s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";

        queryTeachersMefs += "AND n.modules IS NOT NULL and not n.deleteDate IS NOT NULL " +
                "with distinct s,n " +
                "unwind n.modules as rows " +
                "with s, split(rows,\"$\") as modules " +
                "where modules[0] = s.externalId " +
                "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "modules[1] as `" + MEF_CODE + "`, " +
                "modules[2] as `" + MEF_DESCRIPTION + "` " +
                "order by `" + STRUCTURE_UAI + "` , `" + MEF_CODE + "` ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);

        paginator.neoStream(queryTeachersMefs, params, skip, handler);
    }

    /**
     * Process structures mefs
     *
     * @param mefs Array of mefs from Neo4j
     */
    private Either<String, JsonObject> processStucturesMefs(JsonArray mefs) {
        final JsonArray safeMefs = new JsonArray();
        //check unique Mef
        for(Object o : mefs) {
            if (!(o instanceof JsonObject)) continue;
            JsonObject obj = (JsonObject) o;
            if (!isMandatoryFieldsAbsent(obj, MEF_NODE_MANDATORY)) {
                final String key = obj.getString(STRUCTURE_UAI) + obj.getString(MEF_CODE) + obj.getString(MEF_DESCRIPTION);
                if (!checkUniqueMefs.contains(key)) {
                    checkUniqueMefs.add(key);
                    safeMefs.add(obj);
                }
            } else {
                //check and log by processSimpleArray
                safeMefs.add(obj);
            }
        }
        Either<String, JsonObject> event = processSimpleArray(safeMefs, MEF_NODE, MEF_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing structures mefs : " + event.left().getValue());
        } else {
            return event;
        }
    }

    /**
     * Get structures fields of study from Neo4j
     * - Structure FOS codes may be prefixed by ACADEMY-
     *
     * @param handler results
     */
    private void getStucturesFosFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {
        String condition;
        if (hasAcademyPrefix) {
            condition = "CASE WHEN sub.code =~ '(" + this.config.getString("academy-prefix") + ")-[A-Z0-9-]+' " +
                    "THEN reduce(v=sub.code, prefix in split('" +
                    this.config.getString("academy-prefix") +"', '|') | replace(v, prefix + '-', '')) " +
                    "ELSE sub.code END as codelist";
        } else {
            condition = "split(sub.code,\"-\") as codelist";
        }

        String queryStructureFos = "MATCH (sub:Subject)-[:SUBJECT]->";

        queryStructureFos += "(s:Structure) " +
                "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";

        queryStructureFos += "AND sub.code =~ '^(.*-)?([0-9]{2})([A-Z0-9]{4})$' " +
                "with s, sub.label as label, " + condition +
                " return distinct s.UAI as `" + STRUCTURE_UAI + "`, toUpper(" +
                (hasAcademyPrefix ? "codelist" : "codelist[size(codelist)-1]") + ") as `" + STUDYFIELD_CODE + "` " +
                "order by `" + STRUCTURE_UAI + "` , `" + STUDYFIELD_CODE + "` ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(queryStructureFos, params, skip, handler);
    }

    /**
     * Get structures fields of study from Neo4j
     * - Students FOS codes and description are lists in two different fields and must be mapped
     *
     * @param handler results
     */
    private void getStudentFosFromNeo4j(int skip, Handler<Either<String, JsonArray>> handler) {

        String queryStudentFos = "MATCH (u:User)-[:IN]->(pg:ProfileGroup {filter:'Student'})-[:DEPENDS]->";

        queryStudentFos += "(s:Structure) " +
                "where s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";

        queryStudentFos += "AND u.fieldOfStudy IS NOT NULL AND u.deleteDate IS NULL " +
                "with distinct s, u.fieldOfStudy as fos " +
                "with s, " +
                "reduce(x=[], idx in range(0,size(fos)-1) | x + {code:fos[idx]}) as rows " +
                "unwind rows as row " +
                "return distinct s.UAI as `" + STRUCTURE_UAI + "`, " +
                "toUpper(row.code) as `" + STUDYFIELD_CODE + "` " +
                "order by `" + STRUCTURE_UAI + "` , `" + STUDYFIELD_CODE + "` ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(queryStudentFos, params, skip, handler);
    }

    /**
     * Process structures fields of study
     *
     * @param fos Array of fis from Neo4j
     */
    private Either<String, JsonObject> processStucturesFos(JsonArray fos) {
        final JsonArray safeFos = new JsonArray();
        //check unique fos
        for(Object o : fos) {
            if (!(o instanceof JsonObject)) continue;
            JsonObject obj = (JsonObject) o;
            if (!isMandatoryFieldsAbsent(obj, STUDYFIELD_NODE_MANDATORY)) {
                final String key = obj.getString(STRUCTURE_UAI) + obj.getString(STUDYFIELD_CODE) + obj.getString(STUDYFIELD_DESC);
                if (!checkUniqueFos.contains(key)) {
                    checkUniqueFos.add(key);
                    safeFos.add(obj);
                }
            } else {
                //check and log by processSimpleArray
                safeFos.add(obj);
            }
        }
        Either<String, JsonObject> event = processSimpleArray(safeFos, STUDYFIELD_NODE, STUDYFIELD_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing structures fos : " + event.left().getValue());
        } else {
            return event;
        }
    }
}
