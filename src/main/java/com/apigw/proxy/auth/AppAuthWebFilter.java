package com.apigw.proxy.auth;

import com.apigw.common.web.ClientIpResolver;
import com.apigw.common.web.GatewayHeaders;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 第三方接入鉴权过滤器。仅在 {@code apigw.app-auth.enabled=true} 时装配，
 * 顺序在 {@code GatewayProxyWebFilter} **之前**：凭据不过关的请求在匹配路由之前就被挡下，
 * 既不打上游、也不会向外透露「这条路由存不存在」。
 *
 * 凭据（调用方两样一起带）：
 * - {@code X-App-No}     应用编号；
 * - {@code X-App-Secret} 网关签发的密钥明文（只用于当次比对，不记日志、不落库）。
 *
 * 判定口径全部来自 {@link AppCredentialCatalog}（数据）与领域散列/来源口径：
 * 1. 两个头缺任一 → 401；
 * 2. 应用编号认不出来 → 401（不向陌生人暴露哪些编号存在）；
 * 3. 密钥不对、密钥已过期 → 401；
 * 4. 应用已停用 → 403；来源地址不在名单 → 403；
 * 5. 鉴权快照从未加载成功（库故障）→ 503 fail-closed，绝不裸放行。
 *
 * 来源地址与鉴权/流水/限流**同一口径**：{@link ClientIpResolver#resolve}
 * （XFF 最左合法值 → X-Real-IP → 传输层对端），取到后再 canonicalize 与名单里的
 * 规范化字面量精确相等比较——多机轮询、隔层代理都认最初来源，且严格匹配、不做网段。
 *
 * 停用即时性：过滤器每笔请求都读 catalog 的 volatile 快照引用；管理侧停用/改名单后
 * catalog 收到事件原子替换快照，所以**之后的新请求**立刻按新状态判定，没有宽限期。
 */
@Slf4j
public class AppAuthWebFilter implements WebFilter, Ordered {

    /** 比转发过滤器（HIGHEST_PRECEDENCE + 10）更早：先认人，再谈转发。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    /**
     * 认证通过的应用编号（规范化后的可信值）存在 exchange 属性里，交给转发过滤器读取。
     * 不走请求头传递：X-App-No 是网关保留头，入站值在转发前会被整头清掉，可信结论只能
     * 经由这个网关注入、调用方碰不到的属性传给转发器。
     */
    public static final String AUTHENTICATED_APP_NO_ATTR =
            AppAuthWebFilter.class.getName() + ".authenticatedAppNo";

    private final AppCredentialCatalog catalog;
    private final ObjectMapper objectMapper;

    public AppAuthWebFilter(AppCredentialCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (isPassthrough(path)) {
            // 管理接口/actuator 不属于第三方调用流量，不参与凭据校验
            return chain.filter(exchange);
        }

        String appNo = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_NO_HEADER);
        String secret = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_SECRET_HEADER);

        // traceId 与转发侧同一套：调用方带了合法 X-Trace-Id 就沿用，否则网关生成。
        // 只在拒绝答复上需要它——放行后由转发过滤器统一定 traceId，避免两个过滤器各生成一个号
        if (isBlank(appNo) || isBlank(secret)) {
            return reject(exchange, UpstreamFailureKind.APP_UNAUTHENTICATED);
        }

        // 来源地址：全网关唯一口径（XFF→X-Real-IP→对端），再归一成名单用的规范字面量
        String resolvedIp = ClientIpResolver.resolve(exchange.getRequest());
        String canonicalIp = ClientIpResolver.canonicalize(resolvedIp);

        return catalog.authenticate(appNo.trim(), secret, canonicalIp)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        // 认证通过：把规范化后的可信应用编号写进 exchange 属性，转发器只从这里取；
                        // 同时覆盖入站头，让同一过滤链上的流水记录等读者也只看得到可信值。
                        // 入站原始头在转发前仍会被无条件清掉，调用方伪造别的 X-App-No 过不来
                        return chain.filter(withAuthenticatedApp(exchange, decision.authenticatedAppNo()));
                    }
                    return reject(exchange, toError(decision.outcome()));
                });
    }

    private ServerWebExchange withAuthenticatedApp(ServerWebExchange exchange, String authenticatedAppNo) {
        // 用网关认定的编号覆盖入站头：下游与流水只认这个，调用方即便伪造别的 X-App-No 也过不来
        ServerWebExchange mutated = exchange.mutate()
                .request(b -> b.headers(h -> h.set(GatewayHeaders.APP_NO_HEADER, authenticatedAppNo)))
                .build();
        // 可信结论另走 exchange 属性交给转发器（mutate 出来的 exchange 与原 exchange 共用属性表）：
        // 转发前 X-App-No 头会被整头清掉，能被重新写回上游的只有这个网关内部属性里的值
        mutated.getAttributes().put(AUTHENTICATED_APP_NO_ATTR, authenticatedAppNo);
        return mutated;
    }

    private static UpstreamFailureKind toError(AppCredentialCatalog.Outcome outcome) {
        return switch (outcome) {
            case DISABLED, ORIGIN_FORBIDDEN -> UpstreamFailureKind.APP_FORBIDDEN;
            case CONFIG_UNAVAILABLE -> UpstreamFailureKind.APP_CONFIG_UNAVAILABLE;
            // UNKNOWN_APP / BAD_SECRET / SECRET_EXPIRED 统一 401，且对外文案不区分，
            // 避免「编号存在但密钥错」被枚举出有效应用编号
            default -> UpstreamFailureKind.APP_UNAUTHENTICATED;
        };
    }

    private Mono<Void> reject(ServerWebExchange exchange, UpstreamFailureKind kind) {
        String traceId = resolveTraceId(exchange);
        // 服务端日志记下具体拒绝类别（排障用）；调用方只拿到固定文案
        log.debug("接入鉴权拒绝 kind={} traceId={} path={}", kind.errorCode(), traceId,
                exchange.getRequest().getPath().pathWithinApplication().value());
        return GatewayErrors.write(exchange, objectMapper, kind, traceId, null);
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String incoming = GatewayHeaders.normalizeTraceId(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        return incoming != null ? incoming : GatewayHeaders.newRequestId();
    }

    private boolean isPassthrough(String path) {
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/actuator") || path.startsWith("/actuator/");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
