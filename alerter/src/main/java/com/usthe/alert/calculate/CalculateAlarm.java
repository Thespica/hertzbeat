package com.usthe.alert.calculate;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.usthe.alert.AlerterProperties;
import com.usthe.alert.AlerterWorkerPool;
import com.usthe.alert.AlerterDataQueue;
import com.usthe.alert.dao.AlertMonitorDao;
import com.usthe.common.entity.alerter.Alert;
import com.usthe.common.entity.alerter.AlertDefine;
import com.usthe.alert.service.AlertDefineService;
import com.usthe.alert.util.AlertTemplateUtil;
import com.usthe.collector.dispatch.export.MetricsDataExporter;
import com.usthe.common.entity.manager.Monitor;
import com.usthe.common.entity.message.CollectRep;
import com.usthe.common.util.CommonConstants;
import com.usthe.common.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根据告警定义规则和采集数据匹配计算告警
 *
 *
 */
@Configuration
@Slf4j
public class CalculateAlarm {

    private AlerterWorkerPool workerPool;
    private AlerterDataQueue dataQueue;
    private MetricsDataExporter dataExporter;
    private AlertDefineService alertDefineService;
    private AlerterProperties alerterProperties;
    /**
     * 触发中告警信息
     * key - monitorId+alertDefineId 为普通阈值告警
     * key - monitorId 为监控状态可用性可达性告警
     */
    private Map<String, Alert> triggeredAlertMap;

    public CalculateAlarm (AlerterWorkerPool workerPool, AlerterDataQueue dataQueue,
                           AlertDefineService alertDefineService, MetricsDataExporter dataExporter,
                           AlertMonitorDao monitorDao, AlerterProperties alerterProperties) {
        this.workerPool = workerPool;
        this.dataQueue = dataQueue;
        this.dataExporter = dataExporter;
        this.alertDefineService = alertDefineService;
        this.alerterProperties = alerterProperties;
        this.triggeredAlertMap = new ConcurrentHashMap<>(128);
        // 初始化stateAlertMap
        List<Monitor> monitors = monitorDao.findMonitorsByStatusIn(Arrays.asList(CommonConstants.UN_AVAILABLE_CODE,
                CommonConstants.UN_REACHABLE_CODE));
        if (monitors != null) {
            for (Monitor monitor : monitors) {
                Map<String, String> tags = new HashMap<>(6);
                tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitor.getId()));
                tags.put(CommonConstants.TAG_MONITOR_APP, monitor.getApp());
                Alert.AlertBuilder alertBuilder = Alert.builder()
                        .tags(tags)
                        .priority(CommonConstants.ALERT_PRIORITY_CODE_EMERGENCY)
                        .status(CommonConstants.ALERT_STATUS_CODE_PENDING)
                        .target(CommonConstants.AVAILABLE)
                        .content("Monitoring Availability Emergency Alert: " + CollectRep.Code.UN_AVAILABLE.name())
                        .firstTriggerTime(System.currentTimeMillis())
                        .lastTriggerTime(System.currentTimeMillis());
                if (monitor.getStatus() == CommonConstants.UN_REACHABLE_CODE) {
                    alertBuilder
                            .target(CommonConstants.REACHABLE)
                            .content("Monitoring Reachability Emergency Alert: " + CollectRep.Code.UN_REACHABLE.name());
                }
                this.triggeredAlertMap.put(String.valueOf(monitor.getId()), alertBuilder.build());
            }
        }
        startCalculate();
    }

    private void startCalculate() {
        Runnable runnable = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CollectRep.MetricsData metricsData = dataExporter.pollAlertMetricsData();
                    if (metricsData != null) {
                        calculate(metricsData);
                    }
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                }
            }
        };
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
    }

    private void calculate(CollectRep.MetricsData metricsData) {
        long currentTimeMilli = System.currentTimeMillis();
        long monitorId = metricsData.getId();
        String app = metricsData.getApp();
        String metrics = metricsData.getMetrics();
        // 先判断调度优先级为0的指标组采集响应数据状态 UN_REACHABLE/UN_CONNECTABLE 则需发最高级别告警进行监控状态变更
        if (metricsData.getPriority() == 0) {
            if (metricsData.getCode() != CollectRep.Code.SUCCESS) {
                // 采集异常
                if (metricsData.getCode() == CollectRep.Code.UN_AVAILABLE) {
                    // todo 采集器不可用
                } else if (metricsData.getCode() == CollectRep.Code.UN_REACHABLE) {
                    // UN_REACHABLE 对端不可达(网络层icmp)
                    handlerMonitorStatusAlert(String.valueOf(monitorId), app, metricsData.getCode());
                } else if (metricsData.getCode() == CollectRep.Code.UN_CONNECTABLE) {
                    // UN_CONNECTABLE 对端连接失败(传输层tcp,udp)
                    handlerMonitorStatusAlert(String.valueOf(monitorId), app, metricsData.getCode());
                } else {
                    // 其他异常
                    handlerMonitorStatusAlert(String.valueOf(monitorId), app, metricsData.getCode());
                }
                return;
            } else {
                // 判断关联监控之前是否有可用性或者不可达告警,发送恢复告警进行监控状态恢复
                Alert preAlert = triggeredAlertMap.remove(String.valueOf(monitorId));
                if (preAlert != null) {
                    // 发送告警恢复
                    Map<String, String> tags = new HashMap<>(6);
                    tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitorId));
                    tags.put(CommonConstants.TAG_MONITOR_APP, app);
                    String target = CommonConstants.AVAILABLE;
                    String content = "Availability Alert Resolved, Monitor Status Normal Now";
                    if (CommonConstants.REACHABLE.equals(preAlert.getTarget())) {
                        target = CommonConstants.REACHABLE;
                        content = "Reachability Alert Resolved, Monitor Status Normal Now";
                    }
                    Alert resumeAlert = Alert.builder()
                            .tags(tags)
                            .target(target)
                            .content(content)
                            .priority(CommonConstants.ALERT_PRIORITY_CODE_WARNING)
                            .status(CommonConstants.ALERT_STATUS_CODE_RESTORED)
                            .firstTriggerTime(currentTimeMilli)
                            .lastTriggerTime(currentTimeMilli)
                            .times(1)
                            .build();
                    dataQueue.addAlertData(resumeAlert);
                }
            }
        }
        // 查出此监控类型下的此指标集合下关联配置的告警定义信息
        // field - define[]
        Map<String, List<AlertDefine>> defineMap = alertDefineService.getMonitorBindAlertDefines(monitorId, app, metrics);
        if (defineMap == null || defineMap.isEmpty()) {
            return;
        }
        List<CollectRep.Field> fields = metricsData.getFieldsList();
        Map<String, Object> fieldValueMap = new HashMap<>(16);
        for (CollectRep.ValueRow valueRow : metricsData.getValuesList()) {
            if (!valueRow.getColumnsList().isEmpty()) {
                fieldValueMap.clear();
                String instance = valueRow.getInstance();
                if (!"".equals(instance)) {
                    fieldValueMap.put("instance", instance);
                }
                for (int index = 0; index < valueRow.getColumnsList().size(); index++) {
                    String valueStr = valueRow.getColumns(index);
                    CollectRep.Field field = fields.get(index);
                    if (field.getType() == CommonConstants.TYPE_NUMBER) {
                        Double doubleValue = CommonUtil.parseDoubleStr(valueStr);
                        if (doubleValue != null) {
                            fieldValueMap.put(field.getName(), doubleValue);
                        }
                    } else {
                        if (!"".equals(valueStr)) {
                            fieldValueMap.put(field.getName(), valueStr);
                        }
                    }
                }
                for (Map.Entry<String, List<AlertDefine>> entry : defineMap.entrySet()) {
                    List<AlertDefine> defines = entry.getValue();
                    for (AlertDefine define : defines) {
                        String expr = define.getExpr();
                        try {
                            Expression expression = AviatorEvaluator.compile(expr, true);
                            Boolean match = (Boolean) expression.execute(fieldValueMap);
                            if (match) {
                                // 阈值规则匹配，判断已触发阈值次数，触发告警
                                String monitorAlertKey = String.valueOf(monitorId) + define.getId();
                                Alert triggeredAlert = triggeredAlertMap.get(monitorAlertKey);
                                if (triggeredAlert != null) {
                                    int times = triggeredAlert.getTimes() + 1;
                                    triggeredAlert.setTimes(times);
                                    triggeredAlert.setLastTriggerTime(currentTimeMilli);
                                    if (times >= define.getTimes()) {
                                        triggeredAlertMap.remove(monitorAlertKey);
                                        dataQueue.addAlertData(triggeredAlert);
                                    }
                                } else {
                                    int times = 1;
                                    fieldValueMap.put("app", app);
                                    fieldValueMap.put("metrics", metrics);
                                    fieldValueMap.put("metric", define.getField());
                                    Map<String, String> tags = new HashMap<>(6);
                                    tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitorId));
                                    tags.put(CommonConstants.TAG_MONITOR_APP, app);
                                    Alert alert = Alert.builder()
                                            .tags(tags)
                                            .alertDefineId(define.getId())
                                            .priority(define.getPriority())
                                            .status(CommonConstants.ALERT_STATUS_CODE_PENDING)
                                            .target(app + "." + metrics + "." + define.getField())
                                            .times(times)
                                            .firstTriggerTime(currentTimeMilli)
                                            .lastTriggerTime(currentTimeMilli)
                                            // 模板中关键字匹配替换
                                            .content(AlertTemplateUtil.render(define.getTemplate(), fieldValueMap))
                                            .build();
                                    if (times >= define.getTimes()) {
                                        dataQueue.addAlertData(alert);
                                    } else {
                                        triggeredAlertMap.put(monitorAlertKey, alert);
                                    }
                                }
                                // 此优先级以下的阈值规则则忽略
                                break;
                            }
                        } catch (Exception e) {
                            log.warn(e.getMessage());
                        }
                    }
                }

            }
        }
    }

    private void handlerMonitorStatusAlert(String monitorId, String app, CollectRep.Code code) {
        Alert preAlert = triggeredAlertMap.get(monitorId);
        long currentTimeMill = System.currentTimeMillis();
        if (preAlert == null) {
            Map<String, String> tags = new HashMap<>(6);
            tags.put(CommonConstants.TAG_MONITOR_ID, monitorId);
            tags.put(CommonConstants.TAG_MONITOR_APP, app);
            Alert.AlertBuilder alertBuilder = Alert.builder()
                    .tags(tags)
                    .priority(CommonConstants.ALERT_PRIORITY_CODE_EMERGENCY)
                    .status(CommonConstants.ALERT_STATUS_CODE_PENDING)
                    .target(CommonConstants.AVAILABLE)
                    .content("Monitoring Availability Emergency Alert: " + code.name())
                    .firstTriggerTime(currentTimeMill)
                    .lastTriggerTime(currentTimeMill)
                    .nextEvalInterval(alerterProperties.getAlertEvalIntervalBase())
                    .times(1);
            if (code == CollectRep.Code.UN_REACHABLE) {
                alertBuilder
                        .target(CommonConstants.REACHABLE)
                        .content("Monitoring Reachability Emergency Alert: " + code.name());
            }
            Alert alert = alertBuilder.build();
            dataQueue.addAlertData(alert.clone());
            triggeredAlertMap.put(monitorId, alert);
            return;
        }
        if (preAlert.getLastTriggerTime() + preAlert.getNextEvalInterval() >= currentTimeMill) {
            // 还在告警评估时间间隔静默期
            preAlert.setTimes(preAlert.getTimes() + 1);
            triggeredAlertMap.put(monitorId, preAlert);
        } else {
            preAlert.setTimes(preAlert.getTimes() + 1);
            preAlert.setLastTriggerTime(currentTimeMill);
            long nextEvalInterval  = preAlert.getNextEvalInterval() * 2;
            if (preAlert.getNextEvalInterval() == 0) {
                nextEvalInterval = alerterProperties.getAlertEvalIntervalBase();
            }
            nextEvalInterval = Math.min(nextEvalInterval, alerterProperties.getMaxAlertEvalInterval());
            preAlert.setNextEvalInterval(nextEvalInterval);
            triggeredAlertMap.put(monitorId, preAlert);
            dataQueue.addAlertData(preAlert.clone());
        }
    }
}
