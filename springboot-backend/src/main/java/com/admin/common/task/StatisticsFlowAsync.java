package com.admin.common.task;


import com.admin.entity.StatisticsFlow;
import com.admin.entity.User;
import com.admin.mapper.StatisticsFlowMapper;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@EnableScheduling
public class StatisticsFlowAsync {

    @Resource
    UserService userService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Resource
    StatisticsFlowMapper statisticsFlowMapper;

    @Scheduled(cron = "0 0 * * * ?")
    public void statistics_flow() {
        LocalDateTime currentHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        String hourString = currentHour.format(DateTimeFormatter.ofPattern("HH:mm"));
        long time = new Date().getTime();

        // 删除48小时前的数据
        long nowMs = new Date().getTime();
        long cutoffMs = nowMs - 48L * 60 * 60 * 1000;
        statisticsFlowService.remove(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .lt(StatisticsFlow::getCreatedTime, cutoffMs)
        );





        List<User> list = userService.list();
        if (list.isEmpty()) {
            return;
        }
        List<Long> userIds = list.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, Long> lastTotalsByUser = new HashMap<>();
        for (StatisticsFlow last : statisticsFlowMapper.selectLatestByUserIds(userIds)) {
            lastTotalsByUser.put(last.getUserId(), last.getTotalFlow());
        }
        List<StatisticsFlow> statisticsFlowList = new ArrayList<>();

        for (User user : list) {
            long currentFlow = (user.getInFlow() == null ? 0 : user.getInFlow())
                    + (user.getOutFlow() == null ? 0 : user.getOutFlow());

            long currentTotalFlow = currentFlow;
            long incrementFlow = currentTotalFlow;
            Long lastTotalFlow = lastTotalsByUser.get(user.getId());
            if (lastTotalFlow != null) {
                incrementFlow = currentTotalFlow - lastTotalFlow;
                
                if (incrementFlow < 0) {
                    incrementFlow = currentTotalFlow; 
                }
            }

            StatisticsFlow statisticsFlow = new StatisticsFlow();
            statisticsFlow.setUserId(user.getId());
            statisticsFlow.setFlow(incrementFlow);        
            statisticsFlow.setTotalFlow(currentTotalFlow); 
            statisticsFlow.setTime(hourString);
            statisticsFlow.setCreatedTime(time);

            statisticsFlowList.add(statisticsFlow);
        }

        statisticsFlowService.saveBatch(statisticsFlowList);
    }

}
