package io.github.pongsapat3791.ufpmanager;

import io.github.pongsapat3791.ufpmanager.WebServer.BadRequest;
import io.github.pongsapat3791.ufpmanager.WebServer.Request;
import io.github.pongsapat3791.ufplink.api.LinkApi;
import io.github.pongsapat3791.ufplink.api.LinkMachine;
import io.github.pongsapat3791.ufplink.api.LinkPlan;
import io.github.pongsapat3791.ufplink.api.LinkRefusal;
import io.github.pongsapat3791.ufplink.api.LinkRun;
import io.github.pongsapat3791.ufplink.api.LinkTile;
import io.github.pongsapat3791.ufplink.api.UfpLinkApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ultra Fast Pregen Link, through its API: the machines of a host, and who generates what. Only this class names it. */
final class LinkBridge {
    private static LinkApi api() {
        try {
            return UfpLinkApi.get();
        } catch (IllegalStateException notYet) {
            throw new BadRequest("Ultra Fast Pregen Link has not started yet.");
        }
    }

    Map<String, Object> info() {
        try {
            LinkApi api = UfpLinkApi.get();
            return Json.object("version", api.modVersion(), "api", api.apiVersion());
        } catch (IllegalStateException notYet) {
            return Json.object("version", "starting", "api", 0);
        }
    }

    Map<String, Object> poll() {
        LinkApi api;
        try {
            api = UfpLinkApi.get();
        } catch (IllegalStateException notYet) {
            return Json.object("role", "none");
        }
        Map<String, Object> answer = Json.object(
                "role", api.role().name().toLowerCase(Locale.ROOT),
                "tileChunks", api.tileChunks(),
                "port", api.port(),
                "activity", api.activity().orElse(null));
        List<Object> machines = new ArrayList<>();
        for (LinkMachine machine : api.machines()) machines.add(shown(machine));
        answer.put("machines", machines);
        List<Object> refusals = new ArrayList<>();
        for (LinkRefusal refusal : api.refusals()) {
            refusals.add(Json.object("name", refusal.name(), "address", refusal.address(), "reason", refusal.reason(),
                    "when", refusal.whenMillis()));
        }
        answer.put("refusals", refusals);
        answer.put("client", api.client().map(client -> Json.object("name", client.name(), "host", client.host(), "port", client.port(),
                "state", client.state(), "tilesDone", client.tilesDone(), "chunksSent", client.chunksSent())).orElse(null));
        answer.put("run", api.run().map(LinkBridge::shown).orElse(null));
        return answer;
    }

    private static Map<String, Object> shown(LinkMachine machine) {
        return Json.object(
                "name", machine.name(), "address", machine.address(), "version", machine.version(), "threads", machine.threads(),
                "heapMaxMb", machine.maxHeapBytes() >> 20, "system", machine.system(), "state", machine.state(),
                "busy", machine.busy(), "tileX", machine.tileX(), "tileZ", machine.tileZ(), "tilesDone", machine.tilesDone(),
                "chunks", machine.chunksReceived(), "megabytes", machine.bytesReceived() >> 20,
                "chunksPerSecond", machine.chunksPerSecond(), "connectedMs", machine.connectedMillis());
    }

    private static Map<String, Object> shown(LinkRun run) {
        Map<String, Object> answer = shown(run.plan());
        answer.put("helpOthers", run.helpOthers());
        answer.put("running", run.running());
        answer.put("written", run.written());
        answer.put("replaced", run.replaced());
        answer.put("kept", run.kept());
        answer.put("held", run.held());
        answer.put("rejected", run.rejected());
        answer.put("skipped", run.skipped());
        answer.put("elapsedMs", run.elapsedMillis());
        answer.put("stopReason", run.stopReason());
        answer.put("waitingFor", run.waitingFor());
        return answer;
    }

    /** A plan for the page: tiles are [x, z, machine, state, worker], with machines as indexes into `names`, -1 for none. */
    private static Map<String, Object> shown(LinkPlan plan) {
        Map<String, Integer> indexes = new HashMap<>();
        List<String> names = new ArrayList<>();
        List<int[]> tiles = new ArrayList<>();
        for (LinkTile tile : plan.tiles()) {
            tiles.add(new int[] {tile.tileX(), tile.tileZ(), index(tile.planned(), indexes, names), tile.state().ordinal(),
                    index(tile.worker(), indexes, names)});
        }
        return Json.object("dimension", plan.dimension(), "x", plan.centerX(), "z", plan.centerZ(), "sizeK", plan.sizeK(),
                "tileChunks", plan.tileChunks(), "names", names, "tiles", tiles);
    }

    private static int index(String name, Map<String, Integer> indexes, List<String> names) {
        if (name == null) return -1;
        return indexes.computeIfAbsent(name, key -> {
            names.add(key);
            return names.size() - 1;
        });
    }

    private static LinkPlan requested(Request request) {
        String dimension = request.field("dimension");
        if (dimension == null) throw new BadRequest("missing dimension");
        int sizeK = request.number("sizeK");
        if (sizeK < 1 || sizeK > 100) throw new BadRequest("The size is 1 to 100 thousand blocks.");
        try {
            return api().plan(dimension, request.number("x"), request.number("z"), sizeK);
        } catch (IllegalStateException notHost) {
            throw new BadRequest(notHost.getMessage());
        }
    }

    Map<String, Object> plan(Request request) {
        return shown(requested(request));
    }

    /**
     * Starts the area with the page's machine for each tile: `assign` is "x,z,index;x,z,index..." with indexes into the
     * repeated field `name`, -1 for any machine.
     */
    Map<String, Object> start(Request request) {
        LinkPlan base = requested(request);
        List<String> names = request.all("name");
        Map<String, String> chosen = new HashMap<>();
        String assign = request.field("assign");
        if (assign != null && !assign.isEmpty()) {
            for (String entry : assign.split(";")) {
                String[] parts = entry.split(",");
                if (parts.length != 3) throw new BadRequest("bad plan");
                int index;
                try {
                    index = Integer.parseInt(parts[2]);
                } catch (NumberFormatException wrong) {
                    throw new BadRequest("bad plan");
                }
                if (index >= names.size()) throw new BadRequest("bad plan");
                chosen.put(parts[0] + "," + parts[1], index < 0 ? "" : names.get(index));
            }
        }
        List<LinkTile> tiles = new ArrayList<>();
        for (LinkTile tile : base.tiles()) {
            String machine = chosen.get(tile.tileX() + "," + tile.tileZ());
            tiles.add(machine == null ? tile : tile.withPlanned(machine.isEmpty() ? null : machine));
        }
        LinkPlan plan = new LinkPlan(base.dimension(), base.centerX(), base.centerZ(), base.sizeK(), base.tileChunks(), tiles);
        String problem = api().start(plan, !"false".equals(request.field("helpOthers")));
        return Json.object("started", problem == null, "message", problem);
    }

    Map<String, Object> stop() {
        return Json.object("stopping", api().cancel());
    }

    /** Makes this server a host if it is not one, and gives the command other servers join it with. */
    Map<String, Object> invite(Request request) {
        String address = request.field("address");
        try {
            return Json.object("invite", "/ufplink join " + api().invite(address == null || address.isBlank() ? null : address.trim()));
        } catch (IllegalStateException problem) {
            throw new BadRequest(problem.getMessage());
        }
    }

    Map<String, Object> join(Request request) {
        String invite = request.field("invite");
        if (invite == null) throw new BadRequest("missing invite");
        String problem = api().join(invite);
        return Json.object("started", problem == null, "message", problem);
    }

    Map<String, Object> leave() {
        String problem = api().leave();
        return Json.object("started", problem == null, "message", problem);
    }

    Map<String, Object> startLocal(Request request) {
        String dimension = request.field("dimension");
        if (dimension == null) throw new BadRequest("missing dimension");
        String problem = api().startLocal(dimension, request.number("x"), request.number("z"), request.number("sizeK"));
        return Json.object("started", problem == null, "message", problem);
    }

    Map<String, Object> resumeLocal() {
        String problem = api().resumeLocal();
        return Json.object("started", problem == null, "message", problem);
    }
}
