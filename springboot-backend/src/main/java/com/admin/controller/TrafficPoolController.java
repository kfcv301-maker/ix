package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.service.*;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/traffic-pool")
public class TrafficPoolController {
    final TrafficPoolService pools;final NodeGroupService groups;final ManagedTrafficPoolService managed;
    public TrafficPoolController(TrafficPoolService pools,NodeGroupService groups,ManagedTrafficPoolService managed){this.pools=pools;this.groups=groups;this.managed=managed;}
    Long user(){return Long.valueOf(JwtUtil.getUserIdFromToken());}
    public record Selection(String id){ }
    public record GroupSelection(String id,long revision){ }
    public record Assignment(String groupId,List<Long> nodeIds){ }
    public record Adoption(String id,long revision,String fingerprint,String acknowledgement){ }
    @PostMapping("/list") public R list(){return invoke(()->pools.list(user()));}
    @PostMapping("/get") public R get(@RequestBody Selection s){return invoke(()->pools.get(user(),s.id()));}
    @PostMapping("/save") public R save(@RequestBody TrafficPoolService.Settings s){return invoke(()->pools.save(user(),s));}
    @PostMapping("/dns-preview") public R preview(@RequestBody Selection s){return invoke(()->pools.preview(user(),s.id()));}
    @PostMapping("/dns-adopt") public R adopt(@RequestBody Adoption a){return invoke(()->{pools.adopt(user(),a.id(),a.revision(),a.fingerprint(),a.acknowledgement());return null;});}
    @PostMapping("/events") public R events(@RequestBody Selection s){return invoke(()->pools.events(user(),s.id()));}
    @PostMapping("/managed") public R managed(){return invoke(()->managed.get(user()));}
    @PostMapping("/managed/settings") public R managedSettings(@RequestBody ManagedTrafficPoolService.Settings s){return invoke(()->managed.save(user(),s));}
    @PostMapping("/managed/enroll") public R enroll(@RequestBody ManagedTrafficPoolService.Enrollment s){return invoke(()->{managed.enroll(user(),s);return null;});}
    @PostMapping("/groups") public R groups(){return invoke(()->groups.list(user()));}
    @PostMapping("/group/save") public R groupSave(@RequestBody NodeGroupService.Settings s){return invoke(()->groups.save(user(),s));}
    @PostMapping("/group/delete") public R groupDelete(@RequestBody GroupSelection s){return invoke(()->{groups.remove(user(),s.id(),s.revision());return null;});}
    @PostMapping("/group/assign") public R assign(@RequestBody Assignment a){return invoke(()->{groups.assign(user(),a.groupId(),a.nodeIds());return null;});}
    @PostMapping("/group/assign-hosts") public R assignHosts(@RequestBody Assignment a){return invoke(()->{groups.assign(user(),a.groupId(),a.nodeIds(),true);return null;});}
    private R invoke(java.util.function.Supplier<Object> call){
        try{return R.ok(call.get());}catch(SecurityException e){return R.err(403,e.getMessage());}
        catch(IllegalArgumentException|IllegalStateException e){return R.err(e.getMessage());}
        catch(RuntimeException e){return R.err("操作未完成，请刷新后重试；不会删除服务器或已有转发");}
    }
}
