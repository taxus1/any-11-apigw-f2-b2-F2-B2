package com.apigw.proxy.userauth;

import com.apigw.domain.userauth.UserIdentity;

/**
 * 转发器写向上游的身份上下文：这一笔请求「网关认定」的身份、通行标记与应用编号。
 *
 * 由 {@link UserAuthGatekeeper} 在验签之后组装（应用编号来自接入鉴权过滤器写入的
 * exchange 属性，见 {@link com.apigw.proxy.auth.AppAuthWebFilter}），转发器只负责照写：
 * - identity 非空 → 写 X-User-Id / X-Tenant-Id；为空 → 上游一个身份头都看不到（匿名）；
 * - gatewayPass 非空 → 写 X-Gateway-Pass；
 * - authenticatedAppNo 非空 → 写 X-App-No（接入鉴权验过的规范化编号）；为空 → 不写，
 *   调用方入站自带的 X-App-No 已被无条件清掉，绝不可能冒充可信值；
 * - stripAuthorization 为真（用户鉴权已启用）→ 入站的 Authorization 头不原样递上游，
 *   原始令牌只在「调用方↔网关」这一段有意义。
 *
 * 以上头名连同 X-App-Secret 是网关保留头：转发前无条件清零，
 * 只有这里给出的网关结论才允许重新写回（判定口径集中在
 * {@link com.apigw.common.web.GatewayHeaders#isReservedRequestHeader}）。
 */
public record OutboundAuth(UserIdentity identity,
                           String gatewayPass,
                           String authenticatedAppNo,
                           boolean stripAuthorization) {

    /** 用户鉴权整体未启用、也没有接入鉴权结论时的空上下文：不写身份、不盖标记、不动 Authorization。 */
    public static OutboundAuth none() {
        return new OutboundAuth(null, null, null, false);
    }
}
