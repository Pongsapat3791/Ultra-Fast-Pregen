package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.api.LinkMachine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shares a plan's tiles among machines by their threads, in one block of tiles each. Two neighbouring tiles cannot be
 * generated at the same time (see HostJob), so machines that work apart get in each other's way only where their
 * blocks meet.
 */
final class LinkPlanner {
    private LinkPlanner() {}

    /** The machine of each tile, by HostJob.key; tiles stay unplanned when there is no machine. */
    static Map<Long, String> share(List<int[]> tiles, List<LinkMachine> machines) {
        Map<Long, String> plan = new HashMap<>();
        if (!machines.isEmpty()) split(new ArrayList<>(tiles), machines.stream()
                .sorted(Comparator.comparingInt(LinkMachine::threads).reversed().thenComparing(LinkMachine::name)).toList(), plan);
        return plan;
    }

    private static void split(List<int[]> tiles, List<LinkMachine> machines, Map<Long, String> plan) {
        if (machines.size() == 1) {
            for (int[] tile : tiles) plan.put(HostJob.key(tile[0], tile[1]), machines.get(0).name());
            return;
        }
        int middle = machines.size() / 2;
        List<LinkMachine> first = machines.subList(0, middle);
        List<LinkMachine> second = machines.subList(middle, machines.size());
        int axis = extent(tiles, 0) >= extent(tiles, 1) ? 0 : 1;
        tiles.sort(Comparator.<int[]>comparingInt(tile -> tile[axis]).thenComparingInt(tile -> tile[1 - axis]));
        long weightOfFirst = weight(first);
        long total = weightOfFirst + weight(second);
        int cut = (int) Math.max(1, Math.min(tiles.size() - 1, Math.round((double) tiles.size() * weightOfFirst / total)));
        if (tiles.size() < 2) {
            split(tiles, first, plan);
            return;
        }
        split(new ArrayList<>(tiles.subList(0, cut)), first, plan);
        split(new ArrayList<>(tiles.subList(cut, tiles.size())), second, plan);
    }

    private static int extent(List<int[]> tiles, int axis) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] tile : tiles) {
            min = Math.min(min, tile[axis]);
            max = Math.max(max, tile[axis]);
        }
        return max - min;
    }

    private static long weight(List<LinkMachine> machines) {
        return machines.stream().mapToLong(machine -> Math.max(1, machine.threads())).sum();
    }
}
