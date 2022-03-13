package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.lease.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This rule matches if the instance is DOWN or STARTING.
 *
 * Created by Nikos Michalakis on 7/13/16.
 */
public class DownOrStartingRule implements InstanceStatusOverrideRule {
    private static final Logger logger = LoggerFactory.getLogger(DownOrStartingRule.class);

    @Override
    public StatusOverrideResult apply(InstanceInfo instanceInfo,
                                      Lease<InstanceInfo> existingLease,
                                      boolean isReplication) {
        // ReplicationInstance 是 DOWN 或 STARTING - 相信这一点，但是当实例说 UP 时，质疑
        // 客户端实例发送 STARTING 或 DOWN（因为心跳失败），然后我们接受什么
        // 客户说。复制品也是如此。
        // 来自客户端或副本的 OUT_OF_SERVICE 也需要确认，因为服务可能是
        // 目前在 SERVICE
        // 因为 DOWN 和 STARTING 是程序能够确认的，而UP和OUT_OF_SERVICE是可以人工改的
        if ((!InstanceInfo.InstanceStatus.UP.equals(instanceInfo.getStatus()))
                && (!InstanceInfo.InstanceStatus.OUT_OF_SERVICE.equals(instanceInfo.getStatus()))) {
            logger.debug("Trusting the instance status {} from replica or instance for instance {}",
                    instanceInfo.getStatus(), instanceInfo.getId());
            return StatusOverrideResult.matchingStatus(instanceInfo.getStatus());
        }
        return StatusOverrideResult.NO_MATCH;
    }

    @Override
    public String toString() {
        return DownOrStartingRule.class.getName();
    }
}
