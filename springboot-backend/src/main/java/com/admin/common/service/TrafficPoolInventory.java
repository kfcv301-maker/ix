package com.admin.common.service;

import com.admin.common.dto.GostConfigDto;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Passive inventories only. New pool members wait for their next actual Agent report after rule recovery. */
public final class TrafficPoolInventory {
    private TrafficPoolInventory() { }
    private record Snapshot(Set<String> names,long at) { }
    private static final Map<Long,Snapshot> snapshots=new ConcurrentHashMap<>();
    public static synchronized void record(long node,GostConfigDto config,long now) {
        if(config==null || config.getServices()==null)return;
        snapshots.entrySet().removeIf(e->now-e.getValue().at>1800000);
        if(snapshots.size()>=512 && !snapshots.containsKey(node) || config.getServices().size()>8192)return;
        var names=new HashSet<String>();for(var item:config.getServices())if(item!=null && item.getName()!=null){if(item.getName().length()>200)return;names.add(item.getName());}
        int others=snapshots.entrySet().stream().filter(e->e.getKey()!=node).mapToInt(e->e.getValue().names.size()).sum();
        if(others+names.size()>65536)return;
        snapshots.put(node,new Snapshot(Set.copyOf(names),now));
    }
    static Set<String> names(long node,long now) {
        var s=snapshots.get(node);return s==null || now<s.at || now-s.at>1800000?null:s.names;
    }
}
