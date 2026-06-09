package fr.openent.gar;

import fr.openent.gar.controller.DevController;
import fr.openent.gar.controller.GarController;
import fr.openent.gar.controller.SettingController;
import fr.openent.gar.export.ExportTask;
import fr.wseduc.cron.CronTrigger;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.EventBus;
import org.entcore.common.http.BaseServer;
import fr.openent.gar.constants.Field;

import java.text.ParseException;

public class Gar extends BaseServer {

	public static final String GAR_ADDRESS = "openent.mediacentre";
	public static JsonObject CONFIG;

	public final static String AAF = "AAF";
	public final static String AAF1D = "AAF1D";

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
        final Promise<Void> promise = Promise.promise();
		super.start(promise);
        promise.future()
            .compose(e -> this.startGar())
            .onSuccess(e -> startPromise.complete())
            .onFailure(th -> {
                log.error("[Gar@Gar::start] Fail to start GAR", th);
                startPromise.tryFail("[Gar@Gar::start] Fail to start GAR");
            });
	}

    public Future<Void> startGar() {
        Future<Void> startGarFuture;
        final EventBus eb = getEventBus(vertx);

        CONFIG = config;

        final String host = config.getString("host").split("//")[1];

        //adapt the configuration to multi-tenant
        if (config.getValue("id-ent") instanceof String) {
            config.put("id-ent", new JsonObject().put(host, config.getValue("id-ent", "")));
        }

        //default AAF only
        if (!config.containsKey("entid-sources")) {
            final JsonArray jaSources = new JsonArray();
            for (final Object id : config.getJsonObject("id-ent").getMap().values()) {
                if (!(id instanceof String)) continue;
                jaSources.add(id + "-" + AAF);
            }
            config.put("entid-sources", jaSources);
        }

        final JsonObject garRessources = config.getJsonObject("gar-ressources", new JsonObject());
        if ((garRessources.containsKey("cert") && !garRessources.getString("cert").isEmpty()) ||
            (garRessources.containsKey("key") && !garRessources.getString("key").isEmpty())) {
            garRessources.put("tenants", new JsonObject().put(config.getJsonObject("id-ent").getString(host),
                new JsonObject().put("cert", garRessources.getString("cert")).put("key", garRessources.getString("key"))));
            garRessources.remove("cert");
            garRessources.remove("key");
        }

        final JsonObject garSftp = config.getJsonObject("gar-sftp", new JsonObject());
        if (garSftp.containsKey("passphrase")) {
            final JsonObject tenants = new JsonObject();
            tenants.put(config.getJsonObject("id-ent").getString(host), new JsonObject()
                .put("username", garSftp.getString("username")).put("passphrase", garSftp.getString("passphrase"))
                .put("sshkey", garSftp.getString("sshkey")).put("dir-dest", garSftp.getString("dir-dest")));

            garSftp.put("tenants", tenants);
            garSftp.remove("username");
            garSftp.remove("passphrase");
            garSftp.remove("sshkey");
            garSftp.remove("dir-dest");
        }

        addController(new GarController(vertx, config));
        addController(new SettingController(eb));

        final String exportCron = config.getString("export-cron", "");

        // setIsolationGroup/setIsolatedClasses removed in Vert.x 4.x
        startGarFuture = vertx.deployVerticle("fr.openent.gar.export.impl.ExportWorker", new DeploymentOptions()
            .setConfig(config)
            .setWorker(true)).mapEmpty();

        if (config.getBoolean(Field.DEV_DASH_MODE, false)) {
            addController(new DevController(vertx, config));
        }

        // export-cron vide => export périodique GAR désactivé (pas de planification).
        // Évite de relancer le SFTP GAR sur les environnements sans compte GAR réel
        // (sinon échec host-key "Could not verify ssh-ed25519" en boucle dans les logs).
        if (exportCron != null && !exportCron.trim().isEmpty()) {
            try {
                new CronTrigger(vertx, exportCron).schedule(new ExportTask(vertx.eventBus()));
            } catch (ParseException e) {
                log.fatal("An error occurred while setting cron task", e);
                startGarFuture = Future.failedFuture(e);
            }
        } else {
            log.info("[Gar] export-cron vide : export periodique GAR desactive");
        }
        return startGarFuture;
    }

}
