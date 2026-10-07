package com.apigw.proxy.forward;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.proxy.action.HeaderActionApplier;
import com.apigw.proxy.userauth.OutboundAuth;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 把「已经匹配好路由」的请求发往上游，拿到上游响应头后交给上层落回给调用方。
 *
 * 请求方向做的事（顺序固定，<b>顺序本身就是安全合同</b>）：
 * 1. 复制调用方请求头，剔除「逐跳头」和与具体报文绑定的头——
 *    Connection 及其列出的 keep-alive/te/trailer/upgrade/proxy-authorization 等
 *    只对「调用方↔网关」这一段有意义，不能转发给上游；
 *    Content-Length / Transfer-Encoding 与即将发出的报文绑定，交给 HTTP 客户端按实际请求体重算；
 *    Host 也不能沿用，客户端按目标上游地址重新生成；
 * 2. 补 X-Forwarded-For / X-Forwarded-Proto / X-Forwarded-Host，让上游看得到原始链路信息；
 * 3. 按顺序号执行本路由的请求类动作（补头覆盖同名旧值、删头彻底删除）；
 * 4. <b>网关保留头无条件清零</b>（X-User-Id / X-Tenant-Id / X-Gateway-Pass /
 *    X-App-No / X-App-Secret；用户鉴权启用时连 Authorization 一起）——
 *    这一步刻意排在请求动作之后：无论调用方入站塞的、还是路由动作写的，统统清掉，
 *    谁都不能借配置动作给保留头偷渡值；
 * 5. 只按网关自己的验签/鉴权结论把保留头写回（来自 {@link OutboundAuth}）：
 *    没验出身份/应用就一个都不写。上游看到的保留头只能来自网关；
 * 6. 请求体以数据流形式透传，不在网关里全量缓冲（大文件也只过一遍内存）。
 */
@Component
public class UpstreamForwarder {

    /** RFC 7230 逐跳头 + 与报文强绑定的头：这些绝不原样转发。 */
    private static final Set<String> HOP_BY_HOP = new LinkedHashSet<>(List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "trailer", "transfer-encoding", "upgrade", "http2-settings",
            // 内容长度/传输编码与具体报文绑死，交给客户端/框架按实际报文重算
            "content-length", "host"));

    private final WebClient webClient;

    public UpstreamForwarder(WebClient upstreamWebClient) {
        this.webClient = upstreamWebClient;
    }

    /**
     * 发往上游，并在「仍持有上游连接」的 exchangeToMono 回调里把响应交给 handler 处理。
     *
     * 关键点：WebClient（Reactor Netty）要求响应体必须在 exchangeToMono 回调内部消费，
     * 一旦回调返回、连接释放，再拿 bodyToFlux 出来就是空流/已释放缓冲。所以这里把
     * 「拿到状态码+响应头 → 把响应体写回调用方」整个动作作为 handler 传进来，在回调内完成。
     *
     * @param responseHandler 拿到上游响应（状态码、响应头、仍在连接上的响应体流）后执行；
     *                        通常负责头清洗、响应动作、写回调用方
     */
    public Mono<Void> forward(GatewayRoute route, ServerHttpRequest incoming,
                              String traceId, URI targetUri, OutboundAuth auth,
                              Function<UpstreamResponse, Mono<Void>> responseHandler) {
        ServerHttpRequest mutated = prepareRequest(route, incoming, traceId, auth);

        WebClient.RequestBodySpec spec = webClient
                .method(HttpMethod.valueOf(incoming.getMethod().name()))
                .uri(targetUri)
                .headers(h -> h.addAll(mutated.getHeaders()));

        // 请求体流式透传：DataBuffer 原样交给客户端，不缓冲、不改写
        return spec.body(BodyInserters.fromDataBuffers(incoming.getBody()))
                .exchangeToMono(response -> {
                    UpstreamResponse upstreamResponse = new UpstreamResponse(
                            response.statusCode().value(),
                            response.headers().asHttpHeaders(),
                            response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class));
                    return responseHandler.apply(upstreamResponse);
                });
    }

    /**
     * 拼上游目标地址：上游 base（scheme://host:port）+ 原始请求路径（含原始 query）。
     * 用原始（raw）形式拼接，避免对已经编码过的路径做二次编码。
     */
    public static URI resolveTargetUri(String upstreamBase, ServerHttpRequest request) {
        URI base = URI.create(upstreamBase);
        StringBuilder sb = new StringBuilder();
        sb.append(base.getScheme()).append("://").append(base.getRawAuthority());
        // base 里带的路径前缀（如 http://host/svc）拼在请求路径前；一般配置里不带
        String basePath = base.getRawPath();
        if (basePath != null && !basePath.isBlank() && !"/".equals(basePath)) {
            sb.append(basePath);
        }
        sb.append(request.getPath().pathWithinApplication().value());
        if (request.getURI().getRawQuery() != null) {
            sb.append('?').append(request.getURI().getRawQuery());
        }
        return URI.create(sb.toString());
    }

    /** 构造发往上游的请求：头清洗 → X-Forwarded-* → 请求动作 → 保留头清零 → 按网关结论写回。 */
    private ServerHttpRequest prepareRequest(GatewayRoute route, ServerHttpRequest incoming,
                                             String traceId, OutboundAuth auth) {
        return incoming.mutate().headers(headers -> {
            // 1. 剔除逐跳头与报文绑定头（头名大小写不敏感，统一小写比对）
            HOP_BY_HOP.forEach(headers::remove);
            // TE 是逐跳头但 RFC 7231 允许 "trailers"：调用方带了就只放行这一个值，其余一律去掉
            String te = headers.getFirst("te");
            headers.remove("te");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("trailers")) {
                headers.set("te", HttpHeaderValues.TRAILERS.toString());
            }

            // 2. 透传原始链路信息
            String remote = incoming.getRemoteAddress() == null
                    ? null : incoming.getRemoteAddress().getAddress().getHostAddress();
            if (remote != null) {
                String prev = headers.getFirst("X-Forwarded-For");
                headers.set("X-Forwarded-For",
                        prev == null || prev.isBlank() ? remote : prev + ", " + remote);
            }
            headers.set("X-Forwarded-Proto", incoming.getURI().getScheme() == null
                    ? "http" : incoming.getURI().getScheme());
            String originalHost = incoming.getHeaders().getFirst("Host");
            if (originalHost != null) {
                headers.set("X-Forwarded-Host", originalHost);
            }
            headers.set("X-Gateway-Trace-Id", traceId);

            // 3. 请求类动作按顺序号执行；补头覆盖同名值（包括调用方自己塞的），删头彻底删除
            HeaderActionApplier.applyRequestActions(route, headers);

            // 4. 网关保留头无条件清零，且刻意排在所有请求动作之后：
            //    入站塞的、动作补的，到此一律作废；判定口径只认 GatewayHeaders 这一处，
            //    大小写变体/别名拼写/多值/空值同一种处理——按名整头剔除，不看值。
            //    用户鉴权启用时 Authorization 同属保留头（原始令牌不递上游）。
            purgeReservedHeaders(headers, auth.stripAuthorization());

            // 5. 只把网关自己的结论写回；没有结论的头保持「不存在」，空值绝不写出
            if (auth.identity() != null) {
                headers.set(GatewayHeaders.USER_ID_HEADER, auth.identity().userId());
                headers.set(GatewayHeaders.TENANT_ID_HEADER, auth.identity().tenantId());
            }
            if (auth.gatewayPass() != null) {
                headers.set(GatewayHeaders.GATEWAY_PASS_HEADER, auth.gatewayPass());
            }
            if (auth.authenticatedAppNo() != null) {
                headers.set(GatewayHeaders.APP_NO_HEADER, auth.authenticatedAppNo());
            }
        }).build();
    }

    /**
     * 把网关保留头从即将发往上游的报文头上整头剔除。头名只按
     * {@link GatewayHeaders#isReservedRequestHeader} 这一个口径判：
     * HTTP 头名大小写不敏感（{@code X-User-Id} 与 {@code x-user-id} 是同一个头，
     * HttpHeaders 底层的大小写不敏感 Map 会把多遍同名头归并到同一 key），
     * 不承认别名；值为空也一样删——绝不「看值决定删不删」。
     */
    private static void purgeReservedHeaders(HttpHeaders headers, boolean stripAuthorization) {
        headers.keySet().removeIf(name ->
                GatewayHeaders.isReservedRequestHeader(name, stripAuthorization));
    }
}
