package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由聚合的不变量测试（不依赖 Spring 容器）。
 * 覆盖：编号不可改、上游协议与地址合法性、启用开关、
 * 顺序号连续不重且报错带具体位置、类型白名单、动作方向自洽、必填项。
 */
class GatewayRouteTest {

    private GatewayRoute base() {
        return GatewayRoute.create("order-route", "订单服务路由", "http://order-svc:8080", 1, null);
    }

    private static List<GatewayRule> cond(String type, String name, String value, int sortNo) {
        return List.of(GatewayRule.create(null, type, name, value, sortNo));
    }

    @Test
    void create_setsDefaults() {
        GatewayRoute r = base();
        assertEquals("order-route", r.getRouteNo());
        assertEquals(1, r.getEnabled());
        assertEquals(0, r.getVersion());
        assertEquals(0, r.getConditions().size());
        assertEquals(0, r.getActions().size());
    }

    @Test
    void enabled_defaultsToOneWhenNull() {
        assertEquals(1, GatewayRoute.create("r", "n", "http://h:1", null, null).getEnabled());
        assertEquals(0, GatewayRoute.create("r", "n", "http://h:1", 0, null).getEnabled());
    }

    @Test
    void enabled_rejectsOtherValues() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r", "n", "http://h:1", 5, null));
        assertTrue(e.getMessage().contains("启用开关只能是 0（停用）或 1（启用）"), e.getMessage());
    }

    @Test
    void authRequired_defaultsToOpen() {
        GatewayRoute r = base();
        assertEquals(0, r.getAuthRequired());
        assertFalse(r.requiresAuth());
    }

    @Test
    void authRequired_canBeSwitchedOn() {
        GatewayRoute r = base();
        r.changeAuthRequired(1);
        assertEquals(1, r.getAuthRequired());
        assertTrue(r.requiresAuth());
        // null 按开放默认
        r.changeAuthRequired(null);
        assertFalse(r.requiresAuth());
    }

    @Test
    void authRequired_rejectsOtherValues() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.changeAuthRequired(2));
        assertTrue(e.getMessage().contains("登录开关只能是 0（开放）或 1（需登录）"), e.getMessage());
    }

    @Test
    void routeNo_immutableAfterCreate() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.assignRouteNo("renamed"));
        assertTrue(e.getMessage().startsWith("路由编号建后不可修改"), e.getMessage());
    }

    @Test
    void routeNo_rejectsBlankAndIllegal() {
        assertThrows(BizException.class, () -> GatewayRoute.create(" ", "n", "http://h:1", 1, null));
        assertThrows(BizException.class, () -> GatewayRoute.create("a/b", "n", "http://h:1", 1, null));
    }

    @Test
    void upstream_requiresProtocol() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "order-svc:8080", 1, null));
        assertEquals("上游地址必须以 http:// 或 https:// 开头", e.getMessage());
    }

    @Test
    void upstream_rejectsEmptyHost() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "http://", 1, null));
        // URI 解析失败或主机段不合法，两种拒绝都算数
        assertTrue(e.getMessage().contains("不是合法的 URL") || e.getMessage().contains("主机:端口不合法"),
                e.getMessage());
    }

    @Test
    void upstream_rejectsGarbage() {
        BizException e = assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "http://###", 1, null));
        assertTrue(e.getMessage().contains("不是合法的 URL") || e.getMessage().contains("主机:端口不合法"),
                e.getMessage());
    }

    @Test
    void upstream_rejectsBadPort() {
        assertThrows(BizException.class,
                () -> GatewayRoute.create("r1", "n", "http://order-svc:99999", 1, null));
    }

    @Test
    void upstream_acceptsHttpsAndUnderscoreHost() {
        GatewayRoute r = GatewayRoute.create("r1", "n", "https://order_svc:8443/path", 1, null);
        assertEquals("https://order_svc:8443/path", r.getUpstream());
    }

    @Test
    void conditions_sortNoMustBeUnique_andReportsBothPositions() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1),
                GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 1 条与第 2 条的顺序号撞了，都是 1，同组内顺序号不能重",
                e.getMessage());
    }

    @Test
    void actions_sortNoDuplicate_isLabeledAsActionGroup() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-A", null, 1),
                GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-B", null, 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertTrue(e.getMessage().startsWith("转发动作第 1 条与第 2 条"), e.getMessage());
    }

    @Test
    void conditions_sortNoMustBeContiguousFromOne() {
        GatewayRoute r = base();
        List<GatewayRule> cs = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1),
                GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 3));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件的顺序号必须从 1 起连续不跳号，缺了 2（现在有 2 条）", e.getMessage());
    }

    @Test
    void condition_rejectsUnknownType() {
        GatewayRoute r = base();
        List<GatewayRule> cs = cond("COOKIE", "session", "abc", 1);
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertTrue(e.getMessage().contains("匹配条件第 1 条的类型不支持：COOKIE"), e.getMessage());
    }

    @Test
    void condition_isAlwaysRequestStage() {
        GatewayRoute r = base();
        GatewayRule c = GatewayRule.create("RESPONSE", RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1);
        r.replaceRules(List.of(c), List.of());
        assertEquals(RuleTypes.STAGE_REQUEST, r.getConditions().get(0).getStage());
    }

    @Test
    void headerCondition_requiresName() {
        GatewayRoute r = base();
        List<GatewayRule> cs = cond(RuleTypes.TYPE_HEADER, null, "v", 1);
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 1 条缺名字（头名或参数名）", e.getMessage());
    }

    @Test
    void condition_emptyValueRejected() {
        GatewayRoute r = base();
        List<GatewayRule> cs = cond(RuleTypes.TYPE_METHOD, null, "  ", 1);
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(cs, List.of()));
        assertEquals("匹配条件第 1 条缺取值", e.getMessage());
    }

    @Test
    void addHeaderAction_requiresNameAndValue() {
        GatewayRoute r = base();
        List<GatewayRule> noName = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, null, "v", 1));
        assertThrows(BizException.class, () -> r.replaceRules(List.of(), noName));

        List<GatewayRule> noValue = List.of(
                GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-GW", null, 1));
        assertThrows(BizException.class, () -> r.replaceRules(List.of(), noValue));
    }

    @Test
    void removeHeaderAction_needsNoValue() {
        GatewayRoute r = base();
        GatewayRule a = GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-Internal", null, 1);
        r.replaceRules(List.of(), List.of(a));
        assertEquals(RuleTypes.STAGE_REQUEST, r.getActions().get(0).getStage());
        assertEquals(null, r.getActions().get(0).getValue());
    }

    @Test
    void responseAction_stageIsResponse() {
        GatewayRoute r = base();
        GatewayRule a = GatewayRule.create(null, RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t1", 1);
        r.replaceRules(List.of(), List.of(a));
        assertEquals(RuleTypes.STAGE_RESPONSE, r.getActions().get(0).getStage());
    }

    @Test
    void action_stageMustMatchType() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create("REQUEST", RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t1", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertEquals("转发动作第 1 条的方向与类型对不上：RESP_ADD_HEADER 应为 RESPONSE",
                e.getMessage());
    }

    @Test
    void action_rejectsUnknownType() {
        GatewayRoute r = base();
        List<GatewayRule> as = List.of(
                GatewayRule.create(null, "REWRITE_BODY", "X-A", "v", 1));
        BizException e = assertThrows(BizException.class, () -> r.replaceRules(List.of(), as));
        assertTrue(e.getMessage().contains("转发动作第 1 条的类型不支持：REWRITE_BODY"), e.getMessage());
    }

    @Test
    void replaceRules_isWholeReplacement_andAllowsFullReorder() {
        GatewayRoute r = base();
        r.replaceRules(
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1)),
                List.of());
        assertEquals(1, r.getConditions().size());

        // 修改时整批重排也必须按新一批顺序号重新校验
        r.replaceRules(
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/pay/", 1),
                        GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "POST", 2)),
                List.of());
        assertEquals(2, r.getConditions().size());
        assertEquals("/pay/", r.getConditions().get(0).getValue());
    }

    @Test
    void requestActions_onReservedHeaders_areRejected_addAndRemove_caseInsensitive() {
        GatewayRoute r = base();

        for (String reserved : new String[] {
                "X-User-Id", "x-tenant-id", "X-GATEWAY-PASS", "X-App-No", "x-app-secret"}) {
            // 补不行
            BizException add = assertThrows(BizException.class, () -> r.replaceRules(List.of(),
                    List.of(GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER,
                            reserved, "v", 1))));
            assertTrue(add.getMessage().contains("网关保留头"), add.getMessage());
            // 删也不行（清零是网关自己的事，配置动作碰这组头一律拒绝）
            BizException remove = assertThrows(BizException.class, () -> r.replaceRules(List.of(),
                    List.of(GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER,
                            reserved, null, 1))));
            assertTrue(remove.getMessage().contains("网关保留头"), remove.getMessage());
        }
    }

    @Test
    void responseActions_onSameHeaderNames_areAllowed_directionIsIsolated() {
        // 保留头独占的是「发往上游的请求方向」；上游响应里若回同名头，响应动作仍可处理
        GatewayRoute r = base();
        r.replaceRules(List.of(),
                List.of(GatewayRule.create(null, RuleTypes.TYPE_RESP_ADD_HEADER,
                        "X-Trace-Note", "t", 1),
                        GatewayRule.create(null, RuleTypes.TYPE_RESP_REMOVE_HEADER,
                                "X-User-Id", null, 2)));
        assertEquals(2, r.getActions().size());
    }

    @Test
    void requestAction_onAuthorization_isAllowed_runtimeStripsWhenUserAuthEnabled() {
        // Authorization 是有条件保留头：配置侧不拦（无鉴权部署上游可能本来就要看它），
        // 启用用户鉴权时由转发器无条件剥掉
        GatewayRoute r = base();
        r.replaceRules(List.of(),
                List.of(GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER,
                        "Authorization", "Bearer configured", 1)));
        assertEquals(1, r.getActions().size());
    }
}
