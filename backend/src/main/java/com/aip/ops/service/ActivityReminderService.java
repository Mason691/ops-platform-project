package com.aip.ops.service;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动预约提醒定时服务。
 * 每隔 5 分钟扫描即将开始的活动，在预设时间点（24h、1h 前）
 * 向预约用户发送提醒（记录到 ops_remind_log）。
 * 实际微信模板消息发送需对接公众号配置，此处先落数据库记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityReminderService {

    private final OpsActivityMapper activityMapper;
    private final OpsActivityBookingMapper bookingMapper;
    private final OpsRemindLogMapper remindLogMapper;
    private final OpsLiveRoomMapper liveRoomMapper;

    @Value("${ops.remind.hours-before:24,1}")
    private String remindHoursBefore;

    /** 每 5 分钟执行一次 */
    @Scheduled(fixedRate = 300_000)
    public void checkAndRemind() {
        try {
            checkActivityReminders();
            checkLiveRoomReminders();
        } catch (Exception e) {
            log.warn("提醒任务执行异常: {}", e.getMessage());
        }
    }

    private void checkActivityReminders() {
        int[] hours = parseRemindHours();
        if (hours.length == 0) return;

        // 查找所有状态为"进行中"(status=1)且有开始时间的活动
        List<OpsActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<OpsActivity>()
                        .eq(OpsActivity::getStatus, 1)
                        .isNotNull(OpsActivity::getStartTime));

        LocalDateTime now = LocalDateTime.now();
        for (OpsActivity activity : activities) {
            if (activity.getStartTime() == null) continue;
            long hoursUntilStart = java.time.Duration.between(now, activity.getStartTime()).toHours();

            for (int h : hours) {
                // 活动开始前 h 小时，±5分钟窗口
                if (hoursUntilStart <= h && hoursUntilStart > h - 1) {
                    sendActivityReminders(activity, h + "h");
                    break; // 每个时间点只发一次
                }
            }
        }
    }

    /**
     * 检查即将开播的直播间（预告 status=0）。
     * 在开播前 5 分钟向已设置提醒的用户发送通知。
     */
    private void checkLiveRoomReminders() {
        LocalDateTime now = LocalDateTime.now();
        // 查找所有预告中且即将在 5 分钟内开播的直播间
        List<OpsLiveRoom> upcomingRooms = liveRoomMapper.selectList(
                new LambdaQueryWrapper<OpsLiveRoom>()
                        .eq(OpsLiveRoom::getStatus, 0)
                        .isNotNull(OpsLiveRoom::getStartTime));

        for (OpsLiveRoom room : upcomingRooms) {
            if (room.getStartTime() == null) continue;
            long minutesUntilStart = java.time.Duration.between(now, room.getStartTime()).toMinutes();
            // 开播前 0~5 分钟窗口内发送提醒
            if (minutesUntilStart < 0 || minutesUntilStart > 5) continue;

            // 查找该直播间所有待发送的提醒记录
            List<OpsRemindLog> pendingReminds = remindLogMapper.selectList(
                    new LambdaQueryWrapper<OpsRemindLog>()
                            .eq(OpsRemindLog::getLiveRoomId, room.getId())
                            .eq(OpsRemindLog::getRemindType, "LIVE")
                            .eq(OpsRemindLog::getStatus, 0));

            for (OpsRemindLog rl : pendingReminds) {
                // 标记已发送
                rl.setStatus(1);
                rl.setSendTime(now);
                remindLogMapper.updateById(rl);

                log.info("已发送直播提醒: liveRoomId={} title={} userId={}",
                        room.getId(), room.getLiveTitle(), rl.getCreateUser());
                // TODO: 对接微信订阅消息推送
                // sendWechatSubscribeMessage(room, rl.getCreateUser());
            }
        }
    }

    private void sendActivityReminders(OpsActivity activity, String remindType) {
        List<OpsActivityBooking> bookings = bookingMapper.selectList(
                new LambdaQueryWrapper<OpsActivityBooking>()
                        .eq(OpsActivityBooking::getActivityId, activity.getId())
                        .eq(OpsActivityBooking::getStatus, 0));

        for (OpsActivityBooking booking : bookings) {
            // 防重复：检查是否已发送过该类型的提醒
            Long exist = remindLogMapper.selectCount(new LambdaQueryWrapper<OpsRemindLog>()
                    .eq(OpsRemindLog::getBookingId, booking.getId())
                    .eq(OpsRemindLog::getRemindType, remindType));
            if (exist > 0) continue;

            OpsRemindLog rl = new OpsRemindLog();
            rl.setBookingId(booking.getId());
            rl.setRemindType(remindType);
            rl.setStatus(1); // 标记已发送（实际微信模板消息发送后续对接）
            rl.setSendTime(LocalDateTime.now());
            remindLogMapper.insert(rl);

            log.info("已发送提醒: bookingId={} activityId={} type={} title={}",
                    booking.getId(), activity.getId(), remindType, activity.getTitle());
        }
    }

    private int[] parseRemindHours() {
        try {
            String[] parts = remindHoursBefore.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i].trim());
            }
            return result;
        } catch (Exception e) { return new int[0]; }
    }
}
