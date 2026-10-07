package com.apigw.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayHeadersTest {

    @Test
    void acceptsCallerTraceId_withinWhitelist() {
        assertThat(GatewayHeaders.normalizeTraceId("caller-trace.001_ABC")).isEqualTo("caller-trace.001_ABC");
        assertThat(GatewayHeaders.normalizeTraceId("  spaced-id-1234  ")).isEqualTo("spaced-id-1234");
    }

    @Test
    void rejectsIllegalOrTooShortTraceId() {
        assertThat(GatewayHeaders.normalizeTraceId(null)).isNull();
        assertThat(GatewayHeaders.normalizeTraceId("short")).isNull();           // <8
        assertThat(GatewayHeaders.normalizeTraceId("has space 1234")).isNull();
        assertThat(GatewayHeaders.normalizeTraceId("a,b,c\r\nInjected:1")).isNull();
        String tooLong = "x".repeat(65);
        assertThat(GatewayHeaders.normalizeTraceId(tooLong)).isNull();
    }

    @Test
    void generatedRequestId_is32Hex_andSelfAccepting() {
        String id = GatewayHeaders.newRequestId();
        assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(GatewayHeaders.normalizeTraceId(id)).isEqualTo(id);
    }

    @Test
    void appNoWhitelist() {
        assertThat(GatewayHeaders.normalizeAppNo("billing.app-1_2")).isEqualTo("billing.app-1_2");
        assertThat(GatewayHeaders.normalizeAppNo("")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("  ")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("a/b")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("x".repeat(65))).isNull();
    }

    @Test
    void reservedHeaders_coverIdentityPassAndAppCredentials() {
        assertThat(GatewayHeaders.RESERVED_REQUEST_HEADERS).containsExactlyInAnyOrder(
                "x-user-id", "x-tenant-id", "x-gateway-pass", "x-app-no", "x-app-secret");
    }

    @Test
    void reservedHeaderName_isCaseInsensitive_onlyCanonicalSpellingsCount() {
        // 大小写变体是同一个头：全大写/全小写/混写都认
        for (String spelled : new String[] {
                "X-User-Id", "x-user-id", "X-USER-ID", "x-UsEr-iD",
                "X-TENANT-id", "x-gateway-pass", "X-GATEWAY-PASS",
                "x-app-no", "X-APP-NO", "x-app-secret", "X-App-Secret"}) {
            assertThat(GatewayHeaders.isReservedRequestHeader(spelled, false))
                    .as("头名大小写不敏感：%s", spelled).isTrue();
        }
        // 不承认任何「别名」/近似拼写：少个连字符、换个词、复数都不是
        for (String alias : new String[] {
                "XUser-Id", "X-UserID", "X-User", "X-User-Id-Extra",
                "X-Tenant", "X-Gateway", "X-Gw-Pass", "X-App", "X-AppKey",
                "X-App-Secrets", "X-Application-No"}) {
            assertThat(GatewayHeaders.isReservedRequestHeader(alias, false))
                    .as("近似名不是保留头：%s", alias).isFalse();
        }
        // null / 空白不当头名，判否（实际由 HTTP 解码层保证头名非空）
        assertThat(GatewayHeaders.isReservedRequestHeader(null, false)).isFalse();
        assertThat(GatewayHeaders.isReservedRequestHeader("  ", false)).isFalse();
    }

    @Test
    void reservedHeaderName_decisionIgnoresValueEmptyOrRepeated() {
        // 判定只看头名，与值无关：空值/空白值/多值场景下名字仍然命中
        assertThat(GatewayHeaders.isReservedRequestHeader("X-User-Id", false)).isTrue();
        assertThat(GatewayHeaders.isReservedRequestHeader("X-App-Secret", false)).isTrue();
    }

    @Test
    void authorization_isReservedOnlyWhenUserAuthEnabled() {
        // 大小写不敏感同样适用
        assertThat(GatewayHeaders.isReservedRequestHeader("Authorization", true)).isTrue();
        assertThat(GatewayHeaders.isReservedRequestHeader("authorization", true)).isTrue();
        // 用户鉴权未启用：Authorization 是调用方与上游之间的标准头，不属网关保留
        assertThat(GatewayHeaders.isReservedRequestHeader("Authorization", false)).isFalse();
    }
}
