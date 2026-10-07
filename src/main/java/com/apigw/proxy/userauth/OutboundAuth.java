package com.apigw.proxy.userauth;

import com.apigw.domain.userauth.UserIdentity;

/**
 * 转发器写向上游的身份上下文：这一笔请求「网关认定」的身份、通行标记与应用编号。
 *
 * 由 {@link UserAuthGatekeeper} 在验签之后组装（应用编号由应用鉴权过滤器另行并入），
 * 转发器只负责照写：
 * - identity 非空 → 写 X-User-Id / X-Tenant-Id；为空 → 上游一个身份头都看不到（匿名）；
 * - gatewayPass 非空 → 写 X-Gateway-Pass；
 * - appNo 非空（应用鉴权验过）→ 写 X-App-No；为空 → 上游看不到这个头；
 * - stripAuthorization 为真（用户鉴权已启用）→ 入站的 Authorization 头不原样递上游，
 *   原始令牌只在「调用方↔网关」这一段有意义。
 *
 * 注意：这里的每个字段都是「网关验出来的」，调用方在入站塞的同名头
 * 由转发器按 GatewayHeaders.RESERVED_HEADERS 无条件清掉，跟本类无关。
 */
public record OutboundAuth(UserIdentity identity,
                           String gatewayPass,
                           boolean stripAuthorization,
                           String appNo) {

    /** 用户鉴权整体未启用时的空上下文：不写身份、不盖标记、不带应用编号、不动 Authorization。 */
    public static OutboundAuth none() {
        return new OutboundAuth(null, null, false, null);
    }

    /**
     * 应用鉴权认定了调用方应用时，把认定编号并进出站上下文（转发器据此写 X-App-No）。
     * 没走应用鉴权/没验过时保持 null，上游看不到这个头。
     */
    public OutboundAuth withAppNo(String appNo) {
        return new OutboundAuth(identity, gatewayPass, stripAuthorization, appNo);
    }
}
