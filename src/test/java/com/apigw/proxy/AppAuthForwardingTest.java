package com.apigw.proxy;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.auth.AppCredentialCatalog;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.userauth.UserAuthGatekeeper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入鉴权与转发链路联动的端到端（真实 Netty + 两个真实过滤器 + JDK 上游）。
 *
 * 专盯凭据外泄与应用编号冒充：
 * - 认证通过：X-App-Secret 绝不到上游，X-App-No 只可能是网关认定的规范化值；
 * - 调用方伪造的 X-App-No（与凭据编号不同）也只能被可信值覆盖；
 * - 认证失败：不打上游；
 * - 不装接入鉴权过滤器（开关关闭）：入站 X-App-No / X-App-Secret 同样到不了上游。
 */
class AppAuthForwardingTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef01234567";

    private FakeUpstream upstream;
    private InMemoryRouteStore routeStore;
    private RouteCatalog catalog;
    private FakeRepository repository;
    private AppCredentialCatalog appCatalog;

    private DisposableServer server;
    private String baseUrl;
    private WebClient client;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        routeStore = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(routeStore, props);

        repository = new FakeRepository();
        appCatalog = new AppCredentialCatalog(repository, clock);

        server = startServer(true);
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();

        loadRoute();
        // 新服务器共享同一份路由快照目录，确保它也加载到路由
        catalog.refresh().block();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        upstream.close();
    }

    private DisposableServer startServer(boolean withAppAuth) {
        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800));
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(nettyClient))
                .build();

        var proxyFilter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), new UpstreamForwarder(webClient),
                new AccessLogRecorder(), e -> { }, new ObjectMapper(),
                new UserAuthGatekeeper(null, null, clock));

        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        // 接入鉴权顺序在前（+5），转发在后（+10）
        List<org.springframework.web.server.WebFilter> filters = withAppAuth
                ? List.of(new AppAuthWebFilter(appCatalog, new ObjectMapper()), proxyFilter)
                : List.of(proxyFilter);
        WebHandler filtering = new FilteringWebHandler(tail, filters);
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        HttpHandler httpHandler = adapter;
        return HttpServer.create().handle(new ReactorHttpHandlerAdapter(httpHandler)).bindNow();
    }

    private void loadRoute() {
        GatewayRoute r = GatewayRoute.create("order", "order", upstream.baseUrl(), 1, null);
        r.replaceRules(List.of(GatewayRule.create("REQUEST", "PATH_PREFIX", null, "/order/", 1)),
                List.of());
        routeStore.setRoutes(List.of(r));
        catalog.refresh().block();
    }

    private void createApp(String no) {
        ClientApp app = ClientApp.create(no, no, SECRET, null, 1, null, null, null, clock);
        repository.insert(app);
        appCatalog.refreshBlock(Duration.ofSeconds(5));
    }

    @Test
    void authenticatedRequest_forwardsTrustedAppNo_butNeverTheSecret() {
        createApp("app-1");

        var resp = client.get().uri(baseUrl + "/order/1")
                .header(GatewayHeaders.APP_NO_HEADER, "app-1")
                .header(GatewayHeaders.APP_SECRET_HEADER, SECRET)
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got).isNotNull();
        assertThat(got.getRequestHeaders().getFirst("X-App-No")).isEqualTo("app-1");
        assertThat(got.getRequestHeaders().get("X-App-Secret"))
                .as("密钥明文只用于当次比对，绝不重放给上游").isNullOrEmpty();
        // 没启用用户鉴权：没有身份头/通行标记
        assertThat(got.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-Gateway-Pass")).isNullOrEmpty();
    }

    @Test
    void forgedAppNoHeader_isReplacedByAuthenticatedValue_notForwardedAsSent() {
        createApp("app-1");

        var resp = client.get().uri(baseUrl + "/order/1")
                .headers(h -> {
                    // 同名头塞多值：第一遍 app-1 过鉴权，后面混 app-victim 想搭车上游
                    h.addAll(GatewayHeaders.APP_NO_HEADER, List.of("app-1", "app-victim"));
                    h.set(GatewayHeaders.APP_SECRET_HEADER, SECRET);
                })
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        var h = upstream.lastExchange().getRequestHeaders();
        // 上游只能看到网关验出来的可信值一个（单值），混入的伪造值整组被清
        assertThat(h.getFirst("X-App-No")).isEqualTo("app-1");
        assertThat(h.get("X-App-No")).hasSize(1);
        assertThat(h.get("X-App-Secret")).isNullOrEmpty();
    }

    @Test
    void badCredentials_areRejected_andUpstreamNeverHit() {
        createApp("app-1");

        var resp = client.get().uri(baseUrl + "/order/1")
                .header(GatewayHeaders.APP_NO_HEADER, "app-1")
                .header(GatewayHeaders.APP_SECRET_HEADER, "wrong-secret")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        resp.releaseBody().block();
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void appAuthDisabled_inboundAppHeaders_stillDoNotReachUpstream() {
        // 开关关闭 = 不装接入鉴权过滤器：入站应用编号/密钥照样不许到上游
        DisposableServer noAppAuth = startServer(false);
        try {
            String url = "http://127.0.0.1:" + noAppAuth.port() + "/order/1";
            var resp = WebClient.builder().build().get().uri(url)
                    .header(GatewayHeaders.APP_NO_HEADER, "app-billing")
                    .header(GatewayHeaders.APP_SECRET_HEADER, "made-up-secret")
                    .exchange().block();
            assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
            resp.releaseBody().block();

            var h = upstream.lastExchange().getRequestHeaders();
            assertThat(h.get("X-App-No"))
                    .as("接入鉴权关闭时没有可信结论，入站应用编号不许到上游").isNullOrEmpty();
            assertThat(h.get("X-App-Secret")).isNullOrEmpty();
        } finally {
            noAppAuth.disposeNow();
        }
    }

    /** 内存假仓储（与 AppAuthWebFilterTest 同款，独立放此避免跨测试类依赖）。 */
    static class FakeRepository implements AppCredentialRepository {
        final Map<String, ClientApp> map = new HashMap<>();
        final Map<String, Set<String>> origins = new HashMap<>();
        private long seq = 0;

        @Override
        public void insert(ClientApp app) {
            app.assignId(++seq);
            map.put(app.getAppNo(), app);
            origins.put(app.getAppNo(), new LinkedHashSet<>());
        }

        @Override
        public Optional<ClientApp> findByAppNo(String appNo) {
            return Optional.ofNullable(map.get(appNo));
        }

        @Override
        public AppPage page(String keyword, long offset, int limit) {
            return new AppPage(List.of(), 0);
        }

        @Override
        public int updateEnabled(String appNo, int target) {
            ClientApp a = map.get(appNo);
            if (a == null) {
                return 0;
            }
            a.changeEnabled(target);
            return 1;
        }

        @Override
        public void addOrigin(String appNo, String ip, Instant now) {
            origins.get(appNo).add(ip);
        }

        @Override
        public void removeOrigin(String appNo, String ip) {
            origins.get(appNo).remove(ip);
        }

        @Override
        public List<AuthApp> loadAllForAuth() {
            List<AuthApp> out = new ArrayList<>();
            for (ClientApp a : map.values()) {
                out.add(new AuthApp(a.getAppNo(), a.getSecretHash(), a.getSecretExpiresAt(),
                        a.getEnabled() == 1, List.copyOf(origins.get(a.getAppNo()))));
            }
            return out;
        }
    }
}
