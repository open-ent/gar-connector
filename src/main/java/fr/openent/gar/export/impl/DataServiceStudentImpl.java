package fr.openent.gar.export.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.export.DataService;
import fr.openent.gar.helper.impl.PaginatorHelperImpl;
import fr.openent.gar.helper.impl.XmlExportHelperImpl;
import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.entcore.common.utils.StringUtils;

import static fr.openent.gar.constants.GarConstants.*;

public class DataServiceStudentImpl extends DataServiceBaseImpl implements DataService {
    private final PaginatorHelperImpl paginator;
    private final String entId;
    private final String source;

    DataServiceStudentImpl(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source = source;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, STUDENT_ROOT, STUDENT_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
    }

    /**
     * Export Data to folder
     * - Export Students identities
     * - Export Students Mefs
     * - Export Students fields of study
     *
     * @param handler response handler
     */
    @Override
    public void exportData(final Handler<Either<String, JsonObject>> handler) {
        final JsonArray modules = new fr.wseduc.webutils.collections.JsonArray();
        final JsonArray fos = new fr.wseduc.webutils.collections.JsonArray();
        getAndProcessStudentsInfo(0, modules, fos, studentsResult -> {
            if (validResponse(studentsResult, handler)) {
                if (validResponse(processStudentsMefs(modules), handler)) {
                    if (validResponse(processStudentsFos(fos), handler)) {
                        xmlExportHelper.closeFile();
                        handler.handle(new Either.Right<>(
                                new JsonObject().put(
                                        FILE_LIST_KEY,
                                        xmlExportHelper.getFileList()
                                )));
                    }
                }
            }
        });
    }

    /**
     * Process students info, validate data and save to xml
     *
     * @param handler result handler
     */
    private void getAndProcessStudentsInfo(int skip, JsonArray modules, JsonArray fos, final Handler<Either<String, JsonObject>> handler) {
        getStudentsInfoFromNeo4j(skip, entId, this.source, paginator, studentResults -> {
            if (validResponseNeo4j(studentResults, handler)) {
                final JsonArray students = studentResults.right().getValue();
                //fixme : it is necessary to order STRUCTURE_UAI, PERSON_ID, MEF_CODE OR STUDYFIELD_CODE
                populateModulesAndFos(modules, fos, students);
                Either<String, JsonObject> result = processStudentsInfo(students);
                if (studentResults.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessStudentsInfo(skip + PaginatorHelperImpl.LIMIT, modules, fos, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceStudentImpl@getAndProcessStudentsInfo] Failed to process");
            }
        });
    }

    private void populateModulesAndFos(JsonArray modules, JsonArray fos, final JsonArray students) {
        if (!students.isEmpty()) {
            students.forEach(student -> {
                if (student instanceof JsonObject) {
                    final JsonObject fields = (JsonObject) student;
                    populateModule(fields, modules);
                    populateFos(fields, fos);
                }
            });
        }
    }

    private void populateModule(final JsonObject fields, JsonArray modules) {
        final String userModule = StringUtils.trimToNull(fields.getString("module"));
        final JsonArray uais = fields.getJsonArray("profiles");
        //export not empty Mef
        if (userModule != null) {
            if (uais != null && !uais.isEmpty()) {
                uais.forEach(uai -> {
                    if (uai instanceof String) {
                        final String strUai = (String) uai;
                        if (StringUtils.trimToNull(strUai) != null) {
                            final JsonObject jo = new JsonObject();
                            jo.put(STRUCTURE_UAI, strUai);
                            jo.put(PERSON_ID, fields.getString(PERSON_ID));
                            jo.put(MEF_CODE, userModule);
                            modules.add(jo);
                        }
                    }
                });
            }
        }
    }

    private void populateFos(final JsonObject fields, JsonArray fos) {
        final JsonArray userFos = fields.getJsonArray("study");
        final JsonArray uais = fields.getJsonArray("profiles");
        //export not empty fos
        if (userFos != null && !userFos.isEmpty()) {
            if (uais != null && !uais.isEmpty()) {
                uais.forEach(uai -> {
                    if (uai instanceof String) {
                        final String strUai = (String) uai;
                        if (StringUtils.trimToNull(strUai) != null) {
                            userFos.forEach(uFos -> {
                                if (uFos instanceof String) {
                                    final String strUfos = StringUtils.trimToNull((String) uFos);
                                    if (strUfos != null) {
                                        final JsonObject jo = new JsonObject();
                                        jo.put(STRUCTURE_UAI, strUai);
                                        jo.put(PERSON_ID, fields.getString(PERSON_ID));
                                        jo.put(STUDYFIELD_CODE, strUfos.toUpperCase());
                                        fos.add(jo);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        }
    }

    static void getStudentsInfoFromNeo4j(int skip, String entId, String source, PaginatorHelperImpl paginator,
                                         Handler<Either<String, JsonArray>> handler) {
        String query;
        if(source.equals(Gar.AAF1D)){
            query = "MATCH (u:User)-[:IN]->(pg:ProfileGroup {filter:'Student'})-[:DEPENDS]->(s:Structure {source:'" + source + "'}) " +
                    "where s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND u.deleteDate IS NULL " +
                    "OPTIONAL MATCH (u:User)-[:ADMINISTRATIVE_ATTACHMENT]->(sr:Structure {source:'" + source + "'}) " +
                    "WHERE sr.exports IS NOT NULL AND ('GAR-' + $entId) IN sr.exports " +
                    "AND u.deleteDate IS NULL ";
        }else{
            query  = "MATCH (u:User)-[:IN]->(pg:ProfileGroup {filter:'Student'})-[:DEPENDS]->(s:Structure) " +
                    "where s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' " +
                    "AND u.deleteDate IS NULL " +
                    "OPTIONAL MATCH (u:User)-[:ADMINISTRATIVE_ATTACHMENT]->(sr:Structure) " +
                    "WHERE sr.exports IS NOT NULL AND ('GAR-' + $entId) IN sr.exports AND sr.source <> '" + Gar.AAF1D + "' " +
                    "AND u.deleteDate IS NULL ";
        }
        String dataReturn = "return distinct " +
                "u.id  as `" + PERSON_ID + "`, " +
                "u.lastName as `" + PERSON_PATRO_NAME + "`, " +
                "u.lastName as `" + PERSON_NAME + "`, " +
                "u.firstName as `" + PERSON_FIRST_NAME + "`, " +
                "coalesce(u.otherNames, [u.firstName]) as `" + PERSON_OTHER_NAMES + "`, " +
                //TODO GARPersonCivilite
                "sr.UAI as `" + PERSON_STRUCT_ATTACH + "`, " +
                "u.birthDate as `" + PERSON_BIRTH_DATE + "`, " +
                "collect(distinct s.UAI) as profiles, u.module as module, u.fieldOfStudy as study, u.level as level  " +
                "order by " + "`" + PERSON_ID + "`";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }

    /**
     * Process students info
     * Add structures in arrays to match xsd
     *
     * @param students Array of students from Neo4j
     */
    private Either<String, JsonObject> processStudentsInfo(JsonArray students) {
        try {
            for (Object o : students) {
                if (!(o instanceof JsonObject)) continue;
                JsonObject student = (JsonObject) o;
                JsonArray profiles = student.getJsonArray("profiles", null);
                if (profiles == null || profiles.size() == 0) {
                    log.warn("Gar : Student with no profile for export, id "
                            + student.getString("u.id", "unknown"));
                    continue;
                }

                processProfilesStudent(student);

                if (isMandatoryFieldsAbsent(student, STUDENT_NODE_MANDATORY)) {
                    log.warn("Gar : mandatory attribut for Student : " + student);
                    continue;
                }
                reorganizeNodes(student);
                xmlExportHelper.saveObject(STUDENT_NODE, student);
            }
            return new Either.Right<>(null);
        } catch (Exception e) {
            return new Either.Left<>("Error when processing students Info : " + e.getMessage());
        }
    }

    /**
     * Process profiles, set profile from structMap for structures in it
     * Set default profile for other etabs
     * @param person person to process
     *
     */
    public static void processProfilesStudent(JsonObject person) {
        JsonArray profiles = person.getJsonArray("profiles");

        JsonArray garProfiles = new fr.wseduc.webutils.collections.JsonArray();
        JsonArray garEtabs = new fr.wseduc.webutils.collections.JsonArray();
        for(Object o2 : profiles) {
            if(!(o2 instanceof String)) continue;
            String structure = ((String)o2);

            garEtabs.add(structure);

            addProfile(garProfiles, structure, STUDENT_PROFILE);
        }
        person.put(PERSON_PROFILES, garProfiles);
        person.put(PERSON_STRUCTURE, garEtabs);
        person.remove("profiles");
    }

    /**
     * XSD specify precise order for xml tags
     *
     * @param student
     */
    static void reorganizeNodes(JsonObject student) {
        JsonObject personCopy = student.copy();
        student.clear();
        student.put(PERSON_ID, personCopy.getValue(PERSON_ID));
        student.put(PERSON_PROFILES, personCopy.getValue(PERSON_PROFILES));
        student.put(PERSON_PATRO_NAME, personCopy.getValue(PERSON_PATRO_NAME));
        student.put(PERSON_NAME, personCopy.getValue(PERSON_NAME));
        student.put(PERSON_FIRST_NAME, personCopy.getValue(PERSON_FIRST_NAME));
        student.put(PERSON_OTHER_NAMES, personCopy.getValue(PERSON_OTHER_NAMES));
        //TODO GARPersonCivilite
        student.put(PERSON_STRUCT_ATTACH, personCopy.getValue(PERSON_STRUCT_ATTACH));
        student.put(PERSON_STRUCTURE, personCopy.getValue(PERSON_STRUCTURE));
        student.put(PERSON_BIRTH_DATE, personCopy.getValue(PERSON_BIRTH_DATE));
    }

    /**
     * Process mefs info
     *
     * @param mefs Array of mefs from Neo4j
     */
    private Either<String, JsonObject> processStudentsMefs(JsonArray mefs) {
        Either<String, JsonObject> event = processSimpleArray(mefs, PERSON_MEF, PERSON_MEF_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing students mefs : " + event.left().getValue());
        } else {
            return event;
        }
    }

    /**
     * Process fields of study info
     *
     * @param fos Array of fieldsOfStudy from Neo4j
     */
    private Either<String, JsonObject> processStudentsFos(JsonArray fos) {
        Either<String, JsonObject> event = processSimpleArray(fos, STUDENT_STUDYFIELD, STUDENT_STUDYFIELD_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing students fos : " + event.left().getValue());
        } else {
            return event;
        }
    }
}