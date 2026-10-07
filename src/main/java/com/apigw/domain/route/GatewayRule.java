package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import com.apigw.common.web.GatewayHeaders;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * 路由子项：一条匹配条件，或一条转发动作。
 *
 * 类型与方向的合法组合、各类型的必填项，都由 {@link #validateAs} 统一守住；
 * 顺序号只校验底线（>=1），「从 1 起连续不重」由所属聚合 {@link GatewayRoute} 统一校验。
 *
 * 所有报错都带「哪一组、第几条」，调用方不用猜是哪个子项出的问题。
 */
@Getter
@Setter
public class GatewayRule {

    private String id;

    /** CONDITION / ACTION。 */
    private String ruleKind;

    /** REQUEST / RESPONSE；条件恒为 REQUEST。 */
    private String stage;

    /** 具体类型，取值见 {@link RuleTypes}。 */
    private String type;

    /** 头名 / 参数名；PATH_PREFIX 与 METHOD 不填。 */
    private String name;

    /** 匹配值或补头内容；删头动作不填。 */
    private String value;

    /** 同一路由内顺序号，从 1 起、连续、不重。 */
    private Integer sortNo;

    private static final Set<String> CONDITION_TYPES = Set.of(
            RuleTypes.TYPE_PATH_PREFIX,
            RuleTypes.TYPE_METHOD,
            RuleTypes.TYPE_HEADER,
            RuleTypes.TYPE_QUERY);

    private static final Set<String> ACTION_TYPES = Set.of(
            RuleTypes.TYPE_REQ_ADD_HEADER,
            RuleTypes.TYPE_REQ_REMOVE_HEADER,
            RuleTypes.TYPE_RESP_ADD_HEADER,
            RuleTypes.TYPE_RESP_REMOVE_HEADER);

    public static GatewayRule create(String stage, String type, String name, String value, Integer sortNo) {
        GatewayRule rule = new GatewayRule();
        rule.setStage(stage);
        rule.setType(type);
        rule.setName(name);
        rule.setValue(value);
        rule.setSortNo(sortNo);
        return rule;
    }

    /**
     * 按所属 kind 完整校验一条子项。ordinal 是它在同组列表里的位次（从 1 起），
     * 出错时带上组名与位次，前端能直接指出是哪一行写错了。
     */
    public void validateAs(String expectedKind, int ordinal) {
        boolean isCondition = RuleTypes.KIND_CONDITION.equals(expectedKind);
        String label = isCondition ? "匹配条件" : "转发动作";

        if (sortNo == null || sortNo < 1) {
            throw new BizException(label + "第 " + ordinal + " 条的顺序号必须 ≥ 1（同组从 1 开始排）");
        }
        if (type == null || type.isBlank()) {
            throw new BizException(label + "第 " + ordinal + " 条没填类型");
        }
        this.type = type.trim();

        if (isCondition) {
            if (!CONDITION_TYPES.contains(this.type)) {
                throw new BizException(label + "第 " + ordinal + " 条的类型不支持：" + this.type
                        + "（只认 PATH_PREFIX / METHOD / HEADER / QUERY）");
            }
            // 条件恒作用于请求方向，不接受调用方传 RESPONSE
            this.stage = RuleTypes.STAGE_REQUEST;
            validateFields(ordinal, label);
            return;
        }

        if (!ACTION_TYPES.contains(this.type)) {
            throw new BizException(label + "第 " + ordinal + " 条的类型不支持：" + this.type
                    + "（只认 REQ_ADD_HEADER / REQ_REMOVE_HEADER / RESP_ADD_HEADER / RESP_REMOVE_HEADER）");
        }
        // 动作方向必须与类型自洽，避免 RESP_ADD_HEADER 被标成 REQUEST
        String expectStage = this.type.startsWith("RESP_")
                ? RuleTypes.STAGE_RESPONSE
                : RuleTypes.STAGE_REQUEST;
        if (stage != null && !stage.isBlank() && !expectStage.equals(stage.trim())) {
            throw new BizException(label + "第 " + ordinal + " 条的方向与类型对不上："
                    + this.type + " 应为 " + expectStage);
        }
        this.stage = expectStage;
        validateFields(ordinal, label);
        // 请求方向动作不许碰网关保留头：这些头转发前无条件清零、只按网关验签结果写回，
        // 配置侧补/删它们都不会生效（先动作后清零），直接在保存时拒绝，免得配了个假象。
        // Authorization 是「用户鉴权启用时才保留」的有条件保留头，配置侧不拦（无鉴权部署
        // 里上游可能本来就要看它）；启用鉴权时转发器照样必剥。
        if (RuleTypes.STAGE_REQUEST.equals(this.stage)
                && GatewayHeaders.isReservedRequestHeader(this.name, false)) {
            throw new BizException(label + "第 " + ordinal + " 条的头名 " + this.name
                    + " 是网关保留头（X-User-Id / X-Tenant-Id / X-Gateway-Pass / "
                    + "X-App-No / X-App-Secret），请求方向不允许配置补/删，"
                    + "它的值只能由网关按鉴权结果写入");
        }
    }

    /** 按具体类型校验 name / value 的必填要求。 */
    private void validateFields(int ordinal, String label) {
        boolean nameRequired = !RuleTypes.TYPE_PATH_PREFIX.equals(this.type)
                && !RuleTypes.TYPE_METHOD.equals(this.type);
        if (nameRequired) {
            if (name == null || name.isBlank()) {
                throw new BizException(label + "第 " + ordinal + " 条缺名字（头名或参数名）");
            }
            this.name = name.trim();
        } else {
            this.name = null;
        }

        boolean addHeader = RuleTypes.TYPE_REQ_ADD_HEADER.equals(this.type)
                || RuleTypes.TYPE_RESP_ADD_HEADER.equals(this.type);
        boolean valueRequired = addHeader
                || RuleTypes.TYPE_PATH_PREFIX.equals(this.type)
                || RuleTypes.TYPE_METHOD.equals(this.type)
                || RuleTypes.TYPE_HEADER.equals(this.type)
                || RuleTypes.TYPE_QUERY.equals(this.type);
        if (valueRequired) {
            if (value == null || value.isBlank()) {
                throw new BizException(label + "第 " + ordinal + " 条缺取值");
            }
            this.value = value.trim();
        } else {
            // 删头动作不需要取值
            this.value = null;
        }
    }
}
