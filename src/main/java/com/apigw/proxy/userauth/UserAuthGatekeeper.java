package com.apigw.proxy.userauth;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.userauth.GatewayPassSigner;
import com.apigw.domain.userauth.UserIdentity;
import com.apigw.domain.userauth.UserTokenVerifier;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.time.Clock;

/**
 * 用户登录鉴权的守门人：转发过滤器在匹配到路由之后，拿它做两件事——
 *
 * 1. <b>验令牌</b>：从 {@code Authorization: Bearer <token>} 取令牌，交给
 *    {@link UserTokenVerifier} 真验签。受保护路由（authRequired=1）验不过就 401；
 *    开放路由上令牌只是「可选的身份补充」——带对了照常透传身份，没带/带错按匿名放行，
 *    绝不拦（统一口径，理由见 README「开放路由上的坏令牌」）。
 *
 * 2. <b>组装出站身份</b>：验出的身份 + 网关自盖的通行标记，打包成 {@link OutboundAuth}
 *    交给转发器写向上游。身份头只可能来自这里，调用方在入站塞的同名头转发器会先清掉。
 *
 * 验签结论有一层短缓存（见 {@link IdentityCheckCache}）：受保护路由与开放路由
 * <b>共用同一套</b>——同一枚令牌在同一时刻，两个入口拿到的必然是同一个判定，
 * 不存在「受保护这边拦了、开放那边还当有效身份透传」的缝。复用同时被令牌自身
 * {@code exp} 卡死，永远越不过过期那一刻。
 *
 * 两个协作者都可为空（取决于 {@code apigw.user-auth.*} 是否配了密钥）：
 * 没配验签密钥时受保护路由 fail-closed（由过滤器判 503），开放路由不受影响。
 */
public class UserAuthGatekeeper {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 验签结论的复用窗口（两个入口同一套、同一个窗口）：只是省重复验签的性能手段，
     * 复用上限同时受令牌自身 {@code exp} 卡死（见 {@link IdentityCheckCache}）。
     */
    private static final long CHECK_WINDOW_MILLIS = 60_000L;

    private final UserTokenVerifier verifier;
    private final GatewayPassSigner passSigner;
    private final Clock clock;
    private final IdentityCheckCache checks;

    /**
     * @param clock 与验签器判 {@code exp}/{@code nbf} 同一面钟：缓存量「现在」也靠它，
     *              两面钟会让复用窗口与令牌有效期各说各话
     */
    public UserAuthGatekeeper(UserTokenVerifier verifier, GatewayPassSigner passSigner, Clock clock) {
        this.verifier = verifier;
        this.passSigner = passSigner;
        this.clock = clock;
        // 缓存与验签器同生同灭：密钥轮换/停用 = 重建守门人，旧结论随旧实例整体作废
        this.checks = new IdentityCheckCache(CHECK_WINDOW_MILLIS, clock);
    }

    /** 是否配了验签密钥（没配时受保护路由必须 fail-closed，不能裸放行）。 */
    public boolean tokenVerificationEnabled() {
        return verifier != null;
    }

    /**
     * 从请求里取 Bearer 令牌：scheme 大小写不敏感（RFC 7235），
     * 没带、不是 Bearer、令牌段空白都返回 null（调用方按「没带令牌」处理）。
     */
    public String extractBearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(GatewayHeaders.AUTHORIZATION_HEADER);
        if (header == null) {
            return null;
        }
        String v = header.trim();
        if (v.length() <= BEARER_PREFIX.length()
                || !v.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = v.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 真验签：签名、过期、必备声明逐项过（见 {@link UserTokenVerifier}）。 */
    public UserTokenVerifier.Result verify(String token) {
        return checks.check(token, verifier::verify);
    }

    /**
     * 开放路由专用：带了令牌就试着验，验过给身份；没带或验不过一律当匿名（返回 null），
     * 开放路由绝不因为令牌问题拦人。与 {@link #verify} 走同一套结论缓存，
     * 同一枚令牌同一时刻两边的判定必然一致。
     */
    public UserIdentity tryVerifyIdentity(ServerHttpRequest request) {
        if (verifier == null) {
            return null;
        }
        String token = extractBearerToken(request);
        if (token == null) {
            return null;
        }
        UserTokenVerifier.Result result = checks.check(token, verifier::verify);
        return result.ok() ? result.identity() : null;
    }

    /**
     * 组装发往上游的身份上下文。通行标记把 traceId/方法/路径/身份和时间戳一起签进去，
     * 上游用共享密钥验得出「确实过了网关、且身份头没被换过」。
     *
     * @param authenticatedAppNo 接入鉴权验过的可信应用编号（来自 exchange 属性）；
     *                           null 表示没有接入鉴权结论，X-App-No 一个字都不向上游写
     */
    public OutboundAuth outbound(String traceId, ServerHttpRequest request, UserIdentity identity,
                                 String authenticatedAppNo) {
        String pass = null;
        if (passSigner != null) {
            String method = request.getMethod() == null ? "" : request.getMethod().name();
            String path = request.getPath().pathWithinApplication().value();
            pass = passSigner.sign(clock.millis(), traceId, method, path,
                    identity == null ? "" : identity.userId(),
                    identity == null ? "" : identity.tenantId());
        }
        // 用户鉴权启用时，原始令牌（Authorization）不递上游
        return new OutboundAuth(identity, pass, authenticatedAppNo, verifier != null);
    }
}
