// Shiori: where the database is, read from the environment.
package com.example.shiori;

import am.ik.json.Json;
import am.ik.json.JsonNode;

final class DatabaseUrl {

    private DatabaseUrl() {
    }

    // A service tagged postgresql in VCAP_SERVICES, else DATABASE_URL.
    static String fromEnvironment() {
        String url = vcapServices(System.getenv("VCAP_SERVICES"));
        if (url == null) {
            url = System.getenv("DATABASE_URL");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("no database: set DATABASE_URL, or bind a service tagged postgresql");
        }
        return url;
    }

    // The credentials' uri of the first service tagged postgresql: what Cloud Foundry gives
    // an app bound to a user-provided service (-t postgresql) or to a broker's PostgreSQL,
    // whatever the offering is called. An entry of another shape is passed over.
    static String vcapServices(String vcapServices) {
        if (vcapServices == null || vcapServices.isBlank()) {
            return null;
        }
        JsonNode services = Json.parse(vcapServices);
        if (!services.isObject()) {
            return null;
        }
        for (JsonNode instances : services.asObject().asMap().values()) {
            if (!instances.isArray()) {
                continue;
            }
            for (JsonNode instance : instances.asArray().values()) {
                if (instance.isObject() && tagged(instance.asObject().get("tags"), "postgresql")) {
                    JsonNode credentials = instance.asObject().get("credentials");
                    if (credentials.isObject() && credentials.asObject().get("uri").isString()) {
                        return credentials.asObject().get("uri").asString();
                    }
                }
            }
        }
        return null;
    }

    private static boolean tagged(JsonNode tags, String tag) {
        if (tags.isArray()) {
            for (JsonNode each : tags.asArray().values()) {
                if (each.isString() && tag.equals(each.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

}
