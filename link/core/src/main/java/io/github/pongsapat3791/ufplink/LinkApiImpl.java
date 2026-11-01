package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.api.LinkApi;
import io.github.pongsapat3791.ufplink.api.LinkClientStatus;
import io.github.pongsapat3791.ufplink.api.LinkMachine;
import io.github.pongsapat3791.ufplink.api.LinkPlan;
import io.github.pongsapat3791.ufplink.api.LinkRefusal;
import io.github.pongsapat3791.ufplink.api.LinkRun;
import io.github.pongsapat3791.ufplink.api.UfpLinkApi;
import java.util.List;
import java.util.Optional;

/** The API on top of the host, the client and the jobs. */
final class LinkApiImpl implements LinkApi {
    @Override
    public int apiVersion() {
        return UfpLinkApi.VERSION;
    }

    @Override
    public String modVersion() {
        return UfpLink.version();
    }

    @Override
    public Role role() {
        NetworkConfig network = UfpLink.network();
        if (network == null) return Role.NONE;
        return switch (network.role) {
            case HOST -> Role.HOST;
            case CLIENT -> Role.CLIENT;
            case NONE -> Role.NONE;
        };
    }

    @Override
    public int port() {
        LinkHost host = LinkHost.running();
        return host == null ? 0 : host.config().port;
    }

    @Override
    public String invite(String address) {
        return UfpLink.invite(address).code();
    }

    @Override
    public String join(String invite) {
        return UfpLink.join(invite);
    }

    @Override
    public String leave() {
        return UfpLink.leave();
    }

    @Override
    public int tileChunks() {
        NetworkConfig network = UfpLink.network();
        return network != null && network.role == NetworkConfig.Role.HOST ? network.tileChunks : 0;
    }

    @Override
    public List<LinkMachine> machines() {
        LinkHost host = LinkHost.running();
        return host == null ? List.of() : host.sessions().stream().map(HostSession::machine).toList();
    }

    @Override
    public List<LinkRefusal> refusals() {
        LinkHost host = LinkHost.running();
        return host == null ? List.of() : host.refusals();
    }

    @Override
    public Optional<LinkClientStatus> client() {
        LinkClient client = LinkClient.running();
        return client != null && role() == Role.CLIENT ? Optional.of(client.status()) : Optional.empty();
    }

    @Override
    public LinkPlan plan(String dimension, int centerX, int centerZ, int sizeK) {
        if (LinkHost.running() == null) throw new IllegalStateException("This server is not a host.");
        return HostJob.plan(dimension, centerX, centerZ, sizeK, tileChunks(), machines());
    }

    @Override
    public String start(LinkPlan plan, boolean helpOthers) {
        LinkHost host = LinkHost.running();
        if (host == null) return "This server is not a host.";
        if (plan.sizeK() < 1 || plan.sizeK() > 100) return "The size is 1 to 100 thousand blocks.";
        if (!hasWorld(plan.dimension())) return "This server has no world " + plan.dimension() + ".";
        if (SeparateWorld.isOne(plan.dimension())) return "That is a pregen's separate world.";
        return HostJob.start(plan.dimension(), plan, helpOthers);
    }

    @Override
    public Optional<LinkRun> run() {
        return HostJob.latest().map(HostJob::snapshot);
    }

    @Override
    public boolean cancel() {
        PregenJob job = PregenJob.current();
        ImportTask task = ImportTask.current();
        HostJob distributed = HostJob.current();
        if (job == null && task == null && distributed == null) return false;
        String reason = "stopped from the API";
        if (distributed != null) distributed.cancel(reason);
        if (job != null) job.cancel(reason);
        else if (task != null) task.cancel(reason);
        return true;
    }

    @Override
    public String startLocal(String dimension, int centerX, int centerZ, int sizeK) {
        if (sizeK < 1 || sizeK > 100) return "The size is 1 to 100 thousand blocks.";
        if (!hasWorld(dimension)) return "This server has no world " + dimension + ".";
        return PregenJob.start(dimension, sizeK, centerX, centerZ, null);
    }

    @Override
    public String resumeLocal() {
        return PregenJob.resume(null);
    }

    @Override
    public Optional<String> activity() {
        PregenJob job = PregenJob.current();
        ImportTask task = ImportTask.current();
        HostJob distributed = HostJob.current();
        return Optional.ofNullable(job != null ? job.describe() : task != null ? task.describe()
                : distributed != null ? distributed.describe() : null);
    }

    private static boolean hasWorld(String dimension) {
        return UfpLink.worlds().serverRunning() && UfpLink.worlds().dimensions().contains(dimension);
    }
}
