package com.apigw.proxy.forward;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.userauth.UserIdentity;
import com.apigw.proxy.userauth.OutboundAuth;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 转发请求头准备的单元测试：不打网络，直接调 {@code prepareRequest} 验证最终发往上游的头。
 *
 * 覆盖身份透传修复的核心口径：
 * - 网关保留头（X-User-Id / X-Tenant-Id / X-Gateway-Pass / X-App-No / X-App-Secret，
 *   及启用用户鉴权时的 Authorization）上的调用方值，任何路由、有没有验出身份都到不了上游；
 * - 清零排在所有请求动作之后：配置动作对保留头的补/删也被清零覆盖；
 * - 写回只取网关验签/鉴权结论，没结论就不存在，空值绝不写出；
 * - 头名大小写变体、多值、空值同一种处理（按名整头剔除）；
 * - 普通业务头与动作语义不受影响。
 */
class UpstreamForwarderHeaderPrepareTest {

    private static final UserIdentity IDENTITY = new UserIdentity("user-1", "tenant-a");

    private final UpstreamForwarder forwarder = new UpstreamForwarder(null);

    private GatewayRoute route(List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create("r", "r", "http://upstream:8080", 1, null);
        r.replaceRules(List.of(GatewayRule.create("REQUEST", "PATH_PREFIX", null, "/r/", 1)),
                actions);
        return r;
    }

    /**
     * 构造一条「带保留头动作」的存量/越权路由：正常保存路径已被
     * {@link GatewayRule#validateAs} 拒绝，这里绕开聚合校验直接塞，
     * 模拟老配置残留或绕过管理层直接写 Redis，验证运行时清零这道防线独立成立。
     */
    private GatewayRoute legacyRouteWith(GatewayRule... reservedActions) {
        GatewayRoute r = GatewayRoute.create("r", "r", "http://upstream:8080", 1, null);
        r.setActions(List.of(reservedActions));
        return r;
    }

    private GatewayRule reqAdd(String name, String value, int sort) {
        return GatewayRule.create("REQUEST", "REQ_ADD_HEADER", name, value, sort);
    }

    private GatewayRule reqRemove(String name, int sort) {
        return GatewayRule.create("REQUEST", "REQ_REMOVE_HEADER", name, null, sort);
    }

    private ServerHttpRequest prepare(ServerHttpRequest incoming, GatewayRoute route, OutboundAuth auth) {
        try {
            Method m = UpstreamForwarder.class.getDeclaredMethod(
                    "prepareRequest", GatewayRoute.class, ServerHttpRequest.class,
                    String.class, OutboundAuth.class);
            m.setAccessible(true);
            return (ServerHttpRequest) m.invoke(forwarder, route, incoming, "trace-0001", auth);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private MockServerHttpRequest.BaseBuilder<?> baseRequest() {
        return MockServerHttpRequest.get("http://gw/r/1");
    }

    // ---- 保留头：调用方值一律到不了上游 ----

    @Test
    void anonymousOpenRoute_callerForgedIdentityAndAppHeaders_areAllPurged() {
        // 事故回放：开放路由 + 匿名（无验签结论），调用方把身份/租户/通行标记/应用凭据全编一遍
        ServerHttpRequest incoming = baseRequest()
                .header("X-User-Id", "admin")
                .header("X-Tenant-Id", "tenant-victim")
                .header("X-Gateway-Pass", "v1.0.forged")
                .header("X-App-No", "app-billing")
                .header("X-App-Secret", "made-up-secret")
                .build();

        HttpHeaders out = prepare(incoming, route(List.of()), OutboundAuth.none()).getHeaders();

        assertThat(out.get("X-User-Id")).isNullOrEmpty();
        assertThat(out.get("X-Tenant-Id")).isNullOrEmpty();
        assertThat(out.get("X-Gateway-Pass")).isNullOrEmpty();
        assertThat(out.get("X-App-No")).isNullOrEmpty();
        assertThat(out.get("X-App-Secret")).isNullOrEmpty();
    }

    @Test
    void reservedHeaders_caseVariantsAndEmptyAndRepeatedValues_arePurgedUniformly() {
        // 同一种口径：大小写变体、空值、多值，都按名整头清
        ServerHttpRequest incoming = baseRequest()
                .header("x-user-id", "admin", "second-forged")
                .header("X-TENANT-ID", "tenant-victim")
                .header("X-Gateway-Pass", "")
                .header("x-app-no", "   ")
                .header("X-APP-SECRET", "s1", "s2")
                .build();

        HttpHeaders out = prepare(incoming, route(List.of()), OutboundAuth.none()).getHeaders();

        assertThat(out.containsKey("X-User-Id")).isFalse();
        assertThat(out.containsKey("X-Tenant-Id")).isFalse();
        assertThat(out.containsKey("X-Gateway-Pass")).isFalse();
        assertThat(out.containsKey("X-App-No")).isFalse();
        assertThat(out.containsKey("X-App-Secret")).isFalse();
    }

    // ---- 清零与动作的先后：清零最后，动作碰不到保留头 ----

    @Test
    void requestActions_cannotReinjectReservedHeaders_evenAsLastAction() {
        // 即便存量/越权配置把「补 X-User-Id」排在最后一个动作，清零在动作之后，照样清掉，
        // 没有网关结论就不存在（正常配置保存路径已在 GatewayRule 校验里拒绝）
        GatewayRule sneak = GatewayRule.create("REQUEST", "REQ_ADD_HEADER",
                "x-user-id", "config-sneak", 1);
        GatewayRoute r = legacyRouteWith(sneak);
        ServerHttpRequest incoming = baseRequest().build();

        HttpHeaders out = prepare(incoming, r, OutboundAuth.none()).getHeaders();

        assertThat(out.get("X-User-Id")).isNullOrEmpty();
    }

    @Test
    void requestActions_removeReservedHeader_thenGatewayVerdictStillWritten() {
        // 存量配置里「删 X-Tenant-Id」也拦不住网关在清零后按验签结果写回
        GatewayRule remove = GatewayRule.create("REQUEST", "REQ_REMOVE_HEADER",
                "X-Tenant-Id", null, 1);
        GatewayRoute r = legacyRouteWith(remove);
        ServerHttpRequest incoming = baseRequest()
                .header("X-Tenant-Id", "forged")
                .build();

        HttpHeaders out = prepare(incoming, r,
                new OutboundAuth(IDENTITY, "pass-v1", null, true)).getHeaders();

        assertThat(out.getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        assertThat(out.getFirst("X-User-Id")).isEqualTo("user-1");
    }

    // ---- 写回：只认网关结论 ----

    @Test
    void verifiedIdentity_passAndAppNo_areWritten_singleValuesFromGateway() {
        ServerHttpRequest incoming = baseRequest()
                .header("X-User-Id", "forged")
                .header("x-tenant-id", "forged-tenant")
                .header("X-Gateway-Pass", "forged-pass")
                .header("X-App-No", "forged-app")
                .header("X-App-Secret", "forged-secret")
                .header("Authorization", "Bearer caller-token")
                .build();

        HttpHeaders out = prepare(incoming, route(List.of()),
                new OutboundAuth(IDENTITY, "pass-real", "app-real", true)).getHeaders();

        assertThat(out.getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(out.getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        assertThat(out.getFirst("X-Gateway-Pass")).isEqualTo("pass-real");
        assertThat(out.getFirst("X-App-No")).isEqualTo("app-real");
        // 写回都是单值，绝不与入站多值并存
        assertThat(out.get("X-User-Id")).hasSize(1);
        assertThat(out.get("X-App-No")).hasSize(1);
        // 密钥明文任何情况下都不重放给上游
        assertThat(out.get("X-App-Secret")).isNullOrEmpty();
        // 启用用户鉴权：原始令牌不递上游
        assertThat(out.get("Authorization")).isNullOrEmpty();
    }

    @Test
    void userAuthDisabled_authorizationPassesThrough_butAppSecretStillStripped() {
        ServerHttpRequest incoming = baseRequest()
                .header("Authorization", "Bearer upstream-facing-token")
                .header("X-App-Secret", "leak")
                .build();

        HttpHeaders out = prepare(incoming, route(List.of()),
                new OutboundAuth(null, null, null, false)).getHeaders();

        // 用户鉴权未启用：Authorization 不属于网关，原样透传给上游
        assertThat(out.getFirst("Authorization")).isEqualTo("Bearer upstream-facing-token");
        // 但应用密钥头无论开关如何都不许重放
        assertThat(out.get("X-App-Secret")).isNullOrEmpty();
    }

    @Test
    void normalHeaders_hopByHopAndForwarded_stillHandled() {
        ServerHttpRequest incoming = MockServerHttpRequest
                .get("http://gw/r/1")
                .header("X-Business", "keep-me")
                .header("Connection", "close")
                .build();

        HttpHeaders out = prepare(incoming, route(List.of(
                reqAdd("X-Gw", "1", 1),
                reqRemove("X-Business", 2))), OutboundAuth.none()).getHeaders();

        assertThat(out.getFirst("X-Gw")).isEqualTo("1");
        assertThat(out.get("X-Business")).isNullOrEmpty();
        assertThat(out.getFirst("Connection")).isNull();
        assertThat(out.getFirst("X-Gateway-Trace-Id")).isEqualTo("trace-0001");
        // 匿名：一个身份/应用头都不该有
        assertThat(out.containsKey(GatewayHeaders.USER_ID_HEADER)).isFalse();
        assertThat(out.containsKey(GatewayHeaders.APP_NO_HEADER)).isFalse();
    }
}
