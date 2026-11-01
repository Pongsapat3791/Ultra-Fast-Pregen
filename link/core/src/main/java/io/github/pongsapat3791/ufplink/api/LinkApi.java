package io.github.pongsapat3791.ufplink.api;

import java.util.List;
import java.util.Optional;

/**
 * What another mod can see and do on a server with Ultra Fast Pregen Link. It uses plain Java types only, so it works
 * without Minecraft's classes, as Ultra Fast Pregen's API does. Every method is thread-safe, and none of them runs on
 * the server thread unless it says so.
 */
public interface LinkApi {
    enum Role { NONE, HOST, CLIENT }

    int apiVersion();

    /** The installed version, for example {@code 1.21.1-0.2.0}. */
    String modVersion();

    /** {@code role} of config/ufplink-network.conf; {@link Role#NONE} until a server has started. */
    Role role();

    /** The port a host listens on; 0 elsewhere. */
    int port();

    /**
     * Makes this server a host, at once and for later starts, if it is not one, like {@code /ufplink invite}.
     *
     * @param address the addresses clients reach it at, with commas between them; null for all of this machine's
     * @return the line other servers give {@link #join}
     * @throws IllegalStateException when it cannot be one, with the reason
     */
    String invite(String address);

    /** Makes this server a client of the host of an invite, at once and for later starts. Null when it is connecting, or why not. */
    String join(String invite);

    /** Back to no role, at once and for later starts. Null when done, or why not. */
    String leave();

    /** The side of a tile in chunks on a host, from the config; 0 elsewhere. */
    int tileChunks();

    /** A host's machines: every connected client, its own local worker among them. */
    List<LinkMachine> machines();

    /** A host's connections refused since the server started, newest first, 20 at most. */
    List<LinkRefusal> refusals();

    /** A client's connection to its host; empty on any other role. */
    Optional<LinkClientStatus> client();

    /**
     * Cuts an area of sizeK thousand blocks around the center into tiles, and shares them among the connected machines
     * by their threads, in one block of tiles each. Starts nothing.
     *
     * @throws IllegalStateException on a server that is not a host
     */
    LinkPlan plan(String dimension, int centerX, int centerZ, int sizeK);

    /**
     * Starts a distributed pregen by the plan. A machine takes the tiles planned for it, and those planned for nobody.
     *
     * @param helpOthers a machine with no tile of its own left also takes other machines' tiles, and anyone takes those
     *                   of a machine that is not connected; without it those tiles wait for their machine
     * @return null when it started, or why not
     */
    String start(LinkPlan plan, boolean helpOthers);

    /** The distributed pregen running, or else the last one since the server started. */
    Optional<LinkRun> run();

    /** Asks whatever runs to stop after the tile, batch or region file it is on; false when nothing runs. */
    boolean cancel();

    /** Like {@code /ufplink pregen}: this server alone, in a separate world. Null when it started, or why not. */
    String startLocal(String dimension, int centerX, int centerZ, int sizeK);

    /** Like {@code /ufplink pregen resume}. Null when it started, or why not. */
    String resumeLocal();

    /** One line about what runs on this server, as {@code /ufplink status} shows it; empty when nothing runs. */
    Optional<String> activity();
}
