package com.apigw.proxy;

import com.apigw.common.web.ClientIpResolver;
import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogSink;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.userauth.UserIdentity;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.action.HeaderActionApplier;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.forward.UpstreamResponse;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.userauth.OutboundAuth;
import com.apigw.proxy.userauth.UserAuthGatekeeper;
import com.apigw.domain.userauth.UserTokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转发链路的总编排（一个高优先级 WebFilter）。整条路：
 *
 *   请求进来
 *     → 定请求编号（沿用调用方 X-Trace-Id，没带就生成），建这笔请求专属的
 *       流水持有者（进来段：编号/应用/来源/方法/路径/发生时间），并先记文件审计第 1 段
 *     → 从路由快照里按配好的条件匹配唯一路由（匹配不到 → 404 NO_ROUTE）
 *     → 转发器里按顺序号执行请求类动作，打到上游
 *     → 上游响应回来：清洗逐跳/报文绑定头，按顺序号执行响应类动作
 *     → 状态码与响应体交回调用方，在持有者上补齐第二段（命中路由/最终状态码/总耗时），
 *       一行完整流水异步入库，并记文件审计第 2 段
 *
 * 任何一步出岔子都由 {@link #fail} 收口成网关自己的 JSON 答复：
 * 内部堆栈、上游原始错误页一律不往外抛；404/502/504/503 四类错误的状态码、
 * X-Gateway-Error 头、响应体 error 码三者齐备且互不相同。
 *
 * 流水并发安全：流水持有者是这笔请求自己的局部对象（不是按 traceId 共享的 Map），
 * 进来段和回去段改的是同一份，再高并发也不会把 A 的路径配到 B 的状态码。
 *
 * 管理接口（/api 开头）不属转发流量，直接放给后面的 Controller/SCG，不参与匹配。
 */
@Slf4j
@Component
public class GatewayProxyWebFilter implements WebFilter, Ordered {

    /** 比 SCG 自身的转发处理更早：转发流量由我们短路掉，管理流量原样放行。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 这些路径不是转发流量：管理接口，以及网关自有的健康检查/actuator 端点。 */
    private static final List<String> PASSTHROUGH_PREFIXES = List.of("/api", "/actuator");

    /** 上游响应回给调用方前必须剔除的逐跳头与报文绑定头。 */
    private static final Set<String> STRIPPED_RESPONSE_HEADERS = new LinkedHashSet<>(List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "http2-settings",
            // 上游报的 content-length 绑定的是「上游↔网关」这段报文，绝不能照抄：
            // 网关按自己写出去的字节重算，或交给 Netty 走分块传输
            "content-length"));

    private final RouteCatalog routeCatalog;
    private final RouteMatcher routeMatcher;
    private final UpstreamForwarder forwarder;
    private final AccessLogRecorder accessLog;
    private final AccessLogSink accessLogSink;
    private final ObjectMapper objectMapper;
    private final UserAuthGatekeeper userAuth;

    public GatewayProxyWebFilter(RouteCatalog routeCatalog,
                                 RouteMatcher routeMatcher,
                                 UpstreamForwarder forwarder,
                                 AccessLogRecorder accessLog,
                                 AccessLogSink accessLogSink,
                                 ObjectMapper objectMapper,
                                 UserAuthGatekeeper userAuth) {
        this.routeCatalog = routeCatalog;
        this.routeMatcher = routeMatcher;
        this.forwarder = forwarder;
        this.accessLog = accessLog;
        this.accessLogSink = accessLogSink;
        this.objectMapper = objectMapper;
        this.userAuth = userAuth;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (isPassthrough(path)) {
            return chain.filter(exchange);
        }

        // 请求编号：调用方带了合法 X-Trace-Id 就沿用（跨服务能串起来），没带/非法我们生成
        String incomingTraceId = GatewayHeaders.normalizeTraceId(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        final String traceId = incomingTraceId != null
                ? incomingTraceId : GatewayHeaders.newRequestId();
        long startNanos = System.nanoTime();
        String method = exchange.getRequest().getMethod() == null
                ? "-" : exchange.getRequest().getMethod().name();

        // 流水第一段在请求一进来就固定下来：应用编号、来源地址（与鉴权/限流同一口径）、
        // 方法、路径、发生时刻。持有者是这笔请求自己的局部对象（响应式链闭包持有），
        // 高并发下每笔请求各有一份，回去段只补自己那份，天然不会串
        String appNo = GatewayHeaders.normalizeAppNo(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.APP_NO_HEADER));
        String clientIp = ClientIpResolver.resolve(exchange.getRequest());
        AccessLogEntry accessEntry = AccessLogEntry.incoming(
                traceId, appNo, clientIp, method, path, Instant.now());

        // 记账用的「这次到底怎样了」：无论从哪个分支结束，doFinally 都拿它写唯一一条 OUT 日志，
        // 避免成功记一遍、失败又记一遍，让同一次请求在审计里出现两条结果
        AtomicReference<Outcome> outcome = new AtomicReference<>(new Outcome(null, null, "ABORTED"));

        // 所有响应（含错误）都把 traceId 带给调用方，方便和访问日志对账
        exchange.getResponse().getHeaders().set(GatewayErrors.TRACE_HEADER, traceId);
        accessLog.logIncoming(traceId, method, path);

        return routeCatalog.routes()
                .flatMap(routes -> {
                    GatewayRoute route = routeMatcher.match(routes, exchange.getRequest());
                    if (route == null) {
                        outcome.set(new Outcome(null, null, "NO_ROUTE"));
                        return GatewayErrors.write(exchange, objectMapper,
                                UpstreamFailureKind.NO_ROUTE, traceId, null);
                    }

                    // 登录鉴权（标记跟着路由走，一条一配）。开放路由与受保护路由走同一套链路：
                    // - 受保护路由：必须带一张验得过的令牌（签名真/没过期/信息全），任何一样不过都 401；
                    // - 开放路由：不拦人，令牌只是可选的身份补充，验不过按匿名放行（口径见 README）。
                    //   身份头由转发器无条件先清后写，所以坏令牌不会往上游泄露任何伪造身份。
                    UserIdentity identity;
                    if (route.requiresAuth()) {
                        if (!userAuth.tokenVerificationEnabled()) {
                            // 配了「需登录」却没配验签密钥：配置事故，fail-closed，绝不裸放行
                            log.warn("路由 {} 要求登录，但未配置用户令牌验签密钥 traceId={}",
                                    route.getRouteNo(), traceId);
                            outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(),
                                    UpstreamFailureKind.USER_AUTH_CONFIG_UNAVAILABLE.errorCode()));
                            return GatewayErrors.write(exchange, objectMapper,
                                    UpstreamFailureKind.USER_AUTH_CONFIG_UNAVAILABLE, traceId, null);
                        }
                        String token = userAuth.extractBearerToken(exchange.getRequest());
                        if (token == null) {
                            log.debug("登录鉴权拒绝 reason=MISSING_TOKEN route={} traceId={}",
                                    route.getRouteNo(), traceId);
                            return rejectUserUnauthorized(exchange, route, traceId, outcome);
                        }
                        UserTokenVerifier.Result checked = userAuth.verify(token);
                        if (!checked.ok()) {
                            // 具体原因（签名错/过期/声明不全）只在服务端日志，不回给调用方
                            log.debug("登录鉴权拒绝 reason={} route={} traceId={}",
                                    checked.failure(), route.getRouteNo(), traceId);
                            return rejectUserUnauthorized(exchange, route, traceId, outcome);
                        }
                        identity = checked.identity();
                    } else {
                        identity = userAuth.tryVerifyIdentity(exchange.getRequest());
                    }

                    outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(), "FORWARDED"));
                    URI targetUri = UpstreamForwarder.resolveTargetUri(
                            route.getUpstream(), exchange.getRequest());
                    OutboundAuth outboundAuth = userAuth.outbound(
                            traceId, exchange.getRequest(), identity)
                            // 应用鉴权（若开启）验过后会把认定的应用编号放在 exchange 属性里；
                            // 请求头里的 X-App-No 谁都能塞，转发器只信属性这个通道
                            .withAppNo(exchange.getAttribute(
                                    GatewayHeaders.AUTHENTICATED_APP_NO_ATTRIBUTE));
                    // 响应处理必须在 WebClient 的 exchangeToMono 回调内完成（此时仍持有上游连接），
                    // 所以把 writeUpstreamResponse 作为 handler 传进去
                    return forwarder.forward(route, exchange.getRequest(), traceId, targetUri,
                            outboundAuth,
                            upstream -> writeUpstreamResponse(exchange, route, upstream))
                            .onErrorResume(err -> {
                                UpstreamFailureKind kind = UpstreamFailureKind.classify(err);
                                outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(),
                                        kind.errorCode()));
                                return fail(exchange, traceId, kind, err);
                            });
                })
                // 路由目录给不出可用配置（Redis 故障且没有旧快照）：这是网关侧故障，不是没配路由
                .onErrorResume(err -> {
                    log.warn("路由配置不可用，无法转发 traceId={}", traceId, err);
                    outcome.set(new Outcome(null, null,
                            UpstreamFailureKind.CONFIG_UNAVAILABLE.errorCode()));
                    return fail(exchange, traceId, UpstreamFailureKind.CONFIG_UNAVAILABLE, err);
                })
                .doFinally(sig -> {
                    Outcome o = outcome.get();
                    // 状态码取「最终回给调用方的状态」：上游透传码、或网关合成的 404/502/504/503；
                    // 状态行写出前连接就断、一个码都没产出时为 0（语义：未产生状态码，列不留 NULL）
                    int status = exchange.getResponse().getStatusCode() == null
                            ? 0 : exchange.getResponse().getStatusCode().value();
                    long elapsed = elapsedMillis(startNanos);
                    accessLog.logOutcome(traceId, method, path, o.routeNo(), o.upstream(),
                            status, o.result(), elapsed);

                    // 同一持有者补齐第二段 → 完整一行异步入库（只做一次非阻塞入队，不卡响应）
                    safeRecord(accessEntry.complete(o.routeNo(), status, elapsed));
                });
    }

    /** 入落库出口再兜一层：出口契约是不抛异常，这里也不允许任何意外碰到转发收尾。 */
    private void safeRecord(AccessLogEntry entry) {
        try {
            accessLogSink.record(entry);
        } catch (Exception e) {
            log.warn("访问流水入队失败 requestId={}（不影响转发）：{}",
                    entry.requestId(), e.toString());
        }
    }

    /**
     * 把上游响应交回调用方：
     * - 状态码原样（上游 4xx/5xx 是业务结果，不是网关故障，网关不改写）；
     * - 剔除逐跳头与 content-length / transfer-encoding，避免和网关实际写出的报文对不上；
     * - 按顺序号执行响应类动作（只作用在响应头上，绝不碰请求）；
     * - 响应体流式写回，不在网关全量缓冲。
     */
    private Mono<Void> writeUpstreamResponse(ServerWebExchange exchange,
                                             GatewayRoute route,
                                             UpstreamResponse upstream) {
        exchange.getResponse().setStatusCode(resolveStatus(upstream.statusCode()));
        HttpHeaders out = exchange.getResponse().getHeaders();
        upstream.headers().keySet().forEach(h -> {
            String lower = h.toLowerCase();
            if (!STRIPPED_RESPONSE_HEADERS.contains(lower)) {
                out.addAll(h, upstream.headers().get(h));
            }
        });
        // 响应类动作按顺序号执行（补头覆盖上游同名头、删头让调用方收不到）
        HeaderActionApplier.applyResponseActions(route, out);

        var response = exchange.getResponse();
        // 提交前最后确认：content-length / transfer-encoding 不由上游说了算，交给 Netty 按实际报文处理
        response.beforeCommit(() -> {
            out.remove(HttpHeaders.CONTENT_LENGTH);
            out.remove(HttpHeaders.TRANSFER_ENCODING);
            return Mono.empty();
        });
        return response.writeWith(upstream.body().map(b -> (DataBuffer) b));
    }

    /**
     * 统一错误收口：把故障写成网关自己的 JSON 答复。
     * 响应可能已提交（响应体写到一半上游断了），那时状态码改不了，GatewayErrors 内部放弃写错误体。
     */
    private Mono<Void> fail(ServerWebExchange exchange, String traceId, UpstreamFailureKind kind,
                            Throwable detail) {
        // 服务端日志留全证据（含堆栈）；调用方只拿得到 kind 的固定文案，拿不到这行
        log.warn("转发失败 kind={} traceId={}", kind.errorCode(), traceId, detail);
        return GatewayErrors.write(exchange, objectMapper, kind, traceId, detail);
    }

    /**
     * 登录鉴权不过：统一回 401 USER_UNAUTHENTICATED。缺令牌、签名错、过期、声明不全对外都是
     * 同一句固定文案（不区分），避免把校验细节与令牌长什么样漏给调用方。
     * 这里已经匹配到路由，结果照常进访问日志（命中路由 + 401）。
     */
    private Mono<Void> rejectUserUnauthorized(ServerWebExchange exchange, GatewayRoute route,
                                              String traceId, AtomicReference<Outcome> outcome) {
        outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(),
                UpstreamFailureKind.USER_UNAUTHENTICATED.errorCode()));
        return GatewayErrors.write(exchange, objectMapper,
                UpstreamFailureKind.USER_UNAUTHENTICATED, traceId, null);
    }

    private boolean isPassthrough(String path) {
        for (String prefix : PASSTHROUGH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 上游状态码原样透传；非标准码（resolve 返回 null）退化成 502，不让框架抛异常。 */
    private static HttpStatus resolveStatus(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_GATEWAY;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 一次转发在审计日志里的归宿：命中路由、上游地址、结果码。 */
    private record Outcome(String routeNo, String upstream, String result) {
    }
}
