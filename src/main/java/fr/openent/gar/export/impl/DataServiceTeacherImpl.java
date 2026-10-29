package fr.openent.gar.export.impl;

import fr.openent.gar.Gar;
import fr.openent.gar.helper.impl.PaginatorHelperImpl;
import fr.openent.gar.helper.impl.XmlExportHelperImpl;
import fr.openent.gar.export.DataService;
import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.utils.StringUtils;

import java.util.*;

import static fr.openent.gar.constants.GarConstants.*;

public class DataServiceTeacherImpl extends DataServiceBaseImpl implements DataService{
    private final PaginatorHelperImpl paginator;
    private final String entId;
    private final String source;

    DataServiceTeacherImpl(String entId, String source, JsonObject config, String strDate) {
        this.entId = entId;
        this.source = source;
        xmlExportHelper = new XmlExportHelperImpl(entId, source, config, TEACHER_ROOT, TEACHER_FILE_PARAM, strDate);
        paginator = new PaginatorHelperImpl();
    }

    /**
     * Export Data to folder
     * - Export Teachers identities
     * - Export Teachers Mefs
     *
     * @param handler response handler
     */
    @Override
    public void exportData(final Handler<Either<String, JsonObject>> handler) {
        final JsonArray modules = new fr.wseduc.webutils.collections.JsonArray();
        getAndProcessTeachersInfoFromNeo4j(0, modules, resultTeachers -> {
            if (validResponse(resultTeachers, handler)) {
                if (validResponse(processTeachersMefs(modules), handler)) {
                    xmlExportHelper.closeFile();
                    handler.handle(new Either.Right<>(
                            new JsonObject().put(
                                    FILE_LIST_KEY,
                                    xmlExportHelper.getFileList()
                            )));
                } else {
                    log.error("[DataServiceTeacherImpl@exportData] Failed to process");
                }
            } else {
                log.error("[DataServiceTeacherImpl@exportData] Failed to process");
            }
        });
    }

    private void getAndProcessTeachersInfoFromNeo4j(int skip, JsonArray modules,
                                                    final Handler<Either<String, JsonObject>> handler) {
        getTeachersInfoFromNeo4j(skip, this.source, entId, paginator, teacherInfos -> {
            if (validResponseNeo4j(teacherInfos, handler)) {
                final JsonArray teachers = teacherInfos.right().getValue();
                populateModules(modules, teachers,false);
                Either<String, JsonObject> result = processTeachersInfo(teachers);

                if (teacherInfos.right().getValue().size() == PaginatorHelperImpl.LIMIT) {
                    getAndProcessTeachersInfoFromNeo4j(skip + PaginatorHelperImpl.LIMIT, modules, handler);
                } else {
                    handler.handle(result);
                }
            } else {
                log.error("[DataServiceTeacherImpl@getAndProcessTeachersInfoFromNeo4j] Failed to process");
            }
        });
    }

    protected static void populateModules(JsonArray modules, final JsonArray teachers, boolean firstDegree) {
        try {
            if (!teachers.isEmpty()) {
                teachers.forEach(teacher -> {
                    if (teacher instanceof JsonObject) {
                        final JsonObject fields = (JsonObject) teacher;
                        final JsonArray userModules = fields.getJsonArray("modules");
                        if (userModules != null && !userModules.isEmpty()) {
                            userModules.forEach(module -> {
                                if (module instanceof String) {
                                    final String[] mods = (StringUtils.trimToBlank((String) module)).split("\\$");
                                    //export not empty Mef only for Gar Structure
                                    if (mods.length > 1 && StringUtils.trimToNull(mapStructures.get(mods[0])) != null &&
                                            StringUtils.trimToNull(mods[1]) != null) {
                                        final JsonObject jo = new JsonObject();
                                        jo.put(STRUCTURE_UAI, mapStructures.get(mods[0]));
                                        jo.put(PERSON_ID, fields.getString(PERSON_ID));
                                        if(firstDegree)
                                            jo.put(MEF_CODE_1D, mods[1]);
                                        else
                                            jo.put(MEF_CODE, mods[1]);
                                        modules.add(jo);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error when populateModules teachers : ",e);
            throw e;
        }
    }

    protected static void getTeachersInfoFromNeo4j(int skip, String source, String entId, PaginatorHelperImpl paginator,
                                                Handler<Either<String, JsonArray>> handler) {
        String query = "MATCH (u:User)-[:IN|DEPENDS*1..2]->(pg:ProfileGroup)-[:DEPENDS]->";

        if(source.equals(Gar.AAF1D)){
            query += "(s:Structure {source:'" + source + "'}) " +
                    "WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports ";
        }else{
            query += "(s:Structure) WHERE s.exports IS NOT NULL AND ('GAR-' + $entId) IN s.exports AND s.source <> '" + Gar.AAF1D + "' ";
        }

        query += "AND u.deleteDate IS NULL AND pg.filter IN ['Personnel','Teacher'] " +
                // ADMINISTRATIVE ATTACHMENT can reference non GAR exported structure
                "OPTIONAL MATCH (u:User)-[:ADMINISTRATIVE_ATTACHMENT]->(sr:Structure) ";
        String dataReturn = "return distinct u.id  as `" + PERSON_ID + "`, " +
                "u.lastName as `" + PERSON_PATRO_NAME + "`, " +
                "u.lastName as `" + PERSON_NAME + "`, " +
                "u.firstName as `" + PERSON_FIRST_NAME + "`, " +
                "coalesce(u.otherNames, [u.firstName]) as `" + PERSON_OTHER_NAMES + "`, " +
                //TODO GARPersonCivilitep
                // Priority = emailAcademy > emailInternal > email
                "coalesce(u.emailAcademy, u.emailInternal, u.email) as `" + PERSON_MAIL + "`, " +
                "collect(distinct sr.UAI)[0] as `" + PERSON_STRUCT_ATTACH + "`, " +
                "u.birthDate as `" + PERSON_BIRTH_DATE + "`, " +
                "u.functions as functions, u.modules as modules, " +
                "collect(distinct s.UAI+'$'+pg.filter) as UAIprofiles " +
                "order by " + "`" + PERSON_ID + "`";

        query = query + dataReturn;
        query += " ASC SKIP $skip LIMIT $limit ";

        JsonObject params = new JsonObject().put("limit", PaginatorHelperImpl.LIMIT).put("entId", entId);
        paginator.neoStream(query, params, skip, handler);
    }


    /**
     * Process teachers info
     * Add structures in arrays to match xsd
     * @param teachers Array of teachers from Neo4j
     */
    private Either<String, JsonObject> processTeachersInfo(JsonArray teachers) {
        try {
            for(Object o : teachers) {
                if(!(o instanceof JsonObject)) continue;

                JsonObject teacher = (JsonObject) o;
                JsonArray profiles = teacher.getJsonArray("UAIprofiles", null);
                if(profiles == null || profiles.size() == 0) {
                    log.error("Gar : Teacher with no profile or function for export, id "
                            + teacher.getString("u.id", "unknown"));
                    continue;
                }

                Map<String,String> userStructProfiles = new HashMap<>();

                processFunctions(teacher, userStructProfiles, false);
                processTeacherProfiles(teacher, userStructProfiles);

                if(isMandatoryFieldsAbsent(teacher, TEACHER_NODE_MANDATORY)) {
                    log.warn("Gar : mandatory attribut for Teacher : " + teacher);
                    continue;
                }

                reorganizeNodes(teacher);

                xmlExportHelper.saveObject(TEACHER_NODE, teacher);
            }
            return new Either.Right<>(null);
        } catch (Exception e) {
            log.error("Error when processing teachers Info : ", e.getMessage());
            throw e;
        }
    }

    /**
     * Process profiles, set profile from structMap for structures in it
     * Set default profile for other etabs
     * @param teacher person to process
     * @param structMap map for profile by structure
     */
    protected static void processTeacherProfiles(JsonObject teacher, Map<String, String> structMap) {
        JsonArray garProfiles = new fr.wseduc.webutils.collections.JsonArray();
        JsonArray garEtabs = new fr.wseduc.webutils.collections.JsonArray();

        JsonArray profilesUser = teacher.getJsonArray("UAIprofiles");
        String profileUser = TEACHER_PROFILE;

        for(Object profile : profilesUser) {
            if (!(profile instanceof String)) continue;
            String[] uaiProfile = ((String) profile).split("\\$");
            if (uaiProfile.length < 2) continue;
            String structure = uaiProfile[0];
            if (uaiProfile[1].equals("Personnel"))
                profileUser = PERSONNEL_ETAB_PROFILE;

            garEtabs.add(structure);

            if (structMap != null && structMap.containsKey(structure)) {
                addProfile(garProfiles, structure, structMap.get(structure));
                structMap.remove(structure);
            } else {
                addProfile(garProfiles, structure, profileUser);
            }
        }
        if (structMap != null) {
            for (String structUAI : structMap.keySet()) {
                garEtabs.add(structUAI);
                addProfile(garProfiles, structUAI, structMap.get(structUAI));
            }
        }
        teacher.put(PERSON_PROFILES, garProfiles);
        teacher.put(PERSON_STRUCTURE, garEtabs);
    }

    /**
     * XSD specify precise order for xml tags
     *
     * @param teacher informations about the user
     */
    protected static void reorganizeNodes(JsonObject teacher) {
        JsonObject personCopy = teacher.copy();
        teacher.clear();
        teacher.put(PERSON_ID, personCopy.getValue(PERSON_ID));
        teacher.put(PERSON_PROFILES, personCopy.getValue(PERSON_PROFILES));
        teacher.put(PERSON_PATRO_NAME, personCopy.getValue(PERSON_PATRO_NAME));
        teacher.put(PERSON_NAME, personCopy.getValue(PERSON_NAME));
        teacher.put(PERSON_FIRST_NAME, personCopy.getValue(PERSON_FIRST_NAME));
        teacher.put(PERSON_OTHER_NAMES, personCopy.getValue(PERSON_OTHER_NAMES));
        //TODO GARPersonCivilite
        teacher.put(PERSON_STRUCT_ATTACH, personCopy.getValue(PERSON_STRUCT_ATTACH));
        teacher.put(PERSON_STRUCTURE, personCopy.getValue(PERSON_STRUCTURE));
        if(personCopy.getValue(PERSON_BIRTH_DATE) != null && !"".equals(personCopy.getValue(PERSON_BIRTH_DATE))){
            teacher.put(PERSON_BIRTH_DATE, personCopy.getValue(PERSON_BIRTH_DATE));
        }
        teacher.put(TEACHER_POSITION, personCopy.getValue(TEACHER_POSITION));
        if (personCopy.getValue(PERSON_MAIL) != null && !"".equals(personCopy.getValue(PERSON_MAIL))) {
            teacher.put(PERSON_MAIL, personCopy.getValue(PERSON_MAIL));
        }
    }

    /**
     * Process teachers functions
     * Calc profile for Documentalist functions
     * Teacher function is in form structID$functionCode$functionDesc$roleCode and must be splited
     * and analyzed
     * Documentalists have specific role and profile
     * @param teacher to process functions for
     * @param structMap map between structures ID and profile
     */
    protected static void processFunctions(JsonObject teacher, Map<String, String> structMap, boolean firstDegree) {
        JsonArray functions = teacher.getJsonArray("functions", null);
        if(functions == null || functions.size() == 0) {
            return;
        }
        JsonArray garFunctions = new fr.wseduc.webutils.collections.JsonArray();
        for(Object o : functions) {
            if(!(o instanceof String)) continue;
            String[] arrFunction = ((String)o).split("\\$");
            if(arrFunction.length < 4) continue;
            String structID = arrFunction[0];
            if(!mapStructures.containsKey(structID)) {
                continue;
            }
            String structUAI = mapStructures.get(structID);
            String functionCode = arrFunction[1];
            String roleCode = arrFunction[3];
            JsonArray profilesUser = teacher.getJsonArray("UAIprofiles");
            String profileUser = "Teacher";
            for(Object profile : profilesUser) {
                if(!(profile instanceof String)) continue;
                String[] uaiProfile = ((String)profile).split("\\$");
                if(uaiProfile.length < 2) continue;
                String uai = uaiProfile[0];
                if(structUAI.equals(uai))
                    profileUser = uaiProfile[1];
            }
            String profileType = TEACHER_PROFILE;
            final List documentaliste_code = Arrays.asList(DOCUMENTALIST_CODE, CIO_CODE, DCT_CODE);
            if (profileUser.equals("Personnel")) {
                profileType = PERSONNEL_ETAB_PROFILE;

                switch (functionCode) {
                    case DIRECTOR_CODE:
                        profileType = DIRECTOR_PROFILE;
                        break;
                    case COLLECTIVITE_TERRITORIALE_CODE:
                        profileType = COLLECTIVITE_TERRITORIALE_PROFILE;
                        break;
                    case ENSEIGNANT_CODE:
                        profileType = TEACHER_PROFILE;
                        break;
                }

                List vie_scolaire_code = Arrays.asList(EDUCATION_CODE, EDUCATION_ASSISTANT_CODE, ACCOMPAGNEMENT_HANDICAP_2_CODE,
                        ACCOMPAGNMENT_HANDICAP_CODE, ETRANGER_ASSISTANT_CODE, SURVEILLANCE_CODE, CPE_CODE, AUXILIAIRE_VIE_SCOLAIRE_CODE,
                        BESOINS_EDUCATIFS_CODE, EDUCATEUR_INTERNAT_CODE, FORMATION_INSERTION_JEUNES_CODE, COORDINATION_INSERTION_JEUNES_CODE,
                        CONSEILLER_ORIENTATION_CODE);

                List personnel_etab_code = Arrays.asList(SANS_OBJET_CODE, NON_RENSEIGNE_CODE, PREMIER_DEGRE_CODE, PERSONNELS_SECOND_DEGRE_CODE,
                        ADMINISTRATION_CODE, PERSONNEL_ADMINISTRATIF_CODE, CHEF_DE_TRAVAUX_CODE, ACCOMPAGNANT_SOUTIEN_CODE,
                        PSYCHOLOGUE_CODE, OUVRIER_CODE, LABORATOIRE_CODE, PERSONNEL_MEDICO_SOCIAUX_CODE, PERSONNEL_TECHNIQUE_CODE,
                        READAPTATION_CODE, CONSEILLER_FORMATION_CONTINUE_CODE, APPRENTISSAGE_CODE, FORMATION_CONTINUE_ADULTES_CODE,
                        APPRENTI_CLASSIQUE_PROFESSEUR_CODE, PERSONNEL_ADMINISTRATIFS_DE_CENTRALE_CODE, PERSONNELS_ASU_DE_CENTRALE_CODE,
                        AUTRES_ADMINISTRATIONS_CODE, PERSONNELS_BIBLIOTHEQUES_MUSEES_CODE, COMITE_NATIONAL_EVALUATION_CODE,
                        COORDINATEUR_PEDAGOGIQUE_CFA_CODE, CONSEILLERS_PEDAGOGIQUES_EPS_CODE, PERSONNELS_CONTRACTUELS_DE_CENTRALE_CODE,
                        ELEVE_CYCLE_PRE_COP_STAGIAIRE_CODE, EMPLOIS_PARTICULIERS_ACTIONS_DIVERSES_CODE, COORDINATEUR_ORGANISATEUR_ANIMATION_MAFPEN_CODE,
                        INTERVENANTS_EXTERIEURS_CODE, PERSONNELS_INSPECTION_GENERALE_CODE, INSPECTION_CODE, PERSONNELS_INSPECTION_CENTRALE_CODE,
                        PERSONNELS_ITARF_CENTRALE_CODE, PERSONNELS_JEUNESSE_ENGAGEMENT_SPORT_CODE, LABORATOIRE_BIS_CODE, MISE_A_DISPOSITION_SANS_REMBOURSEMENT_CODE,
                        MISE_A_DISPOSITION_AVEC_REMBOURSEMENT_CODE, ENSEIGNANTS_CONGES_MOBILITE_CODE, MVT_PREMIER_DEGRE_CODE,
                        PILOTAGE_ANIMATION_PEDAGOGIE_CODE, PERSONNELS_ENSEIGNANTS_CENTRALE_CODE, REMPLACEMENT_CODE, RETRAITE_CODE,
                        REEMPLOI_AUPRES_CNED_CODE, STAGIAIRE_EN_FORMATION_CODE, _CODE);

                if (documentaliste_code.contains(functionCode)) {
                    profileType = DOCUMENTALIST_PROFILE;
                } else if (vie_scolaire_code.contains(functionCode)) {
                    profileType = VIE_SCOLAIRE_PROFILE;
                } else if (personnel_etab_code.contains(functionCode)) {
                    profileType = PERSONNEL_ETAB_PROFILE;
                }
            } else {
                if (documentaliste_code.contains(functionCode)) {
                    profileType = DOCUMENTALIST_PROFILE;
                }
            }

            structMap.put(structUAI, profileType);

            JsonObject function = new JsonObject();
            function.put(STRUCTURE_UAI, structUAI);
            if(firstDegree)
                function.put(POSITION_CODE_1D, roleCode);
            else
                function.put(POSITION_CODE, roleCode);
            garFunctions.add(function);
        }
        if(firstDegree)
            teacher.put(TEACHER_POSITION_1D, garFunctions);
        else
            teacher.put(TEACHER_POSITION, garFunctions);
        teacher.remove("functions");
    }

    /**
     * Process mefs info
     *
     * @param mefs Array of mefs from Neo4j
     */
    private Either<String, JsonObject> processTeachersMefs(JsonArray mefs) {
        Either<String, JsonObject> event = processSimpleArray(mefs, PERSON_MEF, PERSON_MEF_NODE_MANDATORY);
        if (event.isLeft()) {
            return new Either.Left<>("Error when processing teacher mefs : " + event.left().getValue());
        } else {
            return event;
        }
    }
}
