package com.apigw.proxy;

import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.userauth.GatewayPassSigner;
import com.apigw.domain.userauth.UserTokenVerifier;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.auth.AppCredentialCatalog;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.userauth.UserAuthGatekeeper;
import com.apigw.support.JwtMinter;
import com.apigw.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户登录鉴权 + 身份透传端到端测试（真实 Netty 服务端 + 真实 WebClient 上游 + JDK 上游，无 Redis）。
 *
 * 覆盖题目硬性要求：
 * - 路由级开关：authRequired=1 的路由必须带验得过的令牌，否则 401；开放路由谁都能打；
 * - 验签是真验签：错密钥、假签名、过期（含正好压点）、缺令牌全部 401，且不泄露校验细节；
 * - 透传：验过之后 X-User-Id / X-Tenant-Id 写给上游，原始令牌（Authorization）不递上游；
 * - 防伪：调用方塞的同名身份头/通行标记/应用凭据头一律被清掉（与路由类型、是否验出身份无关），
 *   大小写变体、同名多遍、空值同一口径，上游只看得见网关写的；
 * - 保留头 vs 路由动作：动作能管普通头，动不了保留头（网关最后落笔）；
 * - 应用凭据：应用鉴权验过后认定的 X-App-No 写给上游，X-App-Secret 永不出网关；
 * - 通行标记：X-Gateway-Pass 由网关盖章，上游用共享密钥验得出，伪造的验不过；
 * - 开放/受保护路由混在同一条链路，互不影响；
 * - 开放路由上的坏令牌：放行（匿名），统一口径。
 */
class UserAuthProxyFilterTest {

    private static final String TOKEN_SECRET = "e2e-token-secret-0123456789abcdef0123";
    private static final String PASS_SECRET = "e2e-pass-secret-abcdef01234567890123456";
    private static final String WRONG_SECRET = "caller-made-up-secret-not-the-real-one!!";
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef01234567";

    private FakeUpstream upstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;
    private GatewayPassSigner passSigner;
    /** 验签器与结论缓存共用的一面钟：测试里随拨随走，不靠 sleep 等过期。 */
    private MutableClock clock;
    private UserAuthGatekeeper gatekeeper;

    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);

        passSigner = new GatewayPassSigner(PASS_SECRET);
        clock = MutableClock.at(Instant.now());
        var verifier = new UserTokenVerifier(TOKEN_SECRET, null, clock, new ObjectMapper());
        gatekeeper = new UserAuthGatekeeper(verifier, passSigner, clock);

        server = startServer(gatekeeper);
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    private DisposableServer startServer(UserAuthGatekeeper gatekeeper, WebFilter... prefilters) {
        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();
        var proxyFilter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), new UpstreamForwarder(webClient),
                new AccessLogRecorder(), e -> { }, new ObjectMapper(), gatekeeper);
        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        // 前置过滤器（如应用鉴权）按给定顺序排在转发过滤器之前，与生产 ORDER 一致
        List<WebFilter> filters = new ArrayList<>(List.of(prefilters));
        filters.add(proxyFilter);
        WebHandler filtering = new FilteringWebHandler(tail, filters);
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        HttpHandler httpHandler = adapter;
        return HttpServer.create().handle(new ReactorHttpHandlerAdapter(httpHandler)).bindNow();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        upstream.close();
    }

    // ---- 造路由/令牌的小工具 ----

    private GatewayRule cond(String type, String name, String value, int sort) {
        return GatewayRule.create("REQUEST", type, name, value, sort);
    }

    private GatewayRule act(String type, String name, String value, int sort) {
        return GatewayRule.create(type.startsWith("RESP_") ? "RESPONSE" : "REQUEST",
                type, name, value, sort);
    }

    private GatewayRoute route(String no, int authRequired) {
        return route(no, authRequired, List.of());
    }

    private GatewayRoute route(String no, int authRequired, List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream.baseUrl(), 1, null);
        r.changeAuthRequired(authRequired);
        r.replaceRules(List.of(cond("PATH_PREFIX", null, "/" + no + "/", 1)), actions);
        return r;
    }

    private void loadRoutes(GatewayRoute... routes) {
        store.setRoutes(List.of(routes));
        catalog.refresh().block();
    }

    private String token(String secret, String sub, String tenant, long expEpochSecond) {
        return JwtMinter.mintHs256(secret,
                "{\"sub\":\"" + sub + "\",\"tenant\":\"" + tenant + "\",\"exp\":" + expEpochSecond + "}");
    }

    private String validToken() {
        return token(TOKEN_SECRET, "user-1", "tenant-a", clock.instant().getEpochSecond() + 3600);
    }

    // ---- 受保护路由：必须验过 ----

    @Test
    void protectedRoute_withoutToken_is401_andUpstreamNotHit() {
        loadRoutes(route("secure", 1));

        var resp = client.get().uri(baseUrl + "/secure/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("USER_UNAUTHENTICATED");
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).contains("USER_UNAUTHENTICATED").contains("traceId");
        // 没验过就不打上游
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void protectedRoute_garbageToken_is401_andBodyDoesNotLeakDetails() {
        loadRoutes(route("secure", 1));

        var resp = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = resp.bodyToMono(String.class).block();
        // 统一固定文案：不告诉调用方是签名错、过期还是声明不全
        assertThat(body).contains("USER_UNAUTHENTICATED")
                .doesNotContain("signature").doesNotContain("exp")
                .doesNotContain("HS256").doesNotContain("MALFORMED");
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void protectedRoute_tokenSignedWithWrongSecret_is401() {
        loadRoutes(route("secure", 1));
        String forged = token(WRONG_SECRET, "user-1", "tenant-a",
                clock.instant().getEpochSecond() + 3600);

        var resp = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + forged)
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        resp.releaseBody().block();
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void protectedRoute_expiredToken_is401_boundaryCountsAsExpired() {
        loadRoutes(route("secure", 1));
        long now = clock.instant().getEpochSecond();
        // 已过期 10 秒
        var expired = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + token(TOKEN_SECRET, "user-1", "tenant-a", now - 10))
                .exchange().block();
        assertThat(expired.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        expired.releaseBody().block();

        // 正好压在过期这一刻：也按过期处理，没有宽限缝
        var atBoundary = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + token(TOKEN_SECRET, "user-1", "tenant-a", now))
                .exchange().block();
        assertThat(atBoundary.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        atBoundary.releaseBody().block();
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void cachedConclusion_diesAtExpiry_onBothRouteTypes() {
        // 事故回放：令牌先验过（结论进缓存），过期之后缓存窗口还远没结束——
        // 修复前两个入口都会把旧结论再认一阵；现在 exp 一到结论即废
        loadRoutes(route("secure", 1), route("open", 0));
        String token = token(TOKEN_SECRET, "user-1", "tenant-a",
                clock.instant().getEpochSecond() + 30);

        // 过期前：受保护路由放行并透传身份（结论暖进缓存）
        var warmSecure = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + token).exchange().block();
        assertThat(warmSecure.statusCode()).isEqualTo(HttpStatus.OK);
        warmSecure.releaseBody().block();
        assertThat(upstream.lastExchange().getRequestHeaders().getFirst("X-User-Id"))
                .isEqualTo("user-1");

        // 开放路由同一枚令牌也认（同一套缓存）
        var warmOpen = client.get().uri(baseUrl + "/open/1")
                .header("Authorization", "Bearer " + token).exchange().block();
        assertThat(warmOpen.statusCode()).isEqualTo(HttpStatus.OK);
        warmOpen.releaseBody().block();
        assertThat(upstream.lastExchange().getRequestHeaders().getFirst("X-User-Id"))
                .isEqualTo("user-1");

        // 正好拨到过期那一刻（缓存窗口 60s 远未结束）：受保护路由必须立刻 401
        clock.advanceSeconds(30);
        var atBoundary = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + token).exchange().block();
        assertThat(atBoundary.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        atBoundary.releaseBody().block();

        // 过期之后：受保护路由照样拦，不打上游
        clock.advanceSeconds(5);
        var expiredSecure = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + token).exchange().block();
        assertThat(expiredSecure.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        expiredSecure.releaseBody().block();

        // 同一枚令牌同一时刻，开放路由同样不再认——放行但匿名，身份头一个都不许有
        var expiredOpen = client.get().uri(baseUrl + "/open/1")
                .header("Authorization", "Bearer " + token).exchange().block();
        assertThat(expiredOpen.statusCode()).isEqualTo(HttpStatus.OK);
        expiredOpen.releaseBody().block();
        HttpExchange openGot = upstream.lastExchange();
        assertThat(openGot.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        assertThat(openGot.getRequestHeaders().get("X-Tenant-Id")).isNullOrEmpty();
    }

    @Test
    void protectedRoute_validToken_forwardsIdentity_stripsRawToken_stampsGatewayPass() {
        loadRoutes(route("secure", 1));

        var resp = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + validToken())
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got).isNotNull();
        // 身份头按网关验签结果写给上游
        assertThat(got.getRequestHeaders().getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(got.getRequestHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        // 原始令牌不原样递上游
        assertThat(got.getRequestHeaders().get("Authorization")).isNullOrEmpty();
        // 通行标记由网关盖章，上游用共享密钥验得出
        String pass = got.getRequestHeaders().getFirst("X-Gateway-Pass");
        String traceId = got.getRequestHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(pass).isNotBlank();
        assertThat(passSigner.verify(pass, traceId, "GET", "/secure/1", "user-1", "tenant-a",
                clock.millis(), 60_000)).isTrue();
    }

    @Test
    void protectedRoute_callerInjectedIdentityHeaders_areReplacedByGatewayValues() {
        loadRoutes(route("secure", 1));

        var resp = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + validToken())
                // 调用方试图塞假身份/假通行标记：必须被网关清掉再按验签结果重写
                .header("X-User-Id", "admin")
                .header("X-Tenant-Id", "tenant-victim")
                .header("X-Gateway-Pass", "v1.0.forged")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(got.getRequestHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        String pass = got.getRequestHeaders().getFirst("X-Gateway-Pass");
        assertThat(pass).isNotEqualTo("v1.0.forged");
        String traceId = got.getRequestHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(passSigner.verify(pass, traceId, "GET", "/secure/1", "user-1", "tenant-a",
                clock.millis(), 60_000)).isTrue();
    }

    @Test
    void protectedRoute_butAuthNotConfigured_failsClosed503() {
        // 配了「需登录」却没配验签密钥：配置事故，fail-closed，绝不裸放行
        var gatekeeper = new UserAuthGatekeeper(null, null, clock);
        DisposableServer noAuthServer = startServer(gatekeeper);
        try {
            loadRoutes(route("secure", 1));
            String url = "http://127.0.0.1:" + noAuthServer.port() + "/secure/1";

            var resp = client.get().uri(url).exchange().block();
            assertThat(resp.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                    .isEqualTo("USER_AUTH_CONFIG_UNAVAILABLE");
            resp.releaseBody().block();
            assertThat(upstream.lastExchange()).isNull();
        } finally {
            noAuthServer.disposeNow();
        }
    }

    // ---- 开放路由：谁都能打，令牌只是可选的身份补充 ----

    @Test
    void openRoute_withoutToken_passes_andGatewayPassIsStamped() {
        loadRoutes(route("open", 0));

        var resp = client.get().uri(baseUrl + "/open/1")
                .header("X-User-Id", "admin")
                .header("X-Tenant-Id", "tenant-victim")
                .header("X-Gateway-Pass", "v1.0.forged")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        // 匿名时伪造的身份头一个都到不了上游（事故回归：曾经原样透传给上游）
        assertThat(got.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-Tenant-Id")).isNullOrEmpty();
        // 通行标记仍是网关自己盖的（匿名身份），伪造的进不来
        String pass = got.getRequestHeaders().getFirst("X-Gateway-Pass");
        assertThat(pass).isNotBlank().isNotEqualTo("v1.0.forged");
        String traceId = got.getRequestHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(passSigner.verify(pass, traceId, "GET", "/open/1", "", "",
                clock.millis(), 60_000)).isTrue();
    }

    @Test
    void openRoute_anonymous_forgedAppCredentialHeaders_neverReachUpstream() {
        // 事故回归：开放路由不带令牌，调用方编的应用凭据（含密钥头）一个字都到不了上游
        loadRoutes(route("open", 0));

        var resp = client.get().uri(baseUrl + "/open/1")
                .header("X-App-No", "app-forged")
                .header("X-App-Secret", "secret-forged")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().get("X-App-No")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-App-Secret")).isNullOrEmpty();
    }

    @Test
    void reservedHeaders_caseVariantsDuplicatesAndEmptyValues_allStripped() {
        // 同一口径：头名大小写变体、同一个头出现多遍、空值，都按「伪造」整体清掉，没有绕过缝
        loadRoutes(route("open", 0));

        var resp = client.get().uri(baseUrl + "/open/1")
                .header("x-user-id", "admin")            // 全小写
                .header("X-USER-ID", "root")             // 全大写：同名第二个值，一起清
                .header("X-Tenant-Id", "")               // 空值也是「带了就得清」
                .header("x-gateway-pass", "v1.0.forged") // 小写通行标记
                .header("X-APP-SECRET", "s3cr3t")        // 大写凭据头
                .header("X-App-No", "app-forged")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-Tenant-Id")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-App-No")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-App-Secret")).isNullOrEmpty();
        // 通行标记只剩网关自己盖的那份
        String pass = got.getRequestHeaders().getFirst("X-Gateway-Pass");
        assertThat(pass).isNotBlank().isNotEqualTo("v1.0.forged");
    }

    @Test
    void routeHeaderActions_cannotTouchReservedHeaders_gatewayWritesLast() {
        // 取舍定死：保留头网关最后落笔——路由动作能管普通头，动不了保留头
        loadRoutes(route("secure", 1, List.of(
                act("REQ_ADD_HEADER", "X-User-Id", "action-forged", 1),
                act("REQ_REMOVE_HEADER", "X-Gateway-Pass", null, 2),
                act("REQ_ADD_HEADER", "X-Custom", "from-action", 3))));

        var resp = client.get().uri(baseUrl + "/secure/1")
                .header("Authorization", "Bearer " + validToken())
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        // 动作想覆盖身份头：无效，上游看到的仍是验签结果
        assertThat(got.getRequestHeaders().getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(got.getRequestHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        // 动作想删通行标记：无效，网关盖的章还在且验得过
        String pass = got.getRequestHeaders().getFirst("X-Gateway-Pass");
        String traceId = got.getRequestHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(pass).isNotBlank();
        assertThat(passSigner.verify(pass, traceId, "GET", "/secure/1", "user-1", "tenant-a",
                clock.millis(), 60_000)).isTrue();
        // 普通头动作照常生效（动作语义本身没被破坏）
        assertThat(got.getRequestHeaders().getFirst("X-Custom")).isEqualTo("from-action");
    }

    @Test
    void routeHeaderActions_cannotInventIdentity_onAnonymousOpenRoute() {
        // 开放路由匿名：动作想「造」一个身份头也到不了上游（匿名就是匿名）
        loadRoutes(route("open", 0, List.of(
                act("REQ_ADD_HEADER", "X-User-Id", "action-invented", 1))));

        var resp = client.get().uri(baseUrl + "/open/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        assertThat(upstream.lastExchange().getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
    }

    @Test
    void appAuthEnabled_authenticatedAppNoReachesUpstream_secretNeverLeavesGateway() {
        // 串联应用鉴权过滤器：验过后认定的应用编号写给上游；
        // 密钥明文与调用方塞的伪造身份头永远不出网关
        var repo = new MiniAppRepository();
        var appCatalog = new AppCredentialCatalog(repo, clock);
        repo.insert(ClientApp.create("app-1", "app-1", APP_SECRET, null, 1, null, null, null, clock));
        appCatalog.refreshBlock(Duration.ofSeconds(5));

        DisposableServer appAuthServer = startServer(gatekeeper,
                new AppAuthWebFilter(appCatalog, new ObjectMapper()));
        try {
            loadRoutes(route("open", 0));
            String url = "http://127.0.0.1:" + appAuthServer.port() + "/open/1";

            var resp = client.get().uri(url)
                    .header("X-App-No", "app-1")
                    .header("X-App-Secret", APP_SECRET)
                    // 顺手塞的假身份：必须到不了上游
                    .header("X-User-Id", "admin")
                    .exchange().block();
            assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
            resp.releaseBody().block();

            HttpExchange got = upstream.lastExchange();
            // 认定的应用编号写给上游（网关验签结果，不是调用方原值透传）
            assertThat(got.getRequestHeaders().getFirst("X-App-No")).isEqualTo("app-1");
            // 密钥明文永不出网关
            assertThat(got.getRequestHeaders().get("X-App-Secret")).isNullOrEmpty();
            // 伪造身份头被清掉（没带用户令牌 = 匿名）
            assertThat(got.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        } finally {
            appAuthServer.disposeNow();
        }
    }

    @Test
    void openRoute_withBadToken_passesAnonymously_notBlocked() {
        loadRoutes(route("open", 0));

        // 统一口径：开放路由上坏令牌不拦人，按匿名放行（浏览器里过期令牌不该打死公开接口）
        var resp = client.get().uri(baseUrl + "/open/1")
                .header("Authorization", "Bearer expired.or.garbage.token")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().get("X-User-Id")).isNullOrEmpty();
        assertThat(got.getRequestHeaders().get("X-Tenant-Id")).isNullOrEmpty();
        // 坏令牌本身也不递上游
        assertThat(got.getRequestHeaders().get("Authorization")).isNullOrEmpty();
    }

    @Test
    void openRoute_withValidToken_passes_andIdentityIsPropagated() {
        loadRoutes(route("open", 0));

        var resp = client.get().uri(baseUrl + "/open/1")
                .header("Authorization", "Bearer " + validToken())
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().getFirst("X-User-Id")).isEqualTo("user-1");
        assertThat(got.getRequestHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
        assertThat(got.getRequestHeaders().get("Authorization")).isNullOrEmpty();
    }

    @Test
    void openAndProtectedRoutes_shareOnePipeline_withoutInterference() {
        // 同一套链路混跑：开放路由没带令牌不被拦，受保护路由没带令牌被拦
        loadRoutes(route("open", 0), route("secure", 1));

        var open = client.get().uri(baseUrl + "/open/1").exchange().block();
        assertThat(open.statusCode()).isEqualTo(HttpStatus.OK);
        open.releaseBody().block();

        var secure = client.get().uri(baseUrl + "/secure/1").exchange().block();
        assertThat(secure.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        secure.releaseBody().block();
    }

    /** 最小内存应用仓储：只为喂 {@link AppCredentialCatalog} 的鉴权快照（空名单=不限来源）。 */
    static class MiniAppRepository implements AppCredentialRepository {
        private final Map<String, ClientApp> apps = new HashMap<>();

        @Override
        public void insert(ClientApp app) {
            apps.put(app.getAppNo(), app);
        }

        @Override
        public Optional<ClientApp> findByAppNo(String appNo) {
            return Optional.ofNullable(apps.get(appNo));
        }

        @Override
        public AppPage page(String keyword, long offset, int limit) {
            return new AppPage(List.of(), 0);
        }

        @Override
        public int updateEnabled(String appNo, int targetEnabled) {
            return 0;
        }

        @Override
        public void addOrigin(String appNo, String canonicalIp, Instant now) {
        }

        @Override
        public void removeOrigin(String appNo, String canonicalIp) {
        }

        @Override
        public List<AuthApp> loadAllForAuth() {
            List<AuthApp> out = new ArrayList<>();
            for (ClientApp a : apps.values()) {
                out.add(new AuthApp(a.getAppNo(), a.getSecretHash(), a.getSecretExpiresAt(),
                        a.getEnabled() == 1, List.of()));
            }
            return out;
        }
    }
}
