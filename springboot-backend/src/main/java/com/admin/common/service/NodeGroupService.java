package com.admin.common.service;

import com.admin.mapper.UserMapper;
import com.admin.mapper.NodeMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @DependsOn("trafficPoolMigration")
public class NodeGroupService {
    final JdbcTemplate jdbc; final UserMapper users; final NodeMapper nodes; final TransactionTemplate tx;
    public NodeGroupService(JdbcTemplate jdbc,UserMapper users,NodeMapper nodes,PlatformTransactionManager tm) {
        this.jdbc=jdbc;this.users=users;this.nodes=nodes;tx=new TransactionTemplate(tm);
    }
    public void admin(Long id) {
        var u=id==null?null:users.selectById(id);
        if(u==null || !Objects.equals(u.getRoleId(),0) || !Objects.equals(u.getStatus(),1)) throw new SecurityException("仅管理员可管理分组和流量池");
    }
    static String id(String id) {
        if(id==null || !id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new IllegalArgumentException("无效的标识");
        return id;
    }
    static String name(String name) {
        if(name==null || name.trim().isEmpty() || name.trim().length()>100) throw new IllegalArgumentException("名称须为 1–100 个字符");
        return name.trim();
    }
    public List<Map<String,Object>> list(Long user) {
        admin(user);
        return jdbc.query("SELECT id,name,revision FROM node_group ORDER BY name",(r,n)->Map.of("id",r.getString(1),"name",r.getString(2),"revision",r.getLong(3),
                "nodeIds",jdbc.queryForList("SELECT node_id FROM node_group_member WHERE group_id=? ORDER BY node_id",Long.class,r.getString(1)),
                "hostIds",jdbc.queryForList("SELECT host_id FROM vps_group_member WHERE group_id=? ORDER BY host_id",Long.class,r.getString(1))));
    }
    public record Settings(String id,String name,long revision) { }
    public Object save(Long user,Settings s) {
        admin(user);String name=name(s.name());
        return tx.execute(t->{
            admin(user);
            if(s.id()==null || s.id().isEmpty()) {
                if(s.revision()!=0) throw new IllegalArgumentException("请刷新后重试");
                jdbc.update("INSERT INTO node_group VALUES(?,?,1)",UUID.randomUUID().toString(),name);
            } else if(jdbc.update("UPDATE node_group SET name=?,revision=revision+1 WHERE id=? AND revision=?",name,id(s.id()),s.revision())!=1)
                throw new IllegalArgumentException("分组已更新，请刷新后重试");
            return list(user);
        });
    }
    public void assign(Long user,String group,List<Long> ids) {
        assign(user,group,ids,false);
    }
    public void assign(Long user,String group,List<Long> ids,boolean hosts) {
        admin(user);
        if(ids==null || ids.isEmpty() || ids.size()>200 || new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("每次请选择 1–200 个不同节点");
        tx.executeWithoutResult(t->{
            if(group!=null && !group.isEmpty() && jdbc.queryForList("SELECT id FROM node_group WHERE id=? FOR UPDATE",id(group)).isEmpty()) throw new IllegalArgumentException("分组不存在");
            for(Long node:new TreeSet<>(ids)) {
                if(node==null || jdbc.queryForList(hosts?"SELECT id FROM vps_host WHERE id=? FOR UPDATE":"SELECT id FROM node WHERE id=? FOR UPDATE",node).isEmpty()) throw new IllegalArgumentException("服务器或节点不存在");
                if(!hosts && nodes.countPoolHost(node)!=0) throw new IllegalArgumentException("该节点由独立流量池管理");
                jdbc.update(hosts?"DELETE FROM vps_group_member WHERE host_id=?":"DELETE FROM node_group_member WHERE node_id=?",node);
                if(group!=null && !group.isEmpty()) jdbc.update(hosts?"INSERT INTO vps_group_member VALUES(?,?)":"INSERT INTO node_group_member VALUES(?,?)",node,group);
            }
            admin(user);
        });
    }
    public void remove(Long user,String group,long revision) {
        admin(user);tx.executeWithoutResult(t->{
            if(jdbc.update("DELETE FROM node_group WHERE id=? AND revision=?",id(group),revision)!=1) throw new IllegalArgumentException("分组已更新，请刷新后重试");
            jdbc.update("DELETE FROM node_group_member WHERE group_id=?",group);
            jdbc.update("DELETE FROM vps_group_member WHERE group_id=?",group);
            admin(user);
        }); // Removing a group never deletes nodes, tunnels, or services.
    }
}
