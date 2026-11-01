package io.github.pongsapat3791.ufplink;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a client needs to join a host, as one line to copy: the host's addresses and port, and the secret. A machine on
 * several networks has several addresses, and a client on another subnet can reach only some: it takes the first that
 * answers. Anyone with the line can send chunks to the host, as with the secret itself.
 *
 * @param address the addresses, with commas between them
 */
record Invite(String address, int port, byte[] secret) {
    private static final String PREFIX = "ufp1-";
    private static final String COMMAND = "/ufplink join ";
    private static final int MAX_ADDRESSES = 6;

    String code() {
        byte[] name = address.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(3 + secret.length + name.length);
        bytes.putShort((short) port).put((byte) secret.length).put(secret).put(name);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** The invite in a line, or null when it is not one. */
    static Invite read(String code) {
        String text = code.trim();
        if (text.startsWith(COMMAND)) text = text.substring(COMMAND.length()).trim();
        if (!text.startsWith(PREFIX)) return null;
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(text.substring(PREFIX.length())));
            int port = bytes.getShort() & 0xFFFF;
            byte[] secret = new byte[bytes.get() & 0xFF];
            bytes.get(secret);
            byte[] name = new byte[bytes.remaining()];
            bytes.get(name);
            return port > 0 && secret.length >= 16 && name.length > 0 ? new Invite(new String(name, StandardCharsets.UTF_8), port, secret) : null;
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException wrong) {
            return null;
        }
    }

    /**
     * This machine's addresses, the one of its main network first: connecting a UDP socket sends nothing, it only picks
     * the route. Containers' bridges are left out: nothing outside this machine reaches them.
     */
    static String localAddresses() {
        Set<String> found = new LinkedHashSet<>();
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName("192.0.2.1"), 9);
            InetAddress address = probe.getLocalAddress();
            if (usable(address)) found.add(address.getHostAddress());
        } catch (IOException | RuntimeException failure) {
            // no route: the interfaces may still have an address
        }
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (!network.isUp() || network.isVirtual() || name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")
                        || name.startsWith("virbr")) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (usable(address)) found.add(address.getHostAddress());
                }
            }
        } catch (IOException | RuntimeException failure) {
            // nothing more to read
        }
        return found.isEmpty() ? "127.0.0.1" : found.stream().limit(MAX_ADDRESSES).collect(Collectors.joining(","));
    }

    private static boolean usable(InetAddress address) {
        return address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress();
    }
}
