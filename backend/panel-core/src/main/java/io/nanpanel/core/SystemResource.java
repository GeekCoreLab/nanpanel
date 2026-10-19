package io.nanpanel.core;

import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
@Path("/api/v1/system")
@Produces(MediaType.APPLICATION_JSON)

public class SystemResource {
    @GET public Map<String, String> info() {
        return Map.of("name", "NaNPanel", "version", "0.1.0-SNAPSHOT", "stage", "scaffold");
    }
}
