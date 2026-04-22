package com.kb.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 营销活动 MCP 工具集
 * <p>
 * 将营销平台的 REST API（通过录入的 cURL 转换）封装为 Spring AI @Tool 工具，
 * 供 SkillAgent 在 Tool Calling 时调用。
 * <p>
 * 每个 @Tool 方法对应一条 cURL 接口，description 字段是 LLM 选择工具的依据，
 * 务必写清楚工具的功能边界和参数含义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketingMcpTools {

    private final MarketingApiClient marketingApiClient;

    /**
     * 工具1：创建营销活动
     * 对应 cURL: POST /api/v1/activities
     */
    @Tool(description = "创建一个新的营销活动。需要提供活动名称、活动类型（COUPON=优惠券/DISCOUNT=折扣/GIFT=赠品）、"
            + "开始时间（ISO-8601 格式，如 2024-01-01T10:00:00）、结束时间和活动配置JSON。"
            + "成功后返回包含活动ID的JSON响应。")
    public String createActivity(
            @ToolParam(description = "活动名称，如：双十一满减活动") String name,
            @ToolParam(description = "活动类型：COUPON（优惠券）/ DISCOUNT（折扣）/ GIFT（赠品）") String activityType,
            @ToolParam(description = "活动开始时间，ISO-8601格式，如：2024-11-11T00:00:00") String startTime,
            @ToolParam(description = "活动结束时间，ISO-8601格式，如：2024-11-12T23:59:59") String endTime,
            @ToolParam(description = "活动配置JSON字符串，如：{\"discountRate\":0.8,\"minAmount\":100}") String configJson) {

        log.info("[MCP] createActivity: name={}, type={}", name, activityType);
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("activityType", activityType);
        params.put("startTime", startTime);
        params.put("endTime", endTime);
        try {
            // 解析 configJson 并合并
            if (configJson != null && !configJson.isBlank()) {
                params.put("config", configJson);
            }
        } catch (Exception e) {
            log.warn("[MCP] configJson 解析失败，作为字符串传入", e);
            params.put("config", configJson);
        }
        return marketingApiClient.createActivity(params);
    }

    /**
     * 工具2：查询营销活动列表
     * 对应 cURL: GET /api/v1/activities
     */
    @Tool(description = "查询营销活动列表，可按活动类型、时间范围过滤。"
            + "适用于\"查看当前活动\"\"列出所有优惠券活动\"等查询需求。"
            + "返回活动列表JSON数组。")
    public String listActivities(
            @ToolParam(description = "活动类型过滤，可选：COUPON/DISCOUNT/GIFT，不传则返回全部") String activityType,
            @ToolParam(description = "查询开始日期，格式：yyyy-MM-dd，可为空") String startDate,
            @ToolParam(description = "查询结束日期，格式：yyyy-MM-dd，可为空") String endDate) {

        log.info("[MCP] listActivities: type={}, start={}, end={}", activityType, startDate, endDate);
        return marketingApiClient.listActivities(activityType, startDate, endDate);
    }

    /**
     * 工具3：查询单个活动详情
     * 对应 cURL: GET /api/v1/activities/{activityId}
     */
    @Tool(description = "根据活动ID查询某个营销活动的详细信息，包括活动规则、状态、参与人数等。"
            + "适用于\"查看某活动详情\"\"确认活动是否生效\"等场景。")
    public String getActivityDetail(
            @ToolParam(description = "活动ID，如：ACT_20241111_001") String activityId) {

        log.info("[MCP] getActivityDetail: id={}", activityId);
        return marketingApiClient.getActivityDetail(activityId);
    }

    /**
     * 工具4：更新营销活动
     * 对应 cURL: PUT /api/v1/activities/{activityId}
     */
    @Tool(description = "更新已有营销活动的基本信息（名称、时间、配置等）。"
            + "需要提供活动ID和要更新的字段。"
            + "注意：活动进行中时只能修改部分字段，请先查询活动状态。")
    public String updateActivity(
            @ToolParam(description = "要更新的活动ID") String activityId,
            @ToolParam(description = "新的活动名称，可为空（不修改）") String name,
            @ToolParam(description = "新的结束时间，ISO-8601格式，可为空（不修改）") String endTime,
            @ToolParam(description = "新的活动配置JSON字符串，可为空（不修改）") String configJson) {

        log.info("[MCP] updateActivity: id={}", activityId);
        Map<String, Object> params = new HashMap<>();
        if (name != null && !name.isBlank()) params.put("name", name);
        if (endTime != null && !endTime.isBlank()) params.put("endTime", endTime);
        if (configJson != null && !configJson.isBlank()) params.put("config", configJson);
        return marketingApiClient.updateActivity(activityId, params);
    }

    /**
     * 工具5：启用或禁用营销活动
     * 对应 cURL: PATCH /api/v1/activities/{activityId}/status
     */
    @Tool(description = "启用或禁用某个营销活动。"
            + "enabled=true 表示启用，enabled=false 表示禁用/暂停。"
            + "适用于\"上线活动\"\"暂停活动\"\"下线活动\"等操作。")
    public String toggleActivityStatus(
            @ToolParam(description = "活动ID") String activityId,
            @ToolParam(description = "true=启用活动，false=禁用活动") boolean enabled) {

        log.info("[MCP] toggleActivityStatus: id={}, enabled={}", activityId, enabled);
        return marketingApiClient.toggleActivityStatus(activityId, enabled);
    }

    /**
     * 工具6：创建优惠券
     * 对应 cURL: POST /api/v1/coupons
     */
    @Tool(description = "创建优惠券，需要指定优惠券类型（FIXED=固定金额/PERCENT=折扣百分比）、"
            + "优惠金额或折扣率、使用门槛、有效期和发放数量。"
            + "通常在创建COUPON类型的活动后调用此接口创建具体优惠券。")
    public String createCoupon(
            @ToolParam(description = "所属活动ID，将优惠券关联到活动") String activityId,
            @ToolParam(description = "优惠券类型：FIXED（固定金额）/ PERCENT（折扣百分比）") String couponType,
            @ToolParam(description = "优惠值：FIXED类型时为金额（如50.0），PERCENT类型时为折扣率（如0.8表示八折）") double discountValue,
            @ToolParam(description = "使用门槛，如：100.0 表示满100元可用，0表示无门槛") double minAmount,
            @ToolParam(description = "优惠券有效天数，从发放日起计算") int validDays,
            @ToolParam(description = "总发放数量，如：1000") int totalCount) {

        log.info("[MCP] createCoupon: activityId={}, type={}, discount={}", activityId, couponType, discountValue);
        Map<String, Object> params = new HashMap<>();
        params.put("activityId", activityId);
        params.put("couponType", couponType);
        params.put("discountValue", discountValue);
        params.put("minAmount", minAmount);
        params.put("validDays", validDays);
        params.put("totalCount", totalCount);
        return marketingApiClient.createCoupon(params);
    }

    /**
     * 工具7：配置活动规则（满减、折扣等）
     * 对应 cURL: POST /api/v1/activities/{activityId}/rules
     */
    @Tool(description = "为营销活动配置促销规则，如满减规则（满100减20）、折扣规则（全场九折）、"
            + "赠品规则（买一送一）等。一个活动可以有多条规则。"
            + "适用于\"设置满减规则\"\"配置折扣策略\"等操作。")
    public String configureActivityRules(
            @ToolParam(description = "活动ID") String activityId,
            @ToolParam(description = "规则类型：THRESHOLD_DISCOUNT（满减）/ PERCENT_DISCOUNT（折扣）/ GIFT（赠品）") String ruleType,
            @ToolParam(description = "规则描述，如：满100减20") String ruleDescription,
            @ToolParam(description = "规则配置JSON，如：{\"threshold\":100,\"discount\":20} 或 {\"discountRate\":0.9}") String ruleConfigJson) {

        log.info("[MCP] configureActivityRules: activityId={}, ruleType={}", activityId, ruleType);
        Map<String, Object> rules = new HashMap<>();
        rules.put("ruleType", ruleType);
        rules.put("description", ruleDescription);
        if (ruleConfigJson != null && !ruleConfigJson.isBlank()) {
            rules.put("config", ruleConfigJson);
        }
        return marketingApiClient.configureActivityRules(activityId, rules);
    }
}
