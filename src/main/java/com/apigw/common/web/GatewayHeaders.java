package com.apigw.common.web;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网关注入/识别的请求头约定，全项目统一，别散落字面量。
 *
 * - {@code X-Trace-Id}（**入站**）：调用方带来的追踪号。带了且格式合法就沿用，
 *   跨服务排查能直接串起来；没带/非法由网关生成一个 32 位十六进制 UUID 串。
 *   同一个号还会经响应头 {@code X-Gateway-Trace-Id} 回给调用方。
 * - {@code X-App-No}（**入站**）：调进来的应用编号。带了且格式合法就入账，认不出来留空，
 *   绝不能把伪造垃圾写进流水。开启接入鉴权时，它和 {@code X-App-Secret} 合起来是调用方的凭据；
 *   认证通过后网关会把验出的规范化编号作为出站值写回（见 {@link #RESERVED_REQUEST_HEADERS}）。
 * - {@code X-App-Secret}（**入站**）：调用方持有的密钥明文，仅用于当次校验，
 *   不记录、不回显、不落库（库里只有它的不可逆散列），也绝不出站、不重放给上游。
 *
 * 两个入站头都做白名单校验，原因有二：一是这些值要落库、要进日志，不能放任任意长串/换行注入；
 * 二是追踪号会回写到响应头，非法字符可能变成响应拆分载体。
 */
public final class GatewayHeaders {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String APP_NO_HEADER = "X-App-No";
    /** 应用密钥头：只用于网关当次比对散列，绝不写日志/落库。 */
    public static final String APP_SECRET_HEADER = "X-App-Secret";

    /**
     * 调用方携带用户令牌的入站头：{@code Authorization: Bearer <token>}。
     * 网关验过之后令牌本身不原样转发，上游只看下面两个身份头。
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * 网关写向上游的身份头（出站）：用户标识、租户标识。
     * 这两个头与通行标记由网关<b>独占</b>：入站请求里若带同名头，转发前一律先清掉，
     * 再按网关自己验签的结果写入——调用方塞的假身份一个字都到不了上游。
     */
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /** 网关盖的通行标记（出站）：上游据此确认这笔请求确实过了网关。 */
    public static final String GATEWAY_PASS_HEADER = "X-Gateway-Pass";

    /**
     * 网关保留头（请求方向，<b>无条件</b>由网关独占）。入站请求里这些头无论谁带、
     * 走开放路由还是受保护路由、有没有验出身份，调用方塞的值一个字都不许递到上游：
     * 转发前先整头清掉（见 {@link #RESERVED_REQUEST_HEADERS}），再只按网关自己的
     * 验签/鉴权结果决定写不写、写什么。
     *
     * <ul>
     *   <li>{@code X-User-Id} / {@code X-Tenant-Id} / {@code X-Gateway-Pass}：
     *       用户身份与通行标记，只可能来自用户令牌验签结果；匿名时一律不写；</li>
     *   <li>{@code X-App-No}：第三方接入应用编号，只可能来自接入鉴权（开关关着、
     *       或没验出应用）时一律不写，避免来路不明的应用编号冒充内网可信值；</li>
     *   <li>{@code X-App-Secret}：应用密钥明文，只是「调用方↔网关」这一跳的凭证，
     *       任何情况下都不向上游重放。</li>
     * </ul>
     *
     * {@code Authorization} 性质不同（它是标准头，用户鉴权未启用时网关不拥有它的语义，
     * 调用方可能正拿它和上游做别的约定），因此不在无条件清单里：仅在用户鉴权启用时
     * 按「原始令牌不递上游」剥掉，判定口径同样在这里集中给出（{@link #isReservedRequestHeader}）。
     */
    public static final Set<String> RESERVED_REQUEST_HEADERS = Set.of(
            USER_ID_HEADER.toLowerCase(),
            TENANT_ID_HEADER.toLowerCase(),
            GATEWAY_PASS_HEADER.toLowerCase(),
            APP_NO_HEADER.toLowerCase(),
            APP_SECRET_HEADER.toLowerCase());

    /**
     * 头名是否命中网关保留头。<b>全网关口径唯一</b>，清洗、配置校验都只准走这里：
     * 头名按 HTTP 语义大小写不敏感（统一小写后比），不承认任何别名/拼写变体；
     * 与头出现几遍、值是什么、值是否为空完全无关——空值、多值一样整头清掉。
     *
     * @param stripAuthorization 用户鉴权启用时为真：连同 {@code Authorization} 一起视为保留头
     *                           （启用后原始令牌绝不递上游）；未启用时 Authorization 原样透传
     */
    public static boolean isReservedRequestHeader(String name, boolean stripAuthorization) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase();
        if (RESERVED_REQUEST_HEADERS.contains(lower)) {
            return true;
        }
        return stripAuthorization && AUTHORIZATION_HEADER.toLowerCase().equals(lower);
    }

    /** 追踪号：字母数字与 . _ -，长度 8..64（覆盖常见 trace/span 号与 UUID）。 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** 应用编号：字母数字与 . _ -，长度 1..64（与路由编号一套字符集）。 */
    private static final Pattern APP_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private GatewayHeaders() {
    }

    /** 合法才认，否则 null（调用方据此决定是否自己生成）。 */
    public static String normalizeTraceId(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return TRACE_ID_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 合法才认，否则 null（流水里 app_no 留空）。 */
    public static String normalizeAppNo(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return APP_NO_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 网关自己生成的请求编号：32 位十六进制（去横线的 UUID），与既有响应头口径一致。 */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
