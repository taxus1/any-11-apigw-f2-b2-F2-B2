package com.apigw.proxy.forward;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.proxy.action.HeaderActionApplier;
import com.apigw.proxy.userauth.OutboundAuth;
import io.netty.handler.codec.http.HttpHeaderValues;
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
 * 请求方向做的事（顺序固定）：
 * 1. 复制调用方请求头，剔除「逐跳头」和与具体报文绑定的头——
 *    Connection 及其列出的 keep-alive/te/trailer/upgrade/proxy-authorization 等
 *    只对「调用方↔网关」这一段有意义，不能转发给上游；
 *    Content-Length / Transfer-Encoding 与即将发出的报文绑定，交给 HTTP 客户端按实际请求体重算；
 *    Host 也不能沿用，客户端按目标上游地址重新生成；
 * 2. 清掉网关保留头（{@link GatewayHeaders#RESERVED_HEADERS}：身份/租户/通行标记/应用凭据头）：
 *    <b>无条件</b>清零——与走哪种路由、有没有验出身份无关，调用方塞的同名头一律视为伪造；
 *    用户鉴权启用时连 Authorization 一起剥掉——原始令牌不原样递上游；
 * 3. 补 X-Forwarded-For / X-Forwarded-Proto / X-Forwarded-Host，让上游看得到原始链路信息；
 * 4. 按顺序号执行本路由的请求类动作（补头覆盖同名旧值、删头彻底删除）——
 *    动作只管普通头，它若碰保留头，下一步会被抹掉（见下）；
 * 5. 保留头再清一遍，然后写入网关认定的身份/通行标记/应用编号
 *    （来自 {@link OutboundAuth}，没验出身份就一个都不写）：
 *    网关最后落笔，调用方与路由配置都改不动保留头——
 *    上游看到的保留头取值只可能来自网关的验签结果；
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

    /** 构造发往上游的请求：头清洗 → 保留头清零 → X-Forwarded-* → 请求动作 → 保留头再清零+按验签写入。 */
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

            // 2. 保留头无条件清零：与路由类型、是否验出身份无关，调用方塞的同名值到这里全死。
            //    按头名整体移除（大小写变体、同名多值、空值一起没），清单全项目只有一份
            GatewayHeaders.RESERVED_HEADERS.forEach(headers::remove);
            if (auth.stripAuthorization()) {
                // 用户鉴权启用：原始令牌只在「调用方↔网关」这段有效，绝不原样递上游
                headers.remove(GatewayHeaders.AUTHORIZATION_HEADER);
            }

            // 3. 透传原始链路信息（先加，动作如果想覆盖可以再覆盖）
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

            // 4. 请求类动作按顺序号执行；补头覆盖同名值（包括调用方自己塞的），删头彻底删除。
            //    动作只管普通头：保留头不在这步定稿，下一步网关会重新清算
            HeaderActionApplier.applyRequestActions(route, headers);

            // 5. 网关最后落笔：保留头再清一遍（防动作偷写），然后只按验签结果写入。
            //    匿名就一个身份头都不写；X-App-Secret 任何情况都不写（密钥明文不出网关）。
            //    顺序定死在这一步：保留头谁说了算——网关，不是调用方，也不是路由配置
            GatewayHeaders.RESERVED_HEADERS.forEach(headers::remove);
            if (auth.identity() != null) {
                headers.set(GatewayHeaders.USER_ID_HEADER, auth.identity().userId());
                headers.set(GatewayHeaders.TENANT_ID_HEADER, auth.identity().tenantId());
            }
            if (auth.gatewayPass() != null) {
                headers.set(GatewayHeaders.GATEWAY_PASS_HEADER, auth.gatewayPass());
            }
            if (auth.appNo() != null) {
                headers.set(GatewayHeaders.APP_NO_HEADER, auth.appNo());
            }
        }).build();
    }
}
