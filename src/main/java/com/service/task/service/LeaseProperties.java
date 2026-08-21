package com.service.task.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租约配置：lease_expires_at = 领取时间 + seconds（默认 180 秒 = 3 分钟）。
 */
@Data
@ConfigurationProperties(prefix = "task.lease")
public class LeaseProperties {

    private long seconds = 180;
}
